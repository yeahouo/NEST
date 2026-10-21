package com.umi.nest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.EvalAcademic;
import com.umi.nest.entity.EvalBonus;
import com.umi.nest.entity.EvalSummary;
import com.umi.nest.entity.RankBatch;
import com.umi.nest.entity.RankObjection;
import com.umi.nest.entity.RankRow;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalAcademicMapper;
import com.umi.nest.mapper.EvalBonusMapper;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.RankBatchMapper;
import com.umi.nest.mapper.RankObjectionMapper;
import com.umi.nest.mapper.RankRowMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 综测排名公示与导出（学工 P13）服务。
 *
 * <p>口径来源：《厦门大学嘉庚学院优秀学生奖学金综合测评实施办法（2022年修订）》第六条至第十四条。
 * 综合测评 M = A + B：</p>
 * <ul>
 *   <li>A＝学习成绩，平台只做导入登记台账（eval_academic.score_a），教务部/IT 是 A 的责任方；
 *       <b>缺 A 的学生不参与排名</b>（不补 0），由 /academic/missing 暴露待补清单。</li>
 *   <li>C＝奖励加分 + 惩处扣分的合计（eval_bonus 中 status='counselor_approved' 的 points 之和），
 *       先按大类封顶、再总封顶（默认 8 分），口径的唯一实现处是 {@link EvalBonusService#computeByStudent(String)}。</li>
 *   <li>μ＝(H−L)/n，H/L 取<b>同专业同年级</b>已录 A 的最高/最低分、n 为该专业年级已录 A 人数，
 *       钳制到批次的 [mu_min, mu_max]（默认 0.5~0.7）；n=1 时结果为 0，钳制后自然落到 mu_min。</li>
 *   <li>B＝C×μ；批次 graduation_last=1（毕业学年第二学期）时第十条要求只按 A 排名 ⇒ B=0、M=A。</li>
 *   <li>排名按 M 降序<b>专业内</b>排名，同分并列用 RANK() 语义（1,2,2,4）；另算同专业年级排名。</li>
 *   <li>参评资格＝专业排名 ≤ 专业人数 × qualify_pct（四舍五入）；奖学金等级名额＝同一专业年级人数 ×
 *       tier1/tier2/tier3_pct（各自四舍五入），按 M 降序从第 1 名依次落位（一等→二等→三等），名额用尽为 NULL。</li>
 * </ul>
 *
 * <p><b>排名与等级全部只在本类用 Java 算一份</b>（不在 SQL 里再写一遍窗口函数），compute 时算完落 rank_row 快照，
 * 之后的列表 / 明细 / 公示 / 导出 / 统计一律只读快照。</p>
 *
 * <p><b>无法核验的排除项</b>：第十三条的参评排除（所修课程不及格 / 毕业学年第二学期往年必修不及格 >1 门 /
 * 缓考降级致取得成绩课程总学分 &lt; 所在专业平均应修学分 75% / 未解除纪律处分或受通报批评）需要成绩明细与处分数据，
 * 平台底表没有，因此<b>不硬拦</b>，只在 /stats 的 unverifiableNote 与代码注释里提示。</p>
 *
 * <p>HTTP 约定一律 HTTP 200 + {code,message,data}；状态变更走 CAS，影响行数 ≠ 1 即 400 让前端刷新重试。</p>
 */
@Service
public class RankService {

    /** 批次状态。 */
    public static final String B_DRAFT = "draft";
    public static final String B_COMPUTED = "computed";
    public static final String B_PUBLICITY = "publicity";
    public static final String B_FINISHED = "finished";

    private static final String BATCH_CAS_FAIL = "批次状态已变化，请刷新后重试";
    private static final String OBJ_CAS_FAIL = "异议状态已变化，请刷新后重试";

    private static final String TIER1 = "t1";
    private static final String TIER2 = "t2";
    private static final String TIER3 = "t3";
    private static final Map<String, String> TIER_TEXT = new LinkedHashMap<>();

    static {
        TIER_TEXT.put(TIER1, "一等奖学金");
        TIER_TEXT.put(TIER2, "二等奖学金");
        TIER_TEXT.put(TIER3, "三等奖学金");
    }

    private static final String NO_MAJOR = "未填专业";
    private static final String NO_CLASS = "未填班级";
    private static final String NO_GRADE = "未填年级";

    private static final String MY_NOTE = "综合素质表现得分 C 由加分申报构成；五维得分仅作记实测评佐证，不参与 M 计算";
    private static final String UNVERIFIABLE_NOTE =
            "第十三条的参评排除项（所修课程有不及格 / 毕业学年第二学期往年必修不及格超过 1 门 / "
            + "缓考降级致取得成绩课程总学分不足所在专业平均应修学分 75% / 未解除纪律处分或受通报批评）"
            + "平台底表没有成绩明细与处分数据，无法核验，仅作提示，请人工复核。";

    /** P6 记实测评五维（仅作佐证展示，不参与 M）。 */
    private static final String[] DIMENSIONS = {"moral", "intellectual", "physical", "aesthetic", "labor"};
    private static final String[] DIMENSION_NAMES = {"德", "智", "体", "美", "劳"};

    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));
    private static final BigDecimal ZERO = new BigDecimal("0");
    private static final BigDecimal ZERO2 = new BigDecimal("0.00");

    /** 全批次统一排序：M 降序，兜底按 studentId 升序保证可复现。 */
    private static final Comparator<RankRow> ROW_ORDER =
            Comparator.comparing(RankRow::getScoreM).reversed().thenComparing(RankRow::getStudentId);

    @Autowired
    private RankBatchMapper batchMapper;

    @Autowired
    private RankRowMapper rowMapper;

    @Autowired
    private RankObjectionMapper objectionMapper;

    @Autowired
    private EvalAcademicMapper academicMapper;

    @Autowired
    private EvalBonusMapper bonusMapper;

    @Autowired
    private EvalBonusService bonusService;

    @Autowired
    private StudentProfileMapper profileMapper;

    @Autowired
    private EvalSummaryMapper evalSummaryMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /** 公示期默认工作日数（第十四条第 3 款：公示期不少于 5 个工作日）。 */
    @Value("${nest.rank.publicity-days:5}")
    private int defaultPublicityDays;

    @Value("${nest.eval.weight.moral:0.25}")
    private double weightMoral;

    @Value("${nest.eval.weight.intellectual:0.35}")
    private double weightIntellectual;

    @Value("${nest.eval.weight.physical:0.15}")
    private double weightPhysical;

    @Value("${nest.eval.weight.aesthetic:0.10}")
    private double weightAesthetic;

    @Value("${nest.eval.weight.labor:0.15}")
    private double weightLabor;

    // ---------------------------------------------------------------- 批次

    /** 批次列表：登录可读，非 admin 自动隐藏 draft，返回纯数组。 */
    public Result<?> listBatches(Long actorId, String role, String status, String keyword) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        List<RankBatch> batches = batchMapper.list(!isAdmin(role), trimToNull(status), trimToNull(keyword));
        return Result.success(batches);
    }

    /** 新建批次（admin）。 */
    public Result<?> createBatch(Long actorId, String role, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可维护综测批次");
        }
        RankBatch batch = new RankBatch();
        batch.setGraduationLast(0);
        batch.setMuMin(new BigDecimal("0.5"));
        batch.setMuMax(new BigDecimal("0.7"));
        batch.setQualifyPct(30);
        batch.setTier1Pct(5);
        batch.setTier2Pct(10);
        batch.setTier3Pct(15);
        batch.setPublicityDays(defaultPublicityDays);
        String problem = applyBatchFields(batch, body);
        if (problem == null) {
            problem = batchProblem(batch);
        }
        if (problem != null) {
            return Result.error(400, problem);
        }
        batch.setStatus(B_DRAFT);
        batch.setCreatedBy(actorId);
        batchMapper.insert(batch);
        return Result.success("批次已创建", batchMapper.findById(batch.getId()));
    }

    /** 增量修改批次（admin）：只改传了的字段；进入公示后不允许再改。 */
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateBatch(Long actorId, String role, Long id, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可维护综测批次");
        }
        if (id == null) {
            return Result.error(400, "批次 ID 不能为空");
        }
        RankBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        String status = exist.getStatus();
        if (B_PUBLICITY.equals(status) || B_FINISHED.equals(status)) {
            return Result.error(400, "批次已进入公示，不能再修改");
        }
        String problem = applyBatchFields(exist, body);
        if (problem == null) {
            problem = batchProblem(exist);
        }
        if (problem != null) {
            return Result.error(400, problem);
        }
        int rows = batchMapper.update(exist, status);
        if (rows != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        return Result.success("批次已更新", batchMapper.findById(id));
    }

    /**
     * 计算综测（admin）：按 A（导入台账）+ C（加分审核）+ μ（A 分布）算 M、专业排名与奖学金等级，落 rank_row 快照。
     *
     * <p>draft 与 computed 允许（重）算；publicity / finished 拒绝重算并 400。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result<?> compute(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可执行综测计算");
        }
        if (id == null) {
            return Result.error(400, "批次 ID 不能为空");
        }
        RankBatch batch = batchMapper.findById(id);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        String status = batch.getStatus();
        if (B_PUBLICITY.equals(status) || B_FINISHED.equals(status)) {
            return Result.error(400, "批次已进入公示，不能再重算");
        }
        String term = batch.getAcademicTerm();

        // 1) μ：按专业+年级分组，取 H/L/n
        List<EvalAcademic> academics = academicMapper.listByTerm(term);
        Map<String, List<BigDecimal>> scores = new LinkedHashMap<>();
        for (EvalAcademic a : academics) {
            if (a.getScoreA() == null) {
                continue;
            }
            scores.computeIfAbsent(groupKey(a.getMajor(), a.getGrade()), k -> new ArrayList<>()).add(a.getScoreA());
        }
        BigDecimal muMin = nvl(batch.getMuMin(), new BigDecimal("0.5"));
        BigDecimal muMax = nvl(batch.getMuMax(), new BigDecimal("0.7"));
        Map<String, BigDecimal> muByGroup = new HashMap<>();
        for (Map.Entry<String, List<BigDecimal>> entry : scores.entrySet()) {
            List<BigDecimal> list = entry.getValue();
            BigDecimal high = Collections.max(list);
            BigDecimal low = Collections.min(list);
            BigDecimal mu = high.subtract(low)
                    .divide(BigDecimal.valueOf(list.size()), 3, RoundingMode.HALF_UP);
            if (mu.compareTo(muMin) < 0) {
                mu = muMin;
            }
            if (mu.compareTo(muMax) > 0) {
                mu = muMax;
            }
            muByGroup.put(entry.getKey(), mu);
        }

        // 2) C：唯一口径实现处（分类封顶 + 总封顶）
        Map<Long, EvalBonusService.StudentC> cMap = bonusService.computeByStudent(term);
        boolean graduationLast = batch.getGraduationLast() != null && batch.getGraduationLast() == 1;

        List<RankRow> rows = new ArrayList<>();
        for (EvalAcademic a : academics) {
            if (a.getScoreA() == null) {
                continue;
            }
            String key = groupKey(a.getMajor(), a.getGrade());
            RankRow row = new RankRow();
            row.setBatchId(id);
            row.setStudentId(a.getStudentId());
            row.setMajor(nvlText(a.getMajor(), NO_MAJOR));
            row.setClassName(nvlText(a.getClassName(), NO_CLASS));
            row.setGrade(nvlText(a.getGrade(), NO_GRADE));
            EvalBonusService.StudentC c = cMap.get(a.getStudentId());
            BigDecimal cValue = c == null ? ZERO2 : scale2(c.total);
            BigDecimal mu = nvl(muByGroup.get(key), muMin);
            BigDecimal b = graduationLast ? ZERO2 : scale2(cValue.multiply(mu));
            BigDecimal scoreA = scale2(a.getScoreA());
            row.setScoreA(scoreA);
            row.setCScore(cValue);
            row.setMu(mu);
            row.setScoreB(b);
            row.setScoreM(scale2(scoreA.add(b)));
            rows.add(row);
        }

        // 3) 排名：专业内 + 同专业年级，两处都用同一份 ROW_ORDER 与 RANK() 语义
        for (List<RankRow> group : groupBy(rows, row -> row.getMajor()).values()) {
            assignMajorRanks(group, nvlInt(batch.getQualifyPct(), 30));
        }
        for (List<RankRow> group : groupBy(rows, row -> row.getMajor() + '\u0001' + row.getGrade()).values()) {
            assignGradeRanks(group, nvlInt(batch.getTier1Pct(), 5), nvlInt(batch.getTier2Pct(), 10),
                    nvlInt(batch.getTier3Pct(), 15));
        }

        // 4) 先 CAS 占住状态再落快照（同一事务；CAS 失败时还没写任何快照）
        int cas = batchMapper.markComputed(id, status, LocalDateTime.now());
        if (cas != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        rowMapper.deleteByBatch(id);
        if (!rows.isEmpty()) {
            rowMapper.batchInsert(rows);
        }

        int totalStudents = profileMapper.count(null, null, null, "active");
        int missingAcademic = academicMapper.listMissingByTerm(term).size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch", batchMapper.findById(id));
        data.put("rankedStudents", rows.size());
        data.put("missingAcademic", missingAcademic);
        data.put("totalStudents", totalStudents);
        data.put("unverifiableNote", UNVERIFIABLE_NOTE);
        return Result.success("综测计算完成", data);
    }

    /** 发布公示（admin）：computed → publicity，公示结束时间按工作日折算。 */
    @Transactional(rollbackFor = Exception.class)
    public Result<?> publicize(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可发布综测公示");
        }
        if (id == null) {
            return Result.error(400, "批次 ID 不能为空");
        }
        RankBatch batch = batchMapper.findById(id);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        if (!B_COMPUTED.equals(batch.getStatus())) {
            return Result.error(400, "只有已计算状态的批次可以发布公示（当前：" + batch.getStatus() + "）");
        }
        int days = batch.getPublicityDays() == null || batch.getPublicityDays() < 1
                ? defaultPublicityDays : batch.getPublicityDays();
        // 截到秒：rank_batch.publicity_start 是 DATETIME(0)，MySQL 会把小数秒**四舍五入**（.7 秒存成下一秒），
        // 而异议校验用的是 Java 侧 LocalDateTime.now()，同秒内提交就会出现「now 早于 publicity_start」的假 400。
        LocalDateTime start = LocalDateTime.now().withNano(0);
        LocalDateTime end = addWorkingDays(start, days);
        int rows = batchMapper.startPublicity(id, B_COMPUTED, days, start, end);
        if (rows != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        return Result.success("已发布公示（" + days + " 个工作日）", batchMapper.findById(id));
    }

    /** 结束公示（admin）：publicity → finished。 */
    public Result<?> finish(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可结束综测公示");
        }
        if (id == null) {
            return Result.error(400, "批次 ID 不能为空");
        }
        RankBatch batch = batchMapper.findById(id);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        int rows = batchMapper.updateStatus(id, B_PUBLICITY, B_FINISHED);
        if (rows != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        return Result.success("公示已结束", batchMapper.findById(id));
    }

    // ---------------------------------------------------------------- 排名读接口

    /** 排名列表（teacher/admin，看全专业，不按辅导员过滤）。 */
    public Result<?> list(Long actorId, String role, Long batchId, String major, String className,
                          String keyword, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限查看综测排名");
        }
        if (batchId == null) {
            return Result.error(400, "请选择批次");
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        String majorArg = trimToNull(major);
        String classArg = trimToNull(className);
        String keywordArg = trimToNull(keyword);
        List<RankRow> items = rowMapper.list(batchId, majorArg, classArg, keywordArg, (p - 1) * s, s);
        int total = rowMapper.count(batchId, majorArg, classArg, keywordArg);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return Result.success(data);
    }

    /** 单个学生明细（teacher/admin）：快照行 + 加分申报 + A 台账。 */
    public Result<?> detail(Long actorId, String role, Long batchId, Long studentId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限查看综测明细");
        }
        if (batchId == null) {
            return Result.error(400, "请选择批次");
        }
        if (studentId == null) {
            return Result.error(400, "请选择学生");
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        // 数据级授权（P0-3）：此前这里只判了「是教师/管理员」，任何教师猜到 userId
        // 就能读任意学生的综测明细（列表页有辅导员范围，明细页没有）。判定口径与列表页
        // 的 scopeCounselor 完全一致：未分派学生的辅导员仍按全校区口径，不另立第二套规则。
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(404, "学生档案不存在");
        }
        Long scope = scopeCounselor(actorId, role);
        if (scope != null && !scope.equals(profile.getCounselorId())) {
            return Result.error(403, "该学生不在你的名下");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("row", rowMapper.findByBatchAndStudent(batchId, studentId));
        data.put("bonuses", EvalBonusService.fillText(bonusMapper.listMine(studentId, batch.getAcademicTerm())));
        data.put("academic", academicMapper.findByStudentTerm(studentId, batch.getAcademicTerm()));
        return Result.success(data);
    }

    /**
     * 我的综测（登录即可，只看自己）：批次 + 快照行 + 加分申报 + C 合计与封顶情况 + 我的异议 + 五维佐证。
     */
    public Result<?> mine(Long actorId, String role, String academicTerm) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        String term = trimToNull(academicTerm);
        RankBatch batch = batchMapper.findLatestByTerm(term);
        if (batch != null) {
            term = batch.getAcademicTerm();
        }
        RankRow row = batch == null ? null : rowMapper.findByBatchAndStudent(batch.getId(), actorId);
        List<EvalBonus> bonuses = EvalBonusService.fillText(bonusMapper.listMine(actorId, term));
        EvalBonusService.StudentC c = term == null ? null : bonusService.computeByStudent(term).get(actorId);
        List<String> capped = c == null ? new ArrayList<>() : new ArrayList<>(c.cappedCategories);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("academicTerm", term);
        data.put("batch", batch);
        data.put("row", row);
        data.put("bonuses", bonuses);
        data.put("bonusTotal", c == null ? ZERO2 : scale2(c.total));
        data.put("cappedCategories", capped);
        data.put("objections", objectionMapper.listMine(actorId));
        data.put("evalDims", evalDims(actorId, term));
        data.put("note", MY_NOTE);
        return Result.success(data);
    }

    /** 公示（必须登录）：脱敏纯数组，不含 A/C/μ/B/M 与名次；非公示批次返回空数组。 */
    public Result<?> publicity(Long actorId, String role, Long batchId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (batchId == null) {
            return Result.error(400, "请选择批次");
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!B_PUBLICITY.equals(batch.getStatus())) {
            return Result.success(rows);
        }
        for (RankRow row : rowMapper.listByBatch(batchId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("studentName", row.getStudentName());
            item.put("studentNoTail", row.getStudentNoTail());
            item.put("major", row.getMajor());
            item.put("className", row.getClassName());
            item.put("grade", row.getGrade());
            item.put("awardTier", row.getAwardTier());
            item.put("awardTierText", row.getAwardTierText());
            rows.add(item);
        }
        return Result.success(rows);
    }

    /** 批次统计（teacher/admin）。 */
    public Result<?> stats(Long actorId, String role, Long batchId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限查看综测统计");
        }
        if (batchId == null) {
            return Result.error(400, "请选择批次");
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        String term = batch.getAcademicTerm();
        List<Map<String, Object>> groups = rowMapper.countGroupByMajorGrade(batchId);
        List<Map<String, Object>> byMajor = new ArrayList<>();
        int tier1 = 0;
        int tier2 = 0;
        int tier3 = 0;
        int qualified = 0;
        for (Map<String, Object> group : groups) {
            int g1 = toInt(group.get("tier1"));
            int g2 = toInt(group.get("tier2"));
            int g3 = toInt(group.get("tier3"));
            tier1 += g1;
            tier2 += g2;
            tier3 += g3;
            qualified += toInt(group.get("qualified"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("major", group.get("major"));
            item.put("grade", group.get("grade"));
            item.put("ranked", toInt(group.get("ranked")));
            item.put("tier1", g1);
            item.put("tier2", g2);
            item.put("tier3", g3);
            byMajor.add(item);
        }
        int pending = 0;
        int accepted = 0;
        int rejected = 0;
        for (Map<String, Object> row : objectionMapper.countGroupByStatus(batchId)) {
            String status = row.get("status") == null ? "" : String.valueOf(row.get("status"));
            int count = toInt(row.get("cnt"));
            if ("pending".equals(status)) {
                pending = count;
            } else if ("accepted".equals(status)) {
                accepted = count;
            } else if ("rejected".equals(status)) {
                rejected = count;
            }
        }
        Map<String, Object> objections = new LinkedHashMap<>();
        objections.put("pending", pending);
        objections.put("accepted", accepted);
        objections.put("rejected", rejected);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch", batch);
        data.put("totalStudents", profileMapper.count(null, null, null, "active"));
        int ranked = rowMapper.count(batchId, null, null, null);
        data.put("rankedStudents", ranked);
        data.put("missingAcademic", academicMapper.listMissingByTerm(term).size());
        data.put("tier1", tier1);
        data.put("tier2", tier2);
        data.put("tier3", tier3);
        data.put("qualified", qualified);
        data.put("byMajor", byMajor);
        data.put("objections", objections);
        data.put("bonusPending", bonusService.pendingCount(term));
        data.put("unverifiableNote", UNVERIFIABLE_NOTE);
        return Result.success(data);
    }

    // ---------------------------------------------------------------- 异议

    /** 学生提交公示异议：只能在自己批次处于公示期（status=publicity 且在起止时间内）时提，且同批次只允许一条 pending。 */
    public Result<?> createObjection(Long actorId, String role, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可提交公示异议");
        }
        Long batchId = body == null ? null : longOf(body.get("batchId"));
        if (batchId == null) {
            return Result.error(400, "请选择批次");
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        if (!inPublicityWindow(batch)) {
            return Result.error(400, "不在公示期内");
        }
        if (objectionMapper.findPending(batchId, actorId) != null) {
            return Result.error(400, "你对该批次已提交过异议，请等待答复");
        }
        String reason = body == null ? null : trimToNull(text(body.get("reason")));
        if (reason == null) {
            return Result.error(400, "请填写异议内容");
        }
        if (reason.length() < 4) {
            return Result.error(400, "异议内容至少 4 个字");
        }
        if (reason.length() > 500) {
            return Result.error(400, "异议内容不能超过 500 个字");
        }
        RankObjection objection = new RankObjection();
        objection.setBatchId(batchId);
        objection.setStudentId(actorId);
        objection.setReason(reason);
        objection.setStatus("pending");
        objectionMapper.insert(objection);
        return Result.success("异议已提交，请在公示期内留意辅导员答复", objectionMapper.findById(objection.getId()));
    }

    /** 我的异议（学生）。 */
    public Result<?> myObjections(Long actorId, String role) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可查看本人的公示异议");
        }
        return Result.success(objectionMapper.listMine(actorId));
    }

    /** 异议列表（teacher/admin；辅导员只看名下学生的）。 */
    public Result<?> listObjections(Long actorId, String role, Long batchId, String status, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限查看公示异议");
        }
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        Long scope = scopeCounselor(actorId, role);
        String statusArg = trimToNull(status);
        List<RankObjection> items = objectionMapper.list(batchId, statusArg, scope, (p - 1) * s, s);
        int total = objectionMapper.count(batchId, statusArg, scope);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", items);
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return Result.success(data);
    }

    /** 答复异议：该生辅导员或 admin；answer 去空白后 ≥4 字；CAS 只处理 pending。 */
    @Transactional(rollbackFor = Exception.class)
    public Result<?> handleObjection(Long actorId, String role, Long id, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限处理公示异议");
        }
        if (id == null) {
            return Result.error(400, "异议 ID 不能为空");
        }
        RankObjection exist = objectionMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "异议不存在");
        }
        if (!"pending".equals(exist.getStatus())) {
            return Result.error(400, "该异议已答复，不能重复处理");
        }
        if (!isAdmin(role)) {
            StudentProfile profile = profileMapper.findByUserId(exist.getStudentId());
            if (profile == null || profile.getCounselorId() == null || !profile.getCounselorId().equals(actorId)) {
                return Result.error(403, "无权处理该学生的异议");
            }
        }
        String verdict = body == null ? null : trimToNull(text(body.get("verdict")));
        if (!"accepted".equals(verdict) && !"rejected".equals(verdict)) {
            return Result.error(400, "答复结论只能是 accepted 或 rejected");
        }
        String answer = body == null ? null : trimToNull(text(body.get("answer")));
        if (answer == null || answer.length() < 4) {
            return Result.error(400, "答复内容至少 4 个字");
        }
        if (answer.length() > 500) {
            return Result.error(400, "答复内容不能超过 500 个字");
        }
        int rows = objectionMapper.handle(id, verdict, answer, actorId);
        if (rows != 1) {
            return Result.error(400, OBJ_CAS_FAIL);
        }
        return Result.success("accepted".equals(verdict) ? "已受理该异议" : "已驳回该异议", objectionMapper.findById(id));
    }

    // ---------------------------------------------------------------- 导出

    /** 导出结果：errorCode 非空表示出错（controller 转 Result.error），否则用 csv + filename。 */
    public static class CsvExport {
        public Integer errorCode;
        public String errorMessage;
        public String csv;
        public String filename;
    }

    /**
     * 导出排名 CSV（admin/teacher）：UTF-8 带 BOM 由控制层补，本方法只产 CSV 文本。
     *
     * <p>表头固定：序号,学号,姓名,专业,班级,年级,学习成绩A,综合素质C,μ,综合素质B,综合测评M,专业排名,专业年级排名,专业人数,奖学金等级,是否具备资格。</p>
     */
    public CsvExport export(Long actorId, String role, Long batchId) {
        CsvExport out = new CsvExport();
        if (actorId == null || !isStaff(role)) {
            out.errorCode = 403;
            out.errorMessage = "无权限导出综测排名";
            return out;
        }
        if (batchId == null) {
            out.errorCode = 400;
            out.errorMessage = "请选择批次";
            return out;
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            out.errorCode = 404;
            out.errorMessage = "批次不存在";
            return out;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("序号,学号,姓名,专业,班级,年级,学习成绩A,综合素质C,μ,综合素质B,综合测评M,")
                .append("专业排名,专业年级排名,专业人数,奖学金等级,是否具备资格\r\n");
        int index = 1;
        for (RankRow row : rowMapper.listByBatch(batchId)) {
            sb.append(csvCell(String.valueOf(index++))).append(',')
                    .append(csvCell(row.getStudentNo())).append(',')
                    .append(csvCell(row.getStudentName())).append(',')
                    .append(csvCell(row.getMajor())).append(',')
                    .append(csvCell(row.getClassName())).append(',')
                    .append(csvCell(row.getGrade())).append(',')
                    .append(csvCell(plain(row.getScoreA()))).append(',')
                    .append(csvCell(plain(row.getCScore()))).append(',')
                    .append(csvCell(plain(row.getMu()))).append(',')
                    .append(csvCell(plain(row.getScoreB()))).append(',')
                    .append(csvCell(plain(row.getScoreM()))).append(',')
                    .append(csvCell(text(row.getRankInMajor()))).append(',')
                    .append(csvCell(text(row.getRankInGradeMajor()))).append(',')
                    .append(csvCell(text(row.getTotalInMajor()))).append(',')
                    .append(csvCell(row.getAwardTierText())).append(',')
                    .append(csvCell(row.getQualified() != null && row.getQualified() == 1 ? "是" : "否"))
                    .append("\r\n");
        }
        out.csv = sb.toString();
        String term = batch.getAcademicTerm() == null ? String.valueOf(batchId) : batch.getAcademicTerm();
        out.filename = "rank-" + term + ".csv";
        return out;
    }

    // ---------------------------------------------------------------- 内部计算

    /** 专业内排名 + 参评资格（专业排名 ≤ 专业人数 × qualify_pct，四舍五入）。 */
    private static void assignMajorRanks(List<RankRow> group, int qualifyPct) {
        group.sort(ROW_ORDER);
        int total = group.size();
        int limit = pct(total, qualifyPct);
        int prevRank = 0;
        BigDecimal prevM = null;
        for (int i = 0; i < total; i++) {
            RankRow row = group.get(i);
            int rank = prevM != null && row.getScoreM().compareTo(prevM) == 0 ? prevRank : i + 1;
            prevRank = rank;
            prevM = row.getScoreM();
            row.setRankInMajor(rank);
            row.setTotalInMajor(total);
            row.setQualified(rank <= limit ? 1 : 0);
        }
    }

    /** 同专业年级排名 + 奖学金等级落位（名额＝年级的专业人数 × 各比例，四舍五入，名额用尽为 NULL）。 */
    private static void assignGradeRanks(List<RankRow> group, int tier1Pct, int tier2Pct, int tier3Pct) {
        group.sort(ROW_ORDER);
        int total = group.size();
        int q1 = pct(total, tier1Pct);
        int q2 = pct(total, tier2Pct);
        int q3 = pct(total, tier3Pct);
        int prevRank = 0;
        BigDecimal prevM = null;
        for (int i = 0; i < total; i++) {
            RankRow row = group.get(i);
            int rank = prevM != null && row.getScoreM().compareTo(prevM) == 0 ? prevRank : i + 1;
            prevRank = rank;
            prevM = row.getScoreM();
            row.setRankInGradeMajor(rank);
            String tier = null;
            if (rank <= q1) {
                tier = TIER1;
            } else if (rank <= q1 + q2) {
                tier = TIER2;
            } else if (rank <= q1 + q2 + q3) {
                tier = TIER3;
            }
            row.setAwardTier(tier);
        }
    }

    /** 名额＝n × pct%，四舍五入（第七条按专业人数×比例四舍五入）。 */
    private static int pct(int n, int pct) {
        if (n <= 0 || pct <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(n)
                .multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /** 公示期＝开始时间起 publicity_days 个工作日（跳过周六周日，与 AidReviewService/HonorService 的做法一致）。 */
    private static LocalDateTime addWorkingDays(LocalDateTime start, int days) {
        LocalDateTime cursor = start;
        int left = days;
        while (left > 0) {
            cursor = cursor.plusDays(1);
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return cursor;
    }

    /** 是否处于公示期（状态 publicity，且当前时间落在公示起止之间）。 */
    private static boolean inPublicityWindow(RankBatch batch) {
        if (batch == null || !B_PUBLICITY.equals(batch.getStatus())) {
            return false;
        }
        // 同样截到秒，与 publicize() 写库口径对齐（见那里的注释）
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (batch.getPublicityStart() != null && now.isBefore(batch.getPublicityStart())) {
            return false;
        }
        return batch.getPublicityEnd() == null || !now.isAfter(batch.getPublicityEnd());
    }

    private static String groupKey(String major, String grade) {
        return nvlText(major, NO_MAJOR) + '\u0001' + nvlText(grade, NO_GRADE);
    }

    private static Map<String, List<RankRow>> groupBy(List<RankRow> rows, java.util.function.Function<RankRow, String> key) {
        Map<String, List<RankRow>> map = new LinkedHashMap<>();
        for (RankRow row : rows) {
            map.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    /** P6 五维记实测评明细（佐证展示）：detail_json 的五维得分 × 权重；拿不到给空数组。 */
    private List<Map<String, Object>> evalDims(Long studentId, String term) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (studentId == null || term == null) {
            return out;
        }
        EvalSummary summary = evalSummaryMapper.findByStudentTerm(studentId, term);
        if (summary == null || trimToNull(summary.getDetailJson()) == null) {
            return out;
        }
        double[] weights = {weightMoral, weightIntellectual, weightPhysical, weightAesthetic, weightLabor};
        JsonNode root;
        try {
            root = objectMapper.readTree(summary.getDetailJson());
        } catch (Exception ex) {
            return out;
        }
        if (root == null || !root.isObject()) {
            return out;
        }
        for (int i = 0; i < DIMENSIONS.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dimension", DIMENSIONS[i]);
            item.put("name", DIMENSION_NAMES[i]);
            JsonNode node = root.get(DIMENSIONS[i]);
            BigDecimal score = node != null && node.isNumber() ? scale2(node.decimalValue()) : null;
            item.put("score", score);
            item.put("weightedScore",
                    score == null ? null : score.multiply(BigDecimal.valueOf(weights[i])).setScale(2, RoundingMode.HALF_UP));
            out.add(item);
        }
        return out;
    }

    // ---------------------------------------------------------------- 批次字段与校验

    /** 学期格式，如 2025-2026-1（与 EvalService / EvalBonusService / EvalAcademicService 同一口径）。 */
    private static final Pattern TERM_PATTERN = Pattern.compile("^\\d{4}-\\d{4}-[12]$");

    /** 增量写：只覆盖 body 里给了非 null 值的字段。 */
    private static String applyBatchFields(RankBatch batch, Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        if (body.get("batchName") != null) {
            batch.setBatchName(trim(text(body.get("batchName"))));
        }
        if (body.get("academicTerm") != null) {
            batch.setAcademicTerm(trim(text(body.get("academicTerm"))));
        }
        if (body.get("noticeTitle") != null) {
            batch.setNoticeTitle(trim(text(body.get("noticeTitle"))));
        }
        if (body.get("noticeUrl") != null) {
            batch.setNoticeUrl(trim(text(body.get("noticeUrl"))));
        }
        if (body.get("graduationLast") != null) {
            Integer v = flag(body.get("graduationLast"));
            if (v == null) {
                return "毕业学年标记只能是 0 或 1";
            }
            batch.setGraduationLast(v);
        }
        if (body.get("muMin") != null) {
            BigDecimal v = dec(body.get("muMin"));
            if (v == null) {
                return "μ 下界必须是数字";
            }
            batch.setMuMin(v);
        }
        if (body.get("muMax") != null) {
            BigDecimal v = dec(body.get("muMax"));
            if (v == null) {
                return "μ 上界必须是数字";
            }
            batch.setMuMax(v);
        }
        if (body.get("qualifyPct") != null) {
            Integer v = integer(body.get("qualifyPct"));
            if (v == null) {
                return "参评资格比例必须是整数";
            }
            batch.setQualifyPct(v);
        }
        if (body.get("tier1Pct") != null) {
            Integer v = integer(body.get("tier1Pct"));
            if (v == null) {
                return "一等奖比例必须是整数";
            }
            batch.setTier1Pct(v);
        }
        if (body.get("tier2Pct") != null) {
            Integer v = integer(body.get("tier2Pct"));
            if (v == null) {
                return "二等奖比例必须是整数";
            }
            batch.setTier2Pct(v);
        }
        if (body.get("tier3Pct") != null) {
            Integer v = integer(body.get("tier3Pct"));
            if (v == null) {
                return "三等奖比例必须是整数";
            }
            batch.setTier3Pct(v);
        }
        if (body.get("publicityDays") != null) {
            Integer v = integer(body.get("publicityDays"));
            if (v == null) {
                return "公示天数必须是整数";
            }
            batch.setPublicityDays(v);
        }
        return null;
    }

    private static String batchProblem(RankBatch batch) {
        if (isBlank(batch.getBatchName())) {
            return "请填写批次名称";
        }
        if (batch.getBatchName().length() > 128) {
            return "批次名称不能超过 128 个字";
        }
        if (isBlank(batch.getAcademicTerm())) {
            return "请填写学期";
        }
        if (batch.getAcademicTerm().length() > 16) {
            return "学期不能超过 16 个字";
        }
        if (!TERM_PATTERN.matcher(batch.getAcademicTerm()).matches()) {
            return "学期格式不正确（应为 2024-2025-1）";
        }
        if (batch.getGraduationLast() == null
                || (batch.getGraduationLast() != 0 && batch.getGraduationLast() != 1)) {
            return "毕业学年标记只能是 0 或 1";
        }
        BigDecimal min = batch.getMuMin();
        BigDecimal max = batch.getMuMax();
        if (min == null || min.compareTo(BigDecimal.ZERO) <= 0 || min.compareTo(BigDecimal.ONE) > 0) {
            return "μ 下界必须在 (0,1] 之间";
        }
        if (max == null || max.compareTo(BigDecimal.ZERO) <= 0 || max.compareTo(BigDecimal.ONE) > 0) {
            return "μ 上界必须在 (0,1] 之间";
        }
        if (min.compareTo(max) > 0) {
            return "μ 下界不能大于上界";
        }
        String problem = pctProblem("参评资格比例（前 %）", batch.getQualifyPct());
        if (problem == null) {
            problem = pctProblem("一等奖比例", batch.getTier1Pct());
        }
        if (problem == null) {
            problem = pctProblem("二等奖比例", batch.getTier2Pct());
        }
        if (problem == null) {
            problem = pctProblem("三等奖比例", batch.getTier3Pct());
        }
        if (problem != null) {
            return problem;
        }
        int sum = batch.getTier1Pct() + batch.getTier2Pct() + batch.getTier3Pct();
        if (sum > 100) {
            return "三个等级的名额比例之和不能超过 100%";
        }
        if (batch.getPublicityDays() == null || batch.getPublicityDays() < 1 || batch.getPublicityDays() > 60) {
            return "公示天数（工作日）必须在 1~60 之间";
        }
        if (batch.getNoticeTitle() != null && batch.getNoticeTitle().length() > 255) {
            return "公示标题不能超过 255 个字";
        }
        if (batch.getNoticeUrl() != null && batch.getNoticeUrl().length() > 255) {
            return "公示链接不能超过 255 个字";
        }
        return null;
    }

    private static String pctProblem(String label, Integer value) {
        if (value == null || value < 0 || value > 100) {
            return label + "必须在 0~100 之间";
        }
        return null;
    }

    /** 辅导员范围：admin → null（全校）；未分派学生的辅导员 → null（照 AidService/HonorService 口径看全校）。 */
    private Long scopeCounselor(Long actorId, String role) {
        if (isAdmin(role)) {
            return null;
        }
        return profileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    // ---------------------------------------------------------------- 小工具

    private static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 布尔/0-1 标记：JSON true/false、数字 0/1、字符串 "true"/"false"/"1"/"0" 都收。 */
    private static Integer flag(Object v) {
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            return (n == 0 || n == 1) ? Integer.valueOf(n) : null;
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "是".equals(s)) {
            return 1;
        }
        if ("false".equals(s) || "0".equals(s) || "否".equals(s)) {
            return 0;
        }
        return null;
    }

    private static Integer integer(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return new BigDecimal(String.valueOf(v)).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v, BigDecimal fallback) {
        return v == null ? fallback : v;
    }

    private static int nvlInt(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    private static String nvlText(String v, String fallback) {
        return isBlank(v) ? fallback : v;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimToNull(String s) {
        String t = trim(s);
        return t == null || t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    private static boolean isStaff(String role) {
        return STAFF_ROLES.contains(role);
    }

    private static boolean isStudent(String role) {
        return "student".equals(role);
    }
}
