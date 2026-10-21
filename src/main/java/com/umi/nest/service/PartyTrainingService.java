package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.PartyTraining;
import com.umi.nest.entity.PartyTrainingMember;
import com.umi.nest.mapper.PartyTrainingMapper;
import com.umi.nest.mapper.PartyTrainingMemberMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.umi.nest.service.CommunityService.CAS_FAIL;
import static com.umi.nest.service.CommunityService.flag;
import static com.umi.nest.service.CommunityService.guardLogin;
import static com.umi.nest.service.CommunityService.guardStaff;
import static com.umi.nest.service.CommunityService.has;
import static com.umi.nest.service.CommunityService.integer;
import static com.umi.nest.service.CommunityService.isAdmin;
import static com.umi.nest.service.CommunityService.isBlank;
import static com.umi.nest.service.CommunityService.isStaff;
import static com.umi.nest.service.CommunityService.isStudent;
import static com.umi.nest.service.CommunityService.longOf;
import static com.umi.nest.service.CommunityService.pageOf;
import static com.umi.nest.service.CommunityService.parseDate;
import static com.umi.nest.service.CommunityService.pick;
import static com.umi.nest.service.CommunityService.text;
import static com.umi.nest.service.CommunityService.toInt;
import static com.umi.nest.service.CommunityService.trimToNull;

/**
 * 党校培训（学工 P16）：班次 CRUD / 学生报名 / 辅导员审核 / 结业登记与证书有效期推算。
 *
 * <p>与 {@link PartyService} 的分工：本类只管党校班次与学员，不管阶段台账。</p>
 *
 * <p>契约要点（§11.2 / §11.2-7 / §11.2-1）：</p>
 * <ul>
 *   <li><b>培训时长两条口径并存</b>：国标 S24 第十七条「不少于三天（或不少于二十四个学时）」/
 *       校规 S23「五至七天（或不少于四十个学时）」。平台只登记 {@code days}/{@code hours}
 *       并提示两条口径，<b>不替学校选、不校验、不阻断</b>。</li>
 *   <li><b>结业证书有效期</b>：默认 = 颁发日期 + 一年（校规 S23「自颁发之日起一年内有效」），
 *       <b>传入 certValidUntil 即采信人工覆盖值</b>。</li>
 *   <li><b>§11.1-③ 党校结业标准（及格线、考核权重）无任何来源</b> ⇒ 本类不建分数、不判定及格，
 *       只登记 {@code completeResult = pass|fail} 的人工结论。</li>
 *   <li>状态机全部 CAS：班次 {@code draft -> signup -> ongoing -> finished}（{@code cancelled} 可从
 *       draft/signup 进入）；学员 {@code signed -> approved -> completed}、
 *       {@code approved -> rejected}。服务层先显式前置校验，再 CAS。</li>
 *   <li><b>结业登记写审计（§11.2-5）</b>。</li>
 * </ul>
 */
@Service("partyTrainingService")
public class PartyTrainingService {

    /** 班次状态机。 */
    public static final List<String> TRAINING_FLOW = Arrays.asList("draft", "signup", "ongoing", "finished");

    /** 可取消的班次状态（已结束/已取消不得再改）。 */
    public static final Set<String> TRAINING_CANCELLABLE = new HashSet<>(Arrays.asList("draft", "signup"));

    /** 学生端可见的班次状态（草稿与已取消不下发）。 */
    public static final Set<String> TRAINING_VISIBLE = new HashSet<>(Arrays.asList("signup", "ongoing", "finished"));

    /** 学员状态机。 */
    public static final Set<String> MEMBER_SIGNED = new HashSet<>(Arrays.asList("signed"));

    public static final Set<String> MEMBER_APPROVED = new HashSet<>(Arrays.asList("approved"));

    /** 可以报名（报名中）的班次状态。 */
    public static final Set<String> SIGNUP_OPEN = new HashSet<>(Arrays.asList("signup", "ongoing"));

    @Autowired
    private PartyTrainingMapper trainingMapper;

    @Autowired
    private PartyTrainingMemberMapper memberMapper;

    @Autowired
    private AuditLogService auditLogService;

    // =========================================================================
    // 标签
    // =========================================================================

    /** 班次状态中文文案。 */
    public static String statusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "draft":
                return "草稿";
            case "signup":
                return "报名中";
            case "ongoing":
                return "进行中";
            case "finished":
                return "已结束";
            case "cancelled":
                return "已取消";
            default:
                return status;
        }
    }

    /** 学员状态中文文案。 */
    public static String memberStatusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "signed":
                return "已报名";
            case "approved":
                return "审核通过";
            case "rejected":
                return "已驳回";
            case "completed":
                return "已结业";
            default:
                return status;
        }
    }

    /**
     * <p>审核动作的成功文案：把标签文案（{@link #memberStatusText}）当作「已完成的动作」说明时补一个「已」，
     * 但标签自身已经带「已」前缀（已报名 / 已驳回）时**不再叠加**。</p>
     *
     * <p>原实现无条件写成 {@code "已" + memberStatusText(toStatus)}，驳回时输出「已已驳回」（P16 复测缺陷 D3）。
     * 这里保留 approve → 「已审核通过」的原对外文案，同时不再产生叠字。</p>
     */
    public static String reviewedText(String status) {
        String text = memberStatusText(status);
        return text == null || text.startsWith("已") ? text : "已" + text;
    }

    /** 结业结论中文文案。 */
    public static String completeResultText(String result) {
        if (result == null) {
            return null;
        }
        return "pass".equals(result) ? "合格" : "fail".equals(result) ? "不合格" : result;
    }

    private static boolean isStage(String stage) {
        return PartyService.STAGE_FLOW.contains(stage);
    }

    private static int page(Object value) {
        Integer p = integer(value);
        return p == null || p < 1 ? 1 : p;
    }

    private static int size(Object value) {
        Integer s = integer(value);
        return s == null || s < 1 ? 20 : Math.min(s, 200);
    }

    private void audit(String action, Long actorId, Long targetId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEventType(AuditLogService.TYPE_PARTY);
            entry.setEventAction(action);
            entry.setUserId(actorId);
            entry.setTarget(targetId == null ? null : String.valueOf(targetId));
            entry.setSuccess(true);
            entry.setDetail(detail);
            auditLogService.record(entry);
        } catch (Exception ignored) {
            // 审计失败绝不影响业务（AuditLogService.record 内部同样只 warn）
        }
    }

    // ---- 装配 ----

    private static void fill(PartyTraining training) {
        if (training == null) {
            return;
        }
        training.setStatusText(statusText(training.getStatus()));
        training.setHoursTip(PartyService.TRAINING_HOURS_RULE);
    }

    private static void fill(PartyTrainingMember member) {
        if (member == null) {
            return;
        }
        member.setStatusText(memberStatusText(member.getStatus()));
        member.setCompleteResultText(completeResultText(member.getCompleteResult()));
        if (member.getCertValidUntil() == null) {
            member.setCertValid(null);
        } else {
            member.setCertValid(member.getCertValidUntil().isBefore(LocalDate.now()) ? 0 : 1);
        }
    }

    // =========================================================================
    // 1. 班次（4 条路由）
    // =========================================================================

    /** 新建党校班次（默认草稿）。 */
    public Result<?> createTraining(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        String name = trimToNull(pick(body, "trainingName", "training_name", "name"));
        if (name == null) {
            return Result.error(400, "班次名称不能为空");
        }
        LocalDate startDate = parseDate(pick(body, "startDate", "start_date"));
        LocalDate endDate = parseDate(pick(body, "endDate", "end_date"));
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            return Result.error(400, "结业日期不能早于开班日期");
        }

        PartyTraining training = new PartyTraining();
        training.setTrainingName(name);
        training.setTerm(trimToNull(pick(body, "term")));
        training.setStartDate(startDate);
        training.setEndDate(endDate);
        training.setDays(integer(pick(body, "days")));
        training.setHours(integer(pick(body, "hours")));
        training.setLocation(trimToNull(pick(body, "location")));
        training.setOrganizer(trimToNull(pick(body, "organizer")));
        Integer quota = integer(pick(body, "quota"));
        training.setQuota(quota == null || quota < 0 ? 0 : quota);
        String status = trimToNull(pick(body, "status"));
        if (status != null && !TRAINING_FLOW.contains(status) && !"cancelled".equals(status)) {
            return Result.error(400, "status 只能是 draft/signup/ongoing/finished/cancelled");
        }
        training.setStatus(status == null ? "draft" : status);
        String content = trimToNull(pick(body, "content"));
        training.setContent(content == null
                ? ("培训时长口径：" + PartyService.TRAINING_HOURS_RULE) : content);
        training.setNote(trimToNull(pick(body, "note")));
        training.setCreatedBy(actorId);
        trainingMapper.insert(training);
        fill(training);
        audit("TRAINING_CREATE", actorId, training.getId(), "新建党校班次：" + name);
        return Result.success("班次已创建", training);
    }

    /** 班次列表：学生端只看 signup/ongoing/finished。 */
    public Result<?> listTrainings(Long actorId, String role, String status, String term, String keyword,
                                   Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        boolean hideDraft = isStudent(role);
        List<PartyTraining> rows = trainingMapper.list(status, term, keyword, hideDraft, offset, s);
        long total = trainingMapper.count(status, term, keyword, hideDraft);
        for (PartyTraining row : rows) {
            fill(row);
            row.setMemberCount((int) trainingMapper.countMembers(row.getId()));
        }
        return Result.success(pageOf(rows, (int) total, p, s));
    }

    /** 班次详情（学生端草稿不可见）。 */
    public Result<?> trainingDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyTraining training = trainingMapper.findById(id);
        if (training == null) {
            return Result.error(404, "党校班次不存在");
        }
        if (isStudent(role) && !TRAINING_VISIBLE.contains(training.getStatus())) {
            return Result.error(404, "党校班次不存在");
        }
        fill(training);
        training.setMemberCount((int) trainingMapper.countMembers(id));
        // 学生端额外下发「本人在这期班的报名情况」，避免前端再查一次
        if (isStudent(role)) {
            PartyTrainingMember mine = memberMapper.findByTrainingAndUser(id, actorId);
            if (mine != null) {
                fill(mine);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("training", training);
            data.put("mySignup", mine);
            return Result.success(data);
        }
        return Result.success(training);
    }

    /** 修改班次（CAS on status）。 */
    public Result<?> updateTraining(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyTraining exist = trainingMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "党校班次不存在");
        }
        // 显式前置校验：已结束/已取消的班次不得再改
        if (!TRAINING_CANCELLABLE.contains(exist.getStatus()) && !"ongoing".equals(exist.getStatus())) {
            return Result.error(400, "「" + statusText(exist.getStatus()) + "」状态的班次不能修改");
        }
        String name = trimToNull(pick(body, "trainingName", "training_name", "name"));
        if (name == null) {
            name = exist.getTrainingName();
        }
        LocalDate startDate = has(body, "startDate", "start_date")
                ? parseDate(pick(body, "startDate", "start_date")) : exist.getStartDate();
        LocalDate endDate = has(body, "endDate", "end_date")
                ? parseDate(pick(body, "endDate", "end_date")) : exist.getEndDate();
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            return Result.error(400, "结业日期不能早于开班日期");
        }
        String status = trimToNull(pick(body, "status"));
        if (status != null && !TRAINING_FLOW.contains(status) && !"cancelled".equals(status)) {
            return Result.error(400, "status 只能是 draft/signup/ongoing/finished/cancelled");
        }
        if (status != null && "cancelled".equals(status) && !TRAINING_CANCELLABLE.contains(exist.getStatus())) {
            return Result.error(400, "只有草稿/报名中的班次可以取消，当前状态：" + statusText(exist.getStatus()));
        }

        PartyTraining training = new PartyTraining();
        training.setId(id);
        training.setTrainingName(name);
        training.setTerm(has(body, "term") ? trimToNull(pick(body, "term")) : exist.getTerm());
        training.setStartDate(startDate);
        training.setEndDate(endDate);
        training.setDays(has(body, "days") ? integer(pick(body, "days")) : exist.getDays());
        training.setHours(has(body, "hours") ? integer(pick(body, "hours")) : exist.getHours());
        training.setLocation(has(body, "location") ? trimToNull(pick(body, "location")) : exist.getLocation());
        training.setOrganizer(has(body, "organizer") ? trimToNull(pick(body, "organizer")) : exist.getOrganizer());
        // P16 复测缺陷（已修）：原写法三元两分支是 int / Integer 混合，条件表达式整体提升为 int，
        // exist.getQuota() 被隐式拆箱 ⇒ quota 为 NULL 且请求体不带 quota 时抛 NPE（改班次任何字段都 500）。
        // 注意：**不能**只把结果赋给 Integer 局部变量——拆箱发生在条件表达式内部，必须让两个分支同为引用类型
        // （或改成 if 赋值），否则依然 NPE。
        Integer quota = exist.getQuota();
        if (has(body, "quota")) {
            quota = Math.max(toInt(pick(body, "quota"), 0), 0);
        }
        training.setQuota(quota == null ? 0 : quota);
        training.setStatus(status == null ? exist.getStatus() : status);
        training.setContent(has(body, "content") ? trimToNull(pick(body, "content")) : exist.getContent());
        training.setNote(has(body, "note") ? trimToNull(pick(body, "note")) : exist.getNote());

        int rows = trainingMapper.update(training, exist.getStatus());
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyTraining saved = trainingMapper.findById(id);
        fill(saved);
        audit("TRAINING_UPDATE", actorId, id, "修改党校班次：" + name + " status=" + saved.getStatus());
        return Result.success("已保存", saved);
    }

    // =========================================================================
    // 2. 学员（4 条路由）
    // =========================================================================

    /** 学员名单：学生只看自己；辅导员只看名下；admin 全量。 */
    public Result<?> listTrainingMembers(Long actorId, String role, Long trainingId, Long userId, String status,
                                        Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Long scopeUser = userId;
        Long counselorId = null;
        if (isStudent(role)) {
            if (userId != null && !userId.equals(actorId)) {
                return Result.error(403, "无权查看他人党校报名记录");
            }
            scopeUser = actorId;
        } else if (isStaff(role) && !isAdmin(role)) {
            if (userId != null) {
                scopeUser = userId;
            }
            counselorId = actorId;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyTrainingMember> rows = memberMapper.list(trainingId, scopeUser, counselorId, status, offset, s);
        long total = memberMapper.count(trainingId, scopeUser, counselorId, status);
        for (PartyTrainingMember row : rows) {
            fill(row);
        }
        return Result.success(pageOf(rows, (int) total, p, s));
    }

    /** 学生报名（仅本人；班次必须处于报名中/进行中）。 */
    public Result<?> signupTraining(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyTraining training = trainingMapper.findById(id);
        if (training == null) {
            return Result.error(404, "党校班次不存在");
        }
        // 显式前置校验：只有报名中/进行中的班次可以报名
        if (!SIGNUP_OPEN.contains(training.getStatus())) {
            return Result.error(400, "该班次当前状态为「" + statusText(training.getStatus()) + "」，不接受报名");
        }
        if (memberMapper.findByTrainingAndUser(id, actorId) != null) {
            return Result.error(400, "你已报名该班次");
        }
        // 名额：只做「满了就不再收」的提示性拦截（quota=0 表示不限）
        if (training.getQuota() != null && training.getQuota() > 0
                && trainingMapper.countMembers(id) >= training.getQuota()) {
            return Result.error(400, "该班次名额已满（" + training.getQuota() + " 人）");
        }

        PartyTrainingMember member = new PartyTrainingMember();
        member.setTrainingId(id);
        member.setUserId(actorId);
        member.setSignupAt(LocalDateTime.now().withNano(0));
        member.setStatus("signed");
        member.setNote(trimToNull(pick(body, "note")));
        memberMapper.insert(member);
        fill(member);
        audit("TRAINING_SIGNUP", actorId, member.getId(),
                "党校报名 trainingId=" + id + " userId=" + actorId);
        return Result.success("报名已提交，等待辅导员审核", member);
    }

    /** 辅导员审核报名：signed -> approved / rejected（CAS）。 */
    public Result<?> reviewTrainingMember(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyTrainingMember exist = memberMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "报名记录不存在");
        }
        // 显式前置校验：只有 signed 可以审核
        if (!MEMBER_SIGNED.contains(exist.getStatus())) {
            return Result.error(400, "只有「已报名」的记录可以审核，当前状态：" + memberStatusText(exist.getStatus()));
        }
        String action = trimToNull(pick(body, "action", "result"));
        String toStatus = "reject".equals(action) || "rejected".equals(action) ? "rejected" : "approved";
        String rejectReason = trimToNull(pick(body, "rejectReason", "reject_reason", "reason"));
        if ("rejected".equals(toStatus) && rejectReason == null) {
            return Result.error(400, "驳回必须填写原因");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = memberMapper.review(id, "signed", toStatus, actorId, now, rejectReason);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyTrainingMember saved = memberMapper.findById(id);
        fill(saved);
        // P16 复测缺陷（已修）：memberStatusText 自带「已」前缀，原写法无条件再拼一次 ⇒ 驳回时「已已驳回」。
        return Result.success(reviewedText(toStatus), saved);
    }

    /**
     * 结业登记（§11.2-5 写审计；§11.2-7 证书有效期默认一年、可人工覆盖）。
     *
     * <p>平台不判定及格（§11.1-③ 无及格线来源），只登记人工结论 pass/fail。</p>
     */
    public Result<?> completeTrainingMember(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyTrainingMember exist = memberMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "报名记录不存在");
        }
        // 显式前置校验：只有 approved 可以登记结业
        if (!MEMBER_APPROVED.contains(exist.getStatus())) {
            return Result.error(400, "只有「审核通过」的学员可以登记结业，当前状态："
                    + memberStatusText(exist.getStatus()));
        }
        String result = trimToNull(pick(body, "completeResult", "complete_result", "result"));
        if (result == null) {
            return Result.error(400, "请填写结业结论：pass（合格）或 fail（不合格）");
        }
        if (!"pass".equals(result) && !"fail".equals(result)) {
            return Result.error(400, "结业结论只能是 pass（合格）或 fail（不合格）"
                    + "（党校结业标准/及格线无公开来源，平台不做自动判定）");
        }
        LocalDate completeDate = parseDate(pick(body, "completeDate", "complete_date"));
        if (completeDate == null) {
            completeDate = LocalDate.now();
        }
        LocalDate certIssueDate = parseDate(pick(body, "certIssueDate", "cert_issue_date"));
        if (certIssueDate == null) {
            certIssueDate = completeDate;
        }
        String certNo = trimToNull(pick(body, "certNo", "cert_no"));
        // 证书有效期：默认 = 颁发日期 + 一年（校规 S23）；传入即采信人工覆盖值（§11.2-7）
        LocalDate certValidUntil = parseDate(pick(body, "certValidUntil", "cert_valid_until"));
        if (certValidUntil == null) {
            certValidUntil = certIssueDate.plusDays(PartyService.TRAINING_CERT_VALID_DAYS);
        }

        int rows = memberMapper.complete(id, "approved", result, completeDate, certNo, certIssueDate,
                certValidUntil, trimToNull(pick(body, "note")));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyTrainingMember saved = memberMapper.findById(id);
        fill(saved);
        audit("TRAINING_COMPLETE", actorId, id, "结业登记 userId=" + exist.getUserId()
                + " result=" + result + " certNo=" + certNo + " validUntil=" + certValidUntil);
        return Result.success("结业登记已保存", saved);
    }

    /** 覆盖证书信息（仅已结业；供证书编号/有效期补录）。 */
    public Result<?> updateTrainingCert(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyTrainingMember exist = memberMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "报名记录不存在");
        }
        // 显式前置校验：只有已结业才有证书信息
        if (!"completed".equals(exist.getStatus())) {
            return Result.error(400, "只有「已结业」的学员才有证书信息，当前状态：" + memberStatusText(exist.getStatus()));
        }
        String certNo = has(body, "certNo", "cert_no")
                ? trimToNull(pick(body, "certNo", "cert_no")) : exist.getCertNo();
        LocalDate certIssueDate = has(body, "certIssueDate", "cert_issue_date")
                ? parseDate(pick(body, "certIssueDate", "cert_issue_date")) : exist.getCertIssueDate();
        LocalDate certValidUntil = has(body, "certValidUntil", "cert_valid_until")
                ? parseDate(pick(body, "certValidUntil", "cert_valid_until")) : exist.getCertValidUntil();
        if (certValidUntil == null && certIssueDate != null) {
            certValidUntil = certIssueDate.plusDays(PartyService.TRAINING_CERT_VALID_DAYS);
        }
        int rows = memberMapper.updateCert(id, certNo, certIssueDate, certValidUntil);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyTrainingMember saved = memberMapper.findById(id);
        fill(saved);
        audit("TRAINING_CERT_UPDATE", actorId, id, "覆盖结业证书信息 certNo=" + certNo);
        return Result.success("证书信息已更新", saved);
    }

    // =========================================================================
    // 3. 我的（1 条路由）
    // =========================================================================

    /** 我的党校报名与结业（学生端）。 */
    public Result<?> myTrainings(Long actorId, String role, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyTrainingMember> rows = memberMapper.list(null, actorId, null, null, offset, s);
        long total = memberMapper.count(null, actorId, null, null);
        for (PartyTrainingMember row : rows) {
            fill(row);
        }
        Map<String, Object> data = pageOf(rows, (int) total, p, s);
        data.put("certRule", PartyService.TRAINING_CERT_RULE);
        data.put("hoursRule", PartyService.TRAINING_HOURS_RULE);
        data.put("note", "党校结业标准（及格线、考核权重）无公开来源，平台只登记人工结业结论，不做自动判定（§11.1-③）。");
        return Result.success(data);
    }

    // =========================================================================
    // 4. 统计（1 条路由）
    // =========================================================================

    /** 党校培训统计（班次状态 / 学员状态）。 */
    public Result<?> stats(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> trainings = new ArrayList<>();
        for (Map<String, Object> row : trainingMapper.countGroupByStatus()) {
            String code = text(row.get("status"));
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("statusText", statusText(code));
            trainings.add(item);
        }
        data.put("trainingStatus", trainings);
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> row : memberMapper.countGroupByStatusAll()) {
            String code = text(row.get("status"));
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("statusText", memberStatusText(code));
            members.add(item);
        }
        data.put("memberStatus", members);
        data.put("hoursRule", PartyService.TRAINING_HOURS_RULE);
        data.put("certRule", PartyService.TRAINING_CERT_RULE);
        return Result.success(data);
    }

    /** 是否已结业。 */
    public static boolean isCompleted(PartyTrainingMember member) {
        return member != null && "completed".equals(member.getStatus());
    }

    /** 已交材料标记工具（与 PartyService 同口径）。 */
    public static boolean submittedFlag(Object value) {
        return flag(value) == 1;
    }

    /** 结业结论合法性（pass/fail）。 */
    public static boolean isCompleteResult(String result) {
        return "pass".equals(result) || "fail".equals(result);
    }

    /** 字符串非空（本地包装，避免对 CommunityService 的静态依赖扩散）。 */
    public static boolean notBlank(String value) {
        return !isBlank(value);
    }

    /** 报名是否可审核。 */
    public static boolean isReviewable(PartyTrainingMember member) {
        return member != null && MEMBER_SIGNED.contains(member.getStatus());
    }

    /** 班次是否对学生可见。 */
    public static boolean isVisibleToStudent(PartyTraining training) {
        return training != null && TRAINING_VISIBLE.contains(training.getStatus());
    }

    /** 学员是否来自辅导员名下判断用的占位（由 SQL 的 counselor_id 过滤承担，这里只暴露契约）。 */
    public static boolean sameUser(Long a, Long b) {
        return a != null && a.equals(b);
    }

    /** 从 body 取 userId。 */
    public static Long bodyUserId(Map<String, Object> body) {
        return longOf(pick(body, "userId", "user_id"));
    }
}
