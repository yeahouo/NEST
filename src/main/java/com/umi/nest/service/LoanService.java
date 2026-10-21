package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.StudentLoan;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.StudentLoanMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * P19 生源地信用助学贷款登记服务。
 *
 * <p><b>字段口径是学校自定，不是国家标准</b>（依据只有 S6 资助网存在「生源地贷款」栏目这一条第一手事实）：
 * 不内置金额上下限、不内置生源地行政区划代码、不区分经办行、回执校验码按可选处理。
 * 这些取舍写在本类、DDL 头注释与 {@link #policyNote()} 里，页面直接展示 {@code policyNote()}。</p>
 *
 * <p>状态机（唯一权威在这里，每次流转都带 from 条件即 CAS）：</p>
 * <pre>
 *   submitted --辅导员核验通过--> counselor_approved --学校确认--> confirmed
 *   submitted --辅导员退回--> returned --学生改写重交--> submitted
 *   submitted --辅导员不予登记--> rejected（终态）
 *   submitted/returned --学生撤回--> withdrawn（终态）
 * </pre>
 *
 * <p>可见范围：学生只看自己；辅导员按 {@code student_profile.counselor_id} 只看名下学生（名下没有学生则看全校，
 * 与 P13/P18 同一口径）；admin 看全校。确认（confirmed）只有 admin 能做 —— 那是学校资助中心的动作。</p>
 */
@Service("loanService")
public class LoanService {

    /** 学生可改写的状态（其余状态锁定）。 */
    private static final Set<String> EDITABLE = new LinkedHashSet<>(Arrays.asList("submitted", "returned"));

    private static final Set<String> STATUSES = new LinkedHashSet<>(Arrays.asList(
            "submitted", "counselor_approved", "confirmed", "returned", "rejected", "withdrawn"));

    private static final Map<String, String> STATUS_TEXT = new LinkedHashMap<>();

    static {
        STATUS_TEXT.put("submitted", "待核验");
        STATUS_TEXT.put("counselor_approved", "辅导员已核");
        STATUS_TEXT.put("confirmed", "学校已确认");
        STATUS_TEXT.put("returned", "退回待改");
        STATUS_TEXT.put("rejected", "不予登记");
        STATUS_TEXT.put("withdrawn", "已撤回");
    }

    /**
     * 金额的**防误填上限**。这不是政策上限 —— 政策上限随省份/年度/学段不同，系统不代填也不校验。
     * 只需拦住「把 8000 写成 80000000」这类误输入。
     */
    private static final BigDecimal AMOUNT_SANITY_MAX = new BigDecimal("1000000");

    private static final int MAX_TEXT = 64;
    private static final int MAX_REMARK = 255;
    private static final int MAX_REASON = 255;
    private static final int MAX_EXPORT = 5000;
    private static final int MAX_PAGE_SIZE = 100;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private StudentLoanMapper loanMapper;

    @Autowired
    private StudentProfileMapper studentProfileMapper;

    // =========================================================================
    // 0. 口径说明（页面直接展示）
    // =========================================================================

    /**
     * 页面展示用的口径说明。**注意不要在这里写任何国家/省级标准**：
     * 学校资助网只公开了栏目名，没有可引用的字段清单与金额口径。
     */
    public static String policyNote() {
        return "本页登记的是学校自定的生源地信用助学贷款台账：金额、生源地、经办机构由学生按合同填写，"
                + "系统不校验也不代填任何政策上限；回执校验码按可选项处理。"
                + "登记结果只用于学校内部核验与统计，不改变助学金评定结果。";
    }

    // =========================================================================
    // 1. 学生端
    // =========================================================================

    /** 我的登记（分页 + 状态/学年筛选）。 */
    public Result<?> mine(Long userId, String status, String academicYear, int page, int size) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String st = trimToNull(status);
        if (st != null && !STATUSES.contains(st)) {
            return Result.error("状态筛选不正确");
        }
        int p = Math.max(1, page);
        int s = clampSize(size);
        String year = trimToNull(academicYear);
        List<StudentLoan> rows = loanMapper.listMine(userId, st, year, (p - 1) * s, s);
        int total = loanMapper.countMine(userId, st, year);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows.stream().map(this::toView).collect(java.util.stream.Collectors.toList()));
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        data.put("statusText", STATUS_TEXT);
        data.put("policy", policyNote());
        data.put("years", loanMapper.listAcademicYears());
        return Result.success(data);
    }

    /** 我的汇总：各状态计数 + 学年清单 + 已确认金额合计（同一学生可能有多学年）。 */
    public Result<?> summary(Long userId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        List<StudentLoan> all = loanMapper.listMine(userId, null, null, 0, 500);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : STATUSES) {
            counts.put(key, 0);
        }
        BigDecimal confirmed = BigDecimal.ZERO;
        for (StudentLoan row : all) {
            counts.merge(row.getStatus(), 1, Integer::sum);
            if ("confirmed".equals(row.getStatus()) && row.getAmount() != null) {
                confirmed = confirmed.add(row.getAmount());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", all.size());
        data.put("counts", counts);
        data.put("statusText", STATUS_TEXT);
        // 金额是「学生自报 + 学校确认」的台账数，不是发放凭证；页面上要照此措辞
        data.put("confirmedAmount", confirmed.stripTrailingZeros().toPlainString());
        data.put("years", loanMapper.listAcademicYears());
        data.put("policy", policyNote());
        return Result.success(data);
    }

    /** 学生提交登记。 */
    public Result<?> create(Long userId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String[] err = new String[1];
        StudentLoan record = parse(body, err, true);
        if (err[0] != null) {
            return Result.error(err[0]);
        }
        record.setUserId(userId);

        // 同一学年只留一条有效登记（撤回/不予登记的可以重新提交）；这是学校口径，不是政策要求
        List<StudentLoan> sameYear = loanMapper.listMine(userId, null, record.getAcademicYear(), 0, 100);
        for (StudentLoan row : sameYear) {
            if (!"withdrawn".equals(row.getStatus()) && !"rejected".equals(row.getStatus())) {
                return Result.error("本学年已有贷款登记（" + statusText(row.getStatus()) + "），不能重复提交");
            }
        }

        record.setStatus("submitted");
        loanMapper.insert(record);
        return Result.success("已提交，等待辅导员核验", toView(loanMapper.findById(record.getId())));
    }

    /**
     * 学生改写自己的登记。只允许 submitted / returned（CAS 在 SQL 的 WHERE status IN (...) 里），
     * 命中 0 行说明已被辅导员处理 → 报冲突，不让静默覆盖。
     */
    public Result<?> update(Long userId, Long id, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        StudentLoan existing = loanMapper.findById(id);
        if (existing == null || existing.getDeleted() != null && existing.getDeleted() == 1) {
            return Result.error("登记不存在");
        }
        if (!Objects.equals(existing.getUserId(), userId)) {
            return Result.error(403, "只能修改自己的登记");
        }
        if (!EDITABLE.contains(existing.getStatus())) {
            return Result.error("当前状态（" + statusText(existing.getStatus()) + "）不能修改");
        }
        String[] err = new String[1];
        StudentLoan record = parse(body, err, true);
        if (err[0] != null) {
            return Result.error(err[0]);
        }
        record.setId(id);
        record.setUserId(userId);
        int hit = loanMapper.updateByStudent(record);
        if (hit == 0) {
            return Result.error("这条登记的状态已经变了（可能刚被辅导员处理），请刷新后再试");
        }
        return Result.success("已保存并重新提交", toView(loanMapper.findById(id)));
    }

    /** 学生撤回（submitted / returned 可撤）。 */
    public Result<?> withdraw(Long userId, Long id) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        int hit = loanMapper.withdraw(id, userId);
        if (hit == 0) {
            return Result.error("撤回失败：这条登记不是你提交的，或者状态已经变了");
        }
        return Result.success("已撤回", toView(loanMapper.findById(id)));
    }

    /** 学生自助修改「是否到账」。 */
    public Result<?> updateDisbursed(Long userId, Long id, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        StudentLoan existing = loanMapper.findById(id);
        if (existing == null) {
            return Result.error("登记不存在");
        }
        if (!Objects.equals(existing.getUserId(), userId)) {
            return Result.error(403, "只能修改自己的登记");
        }
        String flag = trimToNull(body == null ? null : body.get("disbursed"));
        int value;
        if ("1".equals(flag) || "true".equalsIgnoreCase(flag) || "是".equals(flag) || "已到账".equals(flag)) {
            value = 1;
        } else if ("0".equals(flag) || "false".equalsIgnoreCase(flag) || "否".equals(flag) || "未到账".equals(flag)) {
            value = 0;
        } else {
            return Result.error("到账状态只能填 是 / 否");
        }
        LocalDate at = null;
        String raw = trimToNull(body == null ? null : body.get("disbursedAt"));
        if (raw != null) {
            try {
                at = LocalDate.parse(raw);
            } catch (Exception e) {
                return Result.error("到账日期格式应为 yyyy-MM-dd");
            }
        }
        if (loanMapper.updateDisbursed(id, userId, value, at) == 0) {
            return Result.error("这条登记的状态已经变了，请刷新后再试");
        }
        return Result.success("已更新", toView(loanMapper.findById(id)));
    }

    /** 详情：本人或员工。 */
    public Result<?> detail(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        StudentLoan record = loanMapper.findById(id);
        if (record == null) {
            return Result.error("登记不存在");
        }
        if (!Objects.equals(record.getUserId(), actorId) && !isStaff(role)) {
            return Result.error(403, "无权查看这条登记");
        }
        if (isStaff(role) && !Objects.equals(record.getUserId(), actorId) && !inScope(actorId, role, record.getUserId())) {
            return Result.error(403, "这条登记不在你的名下学生范围内");
        }
        return Result.success(toView(record));
    }

    // =========================================================================
    // 2. 员工端（辅导员 / 资助中心）
    // =========================================================================

    /** 名单：辅导员只看名下学生，admin 看全校。 */
    public Result<?> review(Long actorId, String role, String status, String academicYear, String keyword,
                            int page, int size) {
        Result<?> guard = guardStaff(actorId, role, "查看贷款名单");
        if (guard != null) {
            return guard;
        }
        String st = trimToNull(status);
        if (st != null && !STATUSES.contains(st)) {
            return Result.error("状态筛选不正确");
        }
        Long scope = scopeOf(actorId, role);
        int p = Math.max(1, page);
        int s = clampSize(size);
        String year = trimToNull(academicYear);
        String kw = trimToNull(keyword);
        List<StudentLoan> rows = loanMapper.listForReview(st, year, kw, scope, (p - 1) * s, s);
        int total = loanMapper.countForReview(st, year, kw, scope);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows.stream().map(this::toView).collect(java.util.stream.Collectors.toList()));
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        data.put("statusText", STATUS_TEXT);
        data.put("scope", scope == null ? "all" : "counselor");
        data.put("years", loanMapper.listAcademicYears());
        data.put("policy", policyNote());
        return Result.success(data);
    }

    /** 员工端各状态计数。 */
    public Result<?> reviewStats(Long actorId, String role, String academicYear) {
        Result<?> guard = guardStaff(actorId, role, "查看贷款统计");
        if (guard != null) {
            return guard;
        }
        Long scope = scopeOf(actorId, role);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : STATUSES) {
            counts.put(key, 0);
        }
        int total = 0;
        for (Map<String, Object> row : loanMapper.countByStatus(trimToNull(academicYear), scope)) {
            String key = String.valueOf(row.get("status"));
            int cnt = toInt(row.get("cnt"));
            counts.put(key, cnt);
            total += cnt;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("counts", counts);
        data.put("statusText", STATUS_TEXT);
        data.put("scope", scope == null ? "all" : "counselor");
        data.put("years", loanMapper.listAcademicYears());
        return Result.success(data);
    }

    /**
     * 辅导员核验。
     *
     * @param action approve（通过）/ return（退回）/ reject（不予登记）
     */
    public Result<?> counselorReview(Long actorId, String role, Long id, String action, String reason) {
        Result<?> guard = guardStaff(actorId, role, "核验贷款登记");
        if (guard != null) {
            return guard;
        }
        StudentLoan record = loanMapper.findById(id);
        if (record == null) {
            return Result.error("登记不存在");
        }
        if (!inScope(actorId, role, record.getUserId())) {
            return Result.error(403, "这条登记不在你的名下学生范围内");
        }
        if (!"submitted".equals(record.getStatus())) {
            return Result.error("当前状态（" + statusText(record.getStatus()) + "）不能核验");
        }
        String act = trimToNull(action);
        String to;
        if ("approve".equals(act)) {
            to = "counselor_approved";
        } else if ("return".equals(act)) {
            to = "returned";
        } else if ("reject".equals(act)) {
            to = "rejected";
        } else {
            return Result.error("核验动作只能是通过 / 退回 / 不予登记");
        }
        String why = trimToNull(reason);
        if (("returned".equals(to) || "rejected".equals(to)) && why == null) {
            return Result.error(("returned".equals(to) ? "退回必须填写原因" : "不予登记必须填写原因"));
        }
        if (why != null && why.codePointCount(0, why.length()) > MAX_REASON) {
            return Result.error("原因不能超过 " + MAX_REASON + " 字");
        }
        if (loanMapper.counselorReview(id, "submitted", to, why, actorId) == 0) {
            return Result.error("这条登记的状态已经变了，请刷新后再试");
        }
        return Result.success("已" + (to.equals("counselor_approved") ? "核验通过，等待学校确认" : statusText(to)),
                toView(loanMapper.findById(id)));
    }

    /** 学校确认（只有 admin）。把「辅导员已核」落成「学校已确认」。 */
    public Result<?> confirm(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "只有学校资助中心（管理员）能确认贷款登记");
        }
        StudentLoan record = loanMapper.findById(id);
        if (record == null) {
            return Result.error("登记不存在");
        }
        if (!"counselor_approved".equals(record.getStatus())) {
            return Result.error("当前状态（" + statusText(record.getStatus()) + "）不能确认，需先由辅导员核验通过");
        }
        if (loanMapper.confirm(id, actorId) == 0) {
            return Result.error("这条登记的状态已经变了，请刷新后再试");
        }
        return Result.success("已确认", toView(loanMapper.findById(id)));
    }

    // =========================================================================
    // 3. 导出
    // =========================================================================

    /**
     * CSV 导出（员工；辅导员按名下学生范围）。
     * <b>脱敏</b>：学号只留后 4 位、姓名只留首字；未登录 / 非 staff → errorCode 403（控制器回 403 空 body）。
     */
    public CsvExport exportCsv(Long actorId, String role, String status, String academicYear) {
        CsvExport out = new CsvExport();
        Result<?> guard = guardStaff(actorId, role, "导出贷款台账");
        if (guard != null) {
            out.errorCode = 403;
            out.errorMessage = guard.getMessage();
            return out;
        }
        Long scope = scopeOf(actorId, role);
        List<StudentLoan> rows = loanMapper.listForExport(trimToNull(status), trimToNull(academicYear),
                scope, MAX_EXPORT + 1);
        if (rows.size() > MAX_EXPORT) {
            out.errorCode = 400;
            out.errorMessage = "导出条数超过 " + MAX_EXPORT + " 条上限，请缩小学年范围";
            return out;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("学号,姓名,班级,学年,生源地,经办机构,合同编号,登记金额,到账,状态,辅导员,核验时间,确认时间,提交时间\n");
        for (StudentLoan row : rows) {
            sb.append(csvCell(maskedStudentNo(row.getStudentNo()))).append(',')
                    .append(csvCell(maskedName(row.getStudentName()))).append(',')
                    .append(csvCell(row.getClassName())).append(',')
                    .append(csvCell(row.getAcademicYear())).append(',')
                    .append(csvCell(originOf(row))).append(',')
                    .append(csvCell(row.getBankName())).append(',')
                    .append(csvCell(row.getContractNo())).append(',')
                    .append(csvCell(row.getAmount() == null ? null : row.getAmount().stripTrailingZeros().toPlainString())).append(',')
                    .append(csvCell(row.getDisbursed() != null && row.getDisbursed() == 1 ? "已到账" : "未到账")).append(',')
                    .append(csvCell(statusText(row.getStatus()))).append(',')
                    .append(csvCell(row.getCounselorName())).append(',')
                    .append(csvCell(stamp(row.getCounselorAt()))).append(',')
                    .append(csvCell(stamp(row.getReviewedAt()))).append(',')
                    .append(csvCell(stamp(row.getSubmittedAt()))).append('\n');
        }
        out.csv = sb.toString();
        out.filename = "student-loan-" + LocalDate.now().format(DAY).replace("-", "") + ".csv";
        return out;
    }

    // =========================================================================
    // 4. 健康检查（部署自检用）
    // =========================================================================

    public Result<?> health() {
        List<String> routes = Arrays.asList(
                "GET /api/loan/my",
                "GET /api/loan/summary",
                "POST /api/loan",
                "PUT /api/loan/{id}",
                "POST /api/loan/{id}/withdraw",
                "POST /api/loan/{id}/disbursed",
                "GET /api/loan/{id}",
                "GET /api/loan/review",
                "GET /api/loan/review/stats",
                "POST /api/loan/{id}/counselor-review",
                "POST /api/loan/{id}/confirm",
                "GET /api/loan/export",
                "GET /api/loan/health");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module", "student-loan");
        data.put("routes", routes);
        data.put("statusText", STATUS_TEXT);
        data.put("policy", policyNote());
        return Result.success(data);
    }

    // =========================================================================
    // 5. 校验与工具
    // =========================================================================

    /** 解析并校验表单；create/update 共用（required=true 全部必填）。 */
    private StudentLoan parse(Map<String, String> body, String[] err, boolean required) {
        Map<String, String> b = body == null ? Collections.emptyMap() : body;
        String year = trimToNull(b.get("academicYear"));
        String province = trimToNull(b.get("originProvince"));
        String city = trimToNull(b.get("originCity"));
        String county = trimToNull(b.get("originCounty"));
        String bank = trimToNull(b.get("bankName"));
        String contract = trimToNull(b.get("contractNo"));
        String receipt = trimToNull(b.get("receiptNo"));
        String amountRaw = trimToNull(b.get("amount"));
        String remark = trimToNull(b.get("remark"));
        String evidence = trimToNull(b.get("evidenceUrl"));

        if (year == null) {
            err[0] = "学年不能为空";
            return null;
        }
        String yearErr = checkAcademicYear(year);
        if (yearErr != null) {
            err[0] = yearErr;
            return null;
        }
        if (province == null) {
            err[0] = "生源地省份不能为空";
            return null;
        }
        if (county == null) {
            err[0] = "生源地县/区不能为空（贷款在生源地县级资助中心办理）";
            return null;
        }
        if (bank == null) {
            err[0] = "经办机构不能为空";
            return null;
        }
        if (amountRaw == null) {
            err[0] = "登记金额不能为空";
            return null;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountRaw);
        } catch (Exception e) {
            err[0] = "登记金额必须是数字";
            return null;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            err[0] = "登记金额必须大于 0";
            return null;
        }
        // 防误填上限，不是政策上限（见 AMOUNT_SANITY_MAX 注释）
        if (amount.compareTo(AMOUNT_SANITY_MAX) > 0) {
            err[0] = "登记金额超过防误填上限（" + AMOUNT_SANITY_MAX.stripTrailingZeros().toPlainString()
                    + " 元），请核对是否多输了几位；系统不设政策上限，这条只防误填";
            return null;
        }
        if (amount.scale() > 2) {
            err[0] = "登记金额最多两位小数";
            return null;
        }
        if (tooLong(province, 32) || tooLong(city, 32) || tooLong(county, 64) || tooLong(bank, MAX_TEXT)
                || tooLong(contract, MAX_TEXT) || tooLong(receipt, MAX_TEXT)) {
            err[0] = "文本字段过长（省/市 32 字、县 64 字、其余 64 字以内）";
            return null;
        }
        if (remark != null && remark.codePointCount(0, remark.length()) > MAX_REMARK) {
            err[0] = "备注不能超过 " + MAX_REMARK + " 字";
            return null;
        }
        if (evidence != null && evidence.codePointCount(0, evidence.length()) > MAX_REMARK) {
            err[0] = "佐证材料说明不能超过 " + MAX_REMARK + " 字";
            return null;
        }
        String flag = trimToNull(b.get("disbursed"));
        int disbursed = 0;
        if ("1".equals(flag) || "true".equalsIgnoreCase(flag) || "是".equals(flag)) {
            disbursed = 1;
        }
        LocalDate disbursedAt = null;
        String at = trimToNull(b.get("disbursedAt"));
        if (at != null) {
            try {
                disbursedAt = LocalDate.parse(at);
            } catch (Exception e) {
                err[0] = "到账日期格式应为 yyyy-MM-dd";
                return null;
            }
        }

        StudentLoan record = new StudentLoan();
        record.setAcademicYear(year);
        record.setOriginProvince(province);
        record.setOriginCity(city);
        record.setOriginCounty(county);
        record.setBankName(bank);
        record.setContractNo(contract);
        record.setReceiptNo(receipt);
        record.setAmount(amount);
        record.setDisbursed(disbursed);
        record.setDisbursedAt(disbursedAt);
        record.setRemark(remark);
        record.setEvidenceUrl(evidence);
        return record;
    }

    /** 学年必须是 2026-2027 这种「连续两年」，不做「当前学年」推定（学校口径可能跨年）。 */
    private String checkAcademicYear(String year) {
        if (!year.matches("\\d{4}-\\d{4}")) {
            return "学年格式应为 2026-2027";
        }
        int a;
        int b;
        try {
            a = Integer.parseInt(year.substring(0, 4));
            b = Integer.parseInt(year.substring(5));
        } catch (Exception e) {
            return "学年格式应为 2026-2027";
        }
        if (b != a + 1) {
            return "学年的后一年应比前一年大 1（例：2026-2027）";
        }
        if (a < 2000 || a > 2100) {
            return "学年看起来不像真实年份";
        }
        return null;
    }

    private boolean tooLong(String value, int max) {
        return value != null && value.codePointCount(0, value.length()) > max;
    }

    private Result<?> guardStaff(Long actorId, String role, String what) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "你的账号角色是「" + (role == null ? "未知" : role) + "」，没有" + what + "的权限");
        }
        return null;
    }

    private boolean isStaff(String role) {
        return role != null && ("admin".equalsIgnoreCase(role.trim()) || "teacher".equalsIgnoreCase(role.trim()));
    }

    private boolean isAdmin(String role) {
        return role != null && "admin".equalsIgnoreCase(role.trim());
    }

    /** 数据级授权范围：admin → null（全校）；名下没有学生的辅导员 → null（与 P13/P18 同口径）。 */
    Long scopeOf(Long actorId, String role) {
        if (isAdmin(role)) {
            return null;
        }
        if (actorId == null) {
            return null;
        }
        return studentProfileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    /** 这条登记的学生是否在员工范围内。 */
    private boolean inScope(Long actorId, String role, Long studentId) {
        if (isAdmin(role)) {
            return true;
        }
        if (actorId == null) {
            return false;
        }
        if (Objects.equals(actorId, studentId)) {
            return false;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(studentId);
        if (profile == null || profile.getCounselorId() == null) {
            // 名下没有学生档案的人：与 scopeOf 同口径（全校可见）时才算在范围内
            return studentProfileMapper.countByCounselor(actorId) == 0;
        }
        return Objects.equals(profile.getCounselorId(), actorId);
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String statusText(String status) {
        return STATUS_TEXT.getOrDefault(status, status == null ? "未知" : status);
    }

    private static String originOf(StudentLoan row) {
        StringBuilder sb = new StringBuilder();
        if (row.getOriginProvince() != null) {
            sb.append(row.getOriginProvince());
        }
        if (row.getOriginCity() != null) {
            sb.append('/').append(row.getOriginCity());
        }
        if (row.getOriginCounty() != null) {
            sb.append('/').append(row.getOriginCounty());
        }
        return sb.toString();
    }

    /** 列表视图：把状态换成中文、到账换成中文，便于页面直接渲染。 */
    private Map<String, Object> toView(StudentLoan row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("userId", row.getUserId());
        view.put("studentName", row.getStudentName());
        view.put("studentNo", row.getStudentNo());
        view.put("className", row.getClassName());
        view.put("academicYear", row.getAcademicYear());
        view.put("originProvince", row.getOriginProvince());
        view.put("originCity", row.getOriginCity());
        view.put("originCounty", row.getOriginCounty());
        view.put("origin", originOf(row));
        view.put("bankName", row.getBankName());
        view.put("contractNo", row.getContractNo());
        view.put("receiptNo", row.getReceiptNo());
        view.put("amount", row.getAmount() == null ? null : row.getAmount().stripTrailingZeros().toPlainString());
        view.put("disbursed", row.getDisbursed() != null && row.getDisbursed() == 1);
        view.put("disbursedText", row.getDisbursed() != null && row.getDisbursed() == 1 ? "已到账" : "未到账");
        view.put("disbursedAt", row.getDisbursedAt() == null ? null : row.getDisbursedAt().format(DAY));
        view.put("status", row.getStatus());
        view.put("statusText", statusText(row.getStatus()));
        view.put("returnReason", row.getReturnReason());
        view.put("submittedAt", stamp(row.getSubmittedAt()));
        view.put("counselorName", row.getCounselorName());
        view.put("counselorAt", stamp(row.getCounselorAt()));
        view.put("reviewedAt", stamp(row.getReviewedAt()));
        view.put("remark", row.getRemark());
        view.put("evidenceUrl", row.getEvidenceUrl());
        view.put("editable", EDITABLE.contains(row.getStatus()));
        view.put("canWithdraw", EDITABLE.contains(row.getStatus()));
        view.put("canCounselorReview", "submitted".equals(row.getStatus()));
        view.put("canConfirm", "counselor_approved".equals(row.getStatus()));
        return view;
    }

    private String stamp(LocalDateTime value) {
        return value == null ? "" : value.format(STAMP);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /** CSV 单元格：含逗号/引号/换行时加引号并转义。 */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\r", " ").replace("\n", " ");
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** 导出脱敏：姓名只留首字（与 CommunityService.maskName 同一实现，直接复用）。 */
    private static String maskedName(String value) {
        return CommunityService.maskName(value);
    }

    /** 导出脱敏：学号只留后 4 位（与 CommunityService.tail4 同一实现）。 */
    private static String maskedStudentNo(String value) {
        return CommunityService.tail4(value);
    }

    /** CSV 导出结果（与 P18 同一形态：errorCode 非空即拒绝，控制器回 HTTP 403 空 body）。 */
    public static class CsvExport {
        public int errorCode;
        public String errorMessage;
        public String csv;
        public String filename;
    }
}
