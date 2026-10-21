package com.umi.nest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.AidApplication;
import com.umi.nest.entity.AidObjection;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.AidApplicationMapper;
import com.umi.nest.mapper.AidObjectionMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 家庭经济困难学生认定（P11）业务服务。
 *
 * <p>依据：《厦门大学嘉庚学院家庭经济困难学生认定工作实施的意见》。</p>
 *
 * <p><b>与 P5 的边界</b>：本类不改动 {@link AidService} 的任何方法语义。
 * aid_application 中只有 aid_type ∈ {need, 困难认定} 的行走本流程；助学金 / 临时困难补助 / 勤工助学
 * 仍由 AidService 处理。级别两档（特困 / 贫困）写在 poverty_level 列，与 P5 的 level（general/difficult/special）
 * 并存不互相覆盖。</p>
 *
 * <p><b>七步状态机</b>（review_stage，顺序推进，非法流转一律 400）：</p>
 * <pre>
 * apply 学生提交
 *   → committee 民主评议（committee_note + pass/reject）
 *   → workgroup 院系认定工作组审核定级（poverty_level ∈ 特困/贫困）
 *   → publicity 公示（publicity_start / publicity_end=+5 个工作日 / objection_deadline=publicity_end）
 *   → objection 复议（学生提、管理员答复，答复完毕回到 publicity）
 *   → report 院系书面评审报告（report_note）
 *   → center 资助管理中心审核
 *   → leader 领导小组审批
 *   → 终态 approved / rejected
 * </pre>
 * 说明：文档称「七步」，落到状态机是 8 个非终态 stage 值 —— 第 7 步（资助中心审核 + 领导小组审批）
 * 在本实现里是两个 stage（center / leader），{@link #stageDefinitions()} 与报告里都写明了这一点。
 */
@Service
public class AidReviewService {

    // =====================================================================
    // 一、常量（对外契约：级别、阶段、结论、十类不能认定）
    // =====================================================================

    /** 认定申请在 aid_application.aid_type 上的英文码（与 P5 保持一致）。 */
    public static final String AID_TYPE_REVIEW = "need";

    /** 认定申请的历史中文码（口径里写的是「困难认定」，迁移脚本两种都认）。 */
    public static final String AID_TYPE_REVIEW_CN = "困难认定";

    /** 级别只有两档。 */
    public static final String LEVEL_EXTREME = "特困";
    public static final String LEVEL_POOR = "贫困";
    public static final List<String> LEVELS = Collections.unmodifiableList(
            Arrays.asList(LEVEL_EXTREME, LEVEL_POOR));

    /** 状态机阶段码（终态 approved / rejected 不在 {@link #STAGES} 里）。 */
    public static final String STAGE_APPLY = "apply";
    public static final String STAGE_COMMITTEE = "committee";
    public static final String STAGE_WORKGROUP = "workgroup";
    public static final String STAGE_PUBLICITY = "publicity";
    public static final String STAGE_OBJECTION = "objection";
    public static final String STAGE_REPORT = "report";
    public static final String STAGE_CENTER = "center";
    public static final String STAGE_LEADER = "leader";
    public static final String STAGE_APPROVED = "approved";
    public static final String STAGE_REJECTED = "rejected";

    /** 8 个非终态阶段（顺序即流程顺序）。 */
    public static final List<String> STAGES = Collections.unmodifiableList(Arrays.asList(
            STAGE_APPLY, STAGE_COMMITTEE, STAGE_WORKGROUP, STAGE_PUBLICITY,
            STAGE_OBJECTION, STAGE_REPORT, STAGE_CENTER, STAGE_LEADER));

    /** 阶段中文名（进度条 / stageText 用）。 */
    private static final Map<String, String> STAGE_TEXTS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(STAGE_APPLY, "学生提交申请");
        m.put(STAGE_COMMITTEE, "民主评议");
        m.put(STAGE_WORKGROUP, "院系认定工作组审核定级");
        m.put(STAGE_PUBLICITY, "公示中");
        m.put(STAGE_OBJECTION, "复议处理中");
        m.put(STAGE_REPORT, "院系书面评审报告");
        m.put(STAGE_CENTER, "资助管理中心审核");
        m.put(STAGE_LEADER, "领导小组审批");
        m.put(STAGE_APPROVED, "已批准");
        m.put(STAGE_REJECTED, "未通过");
        STAGE_TEXTS = Collections.unmodifiableMap(m);
    }

    /** 申请状态（沿用 P5 的 status 取值）。 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    /** 民主评议结论。 */
    public static final String CONCLUSION_PASS = "pass";
    public static final String CONCLUSION_REJECT = "reject";

    /** 复议状态：pending 待答复 / accepted 采纳 / rejected 不采纳。 */
    public static final String OBJECTION_PENDING = "pending";
    public static final String OBJECTION_ACCEPTED = "accepted";
    public static final String OBJECTION_REJECTED = "rejected";
    public static final List<String> OBJECTION_STATUSES = Collections.unmodifiableList(
            Arrays.asList(OBJECTION_PENDING, OBJECTION_ACCEPTED, OBJECTION_REJECTED));

    /** 答复结论（与复议状态同值，库表 status 直接落结论）。 */
    public static final String VERDICT_ACCEPTED = OBJECTION_ACCEPTED;
    public static final String VERDICT_REJECTED = OBJECTION_REJECTED;

    /** 审批环节角色：center 资助管理中心 / leader 领导小组。 */
    public static final String AUDIT_ROLE_CENTER = "center";
    public static final String AUDIT_ROLE_LEADER = "leader";

    /** 公示 5 个工作日；复议答复期限 5 个工作日。 */
    public static final int PUBLICITY_WORKING_DAYS = 5;
    public static final int OBJECTION_WORKING_DAYS = 5;

    /** 校验边界（与 P5 的 MIN_REASON / MAX_REASON 口径一致）。 */
    public static final int MIN_REASON = 4;
    public static final int MAX_REASON = 500;
    public static final int MAX_NOTE = 1000;
    public static final int MAX_EVIDENCE = 250;
    public static final int MAX_MATERIAL_ITEM = 120;
    public static final int MATERIAL_COUNT = 4;
    public static final int MAX_PAGE_SIZE = 100;

    /** 十类不能认定（已获资助的取消资格）：code 是稳定契约，text 是中文说明。 */
    public static final List<Map<String, String>> DISQUALIFY_REASONS;

    static {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(reason("HIGH_END_PHONE", "拥有或使用高档通讯工具"));
        list.add(reason("EXPENSIVE_COMPUTER", "拥有或使用昂贵电脑"));
        list.add(reason("LUXURY_GOODS", "购买或使用奢侈品"));
        list.add(reason("FREQUENT_TRAVEL", "经常旅游"));
        list.add(reason("RENT_OR_HIGH_END_SPENDING", "校外租房或在高档场所消费"));
        list.add(reason("HUGE_DEBT_BY_BUILDING_CAR_INVESTMENT", "因建房、购车、投资欠下巨额债务"));
        list.add(reason("FAMILY_IMPROVED", "家庭经济状况已明显改善"));
        list.add(reason("SUSPENDED_SCHOOLING", "休学或保留入学资格期间"));
        list.add(reason("FALSE_REPORT", "谎报家庭经济状况"));
        list.add(reason("OTHER_INELIGIBLE", "其他不符合认定条件的情形"));
        DISQUALIFY_REASONS = Collections.unmodifiableList(list);
    }

    private static Map<String, String> reason(String code, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("text", text);
        return m;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // =====================================================================
    // 二、依赖
    // =====================================================================

    private final AidApplicationMapper mapper;
    private final AidObjectionMapper objectionMapper;
    private final StudentProfileMapper profileMapper;

    public AidReviewService(AidApplicationMapper mapper,
                            AidObjectionMapper objectionMapper,
                            StudentProfileMapper profileMapper) {
        this.mapper = mapper;
        this.objectionMapper = objectionMapper;
        this.profileMapper = profileMapper;
    }

    // =====================================================================
    // 三、学生端
    // =====================================================================

    /**
     * POST /api/aid/poverty/apply —— 学生提交认定申请。
     *
     * <p>同一学年（cert_year）重复提交 → 400。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result apply(Long studentId, String role, String certYear, String reason,
                        List<String> materials, String familyNote) {
        if (!"student".equals(role)) {
            return Result.error(403, "仅学生可提交家庭经济困难认定申请");
        }
        if (studentId == null) {
            return Result.error(401, "未登录");
        }
        String cy = trim(certYear);
        String certErr = validateCertYear(cy);
        if (certErr != null) {
            return Result.error(certErr);
        }
        String rz = trim(reason);
        if (rz == null) {
            return Result.error("申请理由不能为空");
        }
        if (rz.length() < MIN_REASON || rz.length() > MAX_REASON) {
            return Result.error("申请理由需 " + MIN_REASON + "~" + MAX_REASON + " 字");
        }
        if (materials == null || materials.size() != MATERIAL_COUNT) {
            return Result.error("需提交 " + MATERIAL_COUNT + " 项证明材料");
        }
        List<String> ms = new ArrayList<>();
        for (String item : materials) {
            String t = trim(item);
            if (t == null) {
                return Result.error("证明材料内容不能为空");
            }
            if (t.length() > MAX_MATERIAL_ITEM) {
                return Result.error("单项证明材料描述不超过 " + MAX_MATERIAL_ITEM + " 字");
            }
            ms.add(t);
        }
        String fn = trim(familyNote);
        if (fn != null && fn.length() > MAX_NOTE) {
            return Result.error("家庭情况说明不超过 " + MAX_NOTE + " 字");
        }
        if (mapper.countCertApplications(studentId, cy) > 0) {
            return Result.error("该学年已提交过家庭经济困难认定申请，请勿重复提交");
        }
        String json;
        try {
            json = JSON.writeValueAsString(ms);
        } catch (Exception e) {
            return Result.error("证明材料格式错误");
        }
        AidApplication app = new AidApplication();
        app.setStudentId(studentId);
        app.setAidType(AID_TYPE_REVIEW);
        app.setReason(rz);
        app.setEvidence(truncate(String.join("|", ms), MAX_EVIDENCE));
        app.setMaterials(json);
        app.setFamilyNote(fn);
        app.setStatus(STATUS_PENDING);
        app.setAcademicYear(cy);
        app.setCertYear(cy);
        app.setReviewStage(STAGE_APPLY);
        mapper.insertReview(app);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", app.getId());
        data.put("certYear", cy);
        data.put("reviewStage", STAGE_APPLY);
        data.put("stageText", stageText(STAGE_APPLY));
        data.put("status", STATUS_PENDING);
        data.put("materials", ms);
        return Result.success("认定申请已提交，等待民主评议", data);
    }

    /** GET /api/aid/poverty/mine —— 本人认定申请（含阶段、级别、公示期、复议）。 */
    public Result mine(Long studentId, String role) {
        if (!"student".equals(role)) {
            return Result.error(403, "仅学生可查看本人的认定申请");
        }
        List<AidApplication> all = nullSafe(mapper.listMineReview(studentId));
        AidApplication latest = all.isEmpty() ? null : all.get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", latest == null ? null : studentView(latest));
        data.put("applications", studentViews(all));
        data.put("stage", latest == null ? null : latest.getReviewStage());
        data.put("stageText", latest == null ? null : stageText(latest.getReviewStage()));
        data.put("level", latest == null ? null : latest.getPovertyLevel());
        data.put("publicityStart", latest == null ? null : latest.getPublicityStart());
        data.put("publicityEnd", latest == null ? null : latest.getPublicityEnd());
        data.put("objectionDeadline", latest == null ? null : latest.getObjectionDeadline());
        List<AidObjection> objections = latest == null
                ? Collections.<AidObjection>emptyList()
                : nullSafe(objectionMapper.listByApplication(latest.getId()));
        data.put("objections", objectionViews(objections));
        data.put("levels", LEVELS);
        return Result.success(data);
    }

    /** GET /api/aid/poverty/stages —— 流程阶段定义（顺序 + 中文名），供前端画进度条。 */
    public Result stages() {
        return Result.success("家庭经济困难学生认定流程", stageDefinitions());
    }

    /**
     * POST /api/aid/objection —— 学生提交公示异议（复议）。
     *
     * <p>只能对自己、只在公示期（objection_deadline 之前）内；过期 400。
     * 提交后申请进入 objection 阶段，答复期限 = 提交时间 + 5 个工作日。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result submitObjection(Long studentId, String role, Long applicationId,
                                  String content, String evidence) {
        if (!"student".equals(role)) {
            return Result.error(403, "仅学生可提交公示复议");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        AidApplication app = mapper.findById(applicationId);
        if (app == null) {
            return Result.error("认定申请不存在");
        }
        if (!isReviewApplication(app)) {
            return Result.error("该申请不是家庭经济困难认定申请");
        }
        if (!Objects.equals(app.getStudentId(), studentId)) {
            return Result.error(403, "只能对本人的认定申请提交复议");
        }
        String stage = app.getReviewStage();
        if (!STAGE_PUBLICITY.equals(stage) && !STAGE_OBJECTION.equals(stage)) {
            return Result.error("当前阶段不在公示期内，不能提交复议");
        }
        LocalDateTime deadline = app.getObjectionDeadline();
        if (deadline == null) {
            return Result.error("公示尚未发布，不能提交复议");
        }
        if (LocalDateTime.now().isAfter(deadline)) {
            return Result.error("公示期已结束，不能提交复议");
        }
        String c = trim(content);
        if (c == null) {
            return Result.error("复议内容不能为空");
        }
        if (c.length() > MAX_NOTE) {
            return Result.error("复议内容不超过 " + MAX_NOTE + " 字");
        }
        String ev = trim(evidence);
        if (ev != null && ev.length() > MAX_NOTE) {
            return Result.error("佐证材料说明不超过 " + MAX_NOTE + " 字");
        }
        LocalDateTime answerDeadline = addWorkingDays(LocalDateTime.now(), OBJECTION_WORKING_DAYS);
        AidObjection o = new AidObjection();
        o.setApplicationId(applicationId);
        o.setStudentId(app.getStudentId());
        o.setObjectorId(studentId);
        o.setObjectorName(app.getStudentName());
        o.setContent(c);
        o.setEvidence(ev);
        o.setStatus(OBJECTION_PENDING);
        o.setDeadline(answerDeadline);
        String nextStage = stage;
        if (STAGE_PUBLICITY.equals(stage)) {
            if (mapper.advanceStage(applicationId, STAGE_PUBLICITY, STAGE_OBJECTION) == 0) {
                return Result.error("认定状态已变更，请刷新后重试");
            }
            nextStage = STAGE_OBJECTION;
        }
        objectionMapper.insert(o);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", o.getId());
        data.put("applicationId", applicationId);
        data.put("status", OBJECTION_PENDING);
        data.put("deadline", answerDeadline);
        data.put("reviewStage", nextStage);
        data.put("stageText", stageText(nextStage));
        return Result.success("复议已提交，将在 5 个工作日内答复", data);
    }

    /** GET /api/aid/objection/mine —— 本人复议记录。 */
    public Result myObjections(Long studentId, String role) {
        if (!"student".equals(role)) {
            return Result.error(403, "仅学生可查看本人的复议记录");
        }
        return Result.success(objectionViews(nullSafe(objectionMapper.listMine(studentId))));
    }

    /**
     * GET /api/aid/publicity —— 公示名单。
     *
     * <p><b>隐私口径</b>：只返回 name / gender / level 三个键，
     * 绝不带身份证号、家庭成员、收入、学号。</p>
     */
    public Result publicity(String certYear) {
        List<AidApplication> rows = nullSafe(mapper.listPublicity(trim(certYear)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AidApplication a : rows) {
            if (a == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", a.getStudentName());
            item.put("gender", genderText(a.getGender()));
            item.put("level", a.getPovertyLevel());
            out.add(item);
        }
        return Result.success(out);
    }

    // =====================================================================
    // 四、管理端（teacher=辅导员，范围限 student_profile.counselor_id；admin 全校）
    // =====================================================================

    /** GET /api/aid/review/list —— 认定申请列表。 */
    public Result list(Long actorId, String role, String scope, String stage, String certYear,
                       String keyword, Integer page, Integer size) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可查看认定申请");
        }
        String st = trim(stage);
        if (st != null && !STAGES.contains(st) && !STAGE_APPROVED.equals(st) && !STAGE_REJECTED.equals(st)) {
            return Result.error("未知的认定阶段：" + st);
        }
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Long scopeCounselorId = scopeOf(actorId, role, scope);
        String cy = trim(certYear);
        String kw = trim(keyword);
        List<AidApplication> rows = nullSafe(mapper.listReview(st, cy, kw, scopeCounselorId, (p - 1) * s, s));
        int total = mapper.countReview(st, cy, kw, scopeCounselorId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", briefViews(rows));
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return Result.success(data);
    }

    /** GET /api/aid/review/detail/{id} —— 管理端详情（含证据链）。 */
    public Result detail(Long actorId, String role, String scope, Long id) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可查看认定申请详情");
        }
        if (id == null) {
            return Result.error("申请 ID 不能为空");
        }
        AidApplication app = mapper.findById(id);
        if (app == null) {
            return Result.error("认定申请不存在");
        }
        if (!isReviewApplication(app)) {
            return Result.error("该申请不是家庭经济困难认定申请");
        }
        if (!canTouch(actorId, role, app.getStudentId())) {
            return Result.error(403, "无权查看该学生的认定申请");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", studentView(app));
        data.put("objections", objectionViews(nullSafe(objectionMapper.listByApplication(id))));
        data.put("levels", LEVELS);
        data.put("disqualifyReasons", DISQUALIFY_REASONS);
        data.put("stages", stageDefinitions());
        return Result.success(data);
    }

    /** POST /api/aid/review/committee —— 民主评议：写 committee_note + 结论，推进状态机。 */
    @Transactional(rollbackFor = Exception.class)
    public Result committee(Long actorId, String role, String scope, Long applicationId,
                            String committeeNote, String conclusion) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可开展民主评议");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        String note = trim(committeeNote);
        if (note == null) {
            return Result.error("民主评议意见不能为空");
        }
        if (note.length() > MAX_NOTE) {
            return Result.error("民主评议意见不超过 " + MAX_NOTE + " 字");
        }
        String cc = trim(conclusion);
        if (!CONCLUSION_PASS.equals(cc) && !CONCLUSION_REJECT.equals(cc)) {
            return Result.error("民主评议结论只能是 pass 或 reject");
        }
        Result guard = guardStaffApplication(actorId, role, scope, applicationId);
        if (guard != null) {
            return guard;
        }
        AidApplication app = mapper.findById(applicationId);
        if (!STAGE_APPLY.equals(app.getReviewStage())) {
            return Result.error("当前阶段不是待民主评议，不能提交评议结论");
        }
        if (CONCLUSION_PASS.equals(cc)) {
            if (mapper.committeePass(applicationId, STAGE_APPLY, STAGE_WORKGROUP, note) == 0) {
                return Result.error("认定状态已变更，请刷新后重试");
            }
            return Result.success("民主评议通过，已提交院系认定工作组审核定级",
                    stageView(app, STAGE_WORKGROUP, STATUS_PENDING));
        }
        if (mapper.rejectApplication(applicationId, STAGE_APPLY, "民主评议未通过：" + note) == 0) {
            return Result.error("认定状态已变更，请刷新后重试");
        }
        return Result.success("民主评议未通过，认定申请已结束",
                stageView(app, STAGE_REJECTED, STATUS_REJECTED));
    }

    /**
     * POST /api/aid/review/level —— 院系认定工作组审核定级。
     *
     * <p>级别只能 特困 / 贫困；勾选任一条「十类不能认定」情形 ⇒ 直接 rejected 并记录原因。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result level(Long actorId, String role, String scope, Long applicationId,
                        String level, List<String> disqualifyReasons, String note) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可审核定级");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        String lv = trim(level);
        if (!isValidLevel(lv)) {
            return Result.error("认定级别只能是 " + LEVEL_EXTREME + " 或 " + LEVEL_POOR);
        }
        String nt = trim(note);
        if (nt != null && nt.length() > MAX_NOTE) {
            return Result.error("审核意见不超过 " + MAX_NOTE + " 字");
        }
        List<String> matched;
        try {
            matched = matchReasonTexts(disqualifyReasons);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
        Result guard = guardStaffApplication(actorId, role, scope, applicationId);
        if (guard != null) {
            return guard;
        }
        AidApplication app = mapper.findById(applicationId);
        if (!STAGE_WORKGROUP.equals(app.getReviewStage())) {
            return Result.error("当前阶段不是院系认定工作组审核定级阶段");
        }
        if (!matched.isEmpty()) {
            String why = disqualifyNote(matched, nt);
            if (mapper.rejectApplication(applicationId, STAGE_WORKGROUP, why) == 0) {
                return Result.error("认定状态已变更，请刷新后重试");
            }
            return Result.success("存在不能认定的情形，认定申请已结束",
                    stageView(app, STAGE_REJECTED, STATUS_REJECTED));
        }
        if (mapper.setLevel(applicationId, STAGE_WORKGROUP, STAGE_PUBLICITY, lv, nt) == 0) {
            return Result.error("认定状态已变更，请刷新后重试");
        }
        return Result.success("已认定级别为" + lv + "，进入公示阶段",
                stageView(app, STAGE_PUBLICITY, STATUS_PENDING, lv));
    }

    /**
     * POST /api/aid/review/publicity —— 发布公示。
     *
     * <p>默认：publicity_start = now，publicity_end = publicity_start + 5 个工作日（跳过周六周日），
     * objection_deadline = publicity_end。</p>
     *
     * <p>可选补录（仅 admin）：publicityStart / publicityEnd —— 纸质公示先发、系统后补录/回溯；
     * 给了这两个值就按给定区间写，objection_deadline = publicityEnd。teacher 传了 → 403。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result publishPublicity(Long actorId, String role, String scope, Long applicationId,
                                   String publicityStart, String publicityEnd) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可发布公示");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        String ps = trim(publicityStart);
        String pe = trim(publicityEnd);
        if ((ps != null || pe != null) && !"admin".equals(role)) {
            return Result.error(403, "仅管理员可补录/回溯公示时间");
        }
        Result guard = guardStaffApplication(actorId, role, scope, applicationId);
        if (guard != null) {
            return guard;
        }
        AidApplication app = mapper.findById(applicationId);
        if (!STAGE_PUBLICITY.equals(app.getReviewStage())) {
            return Result.error("当前阶段不是公示阶段，不能发布公示");
        }
        if (app.getPublicityStart() != null) {
            return Result.error("该申请的公示已发布，不能重复发布");
        }
        LocalDateTime start;
        LocalDateTime end;
        boolean custom = ps != null || pe != null;
        if (custom) {
            if (ps == null || pe == null) {
                return Result.error("publicityStart 与 publicityEnd 需同时提供");
            }
            start = parseDateTime(ps, false);
            end = parseDateTime(pe, true);
            if (start == null || end == null) {
                return Result.error("公示时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
            }
            if (!end.isAfter(start)) {
                return Result.error("公示结束时间必须晚于开始时间");
            }
        } else {
            start = LocalDateTime.now();
            end = addWorkingDays(start, PUBLICITY_WORKING_DAYS);
        }
        if (mapper.startPublicity(applicationId, STAGE_PUBLICITY, start, end) == 0) {
            return Result.error("公示状态已变更，请刷新后重试");
        }
        Map<String, Object> data = stageView(app, STAGE_PUBLICITY, STATUS_PENDING);
        data.put("publicityStart", start);
        data.put("publicityEnd", end);
        data.put("objectionDeadline", end);
        data.put("workingDays", custom ? null : PUBLICITY_WORKING_DAYS);
        data.put("customWindow", custom);
        return Result.success(custom ? "已按补录区间发布公示" : "公示已发布，为期 5 个工作日", data);
    }

    /** GET /api/aid/review/objections —— 复议列表。 */
    public Result objections(Long actorId, String role, String scope, String status) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可查看复议列表");
        }
        String st = trim(status);
        if (st != null && !OBJECTION_STATUSES.contains(st)) {
            return Result.error("复议状态只能是 pending / accepted / rejected");
        }
        return Result.success(objectionViews(
                nullSafe(objectionMapper.listByStatus(st, scopeOf(actorId, role, scope)))));
    }

    /**
     * POST /api/aid/review/objections/{id}/answer —— 答复复议。
     *
     * <p>verdict=accepted 且给了 newLevel ⇒ 调整级别；accepted 未给 newLevel ⇒ 取消认定资格；
     * rejected ⇒ 维持原级别。答复完最后一条未答复复议后，申请从 objection 回到 publicity。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result answerObjection(Long actorId, String role, String scope, Long objectionId,
                                  String answer, String verdict, String newLevel) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可答复复议");
        }
        if (objectionId == null) {
            return Result.error("复议 ID 不能为空");
        }
        AidObjection o = objectionMapper.findById(objectionId);
        if (o == null) {
            return Result.error("复议记录不存在");
        }
        if (!canTouch(actorId, role, o.getStudentId())) {
            return Result.error(403, "无权答复该学生的复议");
        }
        if (!OBJECTION_PENDING.equals(o.getStatus())) {
            return Result.error("该复议已答复，不能重复答复");
        }
        String ans = trim(answer);
        if (ans == null) {
            return Result.error("答复内容不能为空");
        }
        if (ans.length() > MAX_NOTE) {
            return Result.error("答复内容不超过 " + MAX_NOTE + " 字");
        }
        String v = trim(verdict);
        if (!VERDICT_ACCEPTED.equals(v) && !VERDICT_REJECTED.equals(v)) {
            return Result.error("答复结论只能是 accepted 或 rejected");
        }
        String lv = trim(newLevel);
        if (lv != null && !isValidLevel(lv)) {
            return Result.error("认定级别只能是 " + LEVEL_EXTREME + " 或 " + LEVEL_POOR);
        }
        if (VERDICT_REJECTED.equals(v) && lv != null) {
            return Result.error("不采纳的复议不能调整认定级别");
        }
        AidApplication app = mapper.findById(o.getApplicationId());
        if (app == null) {
            return Result.error("关联的认定申请不存在");
        }
        if (objectionMapper.answer(objectionId, ans, v, actorId) == 0) {
            return Result.error("复议状态已变更，请刷新后重试");
        }
        String stage = app.getReviewStage();
        String level = app.getPovertyLevel();
        if (VERDICT_ACCEPTED.equals(v)) {
            if (lv != null) {
                mapper.updatePovertyLevel(app.getId(), lv);
                level = lv;
            } else {
                mapper.rejectApplication(app.getId(), stage, "复议采纳，取消认定资格：" + ans);
                stage = STAGE_REJECTED;
            }
        }
        if (STAGE_OBJECTION.equals(stage) && objectionMapper.countPendingByApplication(app.getId()) == 0) {
            mapper.advanceStage(app.getId(), STAGE_OBJECTION, STAGE_PUBLICITY);
            stage = STAGE_PUBLICITY;
        }
        Map<String, Object> data = stageView(app, stage,
                STAGE_REJECTED.equals(stage) ? STATUS_REJECTED : STATUS_PENDING, level);
        data.put("objectionId", objectionId);
        data.put("objectionStatus", v);
        return Result.success("复议已答复", data);
    }

    /** POST /api/aid/review/report —— 院系书面评审报告，推进到资助管理中心审核。 */
    @Transactional(rollbackFor = Exception.class)
    public Result report(Long actorId, String role, String scope, Long applicationId, String reportNote) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可提交院系评审报告");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        String note = trim(reportNote);
        if (note == null) {
            return Result.error("院系评审报告不能为空");
        }
        if (note.length() > MAX_NOTE) {
            return Result.error("院系评审报告不超过 " + MAX_NOTE + " 字");
        }
        Result guard = guardStaffApplication(actorId, role, scope, applicationId);
        if (guard != null) {
            return guard;
        }
        AidApplication app = mapper.findById(applicationId);
        if (!STAGE_PUBLICITY.equals(app.getReviewStage())) {
            return Result.error("当前阶段不能提交院系评审报告（需公示已发布且复议处理完毕）");
        }
        if (app.getPublicityStart() == null) {
            return Result.error("公示尚未发布，不能提交院系评审报告");
        }
        if (objectionMapper.countPendingByApplication(applicationId) > 0) {
            return Result.error("还有未答复的复议，请答复后再提交院系评审报告");
        }
        if (mapper.toReport(applicationId, STAGE_PUBLICITY, STAGE_CENTER, note) == 0) {
            return Result.error("认定状态已变更，请刷新后重试");
        }
        return Result.success("院系评审报告已提交，进入资助管理中心审核",
                stageView(app, STAGE_CENTER, STATUS_PENDING));
    }

    /**
     * POST /api/aid/review/approve —— 资助管理中心审核（role=center）/ 领导小组审批（role=leader）。
     *
     * <p>领导小组审批通过时按当前 poverty_level 定终态 approved；任一环节不通过或被勾选
     * 「十类不能认定」情形 ⇒ 终态 rejected 并记录原因。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result approve(Long actorId, String role, String scope, Long applicationId, String auditRole,
                          Boolean approved, String note, List<String> disqualifyReasons) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可审核审批");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        String ar = trim(auditRole);
        if (!AUDIT_ROLE_CENTER.equals(ar) && !AUDIT_ROLE_LEADER.equals(ar)) {
            return Result.error("审核角色只能是 center 或 leader");
        }
        if (approved == null) {
            return Result.error("approved 不能为空");
        }
        String nt = trim(note);
        if (nt != null && nt.length() > MAX_NOTE) {
            return Result.error("审批意见不超过 " + MAX_NOTE + " 字");
        }
        List<String> matched;
        try {
            matched = matchReasonTexts(disqualifyReasons);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
        Result guard = guardStaffApplication(actorId, role, scope, applicationId);
        if (guard != null) {
            return guard;
        }
        AidApplication app = mapper.findById(applicationId);
        boolean center = AUDIT_ROLE_CENTER.equals(ar);
        String expected = center ? STAGE_CENTER : STAGE_LEADER;
        if (!expected.equals(app.getReviewStage())) {
            return Result.error("当前阶段不是" + (center ? "资助管理中心审核" : "领导小组审批") + "阶段");
        }
        if (!matched.isEmpty()) {
            if (mapper.rejectApplication(applicationId, expected, disqualifyNote(matched, nt)) == 0) {
                return Result.error("认定状态已变更，请刷新后重试");
            }
            return Result.success("存在不能认定的情形，认定申请已结束",
                    stageView(app, STAGE_REJECTED, STATUS_REJECTED));
        }
        if (!Boolean.TRUE.equals(approved)) {
            String why = center ? "资助管理中心审核未通过" : "领导小组审批未通过";
            if (nt != null) {
                why = why + "：" + nt;
            }
            if (mapper.rejectApplication(applicationId, expected, why) == 0) {
                return Result.error("认定状态已变更，请刷新后重试");
            }
            return Result.success(why, stageView(app, STAGE_REJECTED, STATUS_REJECTED));
        }
        String baseNote = center ? "资助管理中心审核通过" : "领导小组审批通过";
        String finalNote = nt == null ? baseNote : baseNote + "：" + nt;
        if (center) {
            if (mapper.approveStage(applicationId, STAGE_CENTER, STAGE_LEADER, actorId, finalNote) == 0) {
                return Result.error("认定状态已变更，请刷新后重试");
            }
            return Result.success("资助管理中心审核通过，已提交领导小组审批",
                    stageView(app, STAGE_LEADER, STATUS_PENDING));
        }
        String level = app.getPovertyLevel();
        if (!isValidLevel(level)) {
            return Result.error("尚未定级，不能完成审批");
        }
        if (mapper.approveFinal(applicationId, STAGE_LEADER, actorId, level, finalNote) == 0) {
            return Result.error("认定状态已变更，请刷新后重试");
        }
        return Result.success("已认定该生为" + level + "，结果本学年有效",
                stageView(app, STAGE_APPROVED, STATUS_APPROVED, level));
    }

    /** GET /api/aid/review/recheck —— 学期复查名单（本学年已批准的认定学生）。 */
    public Result recheckList(Long actorId, String role, String scope, String certYear) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可查看复查名单");
        }
        List<AidApplication> rows = nullSafe(
                mapper.listRecheck(trim(certYear), scopeOf(actorId, role, scope)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AidApplication a : rows) {
            if (a == null) {
                continue;
            }
            Map<String, Object> m = briefView(a);
            m.put("recheckResult", a.getRecheckResult());
            m.put("recheckedAt", a.getRecheckedAt());
            out.add(m);
        }
        return Result.success(out);
    }

    /**
     * POST /api/aid/review/recheck/{id} —— 每学期复查一次（同一学期不能重复复查）+ 随机抽查：
     * 写结论，并可调整级别。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result recheck(Long actorId, String role, String scope, Long applicationId,
                          String result, Boolean adjusted, String newLevel, String note) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可开展学期复查");
        }
        if (applicationId == null) {
            return Result.error("申请 ID 不能为空");
        }
        String r = trim(result);
        if (r == null) {
            return Result.error("复查结论不能为空");
        }
        if (r.length() > 255) {
            return Result.error("复查结论不超过 255 字");
        }
        String lv = trim(newLevel);
        boolean adjust = Boolean.TRUE.equals(adjusted);
        if (adjust && !isValidLevel(lv)) {
            return Result.error("调整后的级别只能是 " + LEVEL_EXTREME + " 或 " + LEVEL_POOR);
        }
        if (!adjust && lv != null) {
            return Result.error("未调整级别时不能传入 newLevel");
        }
        Result guard = guardStaffApplication(actorId, role, scope, applicationId);
        if (guard != null) {
            return guard;
        }
        AidApplication app = mapper.findById(applicationId);
        if (!STATUS_APPROVED.equals(app.getStatus())) {
            return Result.error("只有已批准的认定学生可以复查");
        }
        if (app.getRecheckedAt() != null
                && semesterKey(app.getRecheckedAt().toLocalDate()).equals(semesterKey(LocalDate.now()))) {
            return Result.error("本学期的复查已完成，不能重复复查");
        }
        String nt = trim(note);
        if (nt != null && nt.length() > MAX_NOTE) {
            return Result.error("复查意见不超过 " + MAX_NOTE + " 字");
        }
        if (mapper.updateRecheck(applicationId, trim(app.getCertYear()), r, adjust ? lv : null, nt) == 0) {
            return Result.error("认定状态已变更，请刷新后重试");
        }
        AidApplication after = mapper.findById(applicationId);
        String finalLevel = adjust ? lv : (after != null ? after.getPovertyLevel() : app.getPovertyLevel());
        LocalDateTime recheckedAt = (after != null && after.getRecheckedAt() != null)
                ? after.getRecheckedAt() : LocalDateTime.now();
        Map<String, Object> data = stageView(app, app.getReviewStage(), STATUS_APPROVED, finalLevel);
        data.put("certYear", app.getCertYear());
        data.put("recheckResult", r);
        data.put("recheckedAt", recheckedAt);
        data.put("adjusted", adjust);
        return Result.success(adjust ? "复查完成，级别已调整为" + lv : "复查完成，维持原级别", data);
    }

    /**
     * GET /api/aid/review/stats —— 管理端 6 格统计（data 恰好 6 个数字键，空数据为 0 不为 null）。
     *
     * <p>口径：applied = 本学年认定申请总数（各阶段合计）；
     * committee = 待民主评议；level = 院系认定工作组审核定级中；
     * publicity = 公示中（含复议期内，即 publicity + objection 两个阶段）；
     * objection = 复议待答复（objection 阶段）；final = 已定级 approved。
     * 可见范围与 {@link #list} 完全一致：teacher 限本人名下学生，admin 全校。</p>
     *
     * <p>只查一次库：mapper.countByStage 一条 GROUP BY 取回全部阶段计数。</p>
     */
    public Result stats(Long actorId, String role, String scope, String certYear) {
        if (!isStaff(role)) {
            return Result.error(403, "仅辅导员或管理员可查看认定统计");
        }
        List<Map<String, Object>> rows = nullSafe(
                mapper.countByStage(null, trim(certYear), null, scopeOf(actorId, role, scope)));
        int applied = 0;
        int committee = 0;
        int level = 0;
        int publicity = 0;
        int objection = 0;
        int fin = 0;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object stageValue = row.get("review_stage");
            if (stageValue == null) {
                stageValue = row.get("reviewStage");
            }
            String stage = stageValue == null ? null : String.valueOf(stageValue);
            Object countValue = row.get("cnt");
            if (countValue == null) {
                countValue = row.get("count");
            }
            int cnt = toInt(countValue);
            applied += cnt;
            if (STAGE_COMMITTEE.equals(stage)) {
                committee += cnt;
            } else if (STAGE_WORKGROUP.equals(stage)) {
                level += cnt;
            }
            if (STAGE_PUBLICITY.equals(stage) || STAGE_OBJECTION.equals(stage)) {
                publicity += cnt;
            }
            if (STAGE_OBJECTION.equals(stage)) {
                objection += cnt;
            }
            if (STAGE_APPROVED.equals(stage)) {
                fin += cnt;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applied", applied);
        data.put("committee", committee);
        data.put("level", level);
        data.put("publicity", publicity);
        data.put("objection", objection);
        data.put("final", fin);
        return Result.success(data);
    }

    /** GET /api/aid/review/disqualify-reasons —— 十类不能认定情形（恰好 10 条，code + text）。 */
    public Result disqualifyReasons() {
        return Result.success(DISQUALIFY_REASONS);
    }

    // =====================================================================
    // 五、内部规则：工作日算法、权限、校验、视图
    // =====================================================================

    /**
     * 真工作日加法（跳过周六周日，不引入新依赖）。
     *
     * <p>例：周五 + 5 个工作日 = 下周五。</p>
     */
    static LocalDate addWorkingDays(LocalDate start, int days) {
        if (start == null) {
            throw new IllegalArgumentException("起始日期不能为空");
        }
        LocalDate d = start;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }

    /** 保留时分秒的工作日加法（公示窗口用）。 */
    static LocalDateTime addWorkingDays(LocalDateTime start, int days) {
        LocalDateTime base = start == null ? LocalDateTime.now() : start;
        return LocalDateTime.of(addWorkingDays(base.toLocalDate(), days), base.toLocalTime());
    }

    /**
     * 权限范围：沿用 AidService 的口径 —— admin 或 scope=all 看全校，否则辅导员只看自己名下学生。
     */
    private Long scopeOf(Long actorId, String role, String scope) {
        if ("admin".equals(role) || "all".equals(trim(scope))) {
            return null;
        }
        return profileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    /** 单条数据能否操作：沿用 AidService 的 canTouch 口径。 */
    private boolean canTouch(Long actorId, String role, Long studentId) {
        if ("admin".equals(role)) {
            return true;
        }
        StudentProfile p = profileMapper.findByUserId(studentId);
        if (p == null || p.getCounselorId() == null) {
            return true;
        }
        return p.getCounselorId().equals(actorId);
    }

    /** 管理端写操作的统一闸门：申请存在 → 是认定申请 → 在权限范围内。 */
    private Result guardStaffApplication(Long actorId, String role, String scope, Long applicationId) {
        AidApplication app = mapper.findById(applicationId);
        if (app == null) {
            return Result.error("认定申请不存在");
        }
        if (!isReviewApplication(app)) {
            return Result.error("该申请不是家庭经济困难认定申请");
        }
        if (!canTouch(actorId, role, app.getStudentId())) {
            return Result.error(403, "无权处理该学生的认定申请");
        }
        return null;
    }

    private static boolean isStaff(String role) {
        return "teacher".equals(role) || "admin".equals(role);
    }

    private static boolean isReviewApplication(AidApplication app) {
        return app != null
                && (AID_TYPE_REVIEW.equals(app.getAidType()) || AID_TYPE_REVIEW_CN.equals(app.getAidType()));
    }

    static boolean isValidLevel(String level) {
        return LEVELS.contains(trim(level));
    }

    /** 认定学年格式：yyyy-yyyy 且后一年 = 前一年 + 1。 */
    static String validateCertYear(String certYear) {
        if (certYear == null) {
            return "认定学年不能为空";
        }
        if (!certYear.matches("\\d{4}-\\d{4}")) {
            return "认定学年格式应为 2025-2026";
        }
        int a = Integer.parseInt(certYear.substring(0, 4));
        int b = Integer.parseInt(certYear.substring(5));
        if (b != a + 1) {
            return "认定学年格式应为 2025-2026";
        }
        return null;
    }

    /** 学期标识：9 月–次年 1 月为第 1 学期，2–8 月为第 2 学期（暑假并入第 2 学期）。 */
    static String semesterKey(LocalDate d) {
        int m = d.getMonthValue();
        if (m >= 9) {
            return d.getYear() + "-1";
        }
        if (m == 1) {
            return (d.getYear() - 1) + "-1";
        }
        return d.getYear() + "-2";
    }

    /**
     * 把前端传来的「不能认定情形」匹配成中文说明列表。
     * 兼容 code（HIGH_END_PHONE，忽略大小写）与 text（拥有或使用高档通讯工具）两种写法；
     * 含未知项时抛 IllegalArgumentException（调用方转 400）。
     */
    static List<String> matchReasonTexts(List<String> input) {
        List<String> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        for (String raw : input) {
            String t = trim(raw);
            if (t == null) {
                continue;
            }
            Map<String, String> hit = null;
            for (Map<String, String> r : DISQUALIFY_REASONS) {
                if (r.get("code").equalsIgnoreCase(t) || r.get("text").equals(t)) {
                    hit = r;
                    break;
                }
            }
            if (hit == null) {
                throw new IllegalArgumentException("存在未知的不能认定情形：" + t);
            }
            if (!out.contains(hit.get("text"))) {
                out.add(hit.get("text"));
            }
        }
        return out;
    }

    private static String disqualifyNote(List<String> reasons, String note) {
        StringBuilder sb = new StringBuilder("存在不能认定的情形：");
        sb.append(String.join("、", reasons));
        if (note != null) {
            sb.append("；").append(note);
        }
        return sb.toString();
    }

    /** 解析 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / ISO；日期型 end 取当天 23:59:59。 */
    static LocalDateTime parseDateTime(String text, boolean endOfDay) {
        if (text == null) {
            return null;
        }
        String s = text.replace('T', ' ').trim();
        try {
            if (s.length() == 10) {
                LocalDate d = LocalDate.parse(s);
                return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
            }
            if (s.length() == 19) {
                return LocalDateTime.parse(s, DATE_TIME_FMT);
            }
            if (s.length() == 16) {
                return LocalDateTime.parse(s + ":00", DATE_TIME_FMT);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    static String stageText(String stage) {
        return stage == null ? null : STAGE_TEXTS.get(stage);
    }

    /** 七个业务步骤（center / leader 同属第七步，因此 order 会出现两次 7）。 */
    static List<Map<String, Object>> stageDefinitions() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(stepDefinition(1, STAGE_APPLY));
        list.add(stepDefinition(2, STAGE_COMMITTEE));
        list.add(stepDefinition(3, STAGE_WORKGROUP));
        list.add(stepDefinition(4, STAGE_PUBLICITY));
        list.add(stepDefinition(5, STAGE_OBJECTION));
        list.add(stepDefinition(6, STAGE_REPORT));
        list.add(stepDefinition(7, STAGE_CENTER));
        list.add(stepDefinition(7, STAGE_LEADER));
        return list;
    }

    private static Map<String, Object> stepDefinition(int order, String stage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("stageText", stageText(stage));
        m.put("order", order);
        return m;
    }

    static String genderText(String gender) {
        String g = trim(gender);
        if ("male".equalsIgnoreCase(g) || "M".equalsIgnoreCase(g) || "男".equals(g)) {
            return "男";
        }
        if ("female".equalsIgnoreCase(g) || "F".equalsIgnoreCase(g) || "女".equals(g)) {
            return "女";
        }
        return g == null ? "" : g;
    }

    static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 统计计数转换：兼容 Long / BigDecimal / Integer / String，null 记 0。 */
    static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }

    /** 解析材料的 JSON 数组；解析失败时回退成按 | 切分。 */
    List<String> parseMaterials(String materials) {
        String m = trim(materials);
        if (m == null) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(m, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Arrays.asList(m.split("\\|"));
        }
    }

    // ---- 视图组装 ----

    private Map<String, Object> stageView(AidApplication app, String stage, String status) {
        return stageView(app, stage, status, app == null ? null : app.getPovertyLevel());
    }

    private Map<String, Object> stageView(AidApplication app, String stage, String status, String level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", app == null ? null : app.getId());
        m.put("studentId", app == null ? null : app.getStudentId());
        m.put("studentName", app == null ? null : app.getStudentName());
        m.put("certYear", app == null ? null : app.getCertYear());
        m.put("reviewStage", stage);
        m.put("stageText", stageText(stage));
        m.put("povertyLevel", level);
        m.put("status", status);
        return m;
    }

    /** 学生侧视图：本人可见全部信息（含评议/审批意见）。 */
    private Map<String, Object> studentView(AidApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("certYear", a.getCertYear());
        m.put("academicYear", a.getAcademicYear());
        m.put("reviewStage", a.getReviewStage());
        m.put("stageText", stageText(a.getReviewStage()));
        m.put("level", a.getPovertyLevel());
        m.put("status", a.getStatus());
        m.put("reason", a.getReason());
        m.put("familyNote", a.getFamilyNote());
        m.put("materials", parseMaterials(a.getMaterials()));
        m.put("evidence", a.getEvidence());
        m.put("publicityStart", a.getPublicityStart());
        m.put("publicityEnd", a.getPublicityEnd());
        m.put("objectionDeadline", a.getObjectionDeadline());
        m.put("committeeNote", a.getCommitteeNote());
        m.put("reportNote", a.getReportNote());
        m.put("approveNote", a.getApproveNote());
        m.put("recheckResult", a.getRecheckResult());
        m.put("recheckedAt", a.getRecheckedAt());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private List<Map<String, Object>> studentViews(List<AidApplication> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AidApplication a : nullSafe(rows)) {
            if (a != null) {
                out.add(studentView(a));
            }
        }
        return out;
    }

    /** 管理端列表视图（不含长文本，避免列表接口过重）。 */
    private Map<String, Object> briefView(AidApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("studentId", a.getStudentId());
        m.put("studentName", a.getStudentName());
        m.put("studentNo", a.getStudentNo());
        m.put("className", a.getClassName());
        m.put("certYear", a.getCertYear());
        m.put("reviewStage", a.getReviewStage());
        m.put("stageText", stageText(a.getReviewStage()));
        m.put("povertyLevel", a.getPovertyLevel());
        m.put("status", a.getStatus());
        m.put("reason", a.getReason());
        m.put("publicityStart", a.getPublicityStart());
        m.put("publicityEnd", a.getPublicityEnd());
        m.put("objectionDeadline", a.getObjectionDeadline());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private List<Map<String, Object>> briefViews(List<AidApplication> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AidApplication a : nullSafe(rows)) {
            if (a != null) {
                out.add(briefView(a));
            }
        }
        return out;
    }

    private Map<String, Object> objectionView(AidObjection o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("applicationId", o.getApplicationId());
        m.put("studentId", o.getStudentId());
        m.put("objectorId", o.getObjectorId());
        m.put("objectorName", o.getObjectorName());
        m.put("content", o.getContent());
        m.put("evidence", o.getEvidence());
        m.put("status", o.getStatus());
        m.put("answer", o.getAnswer());
        m.put("answeredBy", o.getAnsweredBy());
        m.put("deadline", o.getDeadline());
        m.put("answeredAt", o.getAnsweredAt());
        m.put("createdAt", o.getCreatedAt());
        m.put("certYear", o.getCertYear());
        m.put("povertyLevel", o.getPovertyLevel());
        return m;
    }

    private List<Map<String, Object>> objectionViews(List<AidObjection> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AidObjection o : nullSafe(rows)) {
            if (o != null) {
                out.add(objectionView(o));
            }
        }
        return out;
    }
}
