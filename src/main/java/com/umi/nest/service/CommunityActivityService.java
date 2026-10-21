package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.CommunityActivity;
import com.umi.nest.entity.CommunityActivitySignup;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.CommunityActivityMapper;
import com.umi.nest.mapper.CommunityActivitySignupMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社区活动与报名（学工 P15）。
 *
 * <p>口径（《学工平台功能对齐说明》§10.2-8）：活动管理限 admin 与辅导员；同活动同学生只允许一次报名
 * （DB 唯一键 uk_cas_activity_student 兜底，服务层先查重）；quota 0 = 不限，满了报名返回 400；
 * signup_deadline 过期报名返回 400；attended / absent 由签到置位；award 只由管理员与辅导员登记；
 * 两阶段（报名 → 审核 → 签到）只体现为状态推进，不额外建阶段表。</p>
 *
 * <p>活动状态机：draft → signup → ongoing → finished，另有 cancelled；PUT 里只允许向前推进，
 * 每次迁移先显式校验前置状态，再用 CAS 落库。</p>
 */
@Service("communityActivityService")
public class CommunityActivityService {

    private static final List<String> ACTIVITY_TYPES = Arrays.asList("culture_festival", "room_contest",
            "room_olympic", "design_contest", "volunteer", "other");
    private static final List<String> LEVELS = Arrays.asList("school", "college", "community");
    private static final List<String> ACTIVITY_STATUSES = Arrays.asList("draft", "signup", "ongoing", "finished", "cancelled");
    private static final List<String> AWARDS = Arrays.asList("none", "first", "second", "third", "excellent");
    private static final List<String> ATTEND_STATUSES = Arrays.asList("attended", "absent");
    /** 可以签到的前置状态（已报名 / 已通过审核）。 */
    private static final List<String> ATTEND_FROM_STATUSES = Arrays.asList("signed", "approved");

    @Autowired
    private CommunityActivityMapper activityMapper;

    @Autowired
    private CommunityActivitySignupMapper signupMapper;

    @Autowired
    private StudentProfileMapper studentProfileMapper;

    // =========================================================================
    // 活动：GET /activities、POST /activities、PUT /activities/{id}
    // =========================================================================

    /** 活动列表（学生只看非草稿、非取消的活动；管理员与辅导员可带 hideDraft=0 看全部）。 */
    public Result<?> listActivities(Long actorId, String role, String term, String activityType, String level,
                                    String status, String keyword) {
        Result<?> guard = CommunityService.guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        boolean hideDraft = !CommunityService.isStaff(role);
        List<CommunityActivity> rows = activityMapper.list(CommunityService.trimToNull(term),
                CommunityService.trimToNull(activityType), CommunityService.trimToNull(level),
                CommunityService.trimToNull(status), CommunityService.trimToNull(keyword), hideDraft);
        return Result.success(rows);
    }

    /** 新建活动（admin / 辅导员）。 */
    public Result<?> createActivity(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        String title = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "title")));
        if (title == null) {
            return Result.error("请填写活动名称 title");
        }
        if (title.length() > 128) {
            return Result.error("活动名称不能超过 128 字");
        }
        String activityType = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "activityType", "activity_type")));
        if (activityType != null && !ACTIVITY_TYPES.contains(activityType)) {
            return Result.error("活动类型只支持 culture_festival / room_contest / room_olympic / design_contest / volunteer / other");
        }
        String level = CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "level"))), "community");
        if (!LEVELS.contains(level)) {
            return Result.error("活动级别只支持 school / college / community");
        }
        LocalDateTime startAt = CommunityService.parseDateTime(CommunityService.pick(body, "startAt", "start_at"));
        LocalDateTime endAt = CommunityService.parseDateTime(CommunityService.pick(body, "endAt", "end_at"));
        if (CommunityService.has(body, "startAt", "start_at") && startAt == null) {
            return Result.error("开始时间 startAt 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        if (CommunityService.has(body, "endAt", "end_at") && endAt == null) {
            return Result.error("结束时间 endAt 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
            return Result.error("结束时间不能早于开始时间");
        }
        LocalDateTime signupDeadline = CommunityService.parseDateTime(
                CommunityService.pick(body, "signupDeadline", "signup_deadline"));
        if (CommunityService.has(body, "signupDeadline", "signup_deadline") && signupDeadline == null) {
            return Result.error("报名截止时间 signupDeadline 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        Integer quota = CommunityService.integer(CommunityService.pick(body, "quota"));
        if (CommunityService.has(body, "quota") && quota == null) {
            return Result.error("名额 quota 必须是数字（0 表示不限）");
        }
        if (quota != null && quota < 0) {
            return Result.error("名额 quota 不能为负数（0 表示不限）");
        }
        CommunityActivity activity = new CommunityActivity();
        activity.setTitle(title);
        activity.setActivityType(activityType == null ? "other" : activityType);
        activity.setTerm(CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "term"))));
        activity.setLevel(level);
        activity.setStartAt(startAt);
        activity.setEndAt(endAt);
        activity.setLocation(CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "location"))));
        activity.setSignupDeadline(signupDeadline);
        activity.setQuota(quota);
        activity.setContent(CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "content"))));
        activity.setHost(CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "host"))));
        activity.setStatus("draft");
        activity.setNoticeUrl(CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "noticeUrl", "notice_url"))));
        activity.setCreatedBy(actorId);
        activityMapper.insert(activity);
        return Result.success(activityMapper.findById(activity.getId()));
    }

    /** 修改活动：已结束 / 已取消不能改；status 只允许按活动状态机向前推进。 */
    public Result<?> updateActivity(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        CommunityActivity exist = activityMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "活动不存在");
        }
        if ("finished".equals(exist.getStatus()) || "cancelled".equals(exist.getStatus())) {
            return Result.error("已结束或已取消的活动不能再修改");
        }
        String title = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "title")));
        if (title != null && title.length() > 128) {
            return Result.error("活动名称不能超过 128 字");
        }
        String activityType = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "activityType", "activity_type")));
        if (activityType != null && !ACTIVITY_TYPES.contains(activityType)) {
            return Result.error("活动类型只支持 culture_festival / room_contest / room_olympic / design_contest / volunteer / other");
        }
        String level = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "level")));
        if (level != null && !LEVELS.contains(level)) {
            return Result.error("活动级别只支持 school / college / community");
        }
        Integer quota = CommunityService.integer(CommunityService.pick(body, "quota"));
        if (CommunityService.has(body, "quota") && quota == null) {
            return Result.error("名额 quota 必须是数字（0 表示不限）");
        }
        if (quota != null && quota < 0) {
            return Result.error("名额 quota 不能为负数（0 表示不限）");
        }
        // 状态前置校验：CAS 的 fromStatus 取自刚读到的现值，单靠它挡不住非法迁移（WHERE status=? 恒成立），
        // 所以每次迁移都必须先显式校验一遍 from 状态。
        String current = exist.getStatus() == null ? "draft" : exist.getStatus();
        String target = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "status")));
        if (target != null) {
            if (!ACTIVITY_STATUSES.contains(target)) {
                return Result.error("活动状态只支持 draft / signup / ongoing / finished / cancelled");
            }
            if (!target.equals(current)) {
                if ("cancelled".equals(target)) {
                    // 取消：草稿 / 报名中 / 进行中都可以取消
                    if ("finished".equals(current)) {
                        return Result.error("已结束的活动不能取消");
                    }
                } else {
                    int from = ACTIVITY_STATUSES.indexOf(current);
                    int to = ACTIVITY_STATUSES.indexOf(target);
                    if ("cancelled".equals(current)) {
                        return Result.error("已取消的活动不能再推进状态");
                    }
                    if (to <= from) {
                        return Result.error("活动状态只能向前推进（当前 " + CommunityService.statusLabel(current)
                                + " → 目标 " + CommunityService.statusLabel(target) + "）");
                    }
                }
            }
        }
        LocalDateTime startAt = CommunityService.has(body, "startAt", "start_at")
                ? CommunityService.parseDateTime(CommunityService.pick(body, "startAt", "start_at")) : exist.getStartAt();
        LocalDateTime endAt = CommunityService.has(body, "endAt", "end_at")
                ? CommunityService.parseDateTime(CommunityService.pick(body, "endAt", "end_at")) : exist.getEndAt();
        if (CommunityService.has(body, "startAt", "start_at") && startAt == null) {
            return Result.error("开始时间 startAt 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        if (CommunityService.has(body, "endAt", "end_at") && endAt == null) {
            return Result.error("结束时间 endAt 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
            return Result.error("结束时间不能早于开始时间");
        }
        LocalDateTime signupDeadline = CommunityService.has(body, "signupDeadline", "signup_deadline")
                ? CommunityService.parseDateTime(CommunityService.pick(body, "signupDeadline", "signup_deadline"))
                : exist.getSignupDeadline();
        if (CommunityService.has(body, "signupDeadline", "signup_deadline") && signupDeadline == null) {
            return Result.error("报名截止时间 signupDeadline 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        CommunityActivity merged = new CommunityActivity();
        merged.setId(exist.getId());
        merged.setTitle(title == null ? exist.getTitle() : title);
        merged.setActivityType(activityType == null ? exist.getActivityType() : activityType);
        merged.setTerm(CommunityService.has(body, "term")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "term"))) : exist.getTerm());
        merged.setLevel(level == null ? exist.getLevel() : level);
        merged.setStartAt(startAt);
        merged.setEndAt(endAt);
        merged.setLocation(CommunityService.has(body, "location")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "location"))) : exist.getLocation());
        merged.setSignupDeadline(signupDeadline);
        merged.setQuota(quota == null ? exist.getQuota() : quota);
        merged.setContent(CommunityService.has(body, "content")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "content"))) : exist.getContent());
        merged.setHost(CommunityService.has(body, "host")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "host"))) : exist.getHost());
        merged.setNoticeUrl(CommunityService.has(body, "noticeUrl", "notice_url")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "noticeUrl", "notice_url"))) : exist.getNoticeUrl());
        merged.setStatus(target == null ? current : target);
        int rows = activityMapper.update(merged, current);
        if (rows != 1) {
            return Result.error(CommunityService.CAS_FAIL);
        }
        return Result.success(activityMapper.findById(id));
    }

    // =========================================================================
    // 报名：POST /activities/{id}/signup、GET /my/activities
    // =========================================================================

    /** 学生报名活动：状态必须是报名中、未过截止时间、名额未满、且未重复报名。 */
    public Result<?> signupActivity(Long actorId, String role, Long activityId, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        CommunityActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            return Result.error(404, "活动不存在");
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可报名活动（未找到你的学生档案）");
        }
        if (!"signup".equals(activity.getStatus())) {
            return Result.error("该活动当前不在报名中（当前状态：" + CommunityService.statusLabel(activity.getStatus()) + "）");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (activity.getSignupDeadline() != null && now.isAfter(activity.getSignupDeadline())) {
            return Result.error("报名已截止（截止时间 " + CommunityService.STAMP.format(activity.getSignupDeadline()) + "）");
        }
        if (signupMapper.findByActivityStudent(activityId, actorId) != null) {
            return Result.error("你已报名该活动（同一活动同一学生只能报名一次）");
        }
        Integer quota = activity.getQuota();
        if (quota != null && quota > 0) {
            Integer used = signupMapper.occupancy(activityId);
            if (used != null && used >= quota) {
                return Result.error("名额已满（" + used + "/" + quota + "）");
            }
        }
        CommunityActivitySignup signup = new CommunityActivitySignup();
        signup.setActivityId(activityId);
        signup.setStudentId(actorId);
        signup.setBuilding(profile.getDormBuilding());
        signup.setRoomNo(profile.getDormRoom());
        signup.setSignupAt(now);
        signup.setStatus("signed");
        // award 列是 NOT NULL DEFAULT 'none'，但 insert 语句无条件写入该列 ⇒ 必须显式给初值，
        // 否则显式插 NULL 时数据库默认值不生效（D1：报名接口 500 Column 'award' cannot be null）。
        signup.setAward("none");
        signupMapper.insert(signup);
        return Result.success(signupMapper.findById(signup.getId()));
    }

    /** 学生查看本人的活动报名（含活动标题与状态）。 */
    public Result<?> myActivities(Long actorId, String role, String activityId) {
        Result<?> guard = CommunityService.guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可查看自己的报名记录");
        }
        List<CommunityActivitySignup> rows = signupMapper.listMine(actorId,
                CommunityService.longOf(activityId));
        return Result.success(rows);
    }

    // =========================================================================
    // 审核与签到：POST /activities/{id}/review、POST /activities/{id}/attendance
    // =========================================================================

    /** 报名审核：signed → approved / rejected。支持单条（signupId 或 studentId）与 items 数组批量。 */
    public Result<?> reviewSignups(Long actorId, String role, Long activityId, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        CommunityActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            return Result.error(404, "活动不存在");
        }
        String action = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "action")));
        if (action == null) {
            return Result.error("请给出审核动作 action（approve / reject）");
        }
        if (!"approve".equals(action) && !"reject".equals(action)) {
            return Result.error("审核动作只支持 approve（通过）/ reject（驳回）");
        }
        String rejectReason = CommunityService.trimToNull(CommunityService.text(
                CommunityService.pick(body, "rejectReason", "reject_reason", "reason")));
        if (rejectReason != null && rejectReason.length() > 255) {
            return Result.error("驳回原因不能超过 255 字");
        }
        List<Long> signupIds = collectSignupIds(activityId, body);
        if (signupIds.isEmpty()) {
            return Result.error("请指定要审核的报名 signupId 或 studentId（也可用 items 数组批量）");
        }
        String toStatus = "approve".equals(action) ? "approved" : "rejected";
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<CommunityActivitySignup> done = new ArrayList<>();
        int failed = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Long signupId : signupIds) {
            CommunityActivitySignup signup = signupMapper.findById(signupId);
            if (signup == null || !activityId.equals(signup.getActivityId())) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "报名记录不存在或不属于该活动"));
                continue;
            }
            if (!"signed".equals(signup.getStatus())) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "只有已报名（signed）的记录可以审核，当前："
                        + CommunityService.statusLabel(signup.getStatus())));
                continue;
            }
            int rows = signupMapper.review(signupId, "signed", toStatus, rejectReason, actorId, now);
            if (rows != 1) {
                failed++;
                errors.add(CommunityService.rowError(signupId, CommunityService.CAS_FAIL));
                continue;
            }
            done.add(signupMapper.findById(signupId));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activityId", activityId);
        data.put("action", action);
        data.put("total", signupIds.size());
        data.put("updated", done.size());
        data.put("failed", failed);
        data.put("errors", errors);
        data.put("items", done);
        return Result.success("审核完成：" + done.size() + " 条，失败 " + failed + " 条", data);
    }

    /** 签到置位：attended / absent（可同时登记 award 与 score；award 只由管理员与辅导员登记）。 */
    public Result<?> markAttendance(Long actorId, String role, Long activityId, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        CommunityActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            return Result.error(404, "活动不存在");
        }
        List<Map<String, Object>> items = itemList(body);
        if (items.isEmpty()) {
            return Result.error("请指定签到记录 signupId 或 studentId（也可用 items 数组批量）");
        }
        String bodyStatus = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "status")));
        String bodyAward = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "award")));
        BigDecimal bodyScore = CommunityService.dec(CommunityService.pick(body, "score"));
        String bodyNote = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "note")));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<CommunityActivitySignup> done = new ArrayList<>();
        int failed = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Long signupId = CommunityService.longOf(CommunityService.pick(item, "signupId", "signup_id"));
            if (signupId == null) {
                Long studentId = CommunityService.longOf(CommunityService.pick(item, "studentId", "student_id"));
                if (studentId != null) {
                    CommunityActivitySignup found = signupMapper.findByActivityStudent(activityId, studentId);
                    signupId = found == null ? null : found.getId();
                }
            }
            if (signupId == null) {
                failed++;
                errors.add(CommunityService.rowError(null, "缺少 signupId / studentId"));
                continue;
            }
            CommunityActivitySignup signup = signupMapper.findById(signupId);
            if (signup == null || !activityId.equals(signup.getActivityId())) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "报名记录不存在或不属于该活动"));
                continue;
            }
            String status = CommunityService.orDefault(CommunityService.trimToNull(
                    CommunityService.text(CommunityService.pick(item, "status"))), bodyStatus);
            if (status == null) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "请给出签到状态 status（attended / absent）"));
                continue;
            }
            if (!ATTEND_STATUSES.contains(status)) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "签到状态只支持 attended / absent"));
                continue;
            }
            String award = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(item, "award")));
            if (award == null) {
                award = bodyAward;
            }
            if (award != null && !AWARDS.contains(award)) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "奖项只支持 none / first / second / third / excellent"));
                continue;
            }
            if (award != null && !"none".equals(award) && !"attended".equals(status)) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "只有已参加（attended）才能登记奖项"));
                continue;
            }
            BigDecimal score = CommunityService.dec(CommunityService.pick(item, "score"));
            if (score == null) {
                score = bodyScore;
            }
            String note = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(item, "note")));
            if (note == null) {
                note = bodyNote;
            }
            String current = signup.getStatus();
            if (status.equals(current)) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "该报名已经是 " + CommunityService.statusLabel(status)));
                continue;
            }
            if (!ATTEND_FROM_STATUSES.contains(current)) {
                failed++;
                errors.add(CommunityService.rowError(signupId, "只有已报名 / 已通过的记录可以签到，当前："
                        + CommunityService.statusLabel(current)));
                continue;
            }
            int rows = signupMapper.markAttendance(signupId, current, status, award, score, note, actorId, now);
            if (rows != 1) {
                failed++;
                errors.add(CommunityService.rowError(signupId, CommunityService.CAS_FAIL));
                continue;
            }
            done.add(signupMapper.findById(signupId));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activityId", activityId);
        data.put("total", items.size());
        data.put("updated", done.size());
        data.put("failed", failed);
        data.put("errors", errors);
        data.put("items", done);
        return Result.success("签到完成：" + done.size() + " 条，失败 " + failed + " 条", data);
    }

    /** 报名单条统计（供活动详情展示）。 */
    public Result<?> signupStats(Long actorId, String role, Long activityId) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            return Result.error(404, "活动不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activity", activity);
        data.put("byStatus", signupMapper.countGroupByStatus(activityId));
        Integer used = signupMapper.occupancy(activityId);
        data.put("occupancy", used == null ? 0 : used);
        data.put("quota", activity.getQuota());
        return Result.success(data);
    }

    /** 从 body 里收集要处理的报名 id（signupId / studentId / items 数组三种写法）。 */
    private List<Long> collectSignupIds(Long activityId, Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> items = itemList(body);
        for (Map<String, Object> item : items) {
            Long signupId = CommunityService.longOf(CommunityService.pick(item, "signupId", "signup_id"));
            if (signupId == null) {
                Long studentId = CommunityService.longOf(CommunityService.pick(item, "studentId", "student_id"));
                if (studentId != null) {
                    CommunityActivitySignup found = signupMapper.findByActivityStudent(activityId, studentId);
                    signupId = found == null ? null : found.getId();
                }
            }
            if (signupId != null && !ids.contains(signupId)) {
                ids.add(signupId);
            }
        }
        return ids;
    }

    /** body 本身或 body.items 数组，统一成条目列表（单条时把 body 当唯一条目）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemList(Map<String, Object> body) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object raw = body == null ? null : CommunityService.pick(body, "items", "list", "rows");
        if (raw instanceof List) {
            for (Object one : (List<Object>) raw) {
                if (one instanceof Map) {
                    items.add((Map<String, Object>) one);
                }
            }
            return items;
        }
        if (body != null && (CommunityService.has(body, "signupId", "signup_id", "studentId", "student_id"))) {
            items.add(body);
        }
        return items;
    }
}
