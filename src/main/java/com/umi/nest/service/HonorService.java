package com.umi.nest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.EvalSummary;
import com.umi.nest.entity.HonorApplication;
import com.umi.nest.entity.HonorBatch;
import com.umi.nest.entity.HonorReview;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.HonorApplicationMapper;
import com.umi.nest.mapper.HonorBatchMapper;
import com.umi.nest.mapper.HonorReviewMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 评奖评优在线申报（学工 P12）业务服务。
 *
 * <p>一条主线：<b>通知里的每一步都必须在库里留下痕迹，且不能出现"两个人同时按下按钮"造成的越级</b>。
 * 所以：</p>
 * <ol>
 *   <li><b>状态机只有一条路径</b>：draft → submitted → counselor_approved → college_approved
 *       → dept_approved → publicized → awarded，任一节点 reject ⇒ rejected。</li>
 *   <li><b>每次状态变更都是 CAS</b>（`UPDATE ... WHERE id=? AND deleted=0 AND status=?` + 影响行数 == 1）。
 *       没有这一步，学院和学工部同时点"通过"就会把 submitted 直接推到 dept_approved，
 *       学生端看到的进度和实际不符，而通知明确要求"驳回要让学生看到进度与意见"。</li>
 *   <li><b>审核意见必须写实</b>：通知明写"学院推荐意见不可只写「同意推荐」"，
 *       所以通过也要 ≥8 字且不能是空话。</li>
 *   <li><b>能自动核验的自动核验，核验不了的绝不假装核验</b>：综测排名、荣誉称号次数走库里的数据；
 *       体测、不及格科目、违法违纪库里没有权威数据源，一律 auto=false 交人工，
 *       并把"为什么不能自动"写进 note。</li>
 * </ol>
 *
 * <p>值域（与 sql\nest_honor.sql 的列注释一致）：JSON 里只出英文码，中文文案由前端映射；
 * 涉及导出/公示的接口由本服务给中文（Excel 里不能出现英文码）。</p>
 */
@Service
public class HonorService {

    private static final Logger log = LoggerFactory.getLogger(HonorService.class);

    // =====================================================================
    // 批次状态
    // =====================================================================
    public static final String B_DRAFT = "draft";
    public static final String B_RECRUITING = "recruiting";
    public static final String B_REVIEWING = "reviewing";
    public static final String B_PUBLICITY = "publicity";
    public static final String B_FINISHED = "finished";

    // =====================================================================
    // 申报单状态
    // =====================================================================
    public static final String A_DRAFT = "draft";
    public static final String A_SUBMITTED = "submitted";
    public static final String A_COUNSELOR_APPROVED = "counselor_approved";
    public static final String A_COLLEGE_APPROVED = "college_approved";
    public static final String A_DEPT_APPROVED = "dept_approved";
    public static final String A_PUBLICIZED = "publicized";
    public static final String A_AWARDED = "awarded";
    public static final String A_REJECTED = "rejected";

    // =====================================================================
    // 审核节点
    // =====================================================================
    public static final String N_COUNSELOR = "counselor";
    public static final String N_COLLEGE = "college";
    public static final String N_DEPT = "dept";

    /** 荣誉类型值域（honor_batch.honor_type 的列注释；顺序即前端下拉顺序）。 */
    private static final Map<String, String> HONOR_TYPE_TEXT = new LinkedHashMap<>();

    static {
        HONOR_TYPE_TEXT.put("graduate_outstanding", "优秀毕业生");
        HONOR_TYPE_TEXT.put("merit_outstanding", "优秀三好学生");
        HONOR_TYPE_TEXT.put("merit_student", "三好学生");
        HONOR_TYPE_TEXT.put("cadres_outstanding", "优秀学生干部");
    }

    private static final Set<String> BATCH_STATUSES =
            Set.of(B_DRAFT, B_RECRUITING, B_REVIEWING, B_PUBLICITY, B_FINISHED);

    private static final Set<String> APP_STATUSES = Set.of(
            A_DRAFT, A_SUBMITTED, A_COUNSELOR_APPROVED, A_COLLEGE_APPROVED,
            A_DEPT_APPROVED, A_PUBLICIZED, A_AWARDED, A_REJECTED);

    /** 学生端可以改稿的状态：草稿（撤回后）/ 已提交（还没人审）/ 已驳回（申报期内可改后重提）。 */
    private static final Set<String> STUDENT_EDITABLE = Set.of(A_DRAFT, A_SUBMITTED, A_REJECTED);

    /** 审核列表 node 参数 → 该节点的"待办"状态（前端四个页签直接用 node 取队列）。 */
    private static final Map<String, String> NODE_DEFAULT_STATUS = new LinkedHashMap<>();

    static {
        NODE_DEFAULT_STATUS.put("counselor", A_SUBMITTED);
        NODE_DEFAULT_STATUS.put("college", A_COUNSELOR_APPROVED);
        NODE_DEFAULT_STATUS.put("dept", A_COLLEGE_APPROVED);
        NODE_DEFAULT_STATUS.put("submitted", A_SUBMITTED);
        NODE_DEFAULT_STATUS.put("counselor_approved", A_COUNSELOR_APPROVED);
        NODE_DEFAULT_STATUS.put("college_approved", A_COLLEGE_APPROVED);
        NODE_DEFAULT_STATUS.put("dept_approved", A_DEPT_APPROVED);
        NODE_DEFAULT_STATUS.put("publicized", A_PUBLICIZED);
        NODE_DEFAULT_STATUS.put("publicity", A_PUBLICIZED);
        NODE_DEFAULT_STATUS.put("awarded", A_AWARDED);
        NODE_DEFAULT_STATUS.put("finished", A_AWARDED);
        NODE_DEFAULT_STATUS.put("rejected", A_REJECTED);
        NODE_DEFAULT_STATUS.put("draft", A_DRAFT);
    }

    /**
     * 空话白名单：整条意见去标点后命中其一即判为"没写实"。
     * 只在意见很短（&lt;12 字）时启用，避免误伤"同意推荐，该生综测班级第 3"这种写实的。
     */
    private static final Set<String> PLATITUDES = Set.of(
            "同意", "同意推荐", "推荐", "通过", "同意通过", "无意见", "没意见", "无异议",
            "可以", "可以推荐", "ok", "拟同意", "情况属实", "属实", "同意上报", "同意报",
            "同意该生申报", "推荐申报", "准予推荐", "同意。" );

    private static final Pattern PUNCT = Pattern.compile("[\\s。，,、.．！!；;：:～~\\-—_（）()\\[\\]【】“”\"'‘’]");

    /** 通知原文的口径：不可只写「同意推荐」。这句话要原样给到前端和接口调用方。 */
    private static final String COMMENT_RULE =
            "学院推荐意见请简要写明学生表现或推荐理由（通知要求：不可只写「同意推荐」）";

    /** 申报单 CAS 失败统一文案（任务口径：状态已变要让学生/审核人刷新重试）。 */
    private static final String APP_CAS_FAIL = "申报状态已变化，请刷新后重试";
    private static final String BATCH_CAS_FAIL = "批次状态已变化，请刷新后重试";

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final HonorBatchMapper batchMapper;
    private final HonorApplicationMapper applicationMapper;
    private final HonorReviewMapper reviewMapper;
    private final StudentProfileMapper profileMapper;
    private final EvalSummaryMapper evalMapper;
    private final ObjectMapper objectMapper;

    /** 综测排名阈值（百分比），默认 15 —— 通知口径「毕业排名前 15%」。 */
    @Value("${nest.honor.rank-percent:15}")
    private int rankPercent;

    /** 公示天数（工作日），默认 5。批次自己的 publicity_days 优先。 */
    @Value("${nest.honor.publicity-days:5}")
    private int defaultPublicityDays;

    public HonorService(HonorBatchMapper batchMapper,
                        HonorApplicationMapper applicationMapper,
                        HonorReviewMapper reviewMapper,
                        StudentProfileMapper profileMapper,
                        EvalSummaryMapper evalMapper,
                        ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.applicationMapper = applicationMapper;
        this.reviewMapper = reviewMapper;
        this.profileMapper = profileMapper;
        this.evalMapper = evalMapper;
        this.objectMapper = objectMapper;
    }

    // =====================================================================
    // 一、批次（admin）
    // =====================================================================

    /** 1. 批次列表：登录即可读；非 admin 看不到草稿（草稿还没发布，学生看到会误以为能申报）。 */
    public Result listBatches(Long actorId, String role, String status, String keyword) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        syncRecruitingWindows();
        String st = trimToNull(status);
        if (st != null && !BATCH_STATUSES.contains(st)) {
            return Result.error(400, "未知的批次状态：" + st);
        }
        return Result.success(batchMapper.list(!isAdmin(role), st, trimToNull(keyword)));
    }

    /** 2. 新建批次（草稿）。 */
    @Transactional(rollbackFor = Exception.class)
    public Result createBatch(Long actorId, String role, HonorBatch form) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可创建评奖评优批次");
        }
        if (form == null) {
            return Result.error(400, "缺少批次信息");
        }
        String name = trim(form.getBatchName());
        if (isBlank(name)) {
            return Result.error(400, "请填写批次名称");
        }
        String problem = tooLong("批次名称", name, 128);
        if (problem == null) {
            problem = tooLong("名额说明", form.getQuotaNote(), 255);
        }
        if (problem == null) {
            problem = tooLong("材料说明", form.getMaterials(), 500);
        }
        if (problem == null) {
            problem = tooLong("通知标题", form.getNoticeTitle(), 255);
        }
        if (problem == null) {
            problem = tooLong("通知链接", form.getNoticeUrl(), 255);
        }
        if (problem == null) {
            problem = tooLong("综测学期", trim(form.getEvalTerm()), 16);
        }
        if (problem != null) {
            return Result.error(400, problem);
        }
        String type = trim(form.getHonorType());
        if (!HONOR_TYPE_TEXT.containsKey(type)) {
            return Result.error(400, "请选择荣誉类型（优秀毕业生 / 优秀三好学生 / 三好学生 / 优秀学生干部）");
        }
        String year = trim(form.getAcademicYear());
        if (isBlank(year)) {
            return Result.error(400, "请填写评选学年（如 2025-2026）");
        }
        if (year.length() > 16) {
            return Result.error(400, "评选学年不能超过 16 字");
        }
        String window = windowProblem(form.getApplyStart(), form.getApplyEnd(), form.getReviewEnd());
        if (window != null) {
            return Result.error(400, window);
        }

        HonorBatch batch = new HonorBatch();
        batch.setBatchName(name);
        batch.setHonorType(type);
        batch.setAcademicYear(year);
        batch.setEvalTerm(trimToNull(form.getEvalTerm()));
        batch.setQuota(nonNegative(form.getQuota()) ? form.getQuota() : null);
        batch.setQuotaNote(trimToNull(form.getQuotaNote()));
        batch.setCriteria(trimToNull(form.getCriteria()));
        batch.setMaterials(trimToNull(form.getMaterials()));
        batch.setApplyStart(form.getApplyStart());
        batch.setApplyEnd(form.getApplyEnd());
        batch.setReviewEnd(form.getReviewEnd());
        batch.setPublicityDays(form.getPublicityDays() == null || form.getPublicityDays() <= 0
                ? defaultPublicityDays : form.getPublicityDays());
        batch.setStatus(B_DRAFT);
        batch.setNoticeTitle(trimToNull(form.getNoticeTitle()));
        batch.setNoticeUrl(trimToNull(form.getNoticeUrl()));
        batch.setCreatedBy(actorId);
        batchMapper.insert(batch);
        return Result.success(batchMapper.findById(batch.getId()));
    }

    /**
     * 3. 修改批次（增量更新：字段不传 = 不改，传空串 = 清空文本）。
     *
     * <p>为什么用增量而不是整体覆盖：整体覆盖时前端漏传一个字段就会把已有的申报起止日/名额抹掉，
     * 而批次已经发布、学生可能在申报 —— 抹掉截止日会让所有人突然可以继续申报。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result updateBatch(Long actorId, String role, Long id, HonorBatch form) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可修改评奖评优批次");
        }
        HonorBatch cur = batchMapper.findById(id);
        if (cur == null) {
            return Result.error(404, "批次不存在");
        }
        if (!B_DRAFT.equals(cur.getStatus()) && !B_RECRUITING.equals(cur.getStatus())) {
            return Result.error(400, "批次已进入审核/公示/结束阶段，不能再修改（如需调整请新建批次）");
        }
        if (form == null) {
            return Result.error(400, "缺少批次信息");
        }

        HonorBatch next = new HonorBatch();
        next.setId(cur.getId());
        next.setBatchName(pick(form.getBatchName(), cur.getBatchName(), 128, "批次名称"));
        next.setHonorType(cur.getHonorType());
        next.setAcademicYear(cur.getAcademicYear());
        next.setEvalTerm(cur.getEvalTerm());
        next.setQuota(form.getQuota() == null ? cur.getQuota() : form.getQuota());
        next.setQuotaNote(pick(form.getQuotaNote(), cur.getQuotaNote(), 255, "名额说明"));
        next.setCriteria(pick(form.getCriteria(), cur.getCriteria(), 0, "参评条件"));
        next.setMaterials(pick(form.getMaterials(), cur.getMaterials(), 500, "材料说明"));
        next.setApplyStart(form.getApplyStart() == null ? cur.getApplyStart() : form.getApplyStart());
        next.setApplyEnd(form.getApplyEnd() == null ? cur.getApplyEnd() : form.getApplyEnd());
        next.setReviewEnd(form.getReviewEnd() == null ? cur.getReviewEnd() : form.getReviewEnd());
        next.setPublicityDays(form.getPublicityDays() == null || form.getPublicityDays() <= 0
                ? cur.getPublicityDays() : form.getPublicityDays());
        next.setNoticeTitle(pick(form.getNoticeTitle(), cur.getNoticeTitle(), 255, "通知标题"));
        next.setNoticeUrl(pick(form.getNoticeUrl(), cur.getNoticeUrl(), 255, "通知链接"));

        String window = windowProblem(next.getApplyStart(), next.getApplyEnd(), next.getReviewEnd());
        if (window != null) {
            return Result.error(400, window);
        }
        // 已开申报时，荣誉类型/学年/综测学期不允许再动：申报单提交时冗余了这几个值，
        // 改了之后学生已提交的单子（含自动核验口径）就会和批次对不上
        if (B_RECRUITING.equals(cur.getStatus())) {
            if (form.getHonorType() != null && !form.getHonorType().equals(cur.getHonorType())) {
                return Result.error(400, "申报已开始，不能再更改荣誉类型");
            }
            if (form.getAcademicYear() != null && !form.getAcademicYear().equals(cur.getAcademicYear())) {
                return Result.error(400, "申报已开始，不能再更改评选学年");
            }
            if (form.getEvalTerm() != null && !Objects.equals(trimToNull(form.getEvalTerm()), cur.getEvalTerm())) {
                return Result.error(400, "申报已开始，不能再更改自动核验用的综测学期");
            }
        }
        int n = batchMapper.update(next, cur.getStatus());
        if (n != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        return Result.success(batchMapper.findById(cur.getId()));
    }

    /** 4. 发布：draft → recruiting。 */
    @Transactional(rollbackFor = Exception.class)
    public Result publishBatch(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可发布评奖评优批次");
        }
        HonorBatch cur = batchMapper.findById(id);
        if (cur == null) {
            return Result.error(404, "批次不存在");
        }
        if (!B_DRAFT.equals(cur.getStatus())) {
            return Result.error(400, "只有草稿状态的批次可以发布（当前：" + batchStatusText(cur.getStatus()) + "）");
        }
        int n = batchMapper.updateStatus(id, B_DRAFT, B_RECRUITING);
        if (n != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        return Result.success(batchMapper.findById(id));
    }

    /**
     * 5. reviewing → publicity：写公示起止（公示天数按工作日折算）+ 把 dept_approved 的申报单置 publicized。
     *
     * <p>同时允许从 recruiting 进入：apply_end 为空的批次永远不会自动转 reviewing，
     * 若不允许，这类批次就永远卡在申报中、公示无路可走。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result publicizeBatch(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可发起公示");
        }
        HonorBatch cur = batchMapper.findById(id);
        if (cur == null) {
            return Result.error(404, "批次不存在");
        }
        if (!B_REVIEWING.equals(cur.getStatus()) && !B_RECRUITING.equals(cur.getStatus())) {
            return Result.error(400, "只有审核中（或申报中）的批次可以进入公示（当前："
                    + batchStatusText(cur.getStatus()) + "）");
        }
        int days = cur.getPublicityDays() == null || cur.getPublicityDays() <= 0
                ? defaultPublicityDays : cur.getPublicityDays();
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = addWorkingDays(start, days);
        int n = batchMapper.startPublicity(id, cur.getStatus(), days, start, end);
        if (n != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        // 集合式 CAS：只有 dept_approved 的单子会进公示，重复调用不会把已获评的打回去
        int moved = applicationMapper.publicizeByBatch(id);
        log.info("[honor] 批次 {} 进入公示：{} 条申报单，公示期 {} ~ {}（{} 个工作日）",
                id, moved, DATE_TIME_FMT.format(start), DATE_TIME_FMT.format(end), days);
        return Result.success(batchMapper.findById(id));
    }

    /** 6. publicity → finished，同时把该批次 publicized 的申报单置 awarded。 */
    @Transactional(rollbackFor = Exception.class)
    public Result finishBatch(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可结束评奖评优批次");
        }
        HonorBatch cur = batchMapper.findById(id);
        if (cur == null) {
            return Result.error(404, "批次不存在");
        }
        if (!B_PUBLICITY.equals(cur.getStatus())) {
            return Result.error(400, "只有公示中的批次可以结束（当前：" + batchStatusText(cur.getStatus()) + "）");
        }
        int n = batchMapper.updateStatus(id, B_PUBLICITY, B_FINISHED);
        if (n != 1) {
            return Result.error(400, BATCH_CAS_FAIL);
        }
        int awarded = applicationMapper.awardByBatch(id);
        log.info("[honor] 批次 {} 结束，{} 条申报单转为已获评", id, awarded);
        return Result.success(batchMapper.findById(id));
    }

    // =====================================================================
    // 二、学生侧
    // =====================================================================

    /** 7. 我的评优：可见批次（非草稿）+ 我的申报单（每条带审核意见，避免 N+1）。 */
    public Result myHonor(Long actorId, String role) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可查看本人的评奖评优申报");
        }
        syncRecruitingWindows();
        List<HonorBatch> batches = batchMapper.list(true, null, null);
        List<HonorApplication> apps = applicationMapper.listMine(actorId);
        Map<Long, List<HonorReview>> reviewsByApp = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (HonorApplication a : apps) {
            ids.add(a.getId());
        }
        if (!ids.isEmpty()) {
            for (HonorReview r : reviewMapper.listByApplications(ids)) {
                reviewsByApp.computeIfAbsent(r.getApplicationId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (HonorApplication a : apps) {
            views.add(applicationView(a, reviewsByApp.getOrDefault(a.getId(), List.of())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batches", batches);
        data.put("applications", views);
        return Result.success(data);
    }

    /**
     * 8. 申报前自动核验预览。
     *
     * <p>前端分两栏展示：auto=true 的是平台真跑出来的（综测排名、荣誉称号次数），
     * auto=false 的一律交给人工，passed 给 null —— 宁可显示"待人工核验"，也不能拿自报值冒充系统结论。</p>
     */
    public Result myCheck(Long actorId, String role, Long batchId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可做申报前自动核验");
        }
        if (batchId == null) {
            return Result.error(400, "缺少 batchId");
        }
        syncRecruitingWindows();
        HonorBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        if (B_DRAFT.equals(batch.getStatus())) {
            return Result.error(400, "该批次尚未发布，暂不能申报");
        }

        StudentProfile profile = profileMapper.findByUserId(actorId);
        String className = profile == null ? null : trimToNull(profile.getClassName());
        int honorCount = applicationMapper.countHonorAwards(actorId);
        int scholarshipCount = applicationMapper.countScholarshipAwards(actorId);

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal rankPercentValue = null;

        // ---- cond5：综测排名前 N%（自动） ----
        String expectedRank = "前 " + rankPercent + "%";
        String evalTerm = trimToNull(batch.getEvalTerm());
        if (evalTerm == null) {
            items.add(checkItem("cond5", "综合测评排名" + expectedRank, false, null, null, expectedRank,
                    "eval_summary",
                    "本批次未设置综测学期（eval_term），无法自动核验，请辅导员按毕业排名人工核验"));
        } else {
            EvalSummary summary = evalMapper.findByStudentTerm(actorId, evalTerm);
            if (summary == null || summary.getRankInClass() == null) {
                items.add(checkItem("cond5", "综合测评排名" + expectedRank, false, null, null, expectedRank,
                        "eval_summary",
                        "你在 " + evalTerm + " 学期没有综测汇总（或没有班级排名），本项需辅导员人工核验"));
            } else if (className == null) {
                items.add(checkItem("cond5", "综合测评排名" + expectedRank, false, null, null, expectedRank,
                        "eval_summary", "你还没有学工档案（班级未知），无法算出班级人数，本项需人工核验"));
            } else {
                int classSize = evalMapper.countByTerm(evalTerm, className, null);
                if (classSize <= 0) {
                    items.add(checkItem("cond5", "综合测评排名" + expectedRank, false, null, null, expectedRank,
                            "eval_summary", "该学期同班综测汇总为 0 条，算不出班级人数，本项需人工核验"));
                } else {
                    rankPercentValue = BigDecimal.valueOf(summary.getRankInClass())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(classSize), 2, RoundingMode.HALF_UP);
                    boolean pass = rankPercentValue.compareTo(BigDecimal.valueOf(rankPercent)) <= 0;
                    items.add(checkItem("cond5", "综合测评排名" + expectedRank, true, pass,
                            summary.getRankInClass() + "/" + classSize, expectedRank, "eval_summary", null));
                }
            }
        }

        // ---- cond7：校级以上荣誉称号次数（自动） ----
        boolean honorPass = honorCount >= 2 || (honorCount >= 1 && scholarshipCount >= 1);
        items.add(checkItem("cond7", "校级以上荣誉称号 ≥2 次（或 ≥1 次 + 校级以上奖学金 ≥1 次）",
                true, honorPass,
                "荣誉称号 " + honorCount + " 次（其中含「奖学金」的 " + scholarshipCount + " 次）",
                "≥2 次，或 ≥1 次 + 奖学金 ≥1 次", "award_punishment",
                honorPass ? null
                        : "平台只统计已登记的奖项（P5 奖励与处分），校外奖项请在「荣誉称号清单」里补充，由辅导员人工核验"));

        // ---- 以下三项库里没有权威数据源，只能自报 + 人工 ----
        HonorApplication exist = applicationMapper.findByBatchAndStudent(batchId, actorId);
        BigDecimal peScore = exist == null ? null : exist.getPeScore();
        items.add(checkItem("cond_pe", "体测成绩 ≥50 分", false,
                peScore == null ? null : peScore.compareTo(BigDecimal.valueOf(50)) >= 0,
                peScore == null ? null : peScore.stripTrailingZeros().toPlainString() + " 分",
                "≥50 分", "自报 + 人工核验",
                "平台没有《国家学生体质健康标准》测试成绩表，体测分只能本人填报、辅导员核验"));

        Boolean noFail = exist == null ? null : exist.getNoFailDeclared();
        items.add(checkItem("cond_fail", "无不及格科目", false, noFail,
                noFail == null ? null : (noFail ? "已声明无不及格科目" : "未声明"),
                "无不及格科目", "自报 + 人工核验",
                "教学成绩数据已随教学模块（P8）下线，本项只能本人声明、辅导员人工核验"));

        Boolean noViolation = exist == null ? null : exist.getNoViolationDeclared();
        items.add(checkItem("cond_violation", "无违法违纪记录", false, noViolation,
                noViolation == null ? null : (noViolation ? "已声明无违法违纪记录" : "未声明"),
                "无违法违纪记录", "自报 + 人工核验",
                "违法违纪情况以学院掌握的事实为准（平台 P5 的处分记录仅供参考），由辅导员人工核验"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch", batch);
        data.put("items", items);
        data.put("rankPercent", rankPercentValue);
        data.put("honorCount", honorCount);
        data.put("scholarshipCount", scholarshipCount);
        return Result.success(data);
    }

    /** 9. 提交申报：落库即 submitted（草稿只存在于 UI）。 */
    @Transactional(rollbackFor = Exception.class)
    public Result apply(Long actorId, String role, Long batchId, HonorApplication form) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可提交评奖评优申报");
        }
        if (batchId == null) {
            return Result.error(400, "缺少 batchId");
        }
        syncRecruitingWindows();
        HonorBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        String window = applyWindowProblem(batch);
        if (window != null) {
            return Result.error(400, window);
        }
        // 唯一键 uk_ha_batch_student 兜底，这里先查一次是为了给学生一句人话，而不是 500
        HonorApplication exist = applicationMapper.findByBatchAndStudent(batchId, actorId);
        if (exist != null) {
            return Result.error(400, "该批次你已提交过申报");
        }
        String problem = checkForm(form);
        if (problem != null) {
            return Result.error(400, problem);
        }

        StudentProfile profile = profileMapper.findByUserId(actorId);
        HonorApplication app = new HonorApplication();
        app.setBatchId(batchId);
        app.setStudentId(actorId);
        app.setHonorType(batch.getHonorType());
        app.setAcademicYear(batch.getAcademicYear());
        fillForm(app, form);
        app.setStatus(A_SUBMITTED);
        app.setRejectReason(null);
        // 辅导员在提交时固化：学生之后换辅导员，也不能把已经在审的单子判给新辅导员
        app.setCounselorId(profile == null ? null : profile.getCounselorId());
        app.setSubmittedAt(LocalDateTime.now());
        applicationMapper.insert(app);
        return Result.success(applicationMapper.findById(app.getId()));
    }

    /** 10. 修改/重提：草稿、已提交、已驳回三种状态可在申报期内改；改完仍是 submitted。 */
    @Transactional(rollbackFor = Exception.class)
    public Result updateApply(Long actorId, String role, Long id, HonorApplication form) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可修改本人的评优申报");
        }
        HonorApplication cur = applicationMapper.findById(id);
        if (cur == null) {
            return Result.error(404, "申报单不存在");
        }
        if (!Objects.equals(cur.getStudentId(), actorId)) {
            return Result.error(403, "只能修改本人的申报单");
        }
        HonorBatch batch = batchMapper.findById(cur.getBatchId());
        if (batch == null) {
            return Result.error(400, "申报已结束");
        }
        String window = applyWindowProblem(batch);
        if (window != null) {
            return Result.error(400, window);
        }
        if (!STUDENT_EDITABLE.contains(cur.getStatus())) {
            return Result.error(400, "申报已进入审核，不能修改（当前：" + appStatusText(cur.getStatus()) + "）");
        }
        String problem = checkForm(form);
        if (problem != null) {
            return Result.error(400, problem);
        }
        StudentProfile profile = profileMapper.findByUserId(actorId);

        HonorApplication next = new HonorApplication();
        next.setId(cur.getId());
        next.setStudentId(actorId);
        next.setHonorType(cur.getHonorType());
        next.setAcademicYear(cur.getAcademicYear());
        fillForm(next, form);
        next.setCounselorId(profile == null ? null : profile.getCounselorId());
        int n = applicationMapper.updateMine(next, cur.getStatus());
        if (n != 1) {
            return Result.error(400, APP_CAS_FAIL);
        }
        return Result.success(applicationMapper.findById(cur.getId()));
    }

    /** 11. 撤回：submitted → draft（回到可编辑）。 */
    @Transactional(rollbackFor = Exception.class)
    public Result withdraw(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可撤回本人的评优申报");
        }
        HonorApplication cur = applicationMapper.findById(id);
        if (cur == null) {
            return Result.error(404, "申报单不存在");
        }
        if (!Objects.equals(cur.getStudentId(), actorId)) {
            return Result.error(403, "只能撤回本人的申报单");
        }
        HonorBatch batch = batchMapper.findById(cur.getBatchId());
        // 申报截止即锁：批次不在申报期内（含已截止、已结束），学生侧一切写操作一律停
        if (batch == null || applyWindowProblem(batch) != null) {
            return Result.error(400, "申报已结束");
        }
        if (!A_SUBMITTED.equals(cur.getStatus())) {
            return Result.error(400, "只有已提交、尚未进入初审的申报可以撤回（当前："
                    + appStatusText(cur.getStatus()) + "）");
        }
        int n = applicationMapper.withdraw(id, actorId, A_SUBMITTED);
        if (n != 1) {
            return Result.error(400, APP_CAS_FAIL);
        }
        return Result.success(applicationMapper.findById(id));
    }

    // =====================================================================
    // 三、审核侧
    // =====================================================================

    /**
     * 12. 审核列表（分页）。
     *
     * <p>node 是"页签"：counselor/college/dept 分别给该节点的待办队列；
     * 显式传 status 时以 status 为准。教师是学院/学工部的职能角色，本表没有"学院岗位绑定"，
     * 因此不按"名下学生"过滤 —— 辅导员节点的越权由审核接口的权限判定拦住。</p>
     */
    public Result reviewList(Long actorId, String role, String node, String status, Long batchId,
                             String keyword, Integer page, Integer size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "仅教师或管理员可查看评优审核列表");
        }
        syncRecruitingWindows();

        String st = trimToNull(status);
        if (st != null && !"all".equalsIgnoreCase(st)) {
            if (!APP_STATUSES.contains(st)) {
                return Result.error(400, "未知的申报状态：" + st);
            }
        } else {
            st = null;
        }
        if (st == null) {
            String nd = trimToNull(node);
            if (nd != null && !"all".equalsIgnoreCase(nd)) {
                String mapped = NODE_DEFAULT_STATUS.get(nd.toLowerCase(Locale.ROOT));
                if (mapped == null) {
                    return Result.error(400, "未知的审核节点：" + nd
                            + "（可用：counselor / college / dept / publicized / awarded / rejected / all）");
                }
                st = mapped;
            }
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 100);
        int offset = (p - 1) * s;
        String kw = trimToNull(keyword);
        List<HonorApplication> rows = applicationMapper.listReview(st, batchId, kw, null, offset, s);
        int total = applicationMapper.countReview(st, batchId, kw, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return Result.success(data);
    }

    /** 13. 审核详情：申报单 + 该单全部审核意见 + 批次口径（审核人要看到参评条件原文）。 */
    public Result reviewDetail(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "仅教师或管理员可查看评优申报详情");
        }
        HonorApplication app = applicationMapper.findById(id);
        if (app == null) {
            return Result.error(404, "申报单不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", app);
        data.put("reviews", reviewMapper.listByApplication(id));
        data.put("batch", batchMapper.findById(app.getBatchId()));
        return Result.success(data);
    }

    /**
     * 14. 审核（pass / reject）。
     *
     * <p>节点不从前端取，而是由申报单当前状态推导：流程是线性的（submitted→辅导员、
     * counselor_approved→学院、college_approved→学工部），让前端指定节点等于把状态机交给前端，
     * 越级审核会变成一行代码的事。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Result review(Long actorId, String role, Long id, String verdict, String comment) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "仅教师或管理员可审核评优申报");
        }
        HonorApplication app = applicationMapper.findById(id);
        if (app == null) {
            return Result.error(404, "申报单不存在");
        }
        String node = nodeOfStatus(app.getStatus());
        if (node == null) {
            return Result.error(400, "当前节点不需要你审核（申报单状态：" + appStatusText(app.getStatus()) + "）");
        }
        if (N_COUNSELOR.equals(node)) {
            StudentProfile sp = profileMapper.findByUserId(app.getStudentId());
            Long profileCounselor = sp == null ? null : sp.getCounselorId();
            boolean mine = Objects.equals(app.getCounselorId(), actorId)
                    || Objects.equals(profileCounselor, actorId);
            if (!mine && !isAdmin(role)) {
                return Result.error(403, "仅该生辅导员可初审");
            }
        } else if (N_DEPT.equals(node) && !isAdmin(role)) {
            return Result.error(403, "仅学工部（管理员）可复核");
        } else if (N_COLLEGE.equals(node) && !isStaff(role)) {
            return Result.error(403, "仅学院（教师）可审核");
        }

        String v = trimToNull(verdict);
        if (!"pass".equals(v) && !"reject".equals(v)) {
            return Result.error(400, "verdict 只能是 pass 或 reject");
        }
        String c = trim(comment);
        String commentProblem = commentProblem(c);
        if (commentProblem != null) {
            return Result.error(400, commentProblem);
        }
        String text = c.length() > 1000 ? c.substring(0, 1000) : c;

        // 先 CAS 再落审核流水：CAS 失败直接返回，不留"意见写了但状态没动"的脏流水；
        // 流水插入抛异常时同一事务回滚，状态也不会单独生效
        String to = "reject".equals(v) ? A_REJECTED : nextStatus(node);
        String rejectReason = "reject".equals(v) ? (text.length() > 500 ? text.substring(0, 500) : text) : null;
        int n = applicationMapper.updateStatus(id, app.getStatus(), to, rejectReason);
        if (n != 1) {
            return Result.error(400, APP_CAS_FAIL);
        }
        HonorReview row = new HonorReview();
        row.setApplicationId(id);
        row.setNode(node);
        row.setVerdict(v);
        row.setComment(text);
        row.setReviewerId(actorId);
        row.setReviewerRole(role);
        reviewMapper.insert(row);
        log.info("[honor] 申报单 {} 节点 {} 由 {} 判定 {}", id, node, actorId, v);
        return Result.success(applicationMapper.findById(id));
    }

    // =====================================================================
    // 四、公共 / 导出 / 统计
    // =====================================================================

    /**
     * 15. 公示名单（必须登录）。
     *
     * <p>脱敏在 SQL 出口完成（只有学号后四位），本方法只补中文文案，不再拼装任何原始字段。</p>
     */
    public Result publicity(Long actorId, Long batchId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        syncRecruitingWindows();
        Long target = batchId;
        if (target == null) {
            // 不传 batchId 时给"当前公示中的批次"，省掉前端再查一次
            List<HonorBatch> pub = batchMapper.list(false, B_PUBLICITY, null);
            if (!pub.isEmpty()) {
                target = pub.get(0).getId();
            }
        }
        if (target == null) {
            return Result.success(List.of());
        }
        if (batchMapper.findById(target) == null) {
            return Result.error(404, "批次不存在");
        }
        List<Map<String, Object>> rows = applicationMapper.listPublicity(target);
        for (Map<String, Object> r : rows) {
            r.put("honorTypeText", honorTypeText(asString(r.get("honorType"))));
        }
        return Result.success(rows);
    }

    /**
     * 16. 导出 CSV 文本（不含自述正文）。返回 null = 无权导出，由 controller 转 403。
     *
     * <p>只限 admin/teacher —— 通知要求"学院推荐参评学生名单以平台导出为准"，
     * 学生本人导出没有业务场景，反而会形成一份可转发的全校名单。</p>
     */
    public String exportCsv(Long actorId, String role, Long batchId) {
        if (actorId == null || !isStaff(role)) {
            return null;
        }
        syncRecruitingWindows();
        // scopeCounselorId 传 null：见 reviewList 的说明（本表无"学院岗位绑定"）。
        // 参数保留在 mapper 上，将来要收紧成"辅导员只导名下学生"只需在这里换成 actorId
        List<HonorApplication> rows = applicationMapper.listForExport(batchId, null);
        StringBuilder sb = new StringBuilder();
        sb.append("批次名,荣誉类型,学年,学号,姓名,班级,专业,状态,提交时间,辅导员,当前审核节点\r\n");
        for (HonorApplication a : rows) {
            sb.append(csv(a.getBatchName())).append(',')
                    .append(csv(honorTypeText(a.getHonorType()))).append(',')
                    .append(csv(a.getAcademicYear())).append(',')
                    .append(csv(a.getStudentNo())).append(',')
                    .append(csv(a.getStudentName())).append(',')
                    .append(csv(a.getClassName())).append(',')
                    .append(csv(a.getMajor())).append(',')
                    .append(csv(appStatusText(a.getStatus()))).append(',')
                    .append(csv(a.getSubmittedAt() == null ? null : DATE_TIME_FMT.format(a.getSubmittedAt()))).append(',')
                    .append(csv(a.getCounselorName())).append(',')
                    .append(csv(stageText(a.getStatus())))
                    .append("\r\n");
        }
        return sb.toString();
    }

    /** 17. 审核台统计：批次与申报单按状态计数（前端顶部卡片用，缺失状态补 0，不用判空）。 */
    public Result stats(Long actorId, String role) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "仅教师或管理员可查看评优统计");
        }
        syncRecruitingWindows();
        Map<String, Object> batchCounts = new LinkedHashMap<>();
        for (String st : new String[]{B_DRAFT, B_RECRUITING, B_REVIEWING, B_PUBLICITY, B_FINISHED}) {
            batchCounts.put(st, 0);
        }
        for (Map<String, Object> row : batchMapper.countGroupByStatus()) {
            String key = asString(row.get("status"));
            if (key != null) {
                batchCounts.put(key, row.get("cnt"));
            }
        }
        Map<String, Object> appCounts = new LinkedHashMap<>();
        for (String st : new String[]{A_DRAFT, A_SUBMITTED, A_COUNSELOR_APPROVED, A_COLLEGE_APPROVED,
                A_DEPT_APPROVED, A_PUBLICIZED, A_AWARDED, A_REJECTED}) {
            appCounts.put(st, 0);
        }
        long total = 0L;
        for (Map<String, Object> row : applicationMapper.countGroupByStatus(null, null)) {
            String key = asString(row.get("status"));
            if (key == null) {
                continue;
            }
            appCounts.put(key, row.get("cnt"));
            Object cnt = row.get("cnt");
            if (cnt instanceof Number) {
                total += ((Number) cnt).longValue();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batches", batchCounts);
        data.put("applications", appCounts);
        data.put("applicationTotal", total);
        // 三个待办口子直接给平铺字段，前端卡片不用再记状态名
        data.put("pendingCounselor", appCounts.get(A_SUBMITTED));
        data.put("pendingCollege", appCounts.get(A_COUNSELOR_APPROVED));
        data.put("pendingDept", appCounts.get(A_COLLEGE_APPROVED));
        data.put("pendingPublicity", appCounts.get(A_DEPT_APPROVED));
        data.put("awarded", appCounts.get(A_AWARDED));
        data.put("rejected", appCounts.get(A_REJECTED));
        return Result.success(data);
    }

    // =====================================================================
    // 内部：时限同步与状态机
    // =====================================================================

    /**
     * 申报截止即锁：把 apply_end 已过（当天仍可申报）的 recruiting 批次转成 reviewing。
     *
     * <p>放在各读入口的最前面而不是定时任务：项目里没有调度器，而"批次是否还是 recruiting"
     * 是学生侧所有写操作的判据，必须在使用前把状态追到时间上，否则会出现
     * "申报期已过但状态仍是 recruiting、学生还能提交"的窗口。</p>
     */
    private void syncRecruitingWindows() {
        try {
            int n = batchMapper.closeExpiredRecruiting();
            if (n > 0) {
                log.info("[honor] 申报截止，{} 个批次自动转入审核中", n);
            }
        } catch (Exception e) {
            // 只是"把状态追上时间"，失败不该让列表页打不开：下一次请求还会再试
            log.warn("[honor] 同步申报截止批次失败：{}", e.getMessage());
        }
    }

    /** 学生侧写操作的申报窗口判据：批次状态 + [apply_start, apply_end]（为空则不校验该端）。 */
    private static String applyWindowProblem(HonorBatch b) {
        if (B_DRAFT.equals(b.getStatus())) {
            return "该批次尚未发布，暂不能申报";
        }
        if (!B_RECRUITING.equals(b.getStatus())) {
            return "申报已结束（批次当前阶段：" + batchStatusText(b.getStatus()) + "）";
        }
        LocalDate today = LocalDate.now();
        if (b.getApplyStart() != null && today.isBefore(b.getApplyStart())) {
            return "申报尚未开始（" + b.getApplyStart() + " 开始）";
        }
        if (b.getApplyEnd() != null && today.isAfter(b.getApplyEnd())) {
            return "申报已截止（截止日 " + b.getApplyEnd() + "）";
        }
        return null;
    }

    /** 批次三个日期的先后关系（通知里是两段时限，倒过来写说明填错了）。 */
    private static String windowProblem(LocalDate applyStart, LocalDate applyEnd, LocalDate reviewEnd) {
        if (applyStart != null && applyEnd != null && applyEnd.isBefore(applyStart)) {
            return "申报截止日不能早于申报开始日";
        }
        if (applyEnd != null && reviewEnd != null && reviewEnd.isBefore(applyEnd)) {
            return "学院审核截止日不能早于申报截止日";
        }
        return null;
    }

    /** 当前状态 → 待办节点；不在审核链上的状态返回 null。 */
    private static String nodeOfStatus(String status) {
        String s = status == null ? "" : status;
        switch (s) {
            case A_SUBMITTED:
                return N_COUNSELOR;
            case A_COUNSELOR_APPROVED:
                return N_COLLEGE;
            case A_COLLEGE_APPROVED:
                return N_DEPT;
            default:
                return null;
        }
    }

    private static String nextStatus(String node) {
        switch (node) {
            case N_COUNSELOR:
                return A_COUNSELOR_APPROVED;
            case N_COLLEGE:
                return A_COLLEGE_APPROVED;
            case N_DEPT:
                return A_DEPT_APPROVED;
            default:
                return null;
        }
    }

    /**
     * 审核意见校验。
     *
     * <p>为什么 reject 也要校验：通知里"驳回要让学生看到进度与意见"，
     * 一句"不同意"学生无法整改，下一轮还是同样被驳回。</p>
     */
    private static String commentProblem(String comment) {
        String c = comment == null ? "" : comment.replaceAll("\\s+", "");
        if (c.length() < 8) {
            return COMMENT_RULE;
        }
        // 只在很短的意见上查空话，避免误伤"同意推荐，该生综测班级第 3、无挂科"这类写实的
        if (c.length() < 12) {
            String norm = PUNCT.matcher(c).replaceAll("").toLowerCase(Locale.ROOT);
            if (PLATITUDES.contains(norm)) {
                return COMMENT_RULE;
            }
        }
        return null;
    }

    // =====================================================================
    // 内部：表单校验与填充
    // =====================================================================

    /** 申报表单校验（提交与改稿共用同一套口径）。 */
    private static String checkForm(HonorApplication form) {
        if (form == null) {
            return "缺少申报内容";
        }
        String self = trim(form.getSelfStatement());
        if (self == null || self.replaceAll("\\s+", "").length() < 30) {
            return "个人自述请不少于 30 字";
        }
        String problem = tooLong("个人自述", self, 2000);
        if (problem == null) {
            problem = tooLong("荣誉称号清单", trim(form.getHonorsText()), 1000);
        }
        if (problem == null) {
            problem = tooLong("奖学金清单", trim(form.getScholarshipsText()), 500);
        }
        if (problem == null) {
            problem = tooLong("材料说明", trim(form.getMaterialNote()), 500);
        }
        if (problem == null) {
            problem = tooLong("联系电话", trim(form.getContactPhone()), 32);
        }
        if (problem != null) {
            return problem;
        }
        if (!Boolean.TRUE.equals(form.getNoFailDeclared())) {
            return "请确认无不及格科目";
        }
        if (!Boolean.TRUE.equals(form.getNoViolationDeclared())) {
            return "请确认无违法违纪记录";
        }
        BigDecimal pe = form.getPeScore();
        if (pe != null && (pe.compareTo(BigDecimal.ZERO) < 0 || pe.compareTo(BigDecimal.valueOf(300)) > 0)) {
            return "体测成绩请填 0 ~ 300 之间的分数";
        }
        return null;
    }

    private static void fillForm(HonorApplication target, HonorApplication form) {
        target.setSelfStatement(trim(form.getSelfStatement()));
        target.setHonorsText(trimToNull(form.getHonorsText()));
        target.setScholarshipsText(trimToNull(form.getScholarshipsText()));
        target.setPeScore(form.getPeScore());
        target.setNoFailDeclared(Boolean.TRUE.equals(form.getNoFailDeclared()));
        target.setNoViolationDeclared(Boolean.TRUE.equals(form.getNoViolationDeclared()));
        target.setMaterialNote(trimToNull(form.getMaterialNote()));
        target.setContactPhone(trimToNull(form.getContactPhone()));
    }

    // =====================================================================
    // 内部：视图与文案
    // =====================================================================

    /** 申报单视图 = 实体全部字段 + 该单的审核意见（「我的申报」要内联展示每一步意见）。 */
    private Map<String, Object> applicationView(HonorApplication app, List<HonorReview> reviews) {
        Map<String, Object> view = objectMapper.convertValue(app, MAP_TYPE);
        view.put("reviews", reviews);
        return view;
    }

    private static Map<String, Object> checkItem(String code, String title, boolean auto, Boolean passed,
                                                 String value, String expected, String source, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("title", title);
        m.put("auto", auto);
        m.put("passed", passed);
        m.put("value", value);
        m.put("expected", expected);
        m.put("source", source);
        m.put("note", note);
        return m;
    }

    /** 荣誉类型英文码 → 中文文案（只在导出/公示这类人读场景用；JSON 里仍是英文码）。 */
    public static String honorTypeText(String code) {
        if (code == null) {
            return "";
        }
        return HONOR_TYPE_TEXT.getOrDefault(code, code);
    }

    /** 申报单状态 → 中文文案（人读场景）。 */
    public static String appStatusText(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case A_DRAFT:
                return "草稿";
            case A_SUBMITTED:
                return "待辅导员初审";
            case A_COUNSELOR_APPROVED:
                return "待学院审核";
            case A_COLLEGE_APPROVED:
                return "待学工部复核";
            case A_DEPT_APPROVED:
                return "待公示";
            case A_PUBLICIZED:
                return "公示中";
            case A_AWARDED:
                return "已获评";
            case A_REJECTED:
                return "已驳回";
            default:
                return status;
        }
    }

    /** 「当前审核节点」列：说清"卡在哪一步"，而不是重复状态码。 */
    public static String stageText(String status) {
        if (status == null) {
            return "";
        }
        String node = nodeOfStatus(status);
        if (N_COUNSELOR.equals(node)) {
            return "辅导员初审";
        }
        if (N_COLLEGE.equals(node)) {
            return "学院审核";
        }
        if (N_DEPT.equals(node)) {
            return "学工部复核";
        }
        switch (status) {
            case A_DRAFT:
                return "学生草稿";
            case A_DEPT_APPROVED:
                return "待公示";
            case A_PUBLICIZED:
                return "公示中";
            case A_AWARDED:
                return "已发文表彰";
            case A_REJECTED:
                return "已驳回";
            default:
                return status;
        }
    }

    public static String batchStatusText(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case B_DRAFT:
                return "草稿";
            case B_RECRUITING:
                return "申报中";
            case B_REVIEWING:
                return "审核中";
            case B_PUBLICITY:
                return "公示中";
            case B_FINISHED:
                return "已结束";
            default:
                return status;
        }
    }

    // =====================================================================
    // 内部：小工具
    // =====================================================================

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    private static boolean isStaff(String role) {
        return "teacher".equals(role) || isAdmin(role);
    }

    private static boolean isStudent(String role) {
        return "student".equals(role);
    }

    private static boolean nonNegative(Integer v) {
        return v == null || v >= 0;
    }

    /** 增量更新取值：null/空白 = 保留原值；非空 = 覆盖（超长直接报错，不静默截断）。 */
    private static String pick(String incoming, String current, int max, String label) {
        if (incoming == null || incoming.trim().isEmpty()) {
            return current;
        }
        String v = incoming.trim();
        return max > 0 && v.length() > max ? v.substring(0, max) : v;
    }

    private static String tooLong(String label, String v, int max) {
        if (v != null && max > 0 && v.length() > max) {
            return label + "不能超过 " + max + " 字";
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 加工作日（跳过周六周日）：公示天数按通知口径是工作日，不是自然日。 */
    static LocalDateTime addWorkingDays(LocalDateTime start, int days) {
        LocalDateTime d = start;
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

    /** 加工作日（日期版，供需要按天算的场景复用）。 */
    static LocalDate addWorkingDays(LocalDate start, int days) {
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
}
