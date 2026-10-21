package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.EvalIndicator;
import com.umi.nest.entity.EvalScore;
import com.umi.nest.entity.EvalSummary;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalIndicatorMapper;
import com.umi.nest.mapper.EvalScoreMapper;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 综合素质测评（德智体美劳）。
 *
 * <p>算法口径（写入文档、页面标注可配置）：</p>
 * <ul>
 *   <li>维度得分 = Σ(该项目得分 ÷ 该项满分 × 100 × 该项维度内权重)</li>
 *   <li>总分 = Σ(维度得分 × 维度权重)，维度权重默认 德 0.25 / 智 0.35 / 体 0.15 / 美 0.10 / 劳 0.15，
 *       可用 nest.eval.weight.* 覆盖</li>
 *   <li>等级：优秀 ≥90 / 良好 ≥80 / 合格 ≥60 / 不合格 &lt;60</li>
 *   <li>排名：同班级、同学期按总分降序，同分并列（名次不跳号之外的并列同号）</li>
 *   <li>同一指标有多来源得分时取优先级最高的一条：辅导员评分 &gt; 系统 &gt; 班评 &gt; 自评</li>
 * </ul>
 */
@Service
public class EvalService {

    /** 维度顺序与中文名（页面按这个顺序渲染） */
    private static final String[] DIMENSIONS = {"moral", "intellectual", "physical", "aesthetic", "labor"};

    private static final Map<String, String> DIMENSION_NAMES = new LinkedHashMap<>();

    static {
        DIMENSION_NAMES.put("moral", "德");
        DIMENSION_NAMES.put("intellectual", "智");
        DIMENSION_NAMES.put("physical", "体");
        DIMENSION_NAMES.put("aesthetic", "美");
        DIMENSION_NAMES.put("labor", "劳");
    }

    private static final Set<String> SOURCES = new LinkedHashSet<>(Arrays.asList("self", "class", "counselor", "system"));

    private static final Map<String, String> SOURCE_NAMES = new LinkedHashMap<>();

    static {
        SOURCE_NAMES.put("self", "自评");
        SOURCE_NAMES.put("class", "班评");
        SOURCE_NAMES.put("counselor", "辅导员评分");
        SOURCE_NAMES.put("system", "系统");
    }

    private static final Map<String, Integer> SOURCE_PRIORITY = new LinkedHashMap<>();

    static {
        SOURCE_PRIORITY.put("counselor", 4);
        SOURCE_PRIORITY.put("system", 3);
        SOURCE_PRIORITY.put("class", 2);
        SOURCE_PRIORITY.put("self", 1);
    }

    private static final Set<String> STAFF_ROLES = new LinkedHashSet<>(Arrays.asList("teacher", "admin"));

    private static final Pattern TERM = Pattern.compile("^\\d{4}-\\d{4}-[12]$");

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal GRADE_EXCELLENT = new BigDecimal("90");
    private static final BigDecimal GRADE_GOOD = new BigDecimal("80");
    private static final BigDecimal GRADE_PASS = new BigDecimal("60");

    private static final int MAX_EVIDENCE = 255;
    private static final int MAX_ITEMS = 200;

    @Autowired
    private EvalIndicatorMapper indicatorMapper;

    @Autowired
    private EvalScoreMapper scoreMapper;

    @Autowired
    private EvalSummaryMapper summaryMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    @Value("${nest.eval.weight.moral:0.25}")
    private double moralWeight;

    @Value("${nest.eval.weight.intellectual:0.35}")
    private double intellectualWeight;

    @Value("${nest.eval.weight.physical:0.15}")
    private double physicalWeight;

    @Value("${nest.eval.weight.aesthetic:0.10}")
    private double aestheticWeight;

    @Value("${nest.eval.weight.labor:0.15}")
    private double laborWeight;

    // ------------------------------------------------------------------
    // 指标与学期（任何登录用户可读：学生要照它自评，辅导员要照它评分）
    // ------------------------------------------------------------------

    public Result<?> indicators(Long userId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        List<EvalIndicator> all = indicatorMapper.listActive();
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (String dim : DIMENSIONS) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (EvalIndicator item : all) {
                if (dim.equals(item.getDimension())) {
                    items.add(indicatorMap(item));
                }
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", dim);
            node.put("name", DIMENSION_NAMES.get(dim));
            node.put("weight", dimensionWeight(dim));
            node.put("items", items);
            node.put("itemCount", items.size());
            dimensions.add(node);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dimensions", dimensions);
        data.put("indicatorCount", all.size());
        data.put("terms", termOptions());
        data.put("currentTerm", currentTerm());
        data.put("gradeRule", "优秀 ≥90 / 良好 ≥80 / 合格 ≥60 / 不合格 <60");
        data.put("formula", "维度得分 = Σ(指标得分 ÷ 满分 × 100 × 维度内权重)；总分 = Σ(维度得分 × 维度权重)");
        data.put("weights", weightsMap());
        return Result.success(data);
    }

    /** 出现过得分的学期 + 当前学期，倒序 */
    private List<String> termOptions() {
        Set<String> terms = new LinkedHashSet<>();
        String now = currentTerm();
        terms.add(now);
        List<String> used = scoreMapper.listTerms();
        if (used != null) {
            terms.addAll(used);
        }
        List<String> list = new ArrayList<>(terms);
        list.sort(Comparator.reverseOrder());
        return list;
    }

    // ------------------------------------------------------------------
    // 学生：自评提交 + 我的测评
    // ------------------------------------------------------------------

    public Result<?> mine(Long userId, String term) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String resolved = resolveTerm(term);
        if (resolved == null) {
            return Result.error("学期格式不正确（应为 2026-2027-1）");
        }
        List<EvalIndicator> all = indicatorMapper.listActive();
        List<EvalScore> scores = scoreMapper.listByStudent(userId, resolved);
        EvalSummary summary = summaryMapper.findByStudentTerm(userId, resolved);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", resolved);
        data.put("exists", !scores.isEmpty());
        data.put("scoreCount", scores.size());
        data.put("indicatorCount", all.size());
        data.put("scores", scoreMaps(scores));
        data.put("dimensions", breakdown(all, scores));
        data.put("progress", progress(all, scores));
        data.put("summary", summaryMap(summary, resolved));
        return Result.success(data);
    }

    public Result<?> submit(Long userId, String role, Map<String, Object> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!"student".equals(role)) {
            return Result.error(403, "只有学生可以提交综测自评");
        }
        return saveScores(userId, "self", userId, body);
    }

    // ------------------------------------------------------------------
    // 辅导员 / 管理员：评分、汇总、重算
    // ------------------------------------------------------------------

    public Result<?> score(Long actorId, String role, Long studentId, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限录入综测评分");
        }
        if (studentId == null) {
            return Result.error("缺少学生 id");
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(404, "学生档案不存在");
        }
        if (!canTouch(actorId, role, profile)) {
            return Result.error(403, "该学生不在你的名下");
        }
        return saveScores(studentId, "counselor", actorId, body);
    }

    /** 学生自评与辅导员评分共用的写入逻辑：逐项 upsert，来源固定 */
    private Result<?> saveScores(Long studentId, String source, Long scorerId, Map<String, Object> body) {
        if (body == null) {
            return Result.error("请求内容为空");
        }
        String term = resolveTerm(str(body.get("term")));
        if (term == null) {
            return Result.error("学期格式不正确（应为 2026-2027-1）");
        }
        Object raw = body.get("scores");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            return Result.error("请至少填写一项得分");
        }
        List<?> items = (List<?>) raw;
        if (items.size() > MAX_ITEMS) {
            return Result.error("一次最多提交 " + MAX_ITEMS + " 项");
        }
        List<EvalIndicator> all = indicatorMapper.listActive();
        Map<Long, EvalIndicator> byId = new LinkedHashMap<>();
        Map<String, EvalIndicator> byCode = new LinkedHashMap<>();
        for (EvalIndicator item : all) {
            byId.put(item.getId(), item);
            byCode.put(item.getCode(), item);
        }
        List<EvalScore> pending = new ArrayList<>();
        int index = 0;
        for (Object one : items) {
            index++;
            if (!(one instanceof Map)) {
                return Result.error("第 " + index + " 项格式不正确");
            }
            Map<?, ?> row = (Map<?, ?>) one;
            EvalIndicator indicator = null;
            Object idValue = row.get("indicatorId");
            if (idValue != null && !"".equals(String.valueOf(idValue))) {
                try {
                    indicator = byId.get(Long.parseLong(String.valueOf(idValue).trim()));
                } catch (NumberFormatException e) {
                    return Result.error("第 " + index + " 项的指标 id 不正确");
                }
            }
            if (indicator == null) {
                String code = str(row.get("code"));
                if (code != null) {
                    indicator = byCode.get(code);
                }
            }
            if (indicator == null) {
                return Result.error("第 " + index + " 项找不到对应指标");
            }
            String rawScore = str(row.get("score"));
            if (rawScore == null) {
                return Result.error("第 " + index + " 项（" + indicator.getName() + "）没有填得分");
            }
            BigDecimal score;
            try {
                score = new BigDecimal(rawScore).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                return Result.error("第 " + index + " 项（" + indicator.getName() + "）得分不是数字");
            }
            // 上界与 calculate/breakdown 同口径：maxScore 缺失时按 100 处理，避免 NPE
            BigDecimal max = indicator.getMaxScore() == null ? new BigDecimal("100") : indicator.getMaxScore();
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(max) > 0) {
                return Result.error("「" + indicator.getName() + "」得分必须在 0 ~ "
                        + plain(max) + " 之间");
            }
            EvalScore entity = new EvalScore();
            entity.setStudentId(studentId);
            entity.setIndicatorId(indicator.getId());
            entity.setAcademicTerm(term);
            entity.setScore(score);
            entity.setSource(source);
            entity.setEvidence(clip(str(row.get("evidence")), MAX_EVIDENCE));
            entity.setScorerId(scorerId);
            pending.add(entity);
        }
        for (EvalScore entity : pending) {
            scoreMapper.upsert(entity);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", term);
        data.put("saved", pending.size());
        data.put("source", source);
        data.put("sourceName", SOURCE_NAMES.get(source));
        return Result.success("已保存 " + pending.size() + " 项" + SOURCE_NAMES.get(source), data);
    }

    public Result<?> summary(Long actorId, String role, String term, String className, String keyword,
                             String scope, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看综测汇总");
        }
        String resolved = resolveTerm(term);
        if (resolved == null) {
            return Result.error("学期格式不正确（应为 2026-2027-1）");
        }
        Long scopeCounselorId = scopeOf(actorId, role, scope);
        String classFilter = trim(className);
        String kw = trim(keyword);
        int pageNo = Math.max(1, page);
        int sizeNo = clamp(size, 1, 200);
        int offset = (pageNo - 1) * sizeNo;

        List<EvalSummary> rows;
        int total;
        if (kw == null) {
            rows = summaryMapper.listByTerm(resolved, classFilter, scopeCounselorId, offset, sizeNo);
            total = summaryMapper.countByTerm(resolved, classFilter, scopeCounselorId);
        } else {
            rows = summaryMapper.searchByTerm(resolved, classFilter, kw, scopeCounselorId, offset, sizeNo);
            total = summaryMapper.countSearch(resolved, classFilter, kw, scopeCounselorId);
        }
        Map<String, Object> data = page(summaryMaps(rows), total, pageNo, sizeNo);
        data.put("term", resolved);
        data.put("terms", termOptions());
        data.put("scope", scopeCounselorId == null ? "all" : "mine");
        data.put("classes", profileMapper.listClassNames(scopeCounselorId));
        data.put("stats", statsOf(resolved, classFilter, scopeCounselorId));
        return Result.success(data);
    }

    public Result<?> detail(Long actorId, String role, Long studentId, String term) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看学生综测");
        }
        String resolved = resolveTerm(term);
        if (resolved == null) {
            return Result.error("学期格式不正确（应为 2026-2027-1）");
        }
        if (studentId == null) {
            return Result.error("缺少学生 id");
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(404, "学生档案不存在");
        }
        if (!canTouch(actorId, role, profile)) {
            return Result.error(403, "该学生不在你的名下");
        }
        List<EvalIndicator> all = indicatorMapper.listActive();
        List<EvalScore> scores = scoreMapper.listByStudent(studentId, resolved);
        EvalSummary summary = summaryMapper.findByStudentTerm(studentId, resolved);

        Map<String, Object> student = new LinkedHashMap<>();
        student.put("userId", studentId);
        student.put("name", profile.getName());
        student.put("studentNo", profile.getStudentNo());
        student.put("className", profile.getClassName());
        student.put("major", profile.getMajor());
        student.put("grade", profile.getGrade());
        student.put("counselorName", profile.getCounselorName());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", resolved);
        data.put("student", student);
        data.put("exists", !scores.isEmpty());
        data.put("scores", scoreMaps(scores));
        data.put("dimensions", breakdown(all, scores));
        data.put("progress", progress(all, scores));
        data.put("summary", summaryMap(summary, resolved));
        return Result.success(data);
    }

    /**
     * 重算某学期汇总：按目前已有的得分（多来源取优先级最高）算总分、等级、班级排名并写回。
     * 辅导员只算自己名下的学生，管理员算全部。
     */
    public Result<?> compute(Long actorId, String role, String term, String className) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限重算综测汇总");
        }
        String resolved = resolveTerm(term);
        if (resolved == null) {
            return Result.error("学期格式不正确（应为 2026-2027-1）");
        }
        Long scopeCounselorId = "teacher".equals(role) && profileMapper.countByCounselor(actorId) > 0
                ? actorId : null;
        String classFilter = trim(className);

        List<EvalIndicator> all = indicatorMapper.listActive();
        if (all.isEmpty()) {
            return Result.error("还没有可用的综测指标，请先执行 sql/nest_xuegen_seed.sql");
        }
        List<EvalScore> scores = scoreMapper.listByTerm(resolved, scopeCounselorId);
        if (scores.isEmpty()) {
            return Result.error("该学期还没有任何得分记录，无法重算");
        }
        Map<Long, List<EvalScore>> byStudent = new LinkedHashMap<>();
        for (EvalScore row : scores) {
            byStudent.computeIfAbsent(row.getStudentId(), k -> new ArrayList<>()).add(row);
        }

        List<EvalSummary> computed = new ArrayList<>();
        for (Map.Entry<Long, List<EvalScore>> entry : byStudent.entrySet()) {
            EvalSummary summary = calculate(all, entry.getKey(), resolved, entry.getValue());
            computed.add(summary);
        }
        // 班级维度排名：同班、同学期按总分降序，同分并列
        Map<String, List<EvalSummary>> byClass = new LinkedHashMap<>();
        for (EvalSummary one : computed) {
            String key = one.getClassName() == null ? "" : one.getClassName();
            byClass.computeIfAbsent(key, k -> new ArrayList<>()).add(one);
        }
        for (List<EvalSummary> group : byClass.values()) {
            group.sort(Comparator.comparing(EvalSummary::getTotalScore).reversed());
            BigDecimal previous = null;
            int rank = 0;
            for (int i = 0; i < group.size(); i++) {
                EvalSummary one = group.get(i);
                if (previous == null || one.getTotalScore().compareTo(previous) != 0) {
                    rank = i + 1;
                    previous = one.getTotalScore();
                }
                one.setRankInClass(rank);
            }
        }
        int written = 0;
        List<String> classes = new ArrayList<>();
        for (EvalSummary one : computed) {
            if (classFilter != null && !classFilter.equals(one.getClassName())) {
                continue;
            }
            summaryMapper.upsert(one);
            written++;
            if (one.getClassName() != null && !classes.contains(one.getClassName())) {
                classes.add(one.getClassName());
            }
        }
        if (written == 0) {
            String where = classFilter == null ? "全部班级" : classFilter;
            return Result.error("该学期（" + where + "）还没有得分记录，无法重算");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", resolved);
        data.put("students", written);
        data.put("classes", classes);
        data.put("scope", scopeCounselorId == null ? "all" : "mine");
        return Result.success("已重算 " + written + " 名学生的综测总分与排名", data);
    }

    // ------------------------------------------------------------------
    // 计算
    // ------------------------------------------------------------------

    /** 单名学生：按优先级取每项得分 → 维度加权 → 总分 + 等级 */
    private EvalSummary calculate(List<EvalIndicator> all, Long studentId, String term, List<EvalScore> scores) {
        Map<Long, EvalScore> picked = new LinkedHashMap<>();
        for (EvalScore row : scores) {
            EvalScore current = picked.get(row.getIndicatorId());
            if (current == null || priority(row.getSource()) > priority(current.getSource())) {
                picked.put(row.getIndicatorId(), row);
            }
        }
        Map<String, BigDecimal> dimScores = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            dimScores.put(dim, BigDecimal.ZERO);
        }
        for (EvalIndicator item : all) {
            EvalScore row = picked.get(item.getId());
            if (row == null || row.getScore() == null) {
                continue;
            }
            BigDecimal max = item.getMaxScore() == null || item.getMaxScore().compareTo(BigDecimal.ZERO) <= 0
                    ? HUNDRED : item.getMaxScore();
            BigDecimal weight = item.getWeight() == null ? BigDecimal.ZERO : item.getWeight();
            BigDecimal value = row.getScore().divide(max, 6, RoundingMode.HALF_UP)
                    .multiply(HUNDRED).multiply(weight).setScale(4, RoundingMode.HALF_UP);
            String dim = item.getDimension();
            dimScores.put(dim, dimScores.getOrDefault(dim, BigDecimal.ZERO).add(value));
        }
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> rounded = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            BigDecimal value = dimScores.getOrDefault(dim, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            rounded.put(dim, value);
            total = total.add(value.multiply(dimensionWeight(dim)));
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        EvalSummary summary = new EvalSummary();
        summary.setStudentId(studentId);
        summary.setAcademicTerm(term);
        summary.setTotalScore(total);
        summary.setGradeLevel(gradeOf(total));
        summary.setDetailJson(detailJson(rounded));
        StudentProfile profile = profileMapper.findByUserId(studentId);
        summary.setClassName(profile == null ? "未填班级" : (profile.getClassName() == null ? "未填班级" : profile.getClassName()));
        return summary;
    }

    private int priority(String source) {
        Integer value = SOURCE_PRIORITY.get(source);
        return value == null ? 0 : value;
    }

    private static String gradeOf(BigDecimal total) {
        if (total.compareTo(GRADE_EXCELLENT) >= 0) {
            return "优秀";
        }
        if (total.compareTo(GRADE_GOOD) >= 0) {
            return "良好";
        }
        if (total.compareTo(GRADE_PASS) >= 0) {
            return "合格";
        }
        return "不合格";
    }

    private static String detailJson(Map<String, BigDecimal> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue().toPlainString());
            first = false;
        }
        return sb.append('}').toString();
    }

    /** 维度权重（可配置） */
    private BigDecimal dimensionWeight(String dimension) {
        double value;
        switch (dimension) {
            case "intellectual":
                value = intellectualWeight;
                break;
            case "physical":
                value = physicalWeight;
                break;
            case "aesthetic":
                value = aestheticWeight;
                break;
            case "labor":
                value = laborWeight;
                break;
            default:
                value = moralWeight;
                break;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> weightsMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            map.put(dim, dimensionWeight(dim));
        }
        return map;
    }

    /** 维度分解：每维的加权得分 + 明细项（含自评/辅导员评分两个来源） */
    private List<Map<String, Object>> breakdown(List<EvalIndicator> all, List<EvalScore> scores) {
        Map<Long, Map<String, EvalScore>> byIndicator = new LinkedHashMap<>();
        for (EvalScore row : scores) {
            byIndicator.computeIfAbsent(row.getIndicatorId(), k -> new LinkedHashMap<>())
                    .put(row.getSource(), row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String dim : DIMENSIONS) {
            List<Map<String, Object>> items = new ArrayList<>();
            BigDecimal weighted = BigDecimal.ZERO;
            int scored = 0;
            for (EvalIndicator item : all) {
                if (!dim.equals(item.getDimension())) {
                    continue;
                }
                Map<String, EvalScore> mine = byIndicator.getOrDefault(item.getId(), new LinkedHashMap<>());
                EvalScore best = null;
                for (EvalScore row : mine.values()) {
                    if (best == null || priority(row.getSource()) > priority(best.getSource())) {
                        best = row;
                    }
                }
                Map<String, Object> node = indicatorMap(item);
                node.put("score", best == null ? null : best.getScore());
                node.put("source", best == null ? null : best.getSource());
                node.put("sourceName", best == null ? null : SOURCE_NAMES.get(best.getSource()));
                node.put("evidence", best == null ? null : best.getEvidence());
                node.put("selfScore", scoreOf(mine.get("self")));
                node.put("counselorScore", scoreOf(mine.get("counselor")));
                if (best != null && best.getScore() != null) {
                    scored++;
                    BigDecimal max = item.getMaxScore() == null || item.getMaxScore().compareTo(BigDecimal.ZERO) <= 0
                            ? HUNDRED : item.getMaxScore();
                    BigDecimal weight = item.getWeight() == null ? BigDecimal.ZERO : item.getWeight();
                    weighted = weighted.add(best.getScore().divide(max, 6, RoundingMode.HALF_UP)
                            .multiply(HUNDRED).multiply(weight));
                }
                items.add(node);
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", dim);
            node.put("name", DIMENSION_NAMES.get(dim));
            node.put("weight", dimensionWeight(dim));
            node.put("score", weighted.setScale(2, RoundingMode.HALF_UP));
            node.put("weightedScore", weighted.multiply(dimensionWeight(dim)).setScale(2, RoundingMode.HALF_UP));
            node.put("scored", scored);
            node.put("itemCount", items.size());
            node.put("items", items);
            result.add(node);
        }
        return result;
    }

    private static BigDecimal scoreOf(EvalScore row) {
        return row == null ? null : row.getScore();
    }

    private Map<String, Object> progress(List<EvalIndicator> all, List<EvalScore> scores) {
        Set<Long> selfDone = new LinkedHashSet<>();
        Set<Long> counselorDone = new LinkedHashSet<>();
        for (EvalScore row : scores) {
            if ("self".equals(row.getSource())) {
                selfDone.add(row.getIndicatorId());
            }
            if ("counselor".equals(row.getSource())) {
                counselorDone.add(row.getIndicatorId());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("indicatorCount", all.size());
        map.put("selfDone", selfDone.size());
        map.put("counselorDone", counselorDone.size());
        map.put("selfRemain", Math.max(0, all.size() - selfDone.size()));
        map.put("selfComplete", !all.isEmpty() && selfDone.size() >= all.size());
        return map;
    }

    /** 汇总统计：已评/未评/平均分/等级分布（未评人数用学籍在册学生数推算） */
    private Map<String, Object> statsOf(String term, String className, Long scopeCounselorId) {
        List<Map<String, Object>> rows = summaryMapper.statsByTerm(term, className, scopeCounselorId);
        Map<String, Object> raw = rows == null || rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
        long graded = longOf(raw.get("graded"));
        int inScope = profileMapper.count(scopeCounselorId, className, null, null);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("students", inScope);
        map.put("graded", graded);
        map.put("ungraded", Math.max(0, inScope - (int) graded));
        map.put("average", round2(raw.get("avg_score")));
        map.put("highest", round2(raw.get("max_score")));
        map.put("lowest", round2(raw.get("min_score")));
        map.put("excellent", longOf(raw.get("excellent")));
        map.put("good", longOf(raw.get("good")));
        map.put("pass", longOf(raw.get("pass")));
        map.put("fail", longOf(raw.get("fail")));
        map.put("scored", scoreMapper.countScoredStudents(term, scopeCounselorId));
        return map;
    }

    // ------------------------------------------------------------------
    // 序列化与工具
    // ------------------------------------------------------------------

    private static Map<String, Object> indicatorMap(EvalIndicator item) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", item.getId());
        node.put("code", item.getCode());
        node.put("name", item.getName());
        node.put("description", item.getDescription());
        node.put("dimension", item.getDimension());
        node.put("maxScore", item.getMaxScore());
        node.put("weight", item.getWeight());
        node.put("sortOrder", item.getSortOrder());
        return node;
    }

    private static List<Map<String, Object>> scoreMaps(List<EvalScore> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EvalScore row : rows) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", row.getId());
            node.put("studentId", row.getStudentId());
            node.put("indicatorId", row.getIndicatorId());
            node.put("indicatorCode", row.getIndicatorCode());
            node.put("indicatorName", row.getIndicatorName());
            node.put("dimension", row.getDimension());
            node.put("term", row.getAcademicTerm());
            node.put("score", row.getScore());
            node.put("source", row.getSource());
            node.put("sourceName", SOURCE_NAMES.get(row.getSource()));
            node.put("evidence", row.getEvidence());
            node.put("scorerId", row.getScorerId());
            node.put("scorerName", row.getScorerName());
            list.add(node);
        }
        return list;
    }

    private static List<Map<String, Object>> summaryMaps(List<EvalSummary> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EvalSummary row : rows) {
            list.add(summaryMap(row, row.getAcademicTerm()));
        }
        return list;
    }

    private static Map<String, Object> summaryMap(EvalSummary row, String term) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (row == null) {
            node.put("exists", false);
            node.put("term", term);
            return node;
        }
        node.put("exists", true);
        node.put("id", row.getId());
        node.put("studentId", row.getStudentId());
        node.put("studentName", row.getStudentName());
        node.put("studentNo", row.getStudentNo());
        node.put("className", row.getClassName());
        node.put("major", row.getMajor());
        node.put("grade", row.getGrade());
        node.put("counselorName", row.getCounselorName());
        node.put("term", row.getAcademicTerm());
        node.put("totalScore", row.getTotalScore());
        node.put("rankInClass", row.getRankInClass());
        node.put("gradeLevel", row.getGradeLevel());
        node.put("detail", parseDetail(row.getDetailJson()));
        node.put("computedAt", row.getComputedAt());
        return node;
    }

    /** detail_json 只用于展示，解析失败就把原始文本放进 detailRaw */
    private static Map<String, Object> parseDetail(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) {
            return map;
        }
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String pair : body.split(",")) {
            int idx = pair.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = pair.substring(0, idx).replace("\"", "").trim();
            String value = pair.substring(idx + 1).replace("\"", "").trim();
            try {
                map.put(key, new BigDecimal(value));
            } catch (NumberFormatException ignored) {
                map.put(key, value);
            }
        }
        return map;
    }

    /** 学期入参归一化：空则用当前学期，非法返回 null */
    private static String resolveTerm(String term) {
        String value = trim(term);
        if (value == null) {
            return currentTerm();
        }
        return TERM.matcher(value).matches() ? value : null;
    }

    /** 当前学期：9 月—次年 1 月为 -1，2—7 月为 -2 */
    private static String currentTerm() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        if (month >= 9) {
            return year + "-" + (year + 1) + "-1";
        }
        if (month >= 2) {
            return (year - 1) + "-" + year + "-2";
        }
        return (year - 1) + "-" + year + "-1";
    }

    /** 辅导员只能看名下学生；未分派学生（counselorId 为空）任何辅导员都能看 */
    private static boolean canTouch(Long actorId, String role, StudentProfile profile) {
        if ("admin".equals(role)) {
            return true;
        }
        return profile.getCounselorId() == null || profile.getCounselorId().equals(actorId);
    }

    /** scope=mine/all：admin 或 scope=all 看全校；辅导员默认只看名下（未分派则看全校） */
    private Long scopeOf(Long actorId, String role, String scope) {
        if ("all".equalsIgnoreCase(trim(scope))) {
            return null;
        }
        if ("teacher".equals(role) && profileMapper.countByCounselor(actorId) > 0) {
            return actorId;
        }
        return null;
    }

    private static Map<String, Object> page(List<?> items, int total, int page, int size) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", items);
        map.put("total", total);
        map.put("page", page);
        map.put("size", size);
        map.put("pages", size <= 0 ? 0 : (total + size - 1) / size);
        return map;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static long longOf(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal round2(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP);
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
