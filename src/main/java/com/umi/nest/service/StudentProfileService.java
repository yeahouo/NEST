package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.LeaveRequest;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.LeaveRequestMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学工平台·学生档案（P3）。
 *
 * <p>三件事：学生看/填自己的档案、辅导员看名下学生并维护宿舍与家庭信息、管理员补建缺失档案。</p>
 *
 * <p>字段口径：姓名/手机号/学号/班级等账号资料来自 users 表（只读，展示时 JOIN 带出）；
 * 学工专有信息（辅导员、宿舍、政治面貌、家庭情况、学籍状态）落在 student_profile。</p>
 *
 * <p>权限：学生只能改自己的家庭联系人与政治面貌；辅导员能改名下学生的宿舍与家庭信息；
 * 只有 admin 能改「辅导员分派」与「学籍状态」（休学/退学影响历史假条口径，不能给辅导员随手改）。</p>
 */
@Service
public class StudentProfileService {

    private static final Logger log = LoggerFactory.getLogger(StudentProfileService.class);

    /** 学籍状态白名单 */
    private static final Set<String> STATUSES = new LinkedHashSet<>(Arrays.asList(
            "active", "suspended", "quit", "graduated"));

    /** 家庭经济档次：low 一般困难 / medium 困难 / high 特别困难 */
    private static final Set<String> INCOME_LEVELS = new LinkedHashSet<>(Arrays.asList("low", "medium", "high"));

    /** 能看名册 / 改档案的角色 */
    private static final Set<String> STAFF_ROLES = new LinkedHashSet<>(Arrays.asList("teacher", "admin"));

    private static final int MAX_CONTACT = 64;
    private static final int MAX_ROOM = 32;
    private static final int MAX_BUILDING = 32;
    private static final int MAX_POLITICAL = 16;
    private static final int SYNC_LIMIT = 500;

    @Autowired
    private StudentProfileMapper profileMapper;

    @Autowired
    private LeaveRequestMapper leaveMapper;

    @Autowired
    private UserMapper userMapper;

    // --------------------------------------------------------------- 学生端

    /** 我的档案。没有建档时给一份从账号资料拼出的占位（exists=false），页面据此提示「尚未建档」。 */
    public Result<?> mine(Long userId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        StudentProfile profile = profileMapper.findByUserId(userId);
        if (profile == null) {
            User user = userMapper.findById(userId);
            data.put("exists", false);
            data.put("profile", placeholder(userId, user));
            data.put("tip", "你的学工档案还没建立，可以先填写家庭联系人与政治面貌，其余由辅导员补全");
        } else {
            data.put("exists", true);
            data.put("profile", profile);
        }
        return Result.success(data);
    }

    /**
     * 学生自助保存：家庭联系人 / 家庭经济档次 / 政治面貌。
     * 没有档案时顺手建档（学号、班级等从账号资料带过来），避免出现「填不了」的死路。
     */
    public Result<?> saveMine(Long userId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要保存的内容");
        }
        String contact = clip(body.get("familyContact"), MAX_CONTACT);
        String income = trim(body.get("familyIncomeLevel"));
        String political = clip(body.get("politicalStatus"), MAX_POLITICAL);
        if (!income.isEmpty() && !INCOME_LEVELS.contains(income)) {
            return Result.error("家庭经济档次不正确（可选：一般困难 / 困难 / 特别困难）");
        }

        StudentProfile profile = profileMapper.findByUserId(userId);
        if (profile == null) {
            User user = userMapper.findById(userId);
            if (user == null) {
                return Result.error(404, "账号不存在");
            }
            if (!"student".equals(user.getRole())) {
                return Result.error(403, "只有学生账号可以建立学工档案");
            }
            StudentProfile fresh = new StudentProfile();
            fresh.setUserId(userId);
            fresh.setStudentNo(emptyToNull(user.getStudentId()));
            fresh.setClassName(emptyToNull(user.getClassName()));
            fresh.setMajor(emptyToNull(user.getMajor()));
            fresh.setGrade(emptyToNull(user.getGrade()));
            fresh.setPoliticalStatus(emptyToNull(political));
            fresh.setFamilyContact(emptyToNull(contact));
            fresh.setFamilyIncomeLevel(emptyToNull(income));
            fresh.setStatus("active");
            profileMapper.insert(fresh);
            log.info("student profile created by self userId={}", userId);
            return Result.success("档案已建立", profileMapper.findByUserId(userId));
        }

        profile.setFamilyContact(emptyToNull(contact));
        profile.setPoliticalStatus(emptyToNull(political));
        profile.setFamilyIncomeLevel(emptyToNull(income));
        profileMapper.update(profile);
        log.info("student profile saved by self userId={}", userId);
        return Result.success("已保存", profileMapper.findByUserId(userId));
    }

    // ----------------------------------------------------- 辅导员 / 管理端

    /**
     * 学生名册。admin 看全校；teacher 有分派时只看名下，未分派时看全校
     * （与请假收件箱同一套范围口径，避免「还没分派就什么都看不到」）。
     *
     * @param scope 传 all 时 teacher 也看全校（用于「查全校学生」的显式切换）
     */
    public Result<?> list(Long actorId, String role, String className, String status, String keyword,
                          String scope, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看学生名册");
        }
        if (!trim(status).isEmpty() && !STATUSES.contains(trim(status))) {
            return Result.error("学籍状态筛选不正确");
        }
        Long counselorId = scopeOf(actorId, role, scope);
        page = Math.max(page, 1);
        size = clamp(size, 1, 200);
        List<StudentProfile> items = profileMapper.list(counselorId, emptyToNull(className), emptyToNull(keyword),
                emptyToNull(status), (page - 1) * size, size);
        int total = profileMapper.count(counselorId, emptyToNull(className), emptyToNull(keyword), emptyToNull(status));
        Map<String, Object> data = page(items, total, page, size);
        data.put("stats", statsOf(counselorId));
        data.put("scope", counselorId == null ? "all" : "mine");
        return Result.success(data);
    }

    /** 学生档案详情：档案本体 + 最近的假条（辅导员上门了解情况时最需要这两样）。 */
    public Result<?> detail(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看学生档案");
        }
        StudentProfile profile = profileMapper.findById(id);
        if (profile == null) {
            return Result.error(404, "学生档案不存在");
        }
        if (!canTouch(actorId, role, profile)) {
            return Result.error(403, "该学生不在你的名下");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", profile);
        List<LeaveRequest> leaves = leaveMapper.listByStudent(profile.getUserId(), null, 0, 20);
        Map<String, Object> leaveStats = new LinkedHashMap<>();
        for (String s : new String[]{"pending", "approved", "rejected", "closed", "cancelled"}) {
            leaveStats.put(s, 0);
        }
        for (LeaveRequest r : leaves) {
            Object cur = leaveStats.get(r.getStatus());
            if (cur instanceof Integer) {
                leaveStats.put(r.getStatus(), ((Integer) cur) + 1);
            }
        }
        data.put("leaves", leaves);
        data.put("leaveStats", leaveStats);
        return Result.success(data);
    }

    /** 改档案。辅导员限宿舍与家庭信息；admin 还能改分派与学籍状态。 */
    public Result<?> update(Long actorId, String role, Long id, Map<String, String> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限修改学生档案");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要保存的内容");
        }
        StudentProfile profile = profileMapper.findById(id);
        if (profile == null) {
            return Result.error(404, "学生档案不存在");
        }
        if (!canTouch(actorId, role, profile)) {
            return Result.error(403, "该学生不在你的名下");
        }

        if (body.containsKey("dormBuilding")) {
            profile.setDormBuilding(emptyToNull(clip(body.get("dormBuilding"), MAX_BUILDING)));
        }
        if (body.containsKey("dormRoom")) {
            profile.setDormRoom(emptyToNull(clip(body.get("dormRoom"), MAX_ROOM)));
        }
        if (body.containsKey("politicalStatus")) {
            profile.setPoliticalStatus(emptyToNull(clip(body.get("politicalStatus"), MAX_POLITICAL)));
        }
        if (body.containsKey("familyContact")) {
            profile.setFamilyContact(emptyToNull(clip(body.get("familyContact"), MAX_CONTACT)));
        }
        if (body.containsKey("familyIncomeLevel")) {
            String income = trim(body.get("familyIncomeLevel"));
            if (!income.isEmpty() && !INCOME_LEVELS.contains(income)) {
                return Result.error("家庭经济档次不正确（可选：一般困难 / 困难 / 特别困难）");
            }
            profile.setFamilyIncomeLevel(emptyToNull(income));
        }

        if ("admin".equals(role)) {
            if (body.containsKey("counselorId")) {
                String raw = trim(body.get("counselorId"));
                if (raw.isEmpty()) {
                    profile.setCounselorId(null);
                } else {
                    long counselorId;
                    try {
                        counselorId = Long.parseLong(raw);
                    } catch (NumberFormatException e) {
                        return Result.error("辅导员 ID 不正确");
                    }
                    User counselor = userMapper.findById(counselorId);
                    if (counselor == null || !STAFF_ROLES.contains(counselor.getRole())) {
                        return Result.error("指定的辅导员不存在或不是教师账号");
                    }
                    profile.setCounselorId(counselorId);
                }
            }
            if (body.containsKey("status")) {
                String status = trim(body.get("status"));
                if (!STATUSES.contains(status)) {
                    return Result.error("学籍状态不正确");
                }
                profile.setStatus(status);
            }
            if (body.containsKey("studentNo")) {
                profile.setStudentNo(emptyToNull(clip(body.get("studentNo"), 32)));
            }
            if (body.containsKey("className")) {
                profile.setClassName(emptyToNull(clip(body.get("className"), 64)));
            }
            if (body.containsKey("major")) {
                profile.setMajor(emptyToNull(clip(body.get("major"), 64)));
            }
            if (body.containsKey("grade")) {
                profile.setGrade(emptyToNull(clip(body.get("grade"), 16)));
            }
        } else if (body.containsKey("counselorId") || body.containsKey("status")) {
            return Result.error(403, "只有管理员可以变更辅导员分派与学生学籍状态");
        }

        if (profileMapper.update(profile) == 0) {
            return Result.error("保存失败，档案可能已被删除");
        }
        log.info("student profile updated id={} by={} role={}", id, actorId, role);
        return Result.success("已保存", profileMapper.findById(id));
    }

    /** 补建档案：给还没建档的在读学生账号各建一条（只有 admin 能跑，一次最多 500 条）。 */
    public Result<?> syncMissing(Long actorId, String role) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!"admin".equals(role)) {
            return Result.error(403, "只有管理员可以补建学生档案");
        }
        List<StudentProfile> missing = profileMapper.listMissingProfile(0, SYNC_LIMIT);
        int created = 0;
        for (StudentProfile m : missing) {
            if (m.getUserId() == null) {
                continue;
            }
            StudentProfile fresh = new StudentProfile();
            fresh.setUserId(m.getUserId());
            fresh.setStudentNo(emptyToNull(m.getStudentNo()));
            fresh.setClassName(emptyToNull(m.getClassName()));
            fresh.setMajor(emptyToNull(m.getMajor()));
            fresh.setGrade(emptyToNull(m.getGrade()));
            fresh.setStatus("active");
            created += profileMapper.insert(fresh) > 0 ? 1 : 0;
        }
        int remain = profileMapper.countMissingProfile();
        log.info("student profile sync by={} created={} remain={}", actorId, created, remain);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("remain", remain);
        String message = created == 0
                ? "没有需要补建的学生档案"
                : ("已补建 " + created + " 条学生档案" + (remain > 0 ? "，还有 " + remain + " 条待补建" : ""));
        return Result.success(message, data);
    }

    /** 名册统计（辅导员工作台 / 名册页顶部发丝格）。 */
    public Map<String, Object> statsOf(Long scopeCounselorId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String s : STATUSES) {
            stats.put(s, 0);
        }
        int total = 0;
        for (Map<String, Object> row : profileMapper.countGroupByStatus(scopeCounselorId)) {
            Object k = row.get("status");
            Object v = row.get("cnt");
            if (k != null && v != null) {
                int count = ((Number) v).intValue();
                stats.put(String.valueOf(k), count);
                total += count;
            }
        }
        stats.put("total", total);
        stats.put("missing", profileMapper.countMissingProfile());
        stats.put("classes", profileMapper.listClassNames(scopeCounselorId));
        return stats;
    }

    // --------------------------------------------------------------- 工具

    /** 范围：admin 或 scope=all → null（全校）；teacher 有分派 → 自己；未分派 → null。 */
    private Long scopeOf(Long actorId, String role, String scope) {
        if ("admin".equals(role) || "all".equalsIgnoreCase(trim(scope))) {
            return null;
        }
        if ("teacher".equals(role) && actorId != null && profileMapper.countByCounselor(actorId) > 0) {
            return actorId;
        }
        return null;
    }

    private boolean canTouch(Long actorId, String role, StudentProfile profile) {
        if ("admin".equals(role)) {
            return true;
        }
        if (!"teacher".equals(role)) {
            return false;
        }
        if (profile.getCounselorId() == null) {
            return true;
        }
        return profile.getCounselorId().equals(actorId);
    }

    private static StudentProfile placeholder(Long userId, User user) {
        StudentProfile p = new StudentProfile();
        p.setUserId(userId);
        if (user != null) {
            p.setStudentNo(user.getStudentId());
            p.setClassName(user.getClassName());
            p.setMajor(user.getMajor());
            p.setGrade(user.getGrade());
            p.setName(user.getRealName() != null && !user.getRealName().isEmpty()
                    ? user.getRealName() : user.getUsername());
            p.setUsername(user.getUsername());
            p.setPhone(user.getPhone());
        }
        p.setStatus("active");
        return p;
    }

    public static String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "active":
                return "在读";
            case "suspended":
                return "休学";
            case "quit":
                return "退学";
            case "graduated":
                return "毕业";
            default:
                return status;
        }
    }

    public static String incomeText(String level) {
        if (level == null) {
            return "未填写";
        }
        switch (level) {
            case "low":
                return "一般困难";
            case "medium":
                return "困难";
            case "high":
                return "特别困难";
            default:
                return level;
        }
    }

    private static Map<String, Object> page(List<?> items, int total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("pages", size > 0 ? (total + size - 1) / size : 0);
        return data;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }

    private static String clip(String s, int max) {
        String t = trim(s);
        return t.length() <= max ? t : t.substring(0, max);
    }
}
