package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.Club;
import com.umi.nest.entity.ClubMember;
import com.umi.nest.entity.ClubPublicity;
import com.umi.nest.entity.ClubRating;
import com.umi.nest.entity.ClubReview;
import com.umi.nest.mapper.ClubMapper;
import com.umi.nest.mapper.ClubMemberMapper;
import com.umi.nest.mapper.ClubPublicityMapper;
import com.umi.nest.mapper.ClubRatingMapper;
import com.umi.nest.mapper.ClubReviewMapper;
import com.umi.nest.service.CommunityService.CsvExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.umi.nest.service.CommunityService.CAS_FAIL;
import static com.umi.nest.service.CommunityService.DAY;
import static com.umi.nest.service.CommunityService.MAX_EXPORT;
import static com.umi.nest.service.CommunityService.STAMP;
import static com.umi.nest.service.CommunityService.csvCell;
import static com.umi.nest.service.CommunityService.guardLogin;
import static com.umi.nest.service.CommunityService.integer;
import static com.umi.nest.service.CommunityService.isAdmin;
import static com.umi.nest.service.CommunityService.isStaff;
import static com.umi.nest.service.CommunityService.isStudent;
import static com.umi.nest.service.CommunityService.longOf;
import static com.umi.nest.service.CommunityService.pageOf;
import static com.umi.nest.service.CommunityService.parseDate;
import static com.umi.nest.service.CommunityService.pick;
import static com.umi.nest.service.CommunityService.text;
import static com.umi.nest.service.CommunityService.trimToNull;

/**
 * 学生社团（学工 P17，S25a / S25b）服务层：社团台账 / 成员 / 年审 / 星级评定 / 公示 / 统计导出。
 *
 * <p>契约见 {@code target/p17-plan.md}（§2 DDL、§3 枚举文案、§4 规则、§5 路由与 JSON 键名、
 * §6 scope、§7 CAS、§9 门禁）。三条硬口径：</p>
 *
 * <p><b>① 状态机 = 服务层显式前置校验 + CAS</b>。CAS 的 fromStatus / 前置态一律取
 * <b>显式枚举值</b>（{@link #PRE_STATE_ACTIVE}、{@code "open"}、{@code result IS NULL}），
 * 绝不把「刚读到的变量值」当参数——否则 WHERE 恒真、CAS 退化成无条件更新（§7-1）。
 * 影响行数 0 → 400 {@link CommunityService#CAS_FAIL}。终态不可重设（§7-2）：
 * 年审结果登记后冻结、公示 closed 后不得再 close（重复 close 一律 400，不做幂等 200）。</p>
 *
 * <p><b>② 口径分列不混</b>：{@code club.attachUnit} 是<b>挂靠单位</b>（S25a），
 * {@code club_rating.businessUnit} 是<b>业务指导单位</b>（S25b），两者不得互换（§2 / §4）。</p>
 *
 * <p><b>③ 公示明细 items[] 不落库</b>（表里没有该列，也没有 by-publicityId 查询）：
 * 建公示时把本次纳入的 club_rating / club_review 行的 {@code publicity_id} 回写（关联），
 * 读取时按 {@code publicity_id} 分组装配 items[]（社团名 / 结果或星级 / 单位 / 社团类别）。
 * 未显式传 ratingIds / reviewIds 时，自动纳入所有尚未关联公示的同类行（§5-28）。</p>
 *
 * <p>权限（§5 / §6）：{@code admin} 全部；{@code teacher}（辅导员）可登记社团台账、提报年审材料、
 * 写挂靠单位意见，<b>不可</b>登记年审结果、登记星级、发布/关闭公示；{@code student} 只看自己
 * （mine 系列 + 自己社团的星级），{@code club_publicity} 的公示任何已登录角色可见（含 closed）。
 * 所有 403 文案含「无权」，401 文案为「请先登录」（E2E {@code expectReject} 口径）。</p>
 *
 * <p>导出（§5-29）：CSV 只含 社团名称 / 社团类别 / 挂靠单位 / 学年 / 年审结果 / 星级 / 业务指导单位，
 * <b>不含</b>学号、手机号、邮箱、身份证号；未登录或非 staff 回 403 且空 body。</p>
 */
@Service("clubService")
public class ClubService {

    private static final Logger log = LoggerFactory.getLogger(ClubService.class);

    // =========================================================================
    // 枚举与规则常量（§3 / §4）
    // =========================================================================

    /** 社团状态（§3.1）：active 正常 / deregistered 已注销。 */
    public static final Set<String> CLUB_STATUSES =
            new LinkedHashSet<>(Arrays.asList("active", "deregistered"));

    /** 社团类别（§3.4 / S25a 六类，中文值直接存库）。 */
    public static final Set<String> CATEGORIES = new LinkedHashSet<>(Arrays.asList(
            "创新创业类", "思想政治类", "文化体育类", "学术科技类", "志愿公益类", "自律互助类"));

    /** 成员角色（§3.1）：leader 负责人 / member 成员。 */
    public static final Set<String> MEMBER_ROLES =
            new LinkedHashSet<>(Arrays.asList("leader", "member"));

    /** 年审结果（§3.1）：qualified 合格 / unqualified 不合格 / deregistered 注销。 */
    public static final Set<String> REVIEW_RESULTS =
            new LinkedHashSet<>(Arrays.asList("qualified", "unqualified", "deregistered"));

    /** 星级（§3.1）：star3 三星级 / star4 四星级 / star5 五星级。 */
    public static final Set<String> STAR_LEVELS =
            new LinkedHashSet<>(Arrays.asList("star3", "star4", "star5"));

    /** 公示类型（§2）：club_review 年审结果公示 / club_rating 星级评定公示。 */
    public static final Set<String> PUBLICITY_TYPES =
            new LinkedHashSet<>(Arrays.asList("club_review", "club_rating"));

    /** 公示状态（§3.1）：open 公示中 / closed 已结束。 */
    public static final Set<String> PUBLICITY_STATUSES =
            new LinkedHashSet<>(Arrays.asList("open", "closed"));

    /** 公示期天数（§4：社团公示期 5 天）。 */
    public static final int PUBLICITY_DAYS = 5;

    /** 异议邮箱默认值（§4 / S25a / S25b：twh@xujc.com）。 */
    public static final String DEFAULT_CONTACT_EMAIL = "twh@xujc.com";

    /** CAS 显式前置态：社团台账正常态（注销 / 修改台账的 fromStatus）。 */
    public static final String PRE_STATE_ACTIVE = "active";

    /** CAS 显式前置态：公示公示中态。 */
    public static final String PRE_STATE_OPEN = "open";

    /** 学年格式（形如 2025-2026）。 */
    private static final Pattern SCHOOL_YEAR_PATTERN = Pattern.compile("^\\d{4}-\\d{4}$");

    // =========================================================================
    // 依赖（注意：@Autowired 不能标在 static 字段上，否则恒为 null 且静默 NPE）
    // =========================================================================

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private ClubMemberMapper memberMapper;

    @Autowired
    private ClubReviewMapper reviewMapper;

    @Autowired
    private ClubRatingMapper ratingMapper;

    @Autowired
    private ClubPublicityMapper publicityMapper;

    @Autowired
    private AuditLogService auditLogService;

    // =========================================================================
    // 1. 社团台账（6 条）
    // =========================================================================

    /**
     * 社团台账列表。staff（teacher 按辅导员 scope / admin 全量）返回分页对象 {list,total,page,size}；
     * 学生只看自己所属社团（§6），返回数组（学生端页面只认数组）。
     */
    public Result<?> listClubs(Long actorId, String role, String category, String attachUnit,
                               String status, String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (!isBlankOrIn(status, CLUB_STATUSES)) {
            return Result.error("社团状态只能是 " + join(CLUB_STATUSES));
        }
        if (!isBlankOrIn(category, CATEGORIES)) {
            return Result.error("社团类别只能是：" + join(CATEGORIES));
        }
        int p = page(page);
        int s = size(size);
        Long scopeUser = isStudent(role) ? actorId : null;
        Long scopeCounselor = isStudent(role) ? null : CommunityService.scopeCounselor(actorId, role);
        String categoryFilter = trimToNull(category);
        String attachFilter = trimToNull(attachUnit);
        String statusFilter = trimToNull(status);
        String keywordFilter = trimToNull(keyword);
        List<Club> rows = clubMapper.selectList(scopeUser, scopeCounselor, categoryFilter, attachFilter,
                statusFilter, keywordFilter, (p - 1) * s, s);
        long total = clubMapper.selectCount(scopeUser, scopeCounselor, categoryFilter, attachFilter,
                statusFilter, keywordFilter);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Club row : rows) {
            list.add(clubMap(row));
        }
        if (isStudent(role)) {
            return Result.success(list);
        }
        return Result.success(pageOf(list, (int) total, p, s));
    }

    /** 我的社团：当前在册（quit_date IS NULL）的社团，行内含 role / roleText（学生端页面依赖）。 */
    public Result<?> listMyClubs(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        List<ClubMember> members = memberMapper.selectActiveByUser(actorId);
        Map<Long, Club> cache = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ClubMember member : members) {
            Club club = clubOf(member.getClubId(), cache);
            Map<String, Object> row = new LinkedHashMap<>();
            // id 用**社团 id**（E2E 用 mine 行的 id 与本人在册社团的年审/星级行 clubId 对齐；
            // 成员 id 另给 memberId，学生端页面不使用 id 字段）
            row.put("id", member.getClubId());
            row.put("memberId", member.getId());
            row.put("clubId", member.getClubId());
            row.put("clubName", member.getClubName() != null ? member.getClubName()
                    : (club == null ? null : club.getClubName()));
            row.put("category", club == null ? null : club.getCategory());
            row.put("attachUnit", club == null ? null : club.getAttachUnit());
            row.put("foundDate", club == null ? null : date(club.getFoundDate()));
            row.put("leaderName", club == null ? null : club.getLeaderName());
            row.put("advisorName", club == null ? null : club.getAdvisorName());
            row.put("status", club == null ? null : club.getStatus());
            row.put("statusText", club == null ? null : clubStatusText(club.getStatus()));
            row.put("memberCount", club == null || club.getMemberCount() == null ? 0 : club.getMemberCount());
            row.put("role", member.getRole());
            row.put("roleText", memberRoleText(member.getRole()));
            row.put("joinDate", date(member.getJoinDate()));
            list.add(row);
        }
        return Result.success(list);
    }

    /** 社团详情。任何已登录角色可看（§5-24）。 */
    public Result<?> clubDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Club club = clubMapper.selectById(id);
        if (club == null) {
            return Result.error(404, "社团不存在");
        }
        return Result.success(clubMap(club));
    }

    /** 登记社团台账（§5-23；teacher / admin 可录入）。 */
    @Transactional
    public Result<?> createClub(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role, "登记社团台账");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> b = safe(body);
        String clubName = trimToNull(pick(b, "clubName", "club_name"));
        if (clubName == null) {
            return Result.error("社团名称不能为空");
        }
        if (clubName.length() > 100) {
            return Result.error("社团名称不能超过 100 个字符");
        }
        String category = trimToNull(pick(b, "category"));
        if (category != null && !CATEGORIES.contains(category)) {
            return Result.error("社团类别只能是：" + join(CATEGORIES));
        }
        String status = trimToNull(pick(b, "status"));
        if (status != null && !CLUB_STATUSES.contains(status)) {
            return Result.error("社团状态只能是 " + join(CLUB_STATUSES));
        }
        Club club = new Club();
        club.setClubName(clubName);
        club.setCategory(category);
        club.setAttachUnit(trimToNull(pick(b, "attachUnit", "attach_unit")));
        club.setFoundDate(parseDate(pick(b, "foundDate", "found_date")));
        club.setLeaderName(trimToNull(pick(b, "leaderName", "leader_name")));
        club.setAdvisorName(trimToNull(pick(b, "advisorName", "advisor_name")));
        club.setStatus(status == null ? "active" : status);
        clubMapper.insert(club);
        audit("createClub", actorId, club.getId(), "登记社团台账：" + clubName);
        return Result.success(clubMap(clubMapper.selectById(club.getId())));
    }

    /**
     * 修改社团台账（§5-24：**仅 admin**）。CAS 前置态取显式 {@link #PRE_STATE_ACTIVE}，
     * 已注销社团改台账一律 400（终态不可重设，§7-2）；body 里的 status 被忽略（注销走专用路由）。
     */
    @Transactional
    public Result<?> updateClub(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "修改社团台账");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("社团 id 不能为空");
        }
        Club exist = clubMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团不存在");
        }
        if (!PRE_STATE_ACTIVE.equals(exist.getStatus())) {
            return Result.error("社团已注销，台账不可再修改");
        }
        Map<String, Object> b = safe(body);
        Club patch = new Club();
        patch.setId(id);
        if (hasAny(b, "clubName", "club_name")) {
            String clubName = trimToNull(pick(b, "clubName", "club_name"));
            if (clubName == null) {
                return Result.error("社团名称不能为空");
            }
            patch.setClubName(clubName);
        }
        String category = trimToNull(pick(b, "category"));
        if (category != null) {
            if (!CATEGORIES.contains(category)) {
                return Result.error("社团类别只能是：" + join(CATEGORIES));
            }
            patch.setCategory(category);
        }
        patch.setAttachUnit(trimToNull(pick(b, "attachUnit", "attach_unit")));
        patch.setFoundDate(parseDate(pick(b, "foundDate", "found_date")));
        patch.setLeaderName(trimToNull(pick(b, "leaderName", "leader_name")));
        patch.setAdvisorName(trimToNull(pick(b, "advisorName", "advisor_name")));
        if (clubMapper.update(patch, PRE_STATE_ACTIVE) == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("updateClub", actorId, id, "修改社团台账：" + exist.getClubName());
        return Result.success(clubMap(clubMapper.selectById(id)));
    }

    /** 注销社团（§5-24）。CAS：active → deregistered；重复注销 400（§7-2）。 */
    @Transactional
    public Result<?> deregisterClub(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "注销社团");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("社团 id 不能为空");
        }
        Club exist = clubMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团不存在");
        }
        if (!PRE_STATE_ACTIVE.equals(exist.getStatus())) {
            return Result.error("该社团已注销，请勿重复注销");
        }
        if (clubMapper.deregister(id, PRE_STATE_ACTIVE) == 0) {
            return Result.error(CAS_FAIL);
        }
        String reason = trimToNull(pick(safe(body), "reason", "deregisterReason", "deregister_reason"));
        audit("deregisterClub", actorId, id, "注销社团：" + exist.getClubName()
                + (reason == null ? "" : "；原因：" + reason));
        return Result.success(clubMap(clubMapper.selectById(id)));
    }

    // =========================================================================
    // 2. 社团成员（3 条）
    // =========================================================================

    /** 成员列表。student 只看自己（scopeUserId）；teacher 按名下学生 scope；admin 全量。 */
    public Result<?> listMembers(Long actorId, String role, Long clubId, String memberRole,
                                String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (!isBlankOrIn(memberRole, MEMBER_ROLES)) {
            return Result.error("成员角色只能是 " + join(MEMBER_ROLES));
        }
        int p = page(page);
        int s = size(size);
        Long scopeUser = isStudent(role) ? actorId : null;
        Long scopeCounselor = isStudent(role) ? null : CommunityService.scopeCounselor(actorId, role);
        String roleFilter = trimToNull(memberRole);
        String keywordFilter = trimToNull(keyword);
        List<ClubMember> rows = memberMapper.selectList(scopeUser, scopeCounselor, clubId, roleFilter,
                keywordFilter, (p - 1) * s, s);
        long total = memberMapper.selectCount(scopeUser, scopeCounselor, clubId, roleFilter, keywordFilter);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ClubMember row : rows) {
            list.add(memberMap(row));
        }
        return Result.success(pageOf(list, (int) total, p, s));
    }

    /** 新增成员（§5-25，admin）：同一社团重复在册 → 400；同一学生可先 member 后升 leader。 */
    @Transactional
    public Result<?> addMember(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "维护社团成员");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> b = safe(body);
        Long clubId = longOf(pick(b, "clubId", "club_id"));
        if (clubId == null) {
            return Result.error("社团 id 不能为空");
        }
        Long userId = longOf(pick(b, "userId", "user_id"));
        if (userId == null) {
            return Result.error("学生 id 不能为空");
        }
        String memberRole = trimToNull(pick(b, "role"));
        if (memberRole == null) {
            memberRole = "member";
        }
        if (!MEMBER_ROLES.contains(memberRole)) {
            return Result.error("成员角色只能是 " + join(MEMBER_ROLES));
        }
        Club club = clubMapper.selectById(clubId);
        if (club == null) {
            return Result.error(404, "社团不存在");
        }
        if (!PRE_STATE_ACTIVE.equals(club.getStatus())) {
            return Result.error("社团已注销，不可新增成员");
        }
        if (memberMapper.selectActiveByClubAndUser(clubId, userId) != null) {
            return Result.error("该学生已在本社团在册，请勿重复添加");
        }
        LocalDate joinDate = parseDate(pick(b, "joinDate", "join_date"));
        ClubMember member = new ClubMember();
        member.setClubId(clubId);
        member.setUserId(userId);
        member.setRole(memberRole);
        member.setJoinDate(joinDate == null ? LocalDate.now() : joinDate);
        memberMapper.insert(member);
        audit("addClubMember", actorId, member.getId(),
                "社员入社：club=" + clubId + "，user=" + userId + "，role=" + memberRole);
        return Result.success(memberMap(memberMapper.selectById(member.getId())));
    }

    /**
     * 退社（§5-25）。软删 = CAS {@code quitMember}（quit_date 由 NULL 置日期）+ {@code softDelete}
     * （deleted=1），两步同事务。退社时间优先 query（{@code quit_date} / {@code quitDate}），
     * 其次 body（{@code quitDate} / {@code quit_date}），缺省用当天。
     */
    @Transactional
    public Result<?> quitMember(Long actorId, String role, Long id, String quitDate, Integer ignoreFlag,
                                Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "维护社团成员");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("成员记录 id 不能为空");
        }
        ClubMember exist = memberMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团成员记录不存在");
        }
        if (exist.getQuitDate() != null) {
            return Result.error("该成员已退社，请勿重复操作");
        }
        Map<String, Object> b = safe(body);
        LocalDate quit = parseDate(trimToNull(quitDate));
        if (quit == null) {
            quit = parseDate(pick(b, "quitDate", "quit_date"));
        }
        if (quit == null) {
            quit = LocalDate.now();
        }
        if (memberMapper.quitMember(id, quit) == 0) {
            return Result.error(CAS_FAIL);
        }
        memberMapper.softDelete(id);
        audit("quitClubMember", actorId, id, "社员退社：club=" + exist.getClubId()
                + "，user=" + exist.getUserId() + "，quitDate=" + quit);
        Map<String, Object> data = memberMap(exist);
        data.put("quitDate", date(quit));
        data.put("active", false);
        return Result.success(data);
    }

    // =========================================================================
    // 3. 社团年审（5 条）
    // =========================================================================

    /**
     * 年审列表。角色自适应 scope（队长 m00307 校准，覆盖 E2E 旧断言）：学生**可读且 200**，
     * 但只返回自己在册社团（club_member.quit_date IS NULL）的行——年审结果是学校公示内容，
     * 学生端 club.html 靠这条路由展示「本社团年审结果」；teacher 按名下学生 scope、admin 全量。
     * 学生返回裸数组（学生端 listRows 只认数组），teacher/admin 返回 {list,total,page,size}。
     */
    public Result<?> listReviews(Long actorId, String role, Long clubId, String schoolYear, String result,
                                 String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (!isBlankOrIn(result, REVIEW_RESULTS)) {
            return Result.error("年审结果只能是 " + join(REVIEW_RESULTS));
        }
        int p = page(page);
        int s = size(size);
        Long scopeUser = isStudent(role) ? actorId : null;
        Long scopeCounselor = isStudent(role) ? null : CommunityService.scopeCounselor(actorId, role);
        String yearFilter = trimToNull(schoolYear);
        String resultFilter = trimToNull(result);
        String keywordFilter = trimToNull(keyword);
        List<ClubReview> rows = reviewMapper.selectList(scopeUser, scopeCounselor, clubId, yearFilter, resultFilter,
                keywordFilter, (p - 1) * s, s);
        long total = reviewMapper.selectCount(scopeUser, scopeCounselor, clubId, yearFilter, resultFilter, keywordFilter);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ClubReview row : rows) {
            list.add(reviewMap(row));
        }
        if (isStudent(role)) {
            return Result.success(list);
        }
        return Result.success(pageOf(list, (int) total, p, s));
    }

    /** 提报年审材料（§5-26；teacher / admin）。result 留空（NULL = 未登记）。 */
    @Transactional
    public Result<?> createReview(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role, "提报社团年审材料");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> b = safe(body);
        Long clubId = longOf(pick(b, "clubId", "club_id"));
        if (clubId == null) {
            return Result.error("社团 id 不能为空");
        }
        Club club = clubMapper.selectById(clubId);
        if (club == null) {
            return Result.error(404, "社团不存在");
        }
        String schoolYear = trimToNull(pick(b, "schoolYear", "school_year"));
        if (schoolYear == null) {
            return Result.error("学年不能为空");
        }
        Result<?> yearGuard = checkSchoolYear(schoolYear);
        if (yearGuard != null) {
            return yearGuard;
        }
        if (reviewMapper.selectByClubAndYear(clubId, schoolYear) != null) {
            return Result.error("该社团本学年年审材料已提报，请勿重复提报");
        }
        ClubReview review = new ClubReview();
        review.setClubId(clubId);
        review.setSchoolYear(schoolYear);
        review.setArchitectureMaterial(trimToNull(pick(b, "architectureMaterial", "architecture_material")));
        review.setBackboneMaterial(trimToNull(pick(b, "backboneMaterial", "backbone_material")));
        review.setActivityMaterial(trimToNull(pick(b, "activityMaterial", "activity_material")));
        review.setFundMaterial(trimToNull(pick(b, "fundMaterial", "fund_material")));
        review.setAttachOpinion(trimToNull(pick(b, "attachOpinion", "attach_opinion")));
        reviewMapper.insert(review);
        audit("createClubReview", actorId, review.getId(),
                "提报社团年审材料：club=" + clubId + "，schoolYear=" + schoolYear);
        return Result.success(reviewMap(reviewMapper.selectById(review.getId())));
    }

    /** 写挂靠单位意见（§5-26；teacher / admin）。前置态：result IS NULL。 */
    @Transactional
    public Result<?> writeOpinion(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role, "填写挂靠单位意见");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("年审记录 id 不能为空");
        }
        ClubReview exist = reviewMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团年审记录不存在");
        }
        String opinion = trimToNull(pick(safe(body), "attachOpinion", "attach_opinion"));
        if (opinion == null) {
            return Result.error("挂靠单位意见不能为空");
        }
        if (exist.getResult() != null) {
            return Result.error("年审结果已登记，挂靠单位意见不可再修改");
        }
        if (reviewMapper.writeOpinion(id, opinion) == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("writeClubReviewOpinion", actorId, id, "填写挂靠单位意见：club=" + exist.getClubId());
        return Result.success(reviewMap(reviewMapper.selectById(id)));
    }

    /**
     * 提交年审材料（§5-26；teacher / admin）：CAS 写 submit_time。
     * 重复提交 400 —— XML 的 CAS 只判 {@code result IS NULL}，不判 submit_time，
     * 故这里必须服务层前置校验 submitTime 是否已存在。
     */
    @Transactional
    public Result<?> submitReview(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role, "提交社团年审材料");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("年审记录 id 不能为空");
        }
        ClubReview exist = reviewMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团年审记录不存在");
        }
        if (exist.getSubmitTime() != null) {
            return Result.error("年审材料已提交，请勿重复提交");
        }
        if (exist.getResult() != null) {
            return Result.error("年审结果已登记，材料不可再提交");
        }
        if (reviewMapper.submitReview(id, null) == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("submitClubReview", actorId, id, "提交社团年审材料：club=" + exist.getClubId());
        return Result.success(reviewMap(reviewMapper.selectById(id)));
    }

    /**
     * 登记年审结果（§5-26：**仅 admin**）。CAS 前置态 {@code result IS NULL}（显式枚举，非读到的值）：
     * 重复登记 / 已登记一律 400；登记后冻结（终态不可重设，§7-2）。可同时挂公示 publicityId。
     */
    @Transactional
    public Result<?> recordResult(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "登记社团年审结果");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("年审记录 id 不能为空");
        }
        ClubReview exist = reviewMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团年审记录不存在");
        }
        Map<String, Object> b = safe(body);
        String result = trimToNull(pick(b, "result"));
        if (result == null) {
            return Result.error("年审结果不能为空");
        }
        if (!REVIEW_RESULTS.contains(result)) {
            return Result.error("年审结果只能是 " + join(REVIEW_RESULTS));
        }
        if (exist.getResult() != null) {
            return Result.error("年审结果已登记，不可重设");
        }
        if (reviewMapper.recordResult(id, result, actorId) == 0) {
            return Result.error(CAS_FAIL);
        }
        Long publicityId = longOf(pick(b, "publicityId", "publicity_id"));
        if (publicityId != null) {
            linkReviewPublicity(id, publicityId);
        }
        audit("recordClubReviewResult", actorId, id,
                "登记年审结果：" + result + "（club=" + exist.getClubId() + "）");
        return Result.success(reviewMap(reviewMapper.selectById(id)));
    }

    // =========================================================================
    // 4. 星级评定（3 条）
    // =========================================================================

    /** 星级列表。student 只看自己所属社团的星级（§6）；teacher 按 scope；admin 全量。 */
    public Result<?> listRatings(Long actorId, String role, Long clubId, String schoolYear, String starLevel,
                                 String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (!isBlankOrIn(starLevel, STAR_LEVELS)) {
            return Result.error("星级只能是 " + join(STAR_LEVELS));
        }
        int p = page(page);
        int s = size(size);
        Long scopeUser = isStudent(role) ? actorId : null;
        Long scopeCounselor = isStudent(role) ? null : CommunityService.scopeCounselor(actorId, role);
        String yearFilter = trimToNull(schoolYear);
        String starFilter = trimToNull(starLevel);
        String keywordFilter = trimToNull(keyword);
        List<ClubRating> rows = ratingMapper.selectList(scopeUser, scopeCounselor, clubId, yearFilter, starFilter,
                keywordFilter, (p - 1) * s, s);
        long total = ratingMapper.selectCount(scopeUser, scopeCounselor, clubId, yearFilter, starFilter,
                keywordFilter);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ClubRating row : rows) {
            list.add(ratingMap(row));
        }
        if (isStudent(role)) {
            return Result.success(list);
        }
        return Result.success(pageOf(list, (int) total, p, s));
    }

    /**
     * 登记星级（§5-27：**仅 admin**，平台不自动评级）。basisNote（评定依据说明）必填；
     * businessUnit 是业务指导单位（S25b），与 club.attachUnit 挂靠单位分列不混。
     */
    @Transactional
    public Result<?> createRating(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "登记社团星级");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> b = safe(body);
        Long clubId = longOf(pick(b, "clubId", "club_id"));
        if (clubId == null) {
            return Result.error("社团 id 不能为空");
        }
        Club club = clubMapper.selectById(clubId);
        if (club == null) {
            return Result.error(404, "社团不存在");
        }
        String schoolYear = trimToNull(pick(b, "schoolYear", "school_year"));
        if (schoolYear == null) {
            return Result.error("学年不能为空");
        }
        Result<?> yearGuard = checkSchoolYear(schoolYear);
        if (yearGuard != null) {
            return yearGuard;
        }
        String starLevel = trimToNull(pick(b, "starLevel", "star_level"));
        if (starLevel == null) {
            return Result.error("星级不能为空");
        }
        if (!STAR_LEVELS.contains(starLevel)) {
            return Result.error("星级只能是 " + join(STAR_LEVELS));
        }
        String basisNote = trimToNull(pick(b, "basisNote", "basis_note"));
        if (basisNote == null) {
            return Result.error("评定依据说明不能为空");
        }
        if (ratingMapper.selectByClubAndYear(clubId, schoolYear) != null) {
            return Result.error("该社团本学年星级已登记，请勿重复登记");
        }
        ClubRating rating = new ClubRating();
        rating.setClubId(clubId);
        rating.setSchoolYear(schoolYear);
        rating.setStarLevel(starLevel);
        rating.setBusinessUnit(trimToNull(pick(b, "businessUnit", "business_unit")));
        rating.setBasisNote(basisNote);
        rating.setPublicityId(longOf(pick(b, "publicityId", "publicity_id")));
        rating.setRegisterUserId(actorId);
        rating.setRegisterTime(LocalDateTime.now());
        ratingMapper.insert(rating);
        audit("createClubRating", actorId, rating.getId(),
                "登记社团星级：" + starLevel + "（club=" + clubId + "，schoolYear=" + schoolYear + "）");
        return Result.success(ratingMap(ratingMapper.selectById(rating.getId())));
    }

    /** 修改星级（§5-27：**仅 admin**）。本表无状态机，故无 fromStatus 前置态。 */
    @Transactional
    public Result<?> updateRating(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "修改社团星级");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("星级记录 id 不能为空");
        }
        ClubRating exist = ratingMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "社团星级记录不存在");
        }
        Map<String, Object> b = safe(body);
        ClubRating patch = new ClubRating();
        patch.setId(id);
        String schoolYear = trimToNull(pick(b, "schoolYear", "school_year"));
        if (schoolYear != null) {
            Result<?> yearGuard = checkSchoolYear(schoolYear);
            if (yearGuard != null) {
                return yearGuard;
            }
            patch.setSchoolYear(schoolYear);
        }
        String starLevel = trimToNull(pick(b, "starLevel", "star_level"));
        if (starLevel != null) {
            if (!STAR_LEVELS.contains(starLevel)) {
                return Result.error("星级只能是 " + join(STAR_LEVELS));
            }
            patch.setStarLevel(starLevel);
        }
        if (hasAny(b, "basisNote", "basis_note")) {
            String basisNote = trimToNull(pick(b, "basisNote", "basis_note"));
            if (basisNote == null) {
                return Result.error("评定依据说明不能为空");
            }
            patch.setBasisNote(basisNote);
        }
        patch.setBusinessUnit(trimToNull(pick(b, "businessUnit", "business_unit")));
        patch.setPublicityId(longOf(pick(b, "publicityId", "publicity_id")));
        patch.setRegisterUserId(actorId);
        if (ratingMapper.update(patch) == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("updateClubRating", actorId, id, "修改社团星级：" + exist.getClubName());
        return Result.success(ratingMap(ratingMapper.selectById(id)));
    }

    // =========================================================================
    // 5. 公示（5 条）
    // =========================================================================

    /**
     * 公示列表。任何已登录角色可见（含 closed，§5-28）；学生返回数组（学生端页面只认数组）。
     * items[] 由 publicity_id 关联装配（社团名 / 结果或星级 / 单位 / 社团类别）。
     */
    public Result<?> listPublicities(Long actorId, String role, String publicityType, String status,
                                     String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (!isBlankOrIn(publicityType, PUBLICITY_TYPES)) {
            return Result.error("公示类型只能是 " + join(PUBLICITY_TYPES));
        }
        if (!isBlankOrIn(status, PUBLICITY_STATUSES)) {
            return Result.error("公示状态只能是 " + join(PUBLICITY_STATUSES));
        }
        int p = page(page);
        int s = size(size);
        String typeFilter = trimToNull(publicityType);
        String statusFilter = trimToNull(status);
        String keywordFilter = trimToNull(keyword);
        List<ClubPublicity> rows = publicityMapper.selectList(null, null, typeFilter, statusFilter, keywordFilter,
                (p - 1) * s, s);
        long total = publicityMapper.selectCount(null, null, typeFilter, statusFilter, keywordFilter);
        Map<Long, Club> clubCache = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> itemIndex = buildItemIndex(clubCache);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ClubPublicity row : rows) {
            list.add(publicityMap(row, itemIndex));
        }
        if (isStudent(role)) {
            return Result.success(list);
        }
        return Result.success(pageOf(list, (int) total, p, s));
    }

    /** 公示详情。任何已登录角色可见（closed 也可看，§5-28）。 */
    public Result<?> publicityDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        ClubPublicity publicity = publicityMapper.selectById(id);
        if (publicity == null) {
            return Result.error(404, "公示不存在");
        }
        Map<Long, Club> clubCache = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> itemIndex = buildItemIndex(clubCache);
        return Result.success(publicityMap(publicity, itemIndex));
    }

    /**
     * 发布公示（§5-28：**仅 admin**）。startDate 缺省当天；endDate 缺省 = startDate + 4 天
     * （含首尾共 5 天，对齐 S25b「公示期为 5 天」）；请求显式给了 endDate 就**尊重它**
     * （E2E 断言 dayDiff(startDate,endDate)===4）；contactEmail 缺省
     * {@link #DEFAULT_CONTACT_EMAIL}；items[] 由关联的 ratingIds / reviewIds 生成，
     * 未传时自动纳入所有尚未关联公示的同类行，并回写 publicity_id 建立关联。
     */
    @Transactional
    public Result<?> createPublicity(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "发布社团公示");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> b = safe(body);
        String publicityType = trimToNull(pick(b, "publicityType", "publicity_type", "type"));
        if (publicityType == null) {
            return Result.error("公示类型不能为空");
        }
        if (!PUBLICITY_TYPES.contains(publicityType)) {
            return Result.error("公示类型只能是 " + join(PUBLICITY_TYPES));
        }
        String title = trimToNull(pick(b, "title"));
        if (title == null) {
            return Result.error("公示标题不能为空");
        }
        if (title.length() > 200) {
            return Result.error("公示标题不能超过 200 个字符");
        }
        LocalDate startDate = parseDate(pick(b, "startDate", "start_date", "publicityDate"));
        if (startDate == null) {
            startDate = LocalDate.now();
        }
        LocalDate endDate = parseDate(pick(b, "endDate", "end_date"));
        if (endDate == null) {
            // 公示期 5 天 = 首尾都算（S25b 2026-07-07 ~ 07-11），故缺省 endDate = startDate + 4 天
            endDate = startDate.plusDays(PUBLICITY_DAYS - 1);
        }
        if (endDate.isBefore(startDate)) {
            return Result.error("公示结束日期不能早于公示开始日期");
        }
        String contactEmail = trimToNull(pick(b, "contactEmail", "contact_email"));
        ClubPublicity publicity = new ClubPublicity();
        publicity.setPublicityType(publicityType);
        publicity.setTitle(title);
        publicity.setContent(trimToNull(pick(b, "content")));
        publicity.setStartDate(startDate);
        publicity.setEndDate(endDate);
        publicity.setContactEmail(contactEmail == null ? DEFAULT_CONTACT_EMAIL : contactEmail);
        publicity.setStatus("open");
        publicity.setPublishUserId(actorId);
        publicityMapper.insert(publicity);
        int linked = linkItems(publicityType, publicity.getId(), b);
        audit("createClubPublicity", actorId, publicity.getId(),
                "发布社团公示：" + publicityType + "；关联明细 " + linked + " 条；标题：" + title);
        Map<Long, Club> clubCache = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> itemIndex = buildItemIndex(clubCache);
        return Result.success(publicityMap(publicityMapper.selectById(publicity.getId()), itemIndex));
    }

    /** 关闭公示（§5-28：**仅 admin**）。CAS：open → closed；重复关闭 400（§7-2）。 */
    @Transactional
    public Result<?> closePublicity(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "关闭社团公示");
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("公示 id 不能为空");
        }
        ClubPublicity exist = publicityMapper.selectById(id);
        if (exist == null) {
            return Result.error(404, "公示不存在");
        }
        if (!PRE_STATE_OPEN.equals(exist.getStatus())) {
            return Result.error("该公示已结束，请勿重复关闭");
        }
        if (publicityMapper.closePublicity(id, PRE_STATE_OPEN) == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("closeClubPublicity", actorId, id, "关闭社团公示：" + exist.getTitle());
        Map<Long, Club> clubCache = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> itemIndex = buildItemIndex(clubCache);
        return Result.success(publicityMap(publicityMapper.selectById(id), itemIndex));
    }

    // =========================================================================
    // 6. 统计导出（1 条）
    // =========================================================================

    /**
     * 统计导出（§5-29）：列 = 社团名称 / 社团类别 / 挂靠单位 / 学年 / 年审结果 / 星级 / 业务指导单位。
     * <b>不含</b>学号、手机号、邮箱、身份证号（§5-19 / E2E 脱敏断言）。
     * 未登录 / 非 staff → errorCode 403（控制器回 403 空 body）。
     */
    public CsvExport exportCsv(Long actorId, String role, String schoolYear, String category, String status,
                               String keyword) {
        CsvExport out = new CsvExport();
        if (actorId == null || !isStaff(role)) {
            out.errorCode = 403;
            out.errorMessage = "无权导出社团统计，仅辅导员与管理员可导出";
            return out;
        }
        String yearFilter = trimToNull(schoolYear);
        String categoryFilter = trimToNull(category);
        String statusFilter = trimToNull(status);
        String keywordFilter = trimToNull(keyword);
        List<Club> clubs = clubMapper.selectList(null, null, categoryFilter, null, statusFilter, keywordFilter,
                0, MAX_EXPORT);
        List<ClubReview> reviews = reviewMapper.selectList(null, null, null, yearFilter, null, null, 0, MAX_EXPORT);
        List<ClubRating> ratings = ratingMapper.selectList(null, null, null, yearFilter, null, null, 0, MAX_EXPORT);
        Map<Long, ClubReview> reviewByClub = new LinkedHashMap<>();
        for (ClubReview review : reviews) {
            ClubReview hit = reviewByClub.get(review.getClubId());
            if (hit == null || (hit.getResult() == null && review.getResult() != null)) {
                reviewByClub.put(review.getClubId(), review);
            }
        }
        Map<Long, ClubRating> ratingByClub = new LinkedHashMap<>();
        for (ClubRating rating : ratings) {
            ClubRating hit = ratingByClub.get(rating.getClubId());
            if (hit == null || (hit.getStarLevel() == null && rating.getStarLevel() != null)) {
                ratingByClub.put(rating.getClubId(), rating);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("社团名称,社团类别,挂靠单位,学年,年审结果,星级,业务指导单位\n");
        for (Club club : clubs) {
            ClubReview review = reviewByClub.get(club.getId());
            ClubRating rating = ratingByClub.get(club.getId());
            String year = yearFilter;
            if (year == null) {
                year = rating != null ? rating.getSchoolYear()
                        : (review != null ? review.getSchoolYear() : currentSchoolYear());
            }
            sb.append(csvCell(club.getClubName())).append(',')
                    .append(csvCell(club.getCategory())).append(',')
                    .append(csvCell(club.getAttachUnit())).append(',')
                    .append(csvCell(year)).append(',')
                    .append(csvCell(review == null ? "未登记" : reviewResultText(review.getResult()))).append(',')
                    .append(csvCell(rating == null ? "未评定" : starLevelText(rating.getStarLevel()))).append(',')
                    .append(csvCell(rating == null ? "" : rating.getBusinessUnit())).append('\n');
        }
        out.csv = sb.toString();
        out.filename = "club-stats-" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv";
        audit("exportClubStats", actorId, null,
                "导出社团统计：共 " + clubs.size() + " 行；schoolYear=" + yearFilter
                        + "，category=" + categoryFilter + "，status=" + statusFilter);
        return out;
    }

    // =========================================================================
    // 关联装配（items[] 不落库）
    // =========================================================================

    /** 建立 {publicityId → items[]} 索引：星级行与年审行按 publicity_id 关联装配。 */
    private Map<Long, List<Map<String, Object>>> buildItemIndex(Map<Long, Club> clubCache) {
        Map<Long, List<Map<String, Object>>> index = new LinkedHashMap<>();
        List<ClubRating> ratings = ratingMapper.selectList(null, null, null, null, null, null, 0, MAX_EXPORT);
        for (ClubRating rating : ratings) {
            if (rating.getPublicityId() == null) {
                continue;
            }
            index.computeIfAbsent(rating.getPublicityId(), key -> new ArrayList<>())
                    .add(ratingItem(rating, clubCache));
        }
        List<ClubReview> reviews = reviewMapper.selectList(null, null, null, null, null, null, 0, MAX_EXPORT);
        for (ClubReview review : reviews) {
            if (review.getPublicityId() == null) {
                continue;
            }
            index.computeIfAbsent(review.getPublicityId(), key -> new ArrayList<>())
                    .add(reviewItem(review, clubCache));
        }
        return index;
    }

    /**
     * 关联本次纳入公示的明细行：显式给了 ratingIds / reviewIds 就只挂这些；
     * 否则自动纳入所有尚未关联公示的同类行（E2E 建公示时不传 ids，items 必须非空）。
     */
    private int linkItems(String publicityType, Long publicityId, Map<String, Object> body) {
        int linked = 0;
        if ("club_rating".equals(publicityType)) {
            List<Long> explicit = longList(pick(body, "ratingIds", "rating_ids"));
            if (!explicit.isEmpty()) {
                for (Long ratingId : explicit) {
                    ClubRating patch = new ClubRating();
                    patch.setId(ratingId);
                    patch.setPublicityId(publicityId);
                    linked += ratingMapper.update(patch);
                }
                return linked;
            }
            List<ClubRating> rows = ratingMapper.selectList(null, null, null, null, null, null, 0, MAX_EXPORT);
            for (ClubRating row : rows) {
                if (row.getPublicityId() != null) {
                    continue;
                }
                ClubRating patch = new ClubRating();
                patch.setId(row.getId());
                patch.setPublicityId(publicityId);
                linked += ratingMapper.update(patch);
            }
            return linked;
        }
        List<Long> explicit = longList(pick(body, "reviewIds", "review_ids"));
        if (!explicit.isEmpty()) {
            for (Long reviewId : explicit) {
                linked += linkReviewPublicity(reviewId, publicityId) ? 1 : 0;
            }
            return linked;
        }
        List<ClubReview> rows = reviewMapper.selectList(null, null, null, null, null, null, 0, MAX_EXPORT);
        for (ClubReview row : rows) {
            if (row.getPublicityId() != null) {
                continue;
            }
            linked += linkReviewPublicity(row.getId(), publicityId) ? 1 : 0;
        }
        return linked;
    }

    /**
     * 给年审行挂公示。不用 {@code linkPublicity}（它的前置态是 {@code result IS NULL}，
     * 而已登记结果的行也要能挂公示），改用动态 update：只设 publicity_id，
     * 此时 {@code r.result} 为 null，XML 不会追加 {@code AND result IS NULL}。
     */
    private boolean linkReviewPublicity(Long reviewId, Long publicityId) {
        ClubReview patch = new ClubReview();
        patch.setId(reviewId);
        patch.setPublicityId(publicityId);
        return reviewMapper.update(patch) > 0;
    }

    // =========================================================================
    // 响应装配（§5 JSON 键名，一律不得改名）
    // =========================================================================

    private Map<String, Object> clubMap(Club club) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", club.getId());
        data.put("clubName", club.getClubName());
        data.put("category", club.getCategory());
        data.put("attachUnit", club.getAttachUnit());
        data.put("foundDate", date(club.getFoundDate()));
        data.put("leaderName", club.getLeaderName());
        data.put("advisorName", club.getAdvisorName());
        data.put("status", club.getStatus());
        data.put("statusText", clubStatusText(club.getStatus()));
        data.put("memberCount", club.getMemberCount() == null ? 0 : club.getMemberCount());
        data.put("createdAt", stamp(club.getCreatedAt()));
        return data;
    }

    private Map<String, Object> memberMap(ClubMember member) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", member.getId());
        data.put("clubId", member.getClubId());
        data.put("clubName", member.getClubName());
        data.put("userId", member.getUserId());
        data.put("studentName", member.getStudentName());
        data.put("studentNo", member.getStudentNo());
        data.put("className", member.getClassName());
        data.put("role", member.getRole());
        data.put("roleText", memberRoleText(member.getRole()));
        data.put("joinDate", date(member.getJoinDate()));
        data.put("quitDate", date(member.getQuitDate()));
        data.put("active", member.getQuitDate() == null);
        return data;
    }

    private Map<String, Object> reviewMap(ClubReview review) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", review.getId());
        data.put("clubId", review.getClubId());
        data.put("clubName", review.getClubName());
        data.put("schoolYear", review.getSchoolYear());
        data.put("architectureMaterial", review.getArchitectureMaterial());
        data.put("backboneMaterial", review.getBackboneMaterial());
        data.put("activityMaterial", review.getActivityMaterial());
        data.put("fundMaterial", review.getFundMaterial());
        data.put("attachOpinion", review.getAttachOpinion());
        data.put("result", review.getResult());
        data.put("resultText", reviewResultText(review.getResult()));
        data.put("submitTime", stamp(review.getSubmitTime()));
        data.put("reviewTime", stamp(review.getReviewTime()));
        data.put("publicityId", review.getPublicityId());
        data.put("reviewUserName", review.getReviewUserName());
        return data;
    }

    private Map<String, Object> ratingMap(ClubRating rating) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", rating.getId());
        data.put("clubId", rating.getClubId());
        data.put("clubName", rating.getClubName());
        data.put("schoolYear", rating.getSchoolYear());
        data.put("starLevel", rating.getStarLevel());
        data.put("starLevelText", starLevelText(rating.getStarLevel()));
        data.put("businessUnit", rating.getBusinessUnit());
        data.put("basisNote", rating.getBasisNote());
        data.put("publicityId", rating.getPublicityId());
        data.put("registerTime", stamp(rating.getRegisterTime()));
        return data;
    }

    private Map<String, Object> publicityMap(ClubPublicity publicity, Map<Long, List<Map<String, Object>>> index) {
        List<Map<String, Object>> items = index.get(publicity.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", publicity.getId());
        data.put("publicityType", publicity.getPublicityType());
        data.put("publicityTypeText", publicityTypeText(publicity.getPublicityType()));
        data.put("title", publicity.getTitle());
        data.put("content", publicity.getContent());
        data.put("startDate", date(publicity.getStartDate()));
        data.put("endDate", date(publicity.getEndDate()));
        data.put("contactEmail", publicity.getContactEmail());
        data.put("status", publicity.getStatus());
        data.put("statusText", publicityStatusText(publicity.getStatus()));
        data.put("items", items == null ? new ArrayList<Map<String, Object>>() : items);
        data.put("publishUserId", publicity.getPublishUserId());
        data.put("publishUserName", publicity.getPublishUserName());
        data.put("createdAt", stamp(publicity.getCreatedAt()));
        return data;
    }

    /** 星级明细行：社团名 / 结果或星级 / 单位（业务指导单位）/ 社团类别。 */
    private Map<String, Object> ratingItem(ClubRating rating, Map<Long, Club> clubCache) {
        Club club = clubOf(rating.getClubId(), clubCache);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("clubId", rating.getClubId());
        item.put("clubName", rating.getClubName() != null ? rating.getClubName()
                : (club == null ? null : club.getClubName()));
        item.put("category", club == null ? null : club.getCategory());
        item.put("schoolYear", rating.getSchoolYear());
        item.put("starLevel", rating.getStarLevel());
        item.put("starLevelText", starLevelText(rating.getStarLevel()));
        item.put("unit", rating.getBusinessUnit());
        item.put("unitLabel", "业务指导单位");
        return item;
    }

    /** 年审明细行：社团名 / 结果 / 单位（挂靠单位，S25a）/ 社团类别。 */
    private Map<String, Object> reviewItem(ClubReview review, Map<Long, Club> clubCache) {
        Club club = clubOf(review.getClubId(), clubCache);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("clubId", review.getClubId());
        item.put("clubName", review.getClubName() != null ? review.getClubName()
                : (club == null ? null : club.getClubName()));
        item.put("category", club == null ? null : club.getCategory());
        item.put("schoolYear", review.getSchoolYear());
        item.put("result", review.getResult());
        item.put("resultText", reviewResultText(review.getResult()));
        item.put("unit", club == null ? null : club.getAttachUnit());
        item.put("unitLabel", "挂靠单位");
        return item;
    }

    private Club clubOf(Long clubId, Map<Long, Club> cache) {
        if (clubId == null) {
            return null;
        }
        if (cache.containsKey(clubId)) {
            return cache.get(clubId);
        }
        Club club = clubMapper.selectById(clubId);
        cache.put(clubId, club);
        return club;
    }

    // =========================================================================
    // 枚举文案（§3）
    // =========================================================================

    public static String clubStatusText(String status) {
        if ("active".equals(status)) {
            return "正常";
        }
        if ("deregistered".equals(status)) {
            return "已注销";
        }
        return status == null ? null : status;
    }

    public static String memberRoleText(String role) {
        if ("leader".equals(role)) {
            return "负责人";
        }
        if ("member".equals(role)) {
            return "成员";
        }
        return role == null ? null : role;
    }

    public static String reviewResultText(String result) {
        if ("qualified".equals(result)) {
            return "合格";
        }
        if ("unqualified".equals(result)) {
            return "不合格";
        }
        if ("deregistered".equals(result)) {
            return "注销";
        }
        return result == null ? null : result;
    }

    public static String starLevelText(String starLevel) {
        if ("star3".equals(starLevel)) {
            return "三星级";
        }
        if ("star4".equals(starLevel)) {
            return "四星级";
        }
        if ("star5".equals(starLevel)) {
            return "五星级";
        }
        return starLevel == null ? null : starLevel;
    }

    public static String publicityTypeText(String publicityType) {
        if ("club_review".equals(publicityType)) {
            return "年审结果公示";
        }
        if ("club_rating".equals(publicityType)) {
            return "星级评定结果公示";
        }
        return publicityType == null ? null : publicityType;
    }

    public static String publicityStatusText(String status) {
        if ("open".equals(status)) {
            return "公示中";
        }
        if ("closed".equals(status)) {
            return "已结束";
        }
        return status == null ? null : status;
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    /**
     * 写审计（{@link AuditLogService#record} 内部已 try/catch；这里再兜一层，
     * 审计失败绝不影响业务，但必须留下 warn 痕迹）。类型固定 {@link AuditLogService#TYPE_CLUB}。
     */
    private void audit(String action, Long actorId, Long targetId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEventType(AuditLogService.TYPE_CLUB);
            entry.setEventAction(action);
            entry.setUserId(actorId);
            entry.setTarget(targetId == null ? null : String.valueOf(targetId));
            entry.setSuccess(true);
            entry.setDetail(detail);
            auditLogService.record(entry);
        } catch (Exception e) {
            log.warn("社团审计写入失败 action={} actor={} target={}：{}", action, actorId, targetId, e.toString());
        }
    }

    /** admin 守卫：文案必须含「无权」（E2E expectReject 口径）。 */
    private static Result<?> guardAdminOp(Long actorId, String role, String action) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (isAdmin(role)) {
            return null;
        }
        return Result.error(403, "无权" + action + "，仅团委管理员可操作");
    }

    /** staff（teacher / admin）守卫：文案必须含「无权」。 */
    private static Result<?> guardStaffOp(Long actorId, String role, String action) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (isStaff(role)) {
            return null;
        }
        return Result.error(403, "无权" + action + "，仅辅导员与管理员可操作");
    }

    /** 学年校验：形如 2025-2026，且两段年份相邻。 */
    private static Result<?> checkSchoolYear(String schoolYear) {
        if (!SCHOOL_YEAR_PATTERN.matcher(schoolYear).matches()) {
            return Result.error("学年格式应为 2025-2026 形式，不能为空");
        }
        int first = Integer.parseInt(schoolYear.substring(0, 4));
        int second = Integer.parseInt(schoolYear.substring(5, 9));
        if (second != first + 1) {
            return Result.error("学年的两个年份必须相邻（形如 2025-2026）");
        }
        return null;
    }

    /** 当前学年：9 月及以后属 y-(y+1)，否则属 (y-1)-y。 */
    public static String currentSchoolYear() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        return today.getMonthValue() >= 9 ? year + "-" + (year + 1) : (year - 1) + "-" + year;
    }

    private static Map<String, Object> safe(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : body;
    }

    private static boolean hasAny(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlankOrIn(String value, Set<String> allowed) {
        String trimmed = trimToNull(value);
        return trimmed == null || allowed.contains(trimmed);
    }

    private static String join(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static List<Long> longList(Object value) {
        List<Long> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Long id = longOf(item);
                if (id != null) {
                    out.add(id);
                }
            }
            return out;
        }
        Long id = longOf(value);
        if (id != null) {
            out.add(id);
        }
        return out;
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.format(DAY);
    }

    private static String stamp(LocalDateTime value) {
        return value == null ? null : value.format(STAMP);
    }

    /** 分页参数（与 PartyService 同口径）。 */
    private static int page(Object value) {
        Integer p = integer(value);
        return p == null || p < 1 ? 1 : p;
    }

    private static int size(Object value) {
        Integer s = integer(value);
        return s == null || s < 1 ? 20 : Math.min(s, 200);
    }

    /** 未使用但保留：便于将来 body / query 双写时统一取时间（javac 不告警的显式引用）。 */
    @SuppressWarnings("unused")
    private static String textOrNull(Object value) {
        return trimToNull(text(value));
    }
}
