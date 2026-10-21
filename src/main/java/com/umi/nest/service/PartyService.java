package com.umi.nest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.PartyApplication;
import com.umi.nest.entity.PartyMaterial;
import com.umi.nest.entity.PartyObservation;
import com.umi.nest.entity.PartyPublicity;
import com.umi.nest.entity.PartyStage;
import com.umi.nest.entity.PartyTrainingMember;
import com.umi.nest.mapper.PartyApplicationMapper;
import com.umi.nest.mapper.PartyMaterialMapper;
import com.umi.nest.mapper.PartyObservationMapper;
import com.umi.nest.mapper.PartyPublicityMapper;
import com.umi.nest.mapper.PartyStageMapper;
import com.umi.nest.mapper.PartyTrainingMapper;
import com.umi.nest.mapper.PartyTrainingMemberMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.umi.nest.service.CommunityService.CsvExport;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.umi.nest.service.CommunityService.CAS_FAIL;
import static com.umi.nest.service.CommunityService.DAY;
import static com.umi.nest.service.CommunityService.csvCell;
import static com.umi.nest.service.CommunityService.dec;
import static com.umi.nest.service.CommunityService.flag;
import static com.umi.nest.service.CommunityService.guardAdmin;
import static com.umi.nest.service.CommunityService.guardLogin;
import static com.umi.nest.service.CommunityService.guardStaff;
import static com.umi.nest.service.CommunityService.has;
import static com.umi.nest.service.CommunityService.integer;
import static com.umi.nest.service.CommunityService.isAdmin;
import static com.umi.nest.service.CommunityService.isBlank;
import static com.umi.nest.service.CommunityService.isStaff;
import static com.umi.nest.service.CommunityService.isStudent;
import static com.umi.nest.service.CommunityService.longOf;
import static com.umi.nest.service.CommunityService.MAX_EXPORT;
import static com.umi.nest.service.CommunityService.orDefault;
import static com.umi.nest.service.CommunityService.pageOf;
import static com.umi.nest.service.CommunityService.parseDate;
import static com.umi.nest.service.CommunityService.parseDateTime;
import static com.umi.nest.service.CommunityService.pick;
import static com.umi.nest.service.CommunityService.tail4;
import static com.umi.nest.service.CommunityService.text;
import static com.umi.nest.service.CommunityService.toInt;
import static com.umi.nest.service.CommunityService.trimToNull;

/**
 * 学生党建台账（学工 P16）：申请登记 / 阶段台账与推进 / 考察与思想汇报 / 公示 / 材料 / 统计导出。
 *
 * <p>契约见《学工平台功能对齐说明》§11.2。本类的三条硬口径：</p>
 *
 * <p><b>① 不做自动资格判定（§11.2-1）</b>。平台只做「台账 + 时限提示 + 公示留痕 + 材料清单」。
 * 所有「应当 / 不得」都是 {@code *Rule} 常量里的**带条款引用（rule_ref）的提示文案**，
 * 只随查询结果下发，<b>不阻断任何推进</b>：阶段推进只校验状态机顺序与日期先后，
 * 不做「满 X 个月才能推进」的资格判定。</p>
 *
 * <p><b>② 期限常量两条并存、平台不替学校选（§11.2-3）</b>。国标 S24 与校规 S23 不一致的
 * 期限（党校培训天数/学时、积极分子考察频率、公示天数）一律保留两条，各自标注来源。</p>
 *
 * <p><b>③ 状态机 = 服务层显式前置校验 + CAS（§11.2-9）</b>。
 * 推进语句形如 {@code WHERE id=#{id} AND deleted=0 AND stage=#{fromStage}}，
 * {@code fromStage} 取**显式枚举前置态**（{@link #PREVIOUS_STAGE}），
 * 绝不把「刚读到的 stage」当参数——否则 WHERE 恒真、CAS 退化成无条件更新。
 * 公示 publish 只接受 {draft}、close 只接受 {publicizing}，
 * <b>已结束（closed）再 close 必须 400，不得做幂等 200</b>（P15 E2E 缺陷 D2）。</p>
 *
 * <p>学生端（{@code /api/party/my/**}）只下发本人数据，且服务层统一抹掉支部内部考察意见
 * （{@code internalNote}）、推优讨论内容（{@code internalDiscussion}）与支部内部备注
 * （{@code internalRemark}）——不依赖前端不渲染（§11.2-4）；抹除后的实体不会回到 staff 视图，
 * 因为抹除只发生在 {@link #sanitize} 的返回值上，不写库。</p>
 */
@Service("partyService")
public class PartyService {

    // =========================================================================
    // 状态机枚举（只向前）
    // =========================================================================

    /** 阶段枚举（顺序即推进方向，只向前）。 */
    public static final List<String> STAGE_FLOW =
            Arrays.asList("applicant", "activist", "candidate", "probationary", "full");

    /** 显式枚举前置态（§11.2-9：CAS 的 fromStage 取这里，不取刚读到的值）。 */
    public static final Map<String, String> PREVIOUS_STAGE = new LinkedHashMap<>();

    static {
        PREVIOUS_STAGE.put("activist", "applicant");
        PREVIOUS_STAGE.put("candidate", "activist");
        PREVIOUS_STAGE.put("probationary", "candidate");
        PREVIOUS_STAGE.put("full", "probationary");
    }

    /** 各阶段对应的台账日期列（推进时写这一列）。 */
    private static final Map<String, String> STAGE_DATE_COLUMN = new LinkedHashMap<>();

    /** 各阶段对应的依据说明列（推进时写这一列）。 */
    private static final Map<String, String> STAGE_REF_COLUMN = new LinkedHashMap<>();

    static {
        STAGE_DATE_COLUMN.put("applicant", "applicant_date");
        STAGE_DATE_COLUMN.put("activist", "activist_date");
        STAGE_DATE_COLUMN.put("candidate", "candidate_date");
        STAGE_DATE_COLUMN.put("probationary", "probationary_date");
        STAGE_DATE_COLUMN.put("full", "full_date");

        STAGE_REF_COLUMN.put("applicant", "applicant_ref");
        STAGE_REF_COLUMN.put("activist", "activist_ref");
        STAGE_REF_COLUMN.put("candidate", "candidate_ref");
        STAGE_REF_COLUMN.put("probationary", "probationary_ref");
        STAGE_REF_COLUMN.put("full", "full_ref");
    }

    /** 公示节点类型（只登记 S22 第 11/14/25 步这三个有来源的节点）。 */
    public static final Set<String> PUBLICITY_NODE_TYPES =
            new HashSet<>(Arrays.asList("training_student", "develop_party", "probation_convert"));

    /** 公示状态机：publish 只接受 {draft}，close 只接受 {publicizing}。 */
    public static final Set<String> PUBLICITY_DRAFT = new HashSet<>(Arrays.asList("draft"));

    public static final Set<String> PUBLICITY_PUBLICIZING = new HashSet<>(Arrays.asList("publicizing"));

    /** 公示默认工作日数（S22 各公示节点均为「公示 5 个工作日」；校规另有 5-7 天表述）。 */
    public static final int PUBLICITY_DEFAULT_WORKDAYS = 5;

    /** 思想汇报/考察审核状态：只有 pending 可以流转（审核 / 退回 / 撤回）。 */
    public static final Set<String> REVIEW_PENDING = new HashSet<>(Arrays.asList("pending"));

    /**
     * 公示异议入口：**只写校级受理渠道**，不写支部个人联系方式（§11.2-6）。
     */
    public static final String DEFAULT_OBJECTION_CHANNEL =
            "对公示对象有异议的，请向学校学生工作部（处）反映；"
                    + "具体受理邮箱/电话以学校公布的校级渠道为准（本平台不登记支部个人联系方式）。";

    // =========================================================================
    // 期限常量：两条来源并存，平台不替学校选（§11.2-3）
    // 每条含「出处 + 原文措辞」，只用于生成提示文案，不用于阻断推进。
    // =========================================================================

    /** 申请后 1 个月内谈话。出处：国标 S24《中国共产党发展党员工作细则》第七条；校规 S23 同。 */
    public static final int TALK_WITHIN_DAYS = 30;

    public static final String TALK_RULE =
            "S24 第七条 / S23：党组织收到入党申请书后，应在一个月内派人同入党申请人谈话，了解基本情况。";

    /** 确定入党积极分子前：党支部 2 个月以上培养教育。出处：校规 S23。 */
    public static final int ACTIVIST_MIN_EDU_DAYS = 60;

    public static final String ACTIVIST_RULE =
            "S23：入党申请人应经党支部两个月以上培养教育，才可确定为入党积极分子。";

    /** 入党积极分子 → 发展对象：1 年以上。出处：国标 S24 第十三条；校规 S23 同。 */
    public static final int CANDIDATE_MIN_DAYS = 365;

    public static final String CANDIDATE_RULE =
            "S24 第十三条 / S23：入党积极分子经过一年以上培养教育和考察，才可列为发展对象。";

    /** 考察频率（两条并存）：国标 S24 第十一条「每半年一次」。 */
    public static final int OBSERVE_INTERVAL_DAYS_NATIONAL = 180;

    public static final String OBSERVE_RULE_NATIONAL =
            "S24 第十一条：党支部每半年对入党积极分子进行一次考察。";

    /** 考察频率（两条并存）：校规 S23「每季度考察」。 */
    public static final int OBSERVE_INTERVAL_DAYS_SCHOOL = 90;

    public static final String OBSERVE_RULE_SCHOOL = "S23：每季度对入党积极分子进行一次考察。";

    /** 党校培训时长（两条并存）：国标 S24 第十七条「不少于三天（或不少于二十四个学时）」。 */
    public static final int TRAINING_MIN_DAYS_NATIONAL = 3;

    public static final int TRAINING_MIN_HOURS_NATIONAL = 24;

    public static final String TRAINING_RULE_NATIONAL =
            "S24 第十七条：不少于三天（或不少于二十四个学时）。";

    /** 党校培训时长（两条并存）：校规 S23「五至七天（或不少于四十个学时）」。 */
    public static final int TRAINING_MIN_DAYS_SCHOOL_LOW = 5;

    public static final int TRAINING_MIN_DAYS_SCHOOL_HIGH = 7;

    public static final int TRAINING_MIN_HOURS_SCHOOL = 40;

    public static final String TRAINING_RULE_SCHOOL =
            "S23：五至七天（或不少于四十个学时）。";

    /** 党校培训时长双口径提示（两条并列，平台不替学校选）。 */
    public static final String TRAINING_HOURS_RULE =
            "培训时长两条口径并存，平台不替学校选择：国标 " + TRAINING_RULE_NATIONAL
                    + "；校规 " + TRAINING_RULE_SCHOOL;

    /** 结业证书：自颁发之日起一年内有效。出处：校规 S23。 */
    public static final int TRAINING_CERT_VALID_DAYS = 365;

    public static final String TRAINING_CERT_RULE = "S23：结业证书自颁发之日起一年内有效。";

    /** 预备期 1 年，自支部（党员）大会通过之日算起。出处：国标 S24 第三十二条。 */
    public static final int PROBATION_MONTHS = 12;

    public static final String PROBATION_RULE =
            "S24 第三十二条：预备党员的预备期为一年，从支部（党员）大会通过其为预备党员之日算起。";

    /** 延长预备期：一次 6-12 个月（不少于半年、最长不超过一年）。出处：国标 S24 第三十一/三十二条。 */
    public static final int PROBATION_EXTEND_MIN_MONTHS = 6;

    public static final int PROBATION_EXTEND_MAX_MONTHS = 12;

    public static final String PROBATION_EXTEND_RULE =
            "S24 第三十一/三十二条：预备期只能延长一次，延长的时间不少于半年、最长不超过一年。";

    /** 上级党委审批：三个月内、最长不超过六个月。出处：国标 S24 第二十六条。 */
    public static final int APPROVAL_WITHIN_MONTHS = 3;

    public static final int APPROVAL_MAX_MONTHS = 6;

    public static final String APPROVAL_RULE =
            "S24 第二十六条：上级党委审批应在三个月内完成，最长不得超过六个月。";

    /** 公示天数（两条并存）：S22 各公示节点「公示 5 个工作日」；校规 S23「公示 5-7 天」。 */
    public static final int PUBLICITY_WORKDAYS_S22 = 5;

    public static final int PUBLICITY_DAYS_SCHOOL_LOW = 5;

    public static final int PUBLICITY_DAYS_SCHOOL_HIGH = 7;

    public static final String PUBLICITY_RULE =
            "S22：涉及公示的节点均为「公示 5 个工作日」；S23：公示 5-7 天（两条口径并存，平台不替学校选）。";

    /** 思想汇报：至少每季度一次；超过三个月未交，提示可能取消入党积极分子资格。出处：校规 S23。 */
    public static final int THOUGHT_REPORT_INTERVAL_DAYS = 90;

    public static final int THOUGHT_REPORT_WARN_DAYS = 90;

    public static final String THOUGHT_REPORT_RULE =
            "S23：至少每季度提交一次书面思想汇报；超过三个月不交思想汇报，可能取消入党积极分子资格"
                    + "（提示，不阻断推进）。";

    /** 再次确定为入党积极分子：原则上 2 年内不得。出处：校规 S23。 */
    public static final int RE_ACTIVIST_BAN_DAYS = 730;

    public static final String RE_ACTIVIST_RULE =
            "S23：原则上 2 年内不得再次确定为入党积极分子。";

    /** §11.1-⑤：发展对象 → 预备党员的**校内统一间隔无规定** ⇒ 本条只登记国标流程，不做硬性时限校验。 */
    public static final String CANDIDATE_TO_PROBATIONARY_RULE =
            "S24 第三十三条：转正/接收手续依次为 本人书面申请 -> 党小组意见 -> 征求党员和群众意见 -> "
                    + "支部委员会审查 -> 支部大会讨论表决 -> 报上级党委审批。"
                    + "（S23 未规定发展对象到预备党员的校内统一间隔，本平台不对该步做硬性时限校验。）";

    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // =========================================================================

    @Autowired
    private PartyApplicationMapper applicationMapper;

    @Autowired
    private PartyStageMapper stageMapper;

    @Autowired
    private PartyObservationMapper observationMapper;

    @Autowired
    private PartyPublicityMapper publicityMapper;

    @Autowired
    private PartyMaterialMapper materialMapper;

    @Autowired
    private PartyTrainingMapper trainingMapper;

    @Autowired
    private PartyTrainingMemberMapper trainingMemberMapper;

    private static final Logger log = LoggerFactory.getLogger(PartyService.class);

    /**
     * 审计服务（**实例**字段）。
     *
     * <p><b>P16 复测缺陷（已修）</b>：本字段原先声明为 {@code static}，而 Spring 的
     * {@code AutowiredAnnotationBeanPostProcessor} 对静态字段**只告警、不注入**（启动日志实测：
     * {@code Autowired annotation is not supported on static fields: private static …PartyService.auditLogService}）
     * ⇒ 生产环境该字段恒为 null，配合 {@link #audit} 的兜底 catch，P16 的全部审计写入被静默吞掉
     * 且调用方毫无感知。现在字段与 {@code audit(...)} 都改回实例成员，注入与调用恢复正常。</p>
     */
    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================================
    // 标签与权限
    // =========================================================================

    /** 阶段中文文案。 */
    public static String stageText(String stage) {
        if (stage == null) {
            return null;
        }
        switch (stage) {
            case "applicant":
                return "入党申请人";
            case "activist":
                return "入党积极分子";
            case "candidate":
                return "发展对象";
            case "probationary":
                return "预备党员";
            case "full":
                return "正式党员";
            default:
                return stage;
        }
    }

    /** 公示节点类型中文文案。 */
    public static String nodeTypeText(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        switch (nodeType) {
            case "training_student":
                return "确定党校学员公示";
            case "develop_party":
                return "发展党员公示";
            case "probation_convert":
                return "拟转正对象公示";
            default:
                return nodeType;
        }
    }

    /** 公示状态中文文案。 */
    public static String publicityStatusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "draft":
                return "草稿";
            case "publicizing":
                return "公示中";
            case "closed":
                return "已结束";
            default:
                return status;
        }
    }

    /** 思想汇报/考察审核状态中文文案。 */
    public static String reviewStatusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "pending":
                return "待审核";
            case "reviewed":
                return "已审核";
            case "returned":
                return "已退回";
            default:
                return status;
        }
    }

    /** 申请状态中文文案。 */
    public static String applicationStatusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "active":
                return "有效";
            case "withdrawn":
                return "已撤回";
            default:
                return status;
        }
    }

    /** 观察类型中文文案。 */
    public static String observationTypeText(String type) {
        if (type == null) {
            return null;
        }
        return "observation".equals(type) ? "考察记录" : "思想汇报";
    }

    /** 材料阶段中文文案（复用 {@link #stageText} 口径，独立方法便于 check-mapper-columns 不误判）。 */
    public static String materialStageText(String stage) {
        return stageText(stage);
    }

    /** 排序键：阶段在流程中的序号（未知返回 -1）。 */
    public static int stageIndex(String stage) {
        return STAGE_FLOW.indexOf(stage);
    }

    /** 阶段合法性。 */
    public static boolean isStage(String stage) {
        return STAGE_FLOW.contains(stage);
    }

    /** 下一个阶段（已是 full 返回 null）。 */
    public static String nextStage(String stage) {
        int index = stageIndex(stage);
        return index < 0 || index >= STAGE_FLOW.size() - 1 ? null : STAGE_FLOW.get(index + 1);
    }

    // ---- 权限守卫（返回 null 表示通过） ----

    /** 解析「这次查询要看谁」：学生强制自己；teacher 用辅导员范围；admin 全量。 */
    private static final class Scope {
        final Long userId;
        final Long counselorId;
        final Result<?> error;

        Scope(Long userId, Long counselorId, Result<?> error) {
            this.userId = userId;
            this.counselorId = counselorId;
            this.error = error;
        }
    }

    private static Scope scope(Long actorId, String role, Long requestedUserId) {
        if (actorId == null) {
            return new Scope(null, null, guardLogin(null));
        }
        if (isStudent(role)) {
            // 学生只能看自己：请求别人一律 403（不静默改写为本人，避免「拿别人数据」被掩盖）
            if (requestedUserId != null && !requestedUserId.equals(actorId)) {
                return new Scope(null, null, Result.error(403, "无权查看他人党建台账"));
            }
            return new Scope(actorId, null, null);
        }
        if (isAdmin(role)) {
            return new Scope(requestedUserId, null, null);
        }
        if (isStaff(role)) {
            // 辅导员：只见 student_profile.counselor_id 名下学生
            if (requestedUserId != null) {
                return new Scope(requestedUserId, actorId, null);
            }
            return new Scope(null, actorId, null);
        }
        return new Scope(null, null, Result.error(403, "无权访问"));
    }

    /** 单条记录的可见性：admin 全量 / teacher 名下 / student 本人。 */
    private static Result<?> checkTarget(Long actorId, String role, Long targetUserId, String what) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (isAdmin(role)) {
            return null;
        }
        if (isStaff(role)) {
            return targetUserId != null && targetUserId.equals(actorId)
                    ? Result.error(403, "辅导员请从名下学生列表进入" + what)
                    : null;
        }
        if (isStudent(role)) {
            return targetUserId != null && targetUserId.equals(actorId)
                    ? null
                    : Result.error(403, "无权查看他人" + what);
        }
        return Result.error(403, "无权访问");
    }

    // =========================================================================
    // 学生端数据抹除（§11.2-4）：服务层抹掉支部内部信息，不靠前端不渲染
    // =========================================================================

    private static PartyStage sanitize(PartyStage stage) {
        if (stage != null) {
            stage.setInternalNote(null);
            stage.setInternalDiscussion(null);
        }
        return stage;
    }

    private static PartyApplication sanitize(PartyApplication application) {
        if (application != null) {
            application.setInternalRemark(null);
        }
        return application;
    }

    private static PartyObservation sanitize(PartyObservation observation) {
        if (observation != null) {
            observation.setInternalNote(null);
        }
        return observation;
    }

    // ---- 装配（展示字段 + 提示字段；提示一律带 rule_ref，不阻断） ----

    private static void fill(PartyApplication application) {
        if (application == null) {
            return;
        }
        boolean overdue = application.getApplyDate() != null
                && (application.getTalkDate() == null
                ? LocalDate.now().isAfter(application.getApplyDate().plusDays(TALK_WITHIN_DAYS))
                : application.getTalkDate().isAfter(application.getApplyDate().plusDays(TALK_WITHIN_DAYS)));
        application.setTalkOverdue(overdue ? 1 : 0);
        if (application.getTalkDate() == null) {
            application.setTalkLimitTip(TALK_RULE
                    + (overdue ? " 提示：本申请递交已超过一个月仍未登记谈话（只提示，不影响推进）。"
                    : " 提示：请在一个月内登记谈话（只提示，不影响推进）。"));
        } else if (overdue) {
            application.setTalkLimitTip(TALK_RULE + " 提示：谈话登记时间晚于递交后一个月（只提示，不影响推进）。");
        } else {
            application.setTalkLimitTip(TALK_RULE);
        }
    }

    private static void fill(PartyStage stage) {
        if (stage == null) {
            return;
        }
        stage.setStageText(stageText(stage.getStage()));
        stage.setNextTip(nextStageTip(stage));
    }

    /**
     * 下一步待办与时限倒计时文案（**只提示不阻断**，§11.2-1）。
     *
     * <p>满 1 年以上才可列为发展对象这类「资格线」只作倒计时展示，服务端不据此拒绝推进。</p>
     */
    public static String nextStageTip(PartyStage stage) {
        if (stage == null || isBlank(stage.getStage())) {
            return "台账尚未建立，请先登记入党申请与谈话。";
        }
        String next = nextStage(stage.getStage());
        if (next == null) {
            return "已完成五阶段台账（正式党员）。" + PROBATION_RULE;
        }
        switch (next) {
            case "activist": {
                LocalDate base = stage.getApplicantDate();
                return "下一步：确定为入党积极分子。" + ACTIVIST_RULE + countdown(base, ACTIVIST_MIN_EDU_DAYS);
            }
            case "candidate": {
                LocalDate base = stage.getActivistDate();
                return "下一步：列为发展对象。" + CANDIDATE_RULE + countdown(base, CANDIDATE_MIN_DAYS);
            }
            case "probationary": {
                return "下一步：接收为预备党员。" + CANDIDATE_TO_PROBATIONARY_RULE
                        + " " + APPROVAL_RULE;
            }
            default: {
                LocalDate base = stage.getProbationaryDate();
                return "下一步：转为正式党员。" + PROBATION_RULE + countdown(base, 365)
                        + " " + PROBATION_EXTEND_RULE;
            }
        }
    }

    private static String countdown(LocalDate base, int days) {
        if (base == null) {
            return " 提示：该步起始日期尚未登记，倒计时不可算（只提示，不影响推进）。";
        }
        long remain = ChronoUnit.DAYS.between(LocalDate.now(), base.plusDays(days));
        if (remain > 0) {
            return " 时限倒计时：距满 " + days + " 天还有 " + remain + " 天（只提示，不影响推进）。";
        }
        return " 时限倒计时：已满 " + days + " 天，可由支部书记按实际情况推进（平台不做自动资格判定）。";
    }

    private static void fill(PartyObservation observation) {
        if (observation == null) {
            return;
        }
        observation.setTypeText(observationTypeText(observation.getType()));
        observation.setReviewStatusText(reviewStatusText(observation.getReviewStatus()));
        boolean report = !"observation".equals(observation.getType());
        int overdue = 0;
        if (report && observation.getSubmittedAt() != null) {
            overdue = ChronoUnit.DAYS.between(observation.getSubmittedAt().toLocalDate(), LocalDate.now())
                    > THOUGHT_REPORT_WARN_DAYS ? 1 : 0;
        }
        observation.setOverdue(overdue);
        observation.setLimitTip(report
                ? THOUGHT_REPORT_RULE
                : OBSERVE_RULE_NATIONAL + " " + OBSERVE_RULE_SCHOOL + "（两条来源并存，平台不替学校选。）");
    }

    private static void fill(PartyPublicity publicity) {
        if (publicity == null) {
            return;
        }
        publicity.setNodeTypeText(nodeTypeText(publicity.getNodeType()));
        publicity.setStatusText(publicityStatusText(publicity.getStatus()));
        if (publicity.getEndDate() == null || !"publicizing".equals(publicity.getStatus())) {
            publicity.setRemainDays(null);
        } else {
            long remain = ChronoUnit.DAYS.between(LocalDate.now(), publicity.getEndDate());
            publicity.setRemainDays((int) remain);
        }
    }

    private static void fill(PartyMaterial material) {
        if (material == null) {
            return;
        }
        material.setStageText(materialStageText(material.getStage()));
        material.setRequiredText(flag(material.getRequired()) == 1 ? "必备" : "选备");
        material.setSubmittedText(flag(material.getSubmitted()) == 1 ? "已交" : "未交");
    }

    /**
     * 写审计（{@link AuditLogService#record} 内部已 try/catch；这里再兜一层，审计失败绝不影响业务，
     * 但必须留下 warn 痕迹——P16 复测的「static 字段恒 null + 静默 catch」就是被这样藏住的）。
     */
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
        } catch (Exception e) {
            log.warn("党建审计写入失败 action={} actor={} target={}：{}", action, actorId, targetId, e.toString());
        }
    }

    /** 分页参数。 */
    private static int page(Object value) {
        Integer p = integer(value);
        return p == null || p < 1 ? 1 : p;
    }

    private static int size(Object value) {
        Integer s = integer(value);
        return s == null || s < 1 ? 20 : Math.min(s, 200);
    }

    /** body 里的 userId：staff 可传，学生传了也只会被 scope 拦；不传则 staff 全量。 */
    private static Long bodyUserId(Map<String, Object> body) {
        return longOf(pick(body, "userId", "user_id"));
    }

    // =========================================================================
    // 1. 申请登记（4 条路由）
    // =========================================================================

    /**
     * 登记入党申请（学生本人自报 / 辅导员代录）。
     *
     * <p>不判定资格：只要有学籍记录即可登记。申请后一个月内谈话只作提示（{@link #TALK_RULE}）。</p>
     */
    public Result<?> registerApplication(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Long targetUserId = bodyUserId(body);
        if (isStudent(role)) {
            targetUserId = actorId;
        } else if (isStaff(role) && targetUserId == null) {
            return Result.error(400, "请指定 userId");
        }
        if (targetUserId == null) {
            return Result.error(400, "请指定 userId");
        }

        LocalDate applyDate = parseDate(pick(body, "applyDate", "apply_date"));
        if (applyDate == null) {
            applyDate = LocalDate.now();
        }
        LocalDate talkDate = parseDate(pick(body, "talkDate", "talk_date"));
        if (talkDate != null && talkDate.isBefore(applyDate)) {
            return Result.error(400, "谈话日期不能早于申请日期");
        }

        PartyApplication exist = applicationMapper.findByUserId(targetUserId);
        if (exist != null) {
            return Result.error(400, "该学生已有入党申请记录（申请日期 " + exist.getApplyDate()
                    + "），请直接修改或先撤回原记录");
        }

        PartyApplication application = new PartyApplication();
        application.setUserId(targetUserId);
        application.setBranchName(trimToNull(pick(body, "branchName", "branch_name")));
        application.setApplyDate(applyDate);
        application.setTalkDate(talkDate);
        application.setTalkBy(trimToNull(pick(body, "talkBy", "talk_by")));
        application.setTalkSummary(trimToNull(pick(body, "talkSummary", "talk_summary")));
        application.setInternalRemark(trimToNull(pick(body, "internalRemark", "internal_remark")));
        application.setStatus("active");
        application.setCreatedBy(actorId);
        applicationMapper.insert(application);

        ensureStage(targetUserId, application.getBranchName(), applyDate);
        fill(application);
        audit("APPLY_REGISTER", actorId, application.getId(),
                "登记入党申请 userId=" + targetUserId + " applyDate=" + applyDate);
        return Result.success("入党申请已登记", sanitize(application));
    }

    /** 申请台账列表：学生只见自己；teacher 只见名下；admin 全量。 */
    public Result<?> listApplications(Long actorId, String role, Long userId, String status, String keyword,
                                      Integer page, Integer size) {
        Scope scope = scope(actorId, role, userId);
        if (scope.error != null) {
            return scope.error;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyApplication> rows = applicationMapper.list(scope.userId, scope.counselorId, status, keyword, offset, s);
        long total = applicationMapper.count(scope.userId, scope.counselorId, status, keyword);
        List<PartyApplication> out = new ArrayList<>();
        for (PartyApplication row : rows) {
            fill(row);
            out.add(isStudent(role) ? sanitize(row) : row);
        }
        return Result.success(pageOf(out, (int) total, p, s));
    }

    /** 申请详情：本人 / 名下 / 全量。 */
    public Result<?> applicationDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyApplication application = applicationMapper.findById(id);
        if (application == null) {
            return Result.error(404, "入党申请记录不存在");
        }
        Result<?> deny = checkTarget(actorId, role, application.getUserId(), "入党申请记录");
        if (deny != null) {
            return deny;
        }
        fill(application);
        return Result.success(isStudent(role) ? sanitize(application) : application);
    }

    /** 修改入党申请（CAS on status=active）。 */
    public Result<?> updateApplication(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        PartyApplication exist = applicationMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "入党申请记录不存在");
        }
        // 显式前置校验：只允许 active（撤回后不得再改）
        if (!"active".equals(exist.getStatus())) {
            return Result.error(400, "只有「有效」状态的申请可以修改，当前状态：" + applicationStatusText(exist.getStatus()));
        }

        PartyApplication application = new PartyApplication();
        application.setId(id);
        application.setBranchName(has(body, "branchName", "branch_name")
                ? trimToNull(pick(body, "branchName", "branch_name")) : exist.getBranchName());
        application.setApplyDate(has(body, "applyDate", "apply_date")
                ? parseDate(pick(body, "applyDate", "apply_date")) : exist.getApplyDate());
        application.setTalkDate(has(body, "talkDate", "talk_date")
                ? parseDate(pick(body, "talkDate", "talk_date")) : exist.getTalkDate());
        application.setTalkBy(has(body, "talkBy", "talk_by")
                ? trimToNull(pick(body, "talkBy", "talk_by")) : exist.getTalkBy());
        application.setTalkSummary(has(body, "talkSummary", "talk_summary")
                ? trimToNull(pick(body, "talkSummary", "talk_summary")) : exist.getTalkSummary());
        application.setInternalRemark(has(body, "internalRemark", "internal_remark")
                ? trimToNull(pick(body, "internalRemark", "internal_remark")) : exist.getInternalRemark());
        application.setStatus(exist.getStatus());
        if (application.getApplyDate() == null) {
            return Result.error(400, "申请日期不能为空");
        }
        if (application.getTalkDate() != null && application.getTalkDate().isBefore(application.getApplyDate())) {
            return Result.error(400, "谈话日期不能早于申请日期");
        }

        int rows = applicationMapper.update(application, "active");
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyApplication saved = applicationMapper.findById(id);
        fill(saved);
        audit("APPLY_UPDATE", actorId, id, "修改入党申请登记");
        return Result.success("已保存", saved);
    }

    // =========================================================================
    // 2. 阶段台账与推进（7 条路由）
    // =========================================================================

    /** 阶段台账列表。 */
    public Result<?> listStages(Long actorId, String role, Long userId, String stage, String branchName,
                                String keyword, Integer page, Integer size) {
        Scope scope = scope(actorId, role, userId);
        if (scope.error != null) {
            return scope.error;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyStage> rows = stageMapper.list(scope.userId, scope.counselorId, stage, branchName, keyword, offset, s);
        long total = stageMapper.count(scope.userId, scope.counselorId, stage, branchName, keyword);
        List<PartyStage> out = new ArrayList<>();
        for (PartyStage row : rows) {
            fill(row);
            out.add(isStudent(role) ? sanitize(row) : row);
        }
        return Result.success(pageOf(out, (int) total, p, s));
    }

    /** 台账详情（student 本人 / teacher 名下 / admin 全量）。 */
    public Result<?> stageDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyStage stage = stageMapper.findById(id);
        if (stage == null) {
            return Result.error(404, "党建台账不存在");
        }
        Result<?> deny = checkTarget(actorId, role, stage.getUserId(), "党建台账");
        if (deny != null) {
            return deny;
        }
        fill(stage);
        return Result.success(isStudent(role) ? sanitize(stage) : stage);
    }

    /** 我的台账（学生端入口；服务层抹掉内部考察意见与推优讨论内容）。 */
    public Result<?> myStage(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        PartyStage stage = stageMapper.findByUserId(actorId);
        if (stage == null) {
            PartyApplication application = applicationMapper.findByUserId(actorId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", null);
            data.put("application", application == null ? null : sanitize(application));
            data.put("message", "尚未建立党建台账；请先登记入党申请。");
            data.put("ruleRef", TALK_RULE);
            return Result.success(data);
        }
        fill(stage);
        return Result.success(sanitize(stage));
    }

    /** 阶段推进（CAS；fromStage 取显式枚举前置态）。staff 可操作。 */
    public Result<?> advanceStage(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyStage exist = stageMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "党建台账不存在");
        }

        String fromStage = exist.getStage();
        int index = stageIndex(fromStage);
        if (index < 0) {
            return Result.error(400, "台账阶段枚举非法：" + fromStage);
        }
        if (index >= STAGE_FLOW.size() - 1) {
            return Result.error(400, "已是最后阶段（正式党员），不能继续推进");
        }
        String toStage = STAGE_FLOW.get(index + 1);

        // 显式前置校验：本轮只允许推进到「当前阶段的下一个」，不接受跳步
        String requested = trimToNull(pick(body, "toStage", "to_stage", "stage"));
        if (requested != null && !requested.equals(toStage)) {
            return Result.error(400, "阶段只能逐级向前，当前阶段 " + stageText(fromStage)
                    + " 的下一步只能是 " + stageText(toStage));
        }

        LocalDate stepDate = parseDate(pick(body, "stepDate", "step_date", "date"));
        if (stepDate == null) {
            stepDate = LocalDate.now();
        }
        // 日期先后：不做资格判定，只做「不能倒序填日期」的一致性校验
        LocalDate previousAnchor = index <= 0 ? null
                : parseDate(readStageDate(exist, STAGE_FLOW.get(index - 1)));
        if (previousAnchor != null && stepDate.isBefore(previousAnchor)) {
            return Result.error(400, stageText(toStage) + "日期不能早于" + stageText(fromStage)
                    + "日期（" + previousAnchor + "）");
        }

        String stepRef = trimToNull(pick(body, "ref", "stepRef", "step_ref", "ruleRef"));
        if (stepRef == null) {
            stepRef = defaultRefFor(toStage, exist);
        }
        String branchName = trimToNull(pick(body, "branchName", "branch_name"));
        if (branchName == null) {
            branchName = exist.getBranchName();
        }

        int rows = stageMapper.advance(id, fromStage, toStage, stepDate, stepRef, branchName);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyStage saved = stageMapper.findById(id);
        fill(saved);
        audit("STAGE_ADVANCE", actorId, id, "阶段推进 " + fromStage + " -> " + toStage
                + "（stepDate=" + stepDate + "）userId=" + exist.getUserId());
        return Result.success("已推进到「" + stageText(toStage) + "」", saved);
    }

    /** 阶段回退：仅 admin + 强制原因 + 审计（§11.2-9）。 */
    public Result<?> rollbackStage(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdmin(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyStage exist = stageMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "党建台账不存在");
        }
        String fromStage = exist.getStage();
        String toStage = trimToNull(pick(body, "toStage", "to_stage", "stage"));
        if (toStage == null || !isStage(toStage)) {
            return Result.error(400, "请指定合法的目标阶段（applicant/activist/candidate/probationary/full）");
        }
        // 显式前置校验：只支持退回**上一级**，且上一级必须已有日期（不能凭空造一个未发生过的阶段）
        String expected = PREVIOUS_STAGE.get(fromStage);
        if (expected == null) {
            return Result.error(400, "当前处于第一阶段（" + stageText(fromStage) + "），无法回退");
        }
        if (!expected.equals(toStage)) {
            return Result.error(400, "只支持逐级回退：当前 " + stageText(fromStage)
                    + " 只能回退到 " + stageText(expected));
        }
        String reason = trimToNull(pick(body, "reason", "rollbackReason", "rollback_reason", "remark"));
        if (reason == null) {
            return Result.error(400, "阶段回退必须填写原因");
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = stageMapper.rollback(id, fromStage, toStage, reason, now);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyStage saved = stageMapper.findById(id);
        fill(saved);
        audit("STAGE_ROLLBACK", actorId, id, "阶段回退 " + fromStage + " -> " + toStage
                + "（原因：" + reason + "）userId=" + exist.getUserId());
        return Result.success("已回退到「" + stageText(toStage) + "」", saved);
    }

    /** 绑定当前阶段的公示记录（CAS on stage）。 */
    public Result<?> bindStagePublicity(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyStage exist = stageMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "党建台账不存在");
        }
        Long publicityId = longOf(pick(body, "publicityId", "publicity_id"));
        if (publicityId == null) {
            return Result.error(400, "请指定 publicityId");
        }
        PartyPublicity publicity = publicityMapper.findById(publicityId);
        if (publicity == null) {
            return Result.error(404, "公示记录不存在");
        }
        if (publicity.getUserId() != null && !publicity.getUserId().equals(exist.getUserId())) {
            return Result.error(400, "该公示不属于本学生，不能绑定");
        }
        int rows = stageMapper.bindPublicity(id, exist.getStage(), publicityId);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyStage saved = stageMapper.findById(id);
        fill(saved);
        audit("STAGE_BIND_PUBLICITY", actorId, id, "绑定公示 publicityId=" + publicityId);
        return Result.success("已绑定公示", saved);
    }

    /** 维护支部信息与内部考察意见（CAS on stage；不推进阶段）。staff 可写。 */
    public Result<?> updateStageInternal(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyStage exist = stageMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "党建台账不存在");
        }
        String branchName = has(body, "branchName", "branch_name")
                ? trimToNull(pick(body, "branchName", "branch_name")) : exist.getBranchName();
        String internalNote = has(body, "internalNote", "internal_note")
                ? trimToNull(pick(body, "internalNote", "internal_note")) : exist.getInternalNote();
        String internalDiscussion = has(body, "internalDiscussion", "internal_discussion")
                ? trimToNull(pick(body, "internalDiscussion", "internal_discussion")) : exist.getInternalDiscussion();

        int rows = stageMapper.updateInternal(id, exist.getStage(), branchName, internalNote, internalDiscussion);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyStage saved = stageMapper.findById(id);
        fill(saved);
        return Result.success("已保存（内部考察意见仅 staff 可见，学生端不下发）", saved);
    }

    // =========================================================================
    // 3. 考察与思想汇报（5 条路由）
    // =========================================================================

    /** 提交思想汇报（学生本人）。 */
    public Result<?> submitThoughtReport(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        String content = trimToNull(pick(body, "content", "text"));
        if (content == null) {
            return Result.error(400, "思想汇报内容不能为空");
        }
        PartyObservation observation = new PartyObservation();
        observation.setUserId(actorId);
        observation.setType("thought_report");
        observation.setSubmittedAt(LocalDateTime.now().withNano(0));
        observation.setPeriodLabel(trimToNull(pick(body, "periodLabel", "period_label")));
        observation.setContent(content);
        observation.setReviewStatus("pending");
        observation.setRuleRef(trimToNull(pick(body, "ruleRef", "rule_ref")) == null
                ? THOUGHT_REPORT_RULE : trimToNull(pick(body, "ruleRef", "rule_ref")));
        observation.setCreatedBy(actorId);
        observationMapper.insert(observation);
        fill(observation);
        return Result.success("思想汇报已提交，等待审核", observation);
    }

    /** 登记考察记录（staff；也可代学生登记思想汇报 type=thought_report）。 */
    public Result<?> recordObservation(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        Long targetUserId = bodyUserId(body);
        if (targetUserId == null) {
            return Result.error(400, "请指定 userId");
        }
        String type = trimToNull(pick(body, "type"));
        if (type == null) {
            type = "observation";
        }
        if (!"observation".equals(type) && !"thought_report".equals(type)) {
            return Result.error(400, "type 只能是 observation 或 thought_report");
        }
        String content = trimToNull(pick(body, "content", "text"));
        if (content == null) {
            return Result.error(400, "内容不能为空");
        }
        String submittedRaw = trimToNull(pick(body, "submittedAt", "submitted_at"));
        LocalDateTime submittedAt = submittedRaw == null ? LocalDateTime.now().withNano(0) : parseDateTime(submittedRaw);
        if (submittedAt == null) {
            return Result.error(400, "提交时间格式不正确（yyyy-MM-dd HH:mm:ss）");
        }

        PartyObservation observation = new PartyObservation();
        observation.setUserId(targetUserId);
        observation.setType(type);
        observation.setSubmittedAt(submittedAt);
        observation.setPeriodLabel(trimToNull(pick(body, "periodLabel", "period_label")));
        observation.setContent(content);
        observation.setReviewStatus("pending");
        observation.setInternalNote(trimToNull(pick(body, "internalNote", "internal_note")));
        observation.setRuleRef(trimToNull(pick(body, "ruleRef", "rule_ref")) == null
                ? ("observation".equals(type)
                ? OBSERVE_RULE_NATIONAL + " " + OBSERVE_RULE_SCHOOL : THOUGHT_REPORT_RULE)
                : trimToNull(pick(body, "ruleRef", "rule_ref")));
        observation.setCreatedBy(actorId);
        observationMapper.insert(observation);
        fill(observation);
        audit("OBSERVE_RECORD", actorId, observation.getId(),
                "登记" + observationTypeText(type) + " userId=" + targetUserId);
        return Result.success("已登记", observation);
    }

    /** 考察/思想汇报台账列表。 */
    public Result<?> listObservations(Long actorId, String role, Long userId, String type, String reviewStatus,
                                      Integer page, Integer size) {
        Scope scope = scope(actorId, role, userId);
        if (scope.error != null) {
            return scope.error;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyObservation> rows = observationMapper.list(scope.userId, scope.counselorId, type, reviewStatus,
                offset, s);
        long total = observationMapper.count(scope.userId, scope.counselorId, type, reviewStatus);
        List<PartyObservation> out = new ArrayList<>();
        for (PartyObservation row : rows) {
            fill(row);
            out.add(isStudent(role) ? sanitize(row) : row);
        }
        return Result.success(pageOf(out, (int) total, p, s));
    }

    /** 我的思想汇报与考察（学生端）。 */
    public Result<?> myObservations(Long actorId, String role, String type, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyObservation> rows = observationMapper.list(actorId, null, type, null, offset, s);
        long total = observationMapper.count(actorId, null, type, null);
        List<PartyObservation> out = new ArrayList<>();
        for (PartyObservation row : rows) {
            fill(row);
            out.add(sanitize(row));
        }
        // 时限提示：最近一条思想汇报距今是否超过 THOUGHT_REPORT_WARN_DAYS（只提示不阻断）
        PartyObservation latest = observationMapper.findLatest(actorId, "thought_report");
        Map<String, Object> data = pageOf(out, (int) total, p, s);
        data.put("thoughtReportRule", THOUGHT_REPORT_RULE);
        data.put("observeRuleNational", OBSERVE_RULE_NATIONAL);
        data.put("observeRuleSchool", OBSERVE_RULE_SCHOOL);
        if (latest == null || latest.getSubmittedAt() == null) {
            data.put("thoughtReportTip", "尚未提交思想汇报。" + THOUGHT_REPORT_RULE);
        } else {
            long days = ChronoUnit.DAYS.between(latest.getSubmittedAt().toLocalDate(), LocalDate.now());
            data.put("thoughtReportTip", "最近一次思想汇报提交于 " + latest.getSubmittedAt().format(STAMP)
                    + "（距今 " + days + " 天）。" + THOUGHT_REPORT_RULE);
        }
        return Result.success(data);
    }

    /** 审核思想汇报 / 考察记录：pending -> reviewed|returned（CAS）。 */
    public Result<?> reviewObservation(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyObservation exist = observationMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "记录不存在");
        }
        // 显式前置校验：只有 pending 可以审核（已审核/已退回不得重复流转）
        if (!REVIEW_PENDING.contains(exist.getReviewStatus())) {
            return Result.error(400, "只有「待审核」的记录可以审核，当前状态：" + reviewStatusText(exist.getReviewStatus()));
        }
        String action = trimToNull(pick(body, "action", "result"));
        String toStatus = "return".equals(action) || "returned".equals(action) ? "returned" : "reviewed";
        String opinion = trimToNull(pick(body, "reviewOpinion", "review_opinion", "opinion"));
        LocalDateTime now = LocalDateTime.now().withNano(0);

        int rows = observationMapper.review(id, "pending", toStatus, actorId, now, opinion);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyObservation saved = observationMapper.findById(id);
        fill(saved);
        audit("OBSERVE_REVIEW", actorId, id, "审核" + observationTypeText(exist.getType())
                + " -> " + reviewStatusText(toStatus) + " userId=" + exist.getUserId());
        return Result.success("已" + reviewStatusText(toStatus), saved);
    }

    // =========================================================================
    // 4. 公示（6 条路由）
    // =========================================================================

    /** 创建公示（默认草稿）。 */
    public Result<?> createPublicity(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        String nodeType = trimToNull(pick(body, "nodeType", "node_type"));
        if (nodeType == null || !PUBLICITY_NODE_TYPES.contains(nodeType)) {
            return Result.error(400, "nodeType 只能是 training_student / develop_party / probation_convert"
                    + "（只登记 S22 第 11/14/25 步这三个有来源的公示节点）");
        }
        String title = trimToNull(pick(body, "title"));
        if (title == null) {
            return Result.error(400, "公示标题不能为空");
        }
        Long targetUserId = bodyUserId(body);
        String stage = trimToNull(pick(body, "stage"));
        if (stage != null && !isStage(stage)) {
            return Result.error(400, "stage 只能是 applicant/activist/candidate/probationary/full");
        }
        LocalDate startDate = parseDate(pick(body, "startDate", "start_date"));
        if (startDate == null) {
            startDate = LocalDate.now();
        }
        Integer workdaysIn = integer(pick(body, "workdays"));
        int workdays = workdaysIn == null || workdaysIn < 1 ? PUBLICITY_DEFAULT_WORKDAYS : workdaysIn;
        LocalDate endDate = parseDate(pick(body, "endDate", "end_date"));
        if (endDate == null) {
            endDate = addWorkdays(startDate, workdays);
        }
        if (endDate.isBefore(startDate)) {
            return Result.error(400, "公示结束日期不能早于起始日期");
        }

        PartyPublicity publicity = new PartyPublicity();
        publicity.setUserId(targetUserId);
        publicity.setNodeType(nodeType);
        publicity.setTitle(title);
        publicity.setStage(stage);
        publicity.setStartDate(startDate);
        publicity.setEndDate(endDate);
        publicity.setWorkdays(workdays);
        String notice = trimToNull(pick(body, "noticeText", "notice_text"));
        publicity.setNoticeText(notice == null ? PUBLICITY_RULE : notice);
        String channel = trimToNull(pick(body, "objectionChannel", "objection_channel"));
        publicity.setObjectionChannel(channel == null ? DEFAULT_OBJECTION_CHANNEL : channel);
        publicity.setStatus("draft");
        publicity.setCreatedBy(actorId);
        publicityMapper.insert(publicity);

        if (targetUserId != null && longOf(publicity.getId()) != null) {
            PartyStage stageRow = stageMapper.findByUserId(targetUserId);
            if (stageRow != null) {
                stageMapper.bindPublicity(stageRow.getId(), stageRow.getStage(), publicity.getId());
            }
        }
        fill(publicity);
        audit("PUBLICITY_CREATE", actorId, publicity.getId(),
                "创建公示 nodeType=" + nodeType + " workdays=" + workdays + " userId=" + targetUserId);
        return Result.success("公示已创建（草稿）", publicity);
    }

    /** 公示列表：学生看公示中/已结束的公示（含本人草稿不可见），staff 看名下或全量。 */
    public Result<?> listPublicities(Long actorId, String role, Long userId, String nodeType, String status,
                                     Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Scope scope = scope(actorId, role, userId);
        if (scope.error != null) {
            return scope.error;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        boolean onlyOpen;
        if (isStudent(role)) {
            // 学生端只看已经公示出去的记录（draft 草稿不下发）
            onlyOpen = true;
        } else {
            onlyOpen = false;
        }
        List<PartyPublicity> rows;
        long total;
        if (onlyOpen) {
            rows = publicityMapper.list(scope.userId, scope.counselorId, nodeType, null, true, offset, s);
            total = publicityMapper.count(scope.userId, scope.counselorId, nodeType, null, true);
        } else {
            rows = publicityMapper.list(scope.userId, scope.counselorId, nodeType, status, false, offset, s);
            total = publicityMapper.count(scope.userId, scope.counselorId, nodeType, status, false);
        }
        for (PartyPublicity row : rows) {
            fill(row);
        }
        return Result.success(pageOf(rows, (int) total, p, s));
    }

    /** 公示详情（名单公示需登录）。 */
    public Result<?> publicityDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyPublicity publicity = publicityMapper.findById(id);
        if (publicity == null) {
            return Result.error(404, "公示记录不存在");
        }
        if (isStudent(role)) {
            // 学生只看已公示（publicizing / closed）且属于本人或全体的记录；草稿一律不可见
            if ("draft".equals(publicity.getStatus())) {
                return Result.error(404, "公示记录不存在");
            }
            if (publicity.getUserId() != null && !publicity.getUserId().equals(actorId)) {
                return Result.error(403, "无权查看他人公示");
            }
        }
        fill(publicity);
        return Result.success(publicity);
    }

    /** 修改公示：只允许 draft（publicizing 之后不得改内容）。 */
    public Result<?> updatePublicity(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyPublicity exist = publicityMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "公示记录不存在");
        }
        // 显式前置校验：只有草稿可改（公示中/已结束都不得改内容）
        if (!PUBLICITY_DRAFT.contains(exist.getStatus())) {
            return Result.error(400, "只有「草稿」状态的公示可以修改，当前状态："
                    + publicityStatusText(exist.getStatus()));
        }
        String nodeType = trimToNull(pick(body, "nodeType", "node_type"));
        if (nodeType != null && !PUBLICITY_NODE_TYPES.contains(nodeType)) {
            return Result.error(400, "nodeType 只能是 training_student / develop_party / probation_convert");
        }
        String title = trimToNull(pick(body, "title"));
        if (title == null) {
            return Result.error(400, "公示标题不能为空");
        }
        String stage = trimToNull(pick(body, "stage"));
        if (stage != null && !isStage(stage)) {
            return Result.error(400, "stage 只能是 applicant/activist/candidate/probationary/full");
        }
        Integer workdays = integer(pick(body, "workdays"));
        int finalWorkdays = workdays == null || workdays < 1 ? exist.getWorkdays() : workdays;
        LocalDate startDate = parseDate(pick(body, "startDate", "start_date"));
        if (startDate == null) {
            startDate = exist.getStartDate();
        }
        LocalDate endDate = parseDate(pick(body, "endDate", "end_date"));
        if (endDate == null) {
            endDate = addWorkdays(startDate, finalWorkdays);
        }
        if (endDate.isBefore(startDate)) {
            return Result.error(400, "公示结束日期不能早于起始日期");
        }

        PartyPublicity publicity = new PartyPublicity();
        publicity.setId(id);
        publicity.setUserId(has(body, "userId", "user_id") ? bodyUserId(body) : exist.getUserId());
        publicity.setNodeType(nodeType == null ? exist.getNodeType() : nodeType);
        publicity.setTitle(title);
        publicity.setStage(stage == null ? exist.getStage() : stage);
        publicity.setStartDate(startDate);
        publicity.setEndDate(endDate);
        publicity.setWorkdays(finalWorkdays);
        publicity.setNoticeText(has(body, "noticeText", "notice_text")
                ? trimToNull(pick(body, "noticeText", "notice_text")) : exist.getNoticeText());
        publicity.setObjectionChannel(has(body, "objectionChannel", "objection_channel")
                ? trimToNull(pick(body, "objectionChannel", "objection_channel")) : exist.getObjectionChannel());
        publicity.setStatus(exist.getStatus());

        if (isBlank(publicity.getObjectionChannel())) {
            return Result.error(400, "异议入口不能为空（只写校级受理渠道，不写支部个人联系方式）");
        }
        int rows = publicityMapper.update(publicity, "draft");
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyPublicity saved = publicityMapper.findById(id);
        fill(saved);
        return Result.success("已保存", saved);
    }

    /**
     * 发布公示：<b>只接受 {draft}</b>（P16 硬口径 §11.2-10）。
     *
     * <p>已公示中 / 已结束再 publish 一律 400，不做幂等 200。</p>
     */
    public Result<?> publishPublicity(Long actorId, String role, Long id) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyPublicity exist = publicityMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "公示记录不存在");
        }
        // 显式前置校验：只接受 draft
        if (!PUBLICITY_DRAFT.contains(exist.getStatus())) {
            return Result.error(400, "只有「草稿」状态的公示可以发布，当前状态："
                    + publicityStatusText(exist.getStatus()));
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = publicityMapper.publish(id, "draft", now);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyPublicity saved = publicityMapper.findById(id);
        fill(saved);
        audit("PUBLICITY_PUBLISH", actorId, id, "发布公示 nodeType=" + exist.getNodeType());
        return Result.success("公示已发布", saved);
    }

    /**
     * 结束公示：<b>只接受 {publicizing}</b>（P16 硬口径 §11.2-10）。
     *
     * <p>关键反例（P15 E2E 缺陷 D2）：判断条件这里<b>不能</b>写成
     * {@code draft/publicizing/closed 都放行}，否则已结束（closed）的公示会被再次「结束」成
     * 幂等 200，状态机退化。已结束再 close 必须 400。</p>
     */
    public Result<?> closePublicity(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyPublicity exist = publicityMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "公示记录不存在");
        }
        // 显式前置校验：只接受 publicizing（closed 再 close 必须 400，不得幂等 200）
        if (!PUBLICITY_PUBLICIZING.contains(exist.getStatus())) {
            return Result.error(400, "只有「公示中」的公示可以结束，当前状态："
                    + publicityStatusText(exist.getStatus()));
        }
        String result = trimToNull(pick(body, "result", "conclusion"));
        if (result == null) {
            result = "公示期满，未收到异议。";
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = publicityMapper.close(id, "publicizing", result, now);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyPublicity saved = publicityMapper.findById(id);
        fill(saved);
        audit("PUBLICITY_CLOSE", actorId, id, "结束公示 nodeId=" + id);
        return Result.success("公示已结束", saved);
    }

    /** 按工作日推算结束日期（跳过周六周日；工作日数从起始日次日起算）。 */
    public static LocalDate addWorkdays(LocalDate start, int workdays) {
        if (start == null) {
            return null;
        }
        LocalDate cursor = start;
        int counted = 0;
        int guard = 0;
        while (counted < workdays && guard < 1000) {
            cursor = cursor.plusDays(1);
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                counted++;
            }
            guard++;
        }
        return cursor;
    }

    // =========================================================================
    // 5. 材料清单（3 条路由）
    // =========================================================================

    /** 新增材料清单项（staff）。 */
    public Result<?> addMaterial(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        Long targetUserId = bodyUserId(body);
        if (targetUserId == null) {
            return Result.error(400, "请指定 userId");
        }
        String materialName = trimToNull(pick(body, "materialName", "material_name", "name"));
        if (materialName == null) {
            return Result.error(400, "材料名称不能为空");
        }
        String stage = trimToNull(pick(body, "stage"));
        if (stage == null || !isStage(stage)) {
            return Result.error(400, "stage 只能是 applicant/activist/candidate/probationary/full");
        }

        PartyMaterial material = new PartyMaterial();
        material.setUserId(targetUserId);
        material.setMaterialName(materialName);
        material.setStage(stage);
        String requiredRaw = trimToNull(pick(body, "required"));
        material.setRequired(requiredRaw == null ? 1 : flag(requiredRaw));
        String submittedRaw = trimToNull(pick(body, "submitted"));
        material.setSubmitted(submittedRaw == null ? 0 : flag(submittedRaw));
        material.setSubmitDate(parseDate(pick(body, "submitDate", "submit_date")));
        material.setAttachmentName(trimToNull(pick(body, "attachmentName", "attachment_name")));
        material.setNote(trimToNull(pick(body, "note")));
        material.setRuleRef(trimToNull(pick(body, "ruleRef", "rule_ref")));
        material.setCreatedBy(actorId);
        materialMapper.insert(material);
        fill(material);
        return Result.success("材料项已登记（只登记名称与状态，平台不生成官方表格 PDF）", material);
    }

    /** 材料清单列表。 */
    public Result<?> listMaterials(Long actorId, String role, Long userId, String stage, Integer submitted,
                                   Integer page, Integer size) {
        Scope scope = scope(actorId, role, userId);
        if (scope.error != null) {
            return scope.error;
        }
        int p = page(page);
        int s = size(size);
        int offset = (p - 1) * s;
        List<PartyMaterial> rows = materialMapper.list(scope.userId, scope.counselorId, stage, submitted, offset, s);
        long total = materialMapper.count(scope.userId, scope.counselorId, stage, submitted);
        for (PartyMaterial row : rows) {
            fill(row);
        }
        return Result.success(pageOf(rows, (int) total, p, s));
    }

    /** 登记交件 / 退件（CAS：不覆盖本人已确认状态之外的并发改名）。 */
    public Result<?> submitMaterial(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error(400, "缺少 id");
        }
        PartyMaterial exist = materialMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "材料项不存在");
        }
        Integer submitted = integer(pick(body, "submitted"));
        if (submitted == null) {
            submitted = 1;
        }
        if (submitted != 0 && submitted != 1) {
            return Result.error(400, "submitted 只能是 0（未交）或 1（已交）");
        }
        LocalDate submitDate = parseDate(pick(body, "submitDate", "submit_date"));
        if (submitted == 1 && submitDate == null) {
            submitDate = LocalDate.now();
        }
        String attachmentName = trimToNull(pick(body, "attachmentName", "attachment_name"));
        String note = trimToNull(pick(body, "note"));
        int rows = materialMapper.submit(id, submitted, submitDate, attachmentName, note);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PartyMaterial saved = materialMapper.findById(id);
        fill(saved);
        return Result.success(submitted == 1 ? "已登记交件" : "已标记为未交", saved);
    }

    // =========================================================================
    // 6. 统计与导出（2 条路由）
    // =========================================================================

    /** 党建综合统计（阶段分布 / 支部分布 / 申请状态 / 公示状态 / 党校 / 材料）。 */
    public Result<?> stats(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> stageGroups = stageMapper.countGroupByStage();
        List<Map<String, Object>> out = new ArrayList<>();
        long totalStage = 0;
        for (Map<String, Object> row : stageGroups) {
            String code = text(row.get("stage"));
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("stageText", stageText(code));
            out.add(item);
            totalStage += toInt(row.get("total"), 0);
        }
        data.put("stageDistribution", out);
        data.put("totalStage", totalStage);

        Map<String, Object> app = new LinkedHashMap<>();
        for (Map<String, Object> row : applicationMapper.countGroupByStatus()) {
            app.put(text(row.get("status")), toInt(row.get("total"), 0));
        }
        data.put("applicationStatus", app);
        data.put("applicationTotal", applicationMapper.count(null, null, null, null));

        Map<String, Object> pub = new LinkedHashMap<>();
        for (Map<String, Object> row : publicityMapper.countGroupByStatus()) {
            pub.put(text(row.get("status")), toInt(row.get("total"), 0));
        }
        data.put("publicityStatus", pub);

        Map<String, Object> observe = new LinkedHashMap<>();
        for (Map<String, Object> row : observationMapper.countGroupByReviewStatus()) {
            observe.put(text(row.get("review_status")), toInt(row.get("total"), 0));
        }
        data.put("observationReviewStatus", observe);

        List<Map<String, Object>> train = new ArrayList<>();
        for (Map<String, Object> row : trainingMapper.countGroupByStatus()) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("statusText", PartyTrainingService.statusText(text(row.get("status"))));
            train.add(item);
        }
        data.put("trainingStatus", train);

        List<Map<String, Object>> trainMember = new ArrayList<>();
        for (Map<String, Object> row : trainingMemberMapper.countGroupByStatusAll()) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("statusText", PartyTrainingService.memberStatusText(text(row.get("status"))));
            trainMember.add(item);
        }
        data.put("trainingMemberStatus", trainMember);

        List<Map<String, Object>> material = new ArrayList<>();
        for (Map<String, Object> row : materialMapper.countGroupByStage()) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("stageText", stageText(text(row.get("stage"))));
            material.add(item);
        }
        data.put("materialByStage", material);

        data.put("rules", rules());
        return Result.success(data);
    }

    /** 全部期限提示口径（两条来源并存，平台不替学校选）。 */
    public static Map<String, Object> rules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("talkWithinDays", TALK_WITHIN_DAYS);
        rules.put("talkRule", TALK_RULE);
        rules.put("activistMinEduDays", ACTIVIST_MIN_EDU_DAYS);
        rules.put("activistRule", ACTIVIST_RULE);
        rules.put("candidateMinDays", CANDIDATE_MIN_DAYS);
        rules.put("candidateRule", CANDIDATE_RULE);
        rules.put("observeIntervalDaysNational", OBSERVE_INTERVAL_DAYS_NATIONAL);
        rules.put("observeRuleNational", OBSERVE_RULE_NATIONAL);
        rules.put("observeIntervalDaysSchool", OBSERVE_INTERVAL_DAYS_SCHOOL);
        rules.put("observeRuleSchool", OBSERVE_RULE_SCHOOL);
        rules.put("trainingRuleNational", TRAINING_RULE_NATIONAL);
        rules.put("trainingRuleSchool", TRAINING_RULE_SCHOOL);
        rules.put("trainingHoursRule", TRAINING_HOURS_RULE);
        rules.put("trainingCertValidDays", TRAINING_CERT_VALID_DAYS);
        rules.put("trainingCertRule", TRAINING_CERT_RULE);
        rules.put("probationMonths", PROBATION_MONTHS);
        rules.put("probationRule", PROBATION_RULE);
        rules.put("probationExtendRule", PROBATION_EXTEND_RULE);
        rules.put("approvalRule", APPROVAL_RULE);
        rules.put("publicityWorkdaysS22", PUBLICITY_WORKDAYS_S22);
        rules.put("publicityDaysSchoolLow", PUBLICITY_DAYS_SCHOOL_LOW);
        rules.put("publicityDaysSchoolHigh", PUBLICITY_DAYS_SCHOOL_HIGH);
        rules.put("publicityRule", PUBLICITY_RULE);
        rules.put("thoughtReportIntervalDays", THOUGHT_REPORT_INTERVAL_DAYS);
        rules.put("thoughtReportRule", THOUGHT_REPORT_RULE);
        rules.put("reActivistBanDays", RE_ACTIVIST_BAN_DAYS);
        rules.put("reActivistRule", RE_ACTIVIST_RULE);
        rules.put("candidateToProbationaryRule", CANDIDATE_TO_PROBATIONARY_RULE);
        rules.put("note", "所有「应当/不得」均为带条款引用的提示文案，平台不做自动资格判定、不阻断推进（§11.2-1）；"
                + "国标 S24 与校规 S23 不一致处两条并存，平台不替学校选（§11.2-3）。");
        return rules;
    }

    /**
     * 导出 CSV（学工党建台账）：**只给学号后四位 + 姓名 + 班级 + 阶段 + 日期 + 支部**，
     * **不含身份证号、不含联系方式**（§11.2-6）。
     */
    public CsvExport exportCsv(Long actorId, String role, String type, String branchName, String stage) {
        CsvExport out = new CsvExport();
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            out.errorCode = 401;
            out.errorMessage = "请先登录";
            return out;
        }
        if (!isStaff(role)) {
            out.errorCode = 403;
            out.errorMessage = "仅辅导员/管理员可导出";
            return out;
        }
        String kind = isBlank(type) ? "stages" : type.trim();
        if (!"stages".equals(kind) && !"applications".equals(kind)) {
            out.errorCode = 400;
            out.errorMessage = "type 只能是 stages（阶段台账）或 applications（申请登记）";
            return out;
        }
        Long counselorId = isAdmin(role) ? null : actorId;

        if ("applications".equals(kind)) {
            List<PartyApplication> rows = applicationMapper.list(null, counselorId, null, null, 0, 100000);
            if (rows.size() > MAX_EXPORT) {
                return tooMany(out);
            }
            StringBuilder sb = new StringBuilder("学号后四位,姓名,班级,申请日期,谈话日期,谈话人,党支部,状态\n");
            for (PartyApplication row : rows) {
                sb.append(csvCell(tail4(row.getStudentNo()))).append(',')
                        .append(csvCell(row.getUserName())).append(',')
                        .append(csvCell(row.getClassName())).append(',')
                        .append(csvCell(fmt(row.getApplyDate()))).append(',')
                        .append(csvCell(fmt(row.getTalkDate()))).append(',')
                        .append(csvCell(row.getTalkBy())).append(',')
                        .append(csvCell(row.getBranchName())).append(',')
                        .append(csvCell(applicationStatusText(row.getStatus()))).append('\n');
            }
            out.csv = sb.toString();
            out.filename = "party_applications_" + LocalDate.now().format(DAY) + ".csv";
        } else {
            List<PartyStage> rows = stageMapper.list(null, counselorId, stage, branchName, null, 0, 100000);
            if (rows.size() > MAX_EXPORT) {
                return tooMany(out);
            }
            StringBuilder sb = new StringBuilder(
                    "学号后四位,姓名,班级,当前阶段,申请人日期,积极分子日期,发展对象日期,预备党员日期,正式党员日期,党支部\n");
            for (PartyStage row : rows) {
                sb.append(csvCell(tail4(row.getStudentNo()))).append(',')
                        .append(csvCell(row.getUserName())).append(',')
                        .append(csvCell(row.getClassName())).append(',')
                        .append(csvCell(stageText(row.getStage()))).append(',')
                        .append(csvCell(fmt(row.getApplicantDate()))).append(',')
                        .append(csvCell(fmt(row.getActivistDate()))).append(',')
                        .append(csvCell(fmt(row.getCandidateDate()))).append(',')
                        .append(csvCell(fmt(row.getProbationaryDate()))).append(',')
                        .append(csvCell(fmt(row.getFullDate()))).append(',')
                        .append(csvCell(row.getBranchName())).append('\n');
            }
            out.csv = sb.toString();
            out.filename = "party_stages_" + LocalDate.now().format(DAY) + ".csv";
        }
        audit("PARTY_EXPORT", actorId, null, "导出党建 CSV type=" + kind + " rows="
                + (out.csv == null ? 0 : out.csv.split("\n").length - 1));
        return out;
    }

    private static CsvExport tooMany(CsvExport out) {
        out.errorCode = 400;
        out.errorMessage = "数据量超过导出上限（" + MAX_EXPORT + " 行），请先按支部/阶段过滤";
        return out;
    }

    private static String fmt(LocalDate date) {
        return date == null ? "" : date.format(DAY);
    }

    // =========================================================================
    // 台账初始化与列名工具
    // =========================================================================

    /** 登记申请时自动建一条阶段台账（幂等）。 */
    private void ensureStage(Long userId, String branchName, LocalDate applyDate) {
        PartyStage exist = stageMapper.findByUserId(userId);
        if (exist != null) {
            return;
        }
        PartyStage stage = new PartyStage();
        stage.setUserId(userId);
        stage.setBranchName(branchName);
        stage.setStage("applicant");
        stage.setApplicantDate(applyDate);
        stage.setApplicantRef(TALK_RULE);
        stageMapper.insert(stage);
    }

    /** 读台账上某阶段的日期（推进时做日期先后一致性校验）。 */
    private static LocalDate readStageDate(PartyStage stage, String stageCode) {
        if (stage == null || stageCode == null) {
            return null;
        }
        switch (stageCode) {
            case "applicant":
                return stage.getApplicantDate();
            case "activist":
                return stage.getActivistDate();
            case "candidate":
                return stage.getCandidateDate();
            case "probationary":
                return stage.getProbationaryDate();
            case "full":
                return stage.getFullDate();
            default:
                return null;
        }
    }

    /** 各步未传 ref 时的默认条款引用（全部来自 §11.1 原文，不编造）。 */
    private static String defaultRefFor(String toStage, PartyStage exist) {
        switch (toStage) {
            case "activist":
                return ACTIVIST_RULE;
            case "candidate":
                return CANDIDATE_RULE;
            case "probationary":
                return CANDIDATE_TO_PROBATIONARY_RULE;
            case "full":
                return PROBATION_RULE;
            default:
                return TALK_RULE;
        }
    }

    /** 学生端「下一步待办倒计时」用到的材料清单提示（不阻断）。 */
    @SuppressWarnings("unused")
    public static String materialTip(String stage, List<PartyMaterial> materials) {
        if (materials == null || materials.isEmpty()) {
            return "材料清单尚未登记（平台只登记名称与状态，不生成官方表格 PDF）。";
        }
        int missing = 0;
        for (PartyMaterial material : materials) {
            if (flag(material.getRequired()) == 1 && flag(material.getSubmitted()) != 1) {
                missing++;
            }
        }
        return missing == 0
                ? "本阶段必备材料均已登记为已交。"
                : "本阶段还有 " + missing + " 项必备材料未登记为已交（只提示，不阻断阶段推进）。";
    }

}
