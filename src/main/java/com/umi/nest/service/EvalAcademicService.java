package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.EvalAcademic;
import com.umi.nest.entity.RankBatch;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalAcademicMapper;
import com.umi.nest.mapper.RankBatchMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 学工平台·学习成绩 A 登记（P13）。
 *
 * <p>办法第十一条的 A 由教务部/IT 算好（必修、选修分别加权，选修还要按 80 分基准二次调整），
 * 平台只做登记台账 —— 所以这里没有任何成绩明细，只有一行 (学生, 学期) 的 A。</p>
 *
 * <p>两条写入口：手工登记/修正（{@link #save}）与教务 CSV 导入（{@link #importCsv}）。
 * 导入刻意不加事务：办法要求「逐行失败不影响其它行」，一行错就只记进 failed 明细。</p>
 */
@Service
public class EvalAcademicService {

    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));

    private static final BigDecimal ZERO = new BigDecimal("0");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_CREDITS = new BigDecimal("999.99");

    private static final int MAX_TERM = 16;
    private static final int MAX_NOTE = 255;

    /** 学期格式，如 2025-2026-1（与 EvalService / WorkstudyService 同一口径）。 */
    private static final Pattern TERM_PATTERN = Pattern.compile("^\\d{4}-\\d{4}-[12]$");

    @Autowired
    private EvalAcademicMapper academicMapper;

    @Autowired
    private RankBatchMapper batchMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    // =====================================================================
    // 一、登记 / 修正（PUT /api/rank/academic）
    // =====================================================================

    /**
     * 登记或修正一个学生的 A：唯一键 (student_id, academic_term) 命中即更新，否则新增。
     *
     * <p>增量语义：没传的字段保持原值，但 A 本身必填（台账存在的意义就是 A）。</p>
     */
    public Result<?> save(Long actorId, String role, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限登记学习成绩");
        }
        if (body == null) {
            return Result.error(400, "缺少登记内容");
        }
        Long studentId = asLong(body.get("studentId"));
        if (studentId == null) {
            return Result.error(400, "请选择学生");
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(400, "该学生没有学工档案，无法登记学习成绩");
        }
        String term = trim(str(body, "academicTerm"));
        if (isBlank(term)) {
            return Result.error(400, "请填写综测学期（如 2024-2025-1）");
        }
        if (term.length() > MAX_TERM) {
            return Result.error(400, "综测学期不能超过 " + MAX_TERM + " 字");
        }
        if (!TERM_PATTERN.matcher(term).matches()) {
            return Result.error(400, "学期格式不正确（应为 2024-2025-1）");
        }
        BigDecimal scoreA = dec(body.get("scoreA"));
        if (scoreA == null) {
            return Result.error(400, "学习成绩 A 不能为空");
        }
        String problem = rangeProblem("学习成绩 A", scoreA, ZERO, HUNDRED);
        if (problem == null) {
            problem = rangeProblem("必修加权", dec(body.get("requiredAvg")), ZERO, HUNDRED);
        }
        if (problem == null) {
            problem = rangeProblem("选修加权", dec(body.get("electiveAvg")), ZERO, HUNDRED);
        }
        if (problem == null) {
            problem = rangeProblem("总学分", dec(body.get("totalCredits")), ZERO, MAX_CREDITS);
        }
        if (problem == null) {
            Integer courseCount = asInt(body.get("courseCount"));
            if (courseCount != null && (courseCount < 0 || courseCount > 99)) {
                problem = "课程门数应在 0~99 之间";
            }
        }
        if (problem == null) {
            String note = trim(str(body, "note"));
            if (note != null && note.length() > MAX_NOTE) {
                problem = "备注不能超过 " + MAX_NOTE + " 字";
            }
        }
        if (problem != null) {
            return Result.error(400, problem);
        }

        EvalAcademic existing = academicMapper.findByStudentTerm(studentId, term);
        boolean created = existing == null;
        EvalAcademic record = created ? new EvalAcademic() : existing;
        record.setStudentId(studentId);
        record.setAcademicTerm(term);
        record.setScoreA(scale2(scoreA));
        // 未传的字段保持原值（新建时就是 null），保证「只改一处不会抹掉别处」
        if (body.containsKey("requiredAvg")) {
            record.setRequiredAvg(scale2(dec(body.get("requiredAvg"))));
        }
        if (body.containsKey("electiveAvg")) {
            record.setElectiveAvg(scale2(dec(body.get("electiveAvg"))));
        }
        if (body.containsKey("totalCredits")) {
            record.setTotalCredits(scale2(dec(body.get("totalCredits"))));
        }
        if (body.containsKey("courseCount")) {
            record.setCourseCount(asInt(body.get("courseCount")));
        }
        if (body.containsKey("note")) {
            record.setNote(trimToNull(str(body, "note")));
        }
        record.setSource("manual");
        record.setImportedBy(actorId);
        if (created) {
            academicMapper.insert(record);
        } else {
            academicMapper.update(record);
        }
        return Result.success(created ? "学习成绩已登记" : "学习成绩已修正",
                academicMapper.findByStudentTerm(studentId, term));
    }

    // =====================================================================
    // 二、CSV 导入（POST /api/rank/academic/import）
    // =====================================================================

    /**
     * CSV 导入，列顺序：学号,学习成绩A,必修加权,选修加权,总学分,课程门数。
     *
     * <p>逐行独立：某一行学号查不到 / 数字不合法，只把该行记进 failed，其余行照常入库。</p>
     */
    public Result<?> importCsv(Long actorId, String role, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限导入学习成绩");
        }
        if (body == null) {
            return Result.error(400, "缺少导入内容");
        }
        String term = trim(str(body, "academicTerm"));
        if (isBlank(term)) {
            return Result.error(400, "请填写综测学期（如 2024-2025-1）");
        }
        if (term.length() > MAX_TERM) {
            return Result.error(400, "综测学期不能超过 " + MAX_TERM + " 字");
        }
        if (!TERM_PATTERN.matcher(term).matches()) {
            return Result.error(400, "学期格式不正确（应为 2024-2025-1）");
        }
        String csv = str(body, "csv");
        if (csv == null || csv.trim().isEmpty()) {
            return Result.error(400, "请粘贴 CSV 内容");
        }

        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int inserted = 0;
        int updated = 0;
        boolean headerChecked = false;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            try {
                String[] cells = line.split(",", -1);
                // 只认第一行非空行是不是表头（学号 三个字），后面的行不因为「前面都失败了」而被当表头跳过
                if (!headerChecked) {
                    headerChecked = true;
                    if (cleanCell(cells[0]).contains("学号")) {
                        continue;
                    }
                }
                if (cells.length < 2) {
                    throw new IllegalArgumentException("列数不足，至少需要「学号,学习成绩A」两列");
                }
                String studentNo = cleanCell(cells[0]);
                if (isBlank(studentNo)) {
                    throw new IllegalArgumentException("学号不能为空");
                }
                Long studentId = academicMapper.findUserIdByStudentNo(studentNo);
                if (studentId == null) {
                    throw new IllegalArgumentException("学号 " + studentNo + " 不在学生档案里");
                }
                BigDecimal scoreA = dec(cleanCell(cells[1]));
                if (scoreA == null) {
                    throw new IllegalArgumentException("学习成绩 A 不是数字：" + cleanCell(cells[1]));
                }
                String bad = rangeProblem("学习成绩 A", scoreA, ZERO, HUNDRED);
                if (bad == null) {
                    bad = rangeProblem("必修加权", decOrNull(cell(cells, 2)), ZERO, HUNDRED);
                }
                if (bad == null) {
                    bad = rangeProblem("选修加权", decOrNull(cell(cells, 3)), ZERO, HUNDRED);
                }
                if (bad == null) {
                    bad = rangeProblem("总学分", decOrNull(cell(cells, 4)), ZERO, MAX_CREDITS);
                }
                Integer courseCount = intOrNull(cell(cells, 5));
                if (bad == null && courseCount != null && (courseCount < 0 || courseCount > 99)) {
                    bad = "课程门数应在 0~99 之间";
                }
                if (bad != null) {
                    throw new IllegalArgumentException(bad);
                }

                EvalAcademic existing = academicMapper.findByStudentTerm(studentId, term);
                boolean created = existing == null;
                EvalAcademic record = created ? new EvalAcademic() : existing;
                record.setStudentId(studentId);
                record.setAcademicTerm(term);
                record.setScoreA(scale2(scoreA));
                // 导入时 CSV 没给的列不覆盖已有值（导一半的列不该把手工登记的内容抹掉）
                if (cell(cells, 2) != null) {
                    record.setRequiredAvg(scale2(dec(cell(cells, 2))));
                }
                if (cell(cells, 3) != null) {
                    record.setElectiveAvg(scale2(dec(cell(cells, 3))));
                }
                if (cell(cells, 4) != null) {
                    record.setTotalCredits(scale2(dec(cell(cells, 4))));
                }
                if (courseCount != null) {
                    record.setCourseCount(courseCount);
                }
                record.setSource("import");
                record.setImportedBy(actorId);
                if (created) {
                    academicMapper.insert(record);
                    inserted++;
                } else {
                    academicMapper.update(record);
                    updated++;
                }
            } catch (IllegalArgumentException e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("line", lineNo);
                item.put("message", e.getMessage());
                failed.add(item);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", inserted);
        data.put("updated", updated);
        data.put("failed", failed);
        return Result.success("导入完成：新增 " + inserted + " 条，更新 " + updated
                + " 条，失败 " + failed.size() + " 条", data);
    }

    // =====================================================================
    // 三、待补清单（GET /api/rank/academic/missing）
    // =====================================================================

    /** 该批次学期里「没登记 A」的在读学生（没有台账行，或有行但 A 为空）。 */
    public Result<?> missing(Long actorId, String role, Long batchId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限查看学习成绩待补清单");
        }
        if (batchId == null) {
            return Result.error(400, "请选择批次");
        }
        RankBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        List<Map<String, Object>> rows = academicMapper.listMissingByTerm(batch.getAcademicTerm());
        // 契约要求返回纯数组（学期由批次本身携带，列表页不需要再解析一层）
        return Result.success(rows == null ? new ArrayList<>() : rows);
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private static boolean isStaff(String role) {
        return role != null && STAFF_ROLES.contains(role);
    }

    /** 传了就必须落在 [min, max]，没传（null）视为没问题。 */
    private static String rangeProblem(String label, BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            return label + "应在 " + plain(min) + "~" + plain(max) + " 之间";
        }
        return null;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String cell(String[] cells, int index) {
        if (index >= cells.length) {
            return null;
        }
        return trimToNull(cleanCell(cells[index]));
    }

    /** 去掉 Excel 导出常见的包裹引号与 BOM。 */
    private static String cleanCell(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\uFEFF", "").trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long asLong(Object v) {
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

    private static Integer asInt(Object v) {
        Long l = asLong(v);
        return l == null ? null : l.intValue();
    }

    private static Integer intOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
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

    private static BigDecimal decOrNull(String s) {
        return s == null ? null : dec(s);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimToNull(String s) {
        String t = trim(s);
        return (t == null || t.isEmpty()) ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
