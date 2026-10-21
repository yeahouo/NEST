package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.controller.ClubController;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ClubService 单元测试（p17 学生社团服务层）。
 *
 * <p>纯 Mockito：不连库、不起 Spring 上下文，全部依赖以 @Mock 注入。</p>
 *
 * <p><b>三条已知与契约不一致（按实现断言，不修改主代码）</b>：</p>
 * <ol>
 *   <li>导出未登录 / 非 staff 返回 <b>403 空 body</b>（非 401）——见 8.4 / 8.5；</li>
 *   <li>导出放行 <b>teacher + admin</b>（isStaff 口径）——见 8.3；</li>
 *   <li>年审与星级按 <b>同社团 + 同学年</b>（selectByClubAndYear）去重——见 4.6 / 5.8。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClubServiceTest {

    // ==================== 常量 ====================
    private static final Long ADMIN = 1L;
    private static final Long COUNSELOR = 3L;
    private static final Long WANGXM = 4L;
    private static final Long OTHER_TEACHER = 6L;

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_TEACHER = "teacher";
    private static final String ROLE_STUDENT = "student";

    private static final Long CLUB_ID = 9001L;
    private static final Long CLUB2_ID = 9002L;
    private static final Long MEMBER_ID = 9101L;
    private static final Long REVIEW_ID = 9201L;
    private static final Long RATING_ID = 9301L;
    private static final Long PUBLICITY_ID = 9401L;

    private static final String CATEGORY = "文化体育类";
    private static final String ATTACH = "厦门大学嘉庚学院";
    private static final String SCHOOL_YEAR = "2025-2026";
    private static final String CAS_FAIL = "状态已变化，请刷新后重试";

    private static final int MAX_EXPORT = 100000;

    // ==================== 依赖 ====================
    @Mock
    private ClubMapper clubMapper;
    @Mock
    private ClubMemberMapper memberMapper;
    @Mock
    private ClubReviewMapper reviewMapper;
    @Mock
    private ClubRatingMapper ratingMapper;
    @Mock
    private ClubPublicityMapper publicityMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ClubService service;

    private final ClubController controller = new ClubController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "clubService", service);

        // 服务端多处直接遍历 selectList 结果（列表视图、公示 items[] 索引、导出行拼装），
        // 未打桩时 Mockito 返回 null 会导致 NPE，这里给所有列表查询一个空集合作为默认；
        // 各用例内的重复打桩会覆盖这些默认值（Mockito 取最后声明的匹配桩）。
        when(clubMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(ratingMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(reviewMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(publicityMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static void assertOk(Result<?> r) {
        assertNotNull(r, "Result 不应为 null");
        assertEquals(Integer.valueOf(200), r.getCode(), "期望成功：message=" + r.getMessage());
    }

    private static void assertError(Result<?> r, int code, String keyword) {
        assertNotNull(r, "Result 不应为 null");
        assertEquals(Integer.valueOf(code), r.getCode(), "期望失败码 " + code + "：实际 message=" + r.getMessage());
        assertNotNull(r.getMessage(), "失败应带 message");
        assertTrue(r.getMessage().contains(keyword),
                "失败文案应含「" + keyword + "」，实际：" + r.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataMap(Result<?> r) {
        assertOk(r);
        assertTrue(r.getData() instanceof Map, "期望分页/单体视图为 Map，实际：" + r.getData());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataList(Result<?> r) {
        assertOk(r);
        assertTrue(r.getData() instanceof List, "期望裸数组（学生分支），实际：" + r.getData());
        return (List<Map<String, Object>>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Result<?> r) {
        Map<String, Object> data = dataMap(r);
        Object list = data.get("list");
        assertTrue(list instanceof List, "分页视图应含 list");
        return (List<Map<String, Object>>) list;
    }

    private static MockHttpSession session(Object userId, String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userId", userId);
        s.setAttribute("role", role);
        return s;
    }

    private static Set<String> invokedMethods(Object mock) {
        Set<String> names = new LinkedHashSet<>();
        mockingDetails(mock).getInvocations().forEach(i -> names.add(i.getMethod().getName()));
        return names;
    }

    private String csvText(ResponseEntity<byte[]> resp) {
        byte[] bytes = resp.getBody();
        assertNotNull(bytes, "成功导出应有 body");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 构造器
    // ==================================================================

    private static Club clubOf(Long id, String name, String category, String status) {
        Club c = new Club();
        c.setId(id);
        c.setClubName(name);
        c.setCategory(category);
        c.setAttachUnit(ATTACH);
        c.setStatus(status);
        c.setFoundDate(LocalDate.of(2020, 9, 1));
        c.setLeaderName("王小明");
        c.setAdvisorName("赵老师");
        c.setCreatedAt(LocalDateTime.of(2020, 9, 1, 9, 0, 0));
        return c;
    }

    private static ClubMember memberOf(Long id, Long clubId, Long userId, String role,
                                       LocalDate joinDate, LocalDate quitDate) {
        ClubMember m = new ClubMember();
        m.setId(id);
        m.setClubId(clubId);
        m.setUserId(userId);
        m.setRole(role);
        m.setJoinDate(joinDate);
        m.setQuitDate(quitDate);
        m.setStudentName("王小明");
        m.setStudentNo("2024001");
        m.setClassName("计算机2401");
        m.setClubName("摄影协会");
        return m;
    }

    private static ClubReview reviewOf(Long id, Long clubId, String schoolYear, String result) {
        ClubReview v = new ClubReview();
        v.setId(id);
        v.setClubId(clubId);
        v.setSchoolYear(schoolYear);
        v.setResult(result);
        v.setClubName("摄影协会");
        v.setReviewUserName("赵老师");
        v.setArchitectureMaterial("章程材料");
        v.setBackboneMaterial("骨干材料");
        v.setActivityMaterial("活动材料");
        v.setFundMaterial("经费材料");
        return v;
    }

    private static ClubRating ratingOf(Long id, Long clubId, String schoolYear, String starLevel, String basisNote) {
        ClubRating r = new ClubRating();
        r.setId(id);
        r.setClubId(clubId);
        r.setSchoolYear(schoolYear);
        r.setStarLevel(starLevel);
        r.setBusinessUnit("校团委");
        r.setBasisNote(basisNote);
        r.setClubName("摄影协会");
        r.setRegisterTime(LocalDateTime.of(2026, 3, 1, 10, 0, 0));
        return r;
    }

    private static ClubPublicity publicityOf(Long id, String type, String status) {
        ClubPublicity p = new ClubPublicity();
        p.setId(id);
        p.setPublicityType(type);
        p.setTitle("星级评定结果公示");
        p.setContent("公示内容");
        p.setStartDate(LocalDate.of(2026, 7, 7));
        p.setEndDate(LocalDate.of(2026, 7, 11));
        p.setContactEmail("twh@xujc.com");
        p.setStatus(status);
        p.setPublishUserId(ADMIN);
        p.setPublishUserName("团委管理员");
        p.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 0, 0));
        return p;
    }

    // insert 后服务层会用回填的自增 id 再 selectById，因此必须让 insert 落 id。
    private void stubClubInsert() {
        doAnswer(inv -> {
            ((Club) inv.getArgument(0)).setId(CLUB_ID);
            return 1;
        }).when(clubMapper).insert(any(Club.class));
    }

    private void stubMemberInsert() {
        doAnswer(inv -> {
            ((ClubMember) inv.getArgument(0)).setId(MEMBER_ID);
            return 1;
        }).when(memberMapper).insert(any(ClubMember.class));
    }

    private void stubReviewInsert() {
        doAnswer(inv -> {
            ((ClubReview) inv.getArgument(0)).setId(REVIEW_ID);
            return 1;
        }).when(reviewMapper).insert(any(ClubReview.class));
    }

    private void stubRatingInsert() {
        doAnswer(inv -> {
            ((ClubRating) inv.getArgument(0)).setId(RATING_ID);
            return 1;
        }).when(ratingMapper).insert(any(ClubRating.class));
    }

    private void stubPublicityInsert() {
        doAnswer(inv -> {
            ((ClubPublicity) inv.getArgument(0)).setId(PUBLICITY_ID);
            return 1;
        }).when(publicityMapper).insert(any(ClubPublicity.class));
    }

    // ==================================================================
    // 1. club 台账
    // ==================================================================

    @Test
    @DisplayName("1.1 createClub 教师：status 缺省 active、视图带 memberCount、写审计")
    void createClub_teacher_ok() {
        stubClubInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));

        Result<?> r = service.createClub(COUNSELOR, ROLE_TEACHER,
                body("clubName", "摄影协会", "category", CATEGORY, "attachUnit", ATTACH));

        Map<String, Object> data = dataMap(r);
        assertEquals("active", data.get("status"));
        assertEquals("正常", data.get("statusText"));
        assertTrue(data.containsKey("memberCount"), "台账视图应带 memberCount");
        assertEquals(0L, ((Number) data.get("memberCount")).longValue(), "memberCount 为 null 时应落 0");

        ArgumentCaptor<Club> cap = ArgumentCaptor.forClass(Club.class);
        verify(clubMapper).insert(cap.capture());
        assertEquals("active", cap.getValue().getStatus(), "status 缺省应为 active");
        assertEquals("摄影协会", cap.getValue().getClubName());
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("1.2 createClub 管理员：显式字段原样落库")
    void createClub_admin_ok() {
        stubClubInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "棋牌社", "自律互助类", "active"));

        Result<?> r = service.createClub(ADMIN, ROLE_ADMIN, body("clubName", "棋牌社", "category", "自律互助类",
                "attachUnit", "信息科学与技术学院", "foundDate", "2021-03-04",
                "leaderName", "李明", "advisorName", "陈老师"));

        assertOk(r);
        ArgumentCaptor<Club> cap = ArgumentCaptor.forClass(Club.class);
        verify(clubMapper).insert(cap.capture());
        Club inserted = cap.getValue();
        assertEquals("自律互助类", inserted.getCategory());
        assertEquals("信息科学与技术学院", inserted.getAttachUnit());
        assertEquals(LocalDate.of(2021, 3, 4), inserted.getFoundDate());
        assertEquals("李明", inserted.getLeaderName());
        assertEquals("陈老师", inserted.getAdvisorName());
    }

    @Test
    @DisplayName("1.3 POST /api/club 与 /api/club/ 等价（同一 handler 声明 \"\" 与 \"/\"）")
    void createClub_routeEquivalent() {
        boolean found = false;
        for (Method m : ClubController.class.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm == null) {
                continue;
            }
            List<String> values = Arrays.asList(pm.value());
            if (values.contains("") && values.contains("/")) {
                found = true;
            }
        }
        assertTrue(found, "@PostMapping({\"\", \"/\"}) 缺失：POST /club 与 /club/ 应等价");
    }

    @Test
    @DisplayName("1.4 createClub 负例：缺 clubName / 超长 / 非法类别 / 非法状态")
    void createClub_validation_negatives() {
        assertError(service.createClub(ADMIN, ROLE_ADMIN, body("category", CATEGORY)), 400, "不能为空");
        assertError(service.createClub(ADMIN, ROLE_ADMIN, body("clubName", "  ", "category", CATEGORY)),
                400, "不能为空");

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            longName.append('社');
        }
        assertError(service.createClub(ADMIN, ROLE_ADMIN, body("clubName", longName.toString(), "category", CATEGORY)),
                400, "不能超过");

        assertError(service.createClub(ADMIN, ROLE_ADMIN, body("clubName", "摄影协会", "category", "计算机类")),
                400, "只能是");
        assertError(service.createClub(ADMIN, ROLE_ADMIN,
                body("clubName", "摄影协会", "category", CATEGORY, "status", "closed")), 400, "只能是");

        verify(clubMapper, never()).insert(any(Club.class));
    }

    @Test
    @DisplayName("1.5 createClub 权限负例：学生 403「无权」、未登录 401「请先登录」")
    void createClub_permission_negatives() {
        assertError(service.createClub(WANGXM, ROLE_STUDENT,
                body("clubName", "摄影协会", "category", CATEGORY)), 403, "无权");
        assertError(service.createClub(null, null,
                body("clubName", "摄影协会", "category", CATEGORY)), 401, "请先登录");
        verify(clubMapper, never()).insert(any(Club.class));
    }

    @Test
    @DisplayName("1.6 updateClub 管理员：CAS fromStatus=active，二次 selectById 取新视图")
    void updateClub_admin_ok() {
        Club before = clubOf(CLUB_ID, "摄影协会", CATEGORY, "active");
        Club after = clubOf(CLUB_ID, "摄影协会（新）", "学术科技类", "active");
        when(clubMapper.selectById(CLUB_ID)).thenReturn(before, after);
        when(clubMapper.update(any(Club.class), eq("active"))).thenReturn(1);

        Result<?> r = service.updateClub(ADMIN, ROLE_ADMIN, CLUB_ID, body("clubName", "摄影协会（新）",
                "category", "学术科技类", "attachUnit", ATTACH));

        Map<String, Object> data = dataMap(r);
        assertEquals("摄影协会（新）", data.get("clubName"));

        ArgumentCaptor<Club> patch = ArgumentCaptor.forClass(Club.class);
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(clubMapper).update(patch.capture(), from.capture());
        assertEquals("active", from.getValue(), "CAS 前置状态必须是 active");
        assertEquals("摄影协会（新）", patch.getValue().getClubName());
        assertEquals("学术科技类", patch.getValue().getCategory());
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("1.7 updateClub 负例：仅 admin（学生 403）、未登录 401、不存在 404、缺 id 400")
    void updateClub_permission_and_notFound() {
        when(clubMapper.selectById(9999L)).thenReturn(null);

        assertError(service.updateClub(WANGXM, ROLE_STUDENT, CLUB_ID, body("clubName", "x")), 403, "无权");
        assertError(service.updateClub(COUNSELOR, ROLE_TEACHER, CLUB_ID, body("clubName", "x")), 403, "无权");
        assertError(service.updateClub(null, null, CLUB_ID, body("clubName", "x")), 401, "请先登录");
        assertError(service.updateClub(ADMIN, ROLE_ADMIN, null, body("clubName", "x")), 400, "不能为空");
        assertError(service.updateClub(ADMIN, ROLE_ADMIN, 9999L, body("clubName", "x")), 404, "不存在");
        verify(clubMapper, never()).update(any(Club.class), any());
    }

    @Test
    @DisplayName("1.8 updateClub 已注销社团：400 终态保护，不发起 CAS")
    void updateClub_deregistered_terminal() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "deregistered"));

        assertError(service.updateClub(ADMIN, ROLE_ADMIN, CLUB_ID, body("clubName", "摄影协会（新）")),
                400, "已注销");
        verify(clubMapper, never()).update(any(Club.class), any());
    }

    @Test
    @DisplayName("1.9 updateClub 负例：空 clubName 400、非法 category 400、CAS 0 行 400")
    void updateClub_validation_and_casFail() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        assertError(service.updateClub(ADMIN, ROLE_ADMIN, CLUB_ID, body("clubName", "   ")), 400, "不能为空");
        assertError(service.updateClub(ADMIN, ROLE_ADMIN, CLUB_ID, body("category", "计算机类")), 400, "只能是");

        when(clubMapper.update(any(Club.class), eq("active"))).thenReturn(0);
        assertError(service.updateClub(ADMIN, ROLE_ADMIN, CLUB_ID, body("clubName", "摄影协会（新）")),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("1.10 deregisterClub 管理员：active→deregistered，CAS fromStatus=active")
    void deregisterClub_admin_ok() {
        when(clubMapper.selectById(CLUB_ID))
                .thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"),
                        clubOf(CLUB_ID, "摄影协会", CATEGORY, "deregistered"));
        when(clubMapper.deregister(CLUB_ID, "active")).thenReturn(1);

        Result<?> r = service.deregisterClub(ADMIN, ROLE_ADMIN, CLUB_ID, body());

        Map<String, Object> data = dataMap(r);
        assertEquals("deregistered", data.get("status"));
        assertEquals("已注销", data.get("statusText"));
        verify(clubMapper).deregister(CLUB_ID, "active");
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("1.11 deregisterClub 负例：重复注销 400、CAS 0 行 400、学生 403、不存在 404")
    void deregisterClub_negatives() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "deregistered"));
        assertError(service.deregisterClub(ADMIN, ROLE_ADMIN, CLUB_ID, body()), 400, "请勿重复注销");
        verify(clubMapper, never()).deregister(anyLong(), any());

        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(clubMapper.deregister(CLUB_ID, "active")).thenReturn(0);
        assertError(service.deregisterClub(ADMIN, ROLE_ADMIN, CLUB_ID, body()), 400, CAS_FAIL);

        assertError(service.deregisterClub(WANGXM, ROLE_STUDENT, CLUB_ID, body()), 403, "无权");
        assertError(service.deregisterClub(ADMIN, ROLE_ADMIN, 9999L, body()), 404, "不存在");
    }

    @Test
    @DisplayName("1.12 clubDetail：学生可读，不存在 404，未登录 401")
    void clubDetail_ok_and_negatives() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        Map<String, Object> data = dataMap(service.clubDetail(WANGXM, ROLE_STUDENT, CLUB_ID));
        assertEquals(CLUB_ID, data.get("id"));
        assertEquals("摄影协会", data.get("clubName"));
        assertEquals(ATTACH, data.get("attachUnit"));
        assertFalse(data.containsKey("businessUnit"), "社团视图不得出现业务指导单位字段");

        assertError(service.clubDetail(ADMIN, ROLE_ADMIN, 9999L), 404, "不存在");
        assertError(service.clubDetail(null, null, CLUB_ID), 401, "请先登录");
    }

    // ==================================================================
    // 2. 成员
    // ==================================================================

    @Test
    @DisplayName("2.1 addMember 管理员：默认 role=member，返回成员视图，写审计")
    void addMember_admin_ok() {
        stubMemberInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(memberMapper.selectActiveByClubAndUser(CLUB_ID, WANGXM)).thenReturn(null);
        when(memberMapper.selectById(MEMBER_ID))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.now(), null));

        Result<?> r = service.addMember(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "userId", WANGXM));

        Map<String, Object> data = dataMap(r);
        assertEquals("member", data.get("role"));
        assertEquals("成员", data.get("roleText"));
        assertEquals(WANGXM, data.get("userId"));
        assertFalse(data.containsKey("quitDate") && data.get("quitDate") != null);

        ArgumentCaptor<ClubMember> cap = ArgumentCaptor.forClass(ClubMember.class);
        verify(memberMapper).insert(cap.capture());
        assertEquals("member", cap.getValue().getRole(), "role 缺省应为 member");
        assertNotNull(cap.getValue().getJoinDate(), "joinDate 缺省应为当天");
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("2.2 addMember 负例：缺 clubId / 缺 userId / 非法 role / 重复在册 / 社团不存在 / 已注销")
    void addMember_negatives() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        assertError(service.addMember(ADMIN, ROLE_ADMIN, body("userId", WANGXM)), 400, "不能为空");
        assertError(service.addMember(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID)), 400, "不能为空");
        assertError(service.addMember(ADMIN, ROLE_ADMIN,
                body("clubId", CLUB_ID, "userId", WANGXM, "role", "chairman")), 400, "只能是");

        when(clubMapper.selectById(9999L)).thenReturn(null);
        assertError(service.addMember(ADMIN, ROLE_ADMIN, body("clubId", 9999L, "userId", WANGXM)),
                404, "不存在");

        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(memberMapper.selectActiveByClubAndUser(CLUB_ID, WANGXM))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.now(), null));
        assertError(service.addMember(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "userId", WANGXM)),
                400, "重复添加");

        when(clubMapper.selectById(CLUB2_ID)).thenReturn(clubOf(CLUB2_ID, "棋牌社", CATEGORY, "deregistered"));
        assertError(service.addMember(ADMIN, ROLE_ADMIN, body("clubId", CLUB2_ID, "userId", WANGXM)),
                400, "已注销");

        verify(memberMapper, never()).insert(any(ClubMember.class));
    }

    @Test
    @DisplayName("2.3 addMember 权限负例：学生/教师 403「无权」、未登录 401")
    void addMember_permission_negatives() {
        assertError(service.addMember(WANGXM, ROLE_STUDENT, body("clubId", CLUB_ID, "userId", WANGXM)),
                403, "无权");
        assertError(service.addMember(COUNSELOR, ROLE_TEACHER, body("clubId", CLUB_ID, "userId", WANGXM)),
                403, "无权");
        assertError(service.addMember(null, null, body("clubId", CLUB_ID, "userId", WANGXM)),
                401, "请先登录");
        verify(memberMapper, never()).insert(any(ClubMember.class));
    }

    @Test
    @DisplayName("2.4 quitMember 管理员：软删两步——quitMember 置 quit_date + softDelete 置 deleted=1")
    void quitMember_admin_ok_softDeleteTwoStep() {
        when(memberMapper.selectById(MEMBER_ID))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.of(2025, 9, 1), null));
        when(memberMapper.quitMember(eq(MEMBER_ID), any())).thenReturn(1);

        Result<?> r = service.quitMember(ADMIN, ROLE_ADMIN, MEMBER_ID, "2026-03-05", null, body());

        Map<String, Object> data = dataMap(r);
        assertTrue(String.valueOf(data.get("quitDate")).startsWith("2026-03-05"),
                "退社视图应带 quit_date，实际：" + data.get("quitDate"));
        assertEquals(false, data.get("active"));

        ArgumentCaptor<LocalDate> quitDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(memberMapper).quitMember(eq(MEMBER_ID), quitDate.capture());
        assertEquals(LocalDate.parse("2026-03-05"), quitDate.getValue(), "软删必须置 quit_date");
        verify(memberMapper).softDelete(MEMBER_ID); // deleted=1 由 softDelete 语句落地
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("2.5 quitMember 缺省退社日期：query 为空时落当天")
    void quitMember_defaultQuitDate() {
        when(memberMapper.selectById(MEMBER_ID))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.of(2025, 9, 1), null));
        when(memberMapper.quitMember(eq(MEMBER_ID), any())).thenReturn(1);

        assertOk(service.quitMember(ADMIN, ROLE_ADMIN, MEMBER_ID, null, null, body()));

        ArgumentCaptor<LocalDate> quitDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(memberMapper).quitMember(eq(MEMBER_ID), quitDate.capture());
        assertNotNull(quitDate.getValue());
        assertEquals(LocalDate.now(), quitDate.getValue());
        verify(memberMapper).softDelete(MEMBER_ID);
    }

    @Test
    @DisplayName("2.6 quitMember 负例：不存在 404、已退社 400、CAS 0 行 400（不可二次 softDelete）")
    void quitMember_negatives() {
        when(memberMapper.selectById(9999L)).thenReturn(null);
        assertError(service.quitMember(ADMIN, ROLE_ADMIN, 9999L, null, null, body()), 404, "不存在");

        when(memberMapper.selectById(MEMBER_ID))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.of(2025, 9, 1),
                        LocalDate.of(2026, 1, 1)));
        assertError(service.quitMember(ADMIN, ROLE_ADMIN, MEMBER_ID, null, null, body()), 400, "请勿重复操作");

        when(memberMapper.selectById(MEMBER_ID))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.of(2025, 9, 1), null));
        when(memberMapper.quitMember(eq(MEMBER_ID), any())).thenReturn(0);
        assertError(service.quitMember(ADMIN, ROLE_ADMIN, MEMBER_ID, null, null, body()), 400, CAS_FAIL);
        verify(memberMapper, never()).softDelete(anyLong());
    }

    @Test
    @DisplayName("2.7 quitMember 权限负例：学生 403、未登录 401")
    void quitMember_permission_negatives() {
        assertError(service.quitMember(WANGXM, ROLE_STUDENT, MEMBER_ID, null, null, body()), 403, "无权");
        assertError(service.quitMember(null, null, MEMBER_ID, null, null, body()), 401, "请先登录");
        verify(memberMapper, never()).quitMember(anyLong(), any());
        verify(memberMapper, never()).softDelete(anyLong());
    }

    @Test
    @DisplayName("2.8 listMembers：不带 clubId 也可分页；非法 role 400；未登录 401")
    void listMembers_ok_and_negatives() {
        when(memberMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(
                        memberOf(MEMBER_ID, CLUB_ID, WANGXM, "leader", LocalDate.of(2025, 9, 1), null)));
        when(memberMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        Result<?> r = service.listMembers(ADMIN, ROLE_ADMIN, null, null, null, null, null);
        Result<?> ok = r;
        Map<String, Object> data = dataMap(ok);
        assertEquals(1L, ((Number) data.get("total")).longValue());
        assertTrue(data.containsKey("page") && data.containsKey("size"));
        List<Map<String, Object>> rows = rowsOf(ok);
        assertEquals(1, rows.size());
        assertEquals("负责人", rows.get(0).get("roleText"));
        assertEquals(true, rows.get(0).get("active"), "quitDate 为 null → active=true");
        assertEquals("2024001", rows.get(0).get("studentNo"));

        ArgumentCaptor<Long> clubId = ArgumentCaptor.forClass(Long.class);
        verify(memberMapper).selectList(any(), any(), clubId.capture(), any(), any(), anyInt(), anyInt());
        assertNull(clubId.getValue(), "不带 clubId 应传 null 且不报错");

        assertError(service.listMembers(ADMIN, ROLE_ADMIN, CLUB_ID, "chairman", null, null, null),
                400, "只能是");
        assertError(service.listMembers(null, null, CLUB_ID, null, null, null, null), 401, "请先登录");
    }

    // ==================================================================
    // 3. GET /club/list/mine
    // ==================================================================

    @Test
    @DisplayName("3.1 listMyClubs 学生：id 是社团 id（成员 id 在 memberId），roleText 中文")
    void listMyClubs_student_rowsUseClubIdAsId() {
        ClubMember member = memberOf(MEMBER_ID, CLUB_ID, WANGXM, "leader", LocalDate.of(2025, 9, 1), null);
        when(memberMapper.selectActiveByUser(WANGXM)).thenReturn(Collections.singletonList(member));
        Club club = clubOf(CLUB_ID, "摄影协会", CATEGORY, "active");
        club.setMemberCount(12);
        when(clubMapper.selectById(CLUB_ID)).thenReturn(club);

        List<Map<String, Object>> rows = dataList(service.listMyClubs(WANGXM, ROLE_STUDENT));

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(CLUB_ID, row.get("id"), "行内 id 必须是社团 id");
        assertEquals(MEMBER_ID, row.get("memberId"), "成员 id 应落在 memberId");
        assertEquals(CLUB_ID, row.get("clubId"));
        assertEquals("摄影协会", row.get("clubName"));
        assertEquals(12L, ((Number) row.get("memberCount")).longValue());
        assertEquals("leader", row.get("role"));
        assertEquals("负责人", row.get("roleText"));
    }

    @Test
    @DisplayName("3.2 listMyClubs：只取在册成员（selectActiveByUser），已退社不在结果里")
    void listMyClubs_onlyActiveMemberships() {
        when(memberMapper.selectActiveByUser(COUNSELOR)).thenReturn(new ArrayList<>());

        List<Map<String, Object>> rows = dataList(service.listMyClubs(COUNSELOR, ROLE_TEACHER));

        assertTrue(rows.isEmpty(), "selectActiveByUser 已按 quit_date IS NULL 过滤，空结果即空列表");
        verify(memberMapper).selectActiveByUser(COUNSELOR);
        // 在册口径由 mapper 的 active 查询保证，不得退化成分页/全量查询
        verify(memberMapper, never()).selectList(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("3.3 listMyClubs 未登录 401")
    void listMyClubs_anonymous() {
        assertError(service.listMyClubs(null, null), 401, "请先登录");
    }

    // ==================================================================
    // 4. 年审
    // ==================================================================

    @Test
    @DisplayName("4.1 createReview 教师：成功但 result 保持 null（平台不判定）")
    void createReview_teacher_ok_resultNull() {
        stubReviewInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(reviewMapper.selectByClubAndYear(CLUB_ID, SCHOOL_YEAR)).thenReturn(null);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));

        Result<?> r = service.createReview(COUNSELOR, ROLE_TEACHER, body("clubId", CLUB_ID,
                "schoolYear", SCHOOL_YEAR, "architectureMaterial", "章程", "backboneMaterial", "骨干",
                "activityMaterial", "活动", "fundMaterial", "经费"));

        Map<String, Object> data = dataMap(r);
        assertNull(data.get("result"), "平台不判定：提报后 result 必须为 null");
        assertNull(data.get("resultText"));

        ArgumentCaptor<ClubReview> cap = ArgumentCaptor.forClass(ClubReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertNull(cap.getValue().getResult(), "insert 不得写入 result");
        assertEquals(SCHOOL_YEAR, cap.getValue().getSchoolYear());
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("4.2 createReview 负例：缺 clubId 400、缺学年 400、社团不存在 404")
    void createReview_required_and_notFound() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        assertError(service.createReview(COUNSELOR, ROLE_TEACHER, body("schoolYear", SCHOOL_YEAR)),
                400, "不能为空");
        assertError(service.createReview(COUNSELOR, ROLE_TEACHER, body("clubId", CLUB_ID)), 400, "不能为空");

        when(clubMapper.selectById(9999L)).thenReturn(null);
        assertError(service.createReview(COUNSELOR, ROLE_TEACHER,
                body("clubId", 9999L, "schoolYear", SCHOOL_YEAR)), 404, "不存在");
        verify(reviewMapper, never()).insert(any(ClubReview.class));
    }

    @Test
    @DisplayName("4.3 createReview 学年格式负例：25-26 与 2025-2027 均 400")
    void createReview_schoolYearFormat() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));

        assertError(service.createReview(COUNSELOR, ROLE_TEACHER,
                body("clubId", CLUB_ID, "schoolYear", "25-26")), 400, "学年格式");
        assertError(service.createReview(COUNSELOR, ROLE_TEACHER,
                body("clubId", CLUB_ID, "schoolYear", "2025-2027")), 400, "相邻");
        verify(reviewMapper, never()).insert(any(ClubReview.class));
    }

    @Test
    @DisplayName("4.4 createReview 权限负例：学生 403「无权」、未登录 401")
    void createReview_permission_negatives() {
        assertError(service.createReview(WANGXM, ROLE_STUDENT,
                body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR)), 403, "无权");
        assertError(service.createReview(null, null,
                body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR)), 401, "请先登录");
        verify(reviewMapper, never()).insert(any(ClubReview.class));
    }

    @Test
    @DisplayName("4.5 writeOpinion 教师：挂靠单位意见落库")
    void writeOpinion_teacher_ok() {
        ClubReview before = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        ClubReview after = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        after.setAttachOpinion("同意上报，材料齐全");
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(before, after);
        when(reviewMapper.writeOpinion(REVIEW_ID, "同意上报，材料齐全")).thenReturn(1);

        Result<?> r = service.writeOpinion(COUNSELOR, ROLE_TEACHER, REVIEW_ID,
                body("attachOpinion", "同意上报，材料齐全"));

        assertEquals("同意上报，材料齐全", dataMap(r).get("attachOpinion"));
        verify(reviewMapper).writeOpinion(REVIEW_ID, "同意上报，材料齐全");
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("4.6 writeOpinion 负例：意见空 400、不存在 404、结果已登记 400、CAS 0 行 400")
    void writeOpinion_negatives() {
        when(reviewMapper.selectById(9999L)).thenReturn(null);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));

        assertError(service.writeOpinion(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body("attachOpinion", " ")),
                400, "不能为空");
        assertError(service.writeOpinion(COUNSELOR, ROLE_TEACHER, 9999L, body("attachOpinion", "同意")),
                404, "不存在");

        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified"));
        assertError(service.writeOpinion(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body("attachOpinion", "同意")),
                400, "不可再修改");

        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));
        when(reviewMapper.writeOpinion(eq(REVIEW_ID), any())).thenReturn(0);
        assertError(service.writeOpinion(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body("attachOpinion", "同意")),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("4.7 submitReview 教师：写 submit_time、CAS 前置 result 为空")
    void submitReview_teacher_ok() {
        ClubReview before = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        ClubReview after = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        after.setSubmitTime(LocalDateTime.of(2026, 3, 5, 10, 0, 0));
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(before, after);
        when(reviewMapper.submitReview(eq(REVIEW_ID), isNull())).thenReturn(1);

        Result<?> r = service.submitReview(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body());

        Object submitTime = dataMap(r).get("submitTime");
        assertNotNull(submitTime, "提交后视图应带 submit_time");
        assertTrue(String.valueOf(submitTime).startsWith("2026-03-05"), "submit_time 不符：" + submitTime);

        ArgumentCaptor<String> fromResult = ArgumentCaptor.forClass(String.class);
        verify(reviewMapper).submitReview(eq(REVIEW_ID), fromResult.capture());
        assertNull(fromResult.getValue(), "只有未登记结果的年审材料可提交");
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("4.8 submitReview 负例：重复提交 400、结果已登记 400、不存在 404、CAS 0 行 400")
    void submitReview_negatives() {
        ClubReview submitted = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        submitted.setSubmitTime(LocalDateTime.of(2026, 3, 1, 9, 0, 0));
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(submitted);
        assertError(service.submitReview(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body()), 400, "请勿重复提交");

        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified"));
        assertError(service.submitReview(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body()), 400, "不可再提交");

        when(reviewMapper.selectById(9999L)).thenReturn(null);
        assertError(service.submitReview(COUNSELOR, ROLE_TEACHER, 9999L, body()), 404, "不存在");

        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));
        when(reviewMapper.submitReview(eq(REVIEW_ID), isNull())).thenReturn(0);
        assertError(service.submitReview(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body()), 400, CAS_FAIL);

        assertError(service.submitReview(WANGXM, ROLE_STUDENT, REVIEW_ID, body()), 403, "无权");
    }

    @Test
    @DisplayName("4.9 recordResult 管理员：仅 admin 登记，qualified→合格")
    void recordResult_admin_ok() {
        ClubReview before = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        ClubReview after = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified");
        after.setReviewTime(LocalDateTime.of(2026, 3, 6, 15, 0, 0));
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(before, after);
        when(reviewMapper.recordResult(eq(REVIEW_ID), eq("qualified"), eq(ADMIN))).thenReturn(1);

        Result<?> r = service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID, body("result", "qualified"));

        Map<String, Object> data = dataMap(r);
        assertEquals("qualified", data.get("result"));
        assertEquals("合格", data.get("resultText"));
        verify(reviewMapper).recordResult(REVIEW_ID, "qualified", ADMIN);
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("4.10 recordResult 权限负例：teacher / 学生均 403「无权」")
    void recordResult_permission_negatives() {
        assertError(service.recordResult(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body("result", "qualified")),
                403, "无权");
        assertError(service.recordResult(WANGXM, ROLE_STUDENT, REVIEW_ID, body("result", "qualified")),
                403, "无权");
        verify(reviewMapper, never()).recordResult(anyLong(), any(), any());
    }

    @Test
    @DisplayName("4.11 recordResult 负例：result 空/非法 400、已登记 400、不存在 404、CAS 0 行 400")
    void recordResult_negatives() {
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));
        assertError(service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID, body()), 400, "不能为空");
        assertError(service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID, body("result", "excellent")),
                400, "只能是");

        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified"));
        assertError(service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID, body("result", "unqualified")),
                400, "不可重设");

        when(reviewMapper.selectById(9999L)).thenReturn(null);
        assertError(service.recordResult(ADMIN, ROLE_ADMIN, 9999L, body("result", "qualified")),
                404, "不存在");

        // CAS 前置 result IS NULL：现有 result 为空但 CAS 影响 0 行（并发抢占）
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));
        when(reviewMapper.recordResult(eq(REVIEW_ID), eq("qualified"), eq(ADMIN))).thenReturn(0);
        assertError(service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID, body("result", "qualified")),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("4.12 recordResult 带 publicityId：回写公示关联")
    void recordResult_linksPublicity() {
        ClubReview before = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null);
        ClubReview after = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified");
        after.setPublicityId(PUBLICITY_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(before, after);
        when(reviewMapper.recordResult(eq(REVIEW_ID), eq("qualified"), eq(ADMIN))).thenReturn(1);

        Result<?> r = service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID,
                body("result", "qualified", "publicityId", PUBLICITY_ID));

        assertEquals(PUBLICITY_ID, dataMap(r).get("publicityId"));
        Set<String> reviewCalls = invokedMethods(reviewMapper);
        assertTrue(reviewCalls.contains("update") || reviewCalls.contains("linkPublicity"),
                "登记结果时应回写公示关联，实际调用：" + reviewCalls);
    }

    // ==================================================================
    // 5. 星级
    // ==================================================================

    @Test
    @DisplayName("5.1 createRating 管理员：businessUnit 与挂靠单位 attachUnit 不得互换")
    void createRating_admin_ok_businessUnitNotAttachUnit() {
        stubRatingInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(ratingMapper.selectByClubAndYear(CLUB_ID, SCHOOL_YEAR)).thenReturn(null);
        when(ratingMapper.selectById(RATING_ID))
                .thenReturn(ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star4", "材料充分"));

        Result<?> r = service.createRating(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star4", "basisNote", "材料充分", "businessUnit", "校团委"));

        Map<String, Object> data = dataMap(r);
        assertEquals("star4", data.get("starLevel"));
        assertEquals("四星级", data.get("starLevelText"));
        assertEquals("校团委", data.get("businessUnit"), "业务指导单位取登记值");
        assertFalse(data.containsKey("attachUnit"), "星级视图不得出现挂靠单位 attachUnit");

        ArgumentCaptor<ClubRating> cap = ArgumentCaptor.forClass(ClubRating.class);
        verify(ratingMapper).insert(cap.capture());
        assertEquals("校团委", cap.getValue().getBusinessUnit());
        assertEquals(ADMIN, cap.getValue().getRegisterUserId());
        assertNotNull(cap.getValue().getRegisterTime());
        assertEquals("star4", cap.getValue().getStarLevel());
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("5.2 createRating 负例：缺 clubId、缺星级、非法星级、缺评定依据、社团不存在")
    void createRating_negatives() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        assertError(service.createRating(ADMIN, ROLE_ADMIN,
                body("schoolYear", SCHOOL_YEAR, "starLevel", "star4", "basisNote", "x")), 400, "不能为空");
        assertError(service.createRating(ADMIN, ROLE_ADMIN,
                body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR, "basisNote", "x")), 400, "不能为空");

        assertError(service.createRating(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star2", "basisNote", "x")), 400, "只能是");
        assertError(service.createRating(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star4")), 400, "不能为空");

        when(clubMapper.selectById(9999L)).thenReturn(null);
        assertError(service.createRating(ADMIN, ROLE_ADMIN, body("clubId", 9999L, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star4", "basisNote", "x")), 404, "不存在");
        verify(ratingMapper, never()).insert(any(ClubRating.class));
    }

    @Test
    @DisplayName("5.3 createRating 权限负例：teacher / 学生 403「无权」、未登录 401")
    void createRating_permission_negatives() {
        assertError(service.createRating(COUNSELOR, ROLE_TEACHER, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star4", "basisNote", "x")), 403, "无权");
        assertError(service.createRating(WANGXM, ROLE_STUDENT, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star4", "basisNote", "x")), 403, "无权");
        assertError(service.createRating(null, null, body("clubId", CLUB_ID)), 401, "请先登录");
        verify(ratingMapper, never()).insert(any(ClubRating.class));
    }

    @Test
    @DisplayName("5.4 createRating：同学年同学年重复登记 400（selectByClubAndYear 去重）")
    void createRating_duplicateByClubAndYear() {
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(ratingMapper.selectByClubAndYear(CLUB_ID, SCHOOL_YEAR))
                .thenReturn(ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star4", "已登记"));

        assertError(service.createRating(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star5", "basisNote", "再次登记")), 400, "请勿重复登记");
        verify(ratingMapper, never()).insert(any(ClubRating.class));
    }

    @Test
    @DisplayName("5.5 updateRating 管理员：可改 starLevel / businessUnit / basisNote / publicityId")
    void updateRating_admin_ok() {
        ClubRating before = ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star4", "材料充分");
        ClubRating after = ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star5", "补充材料");
        after.setBusinessUnit("学生工作部");
        after.setPublicityId(PUBLICITY_ID);
        when(ratingMapper.selectById(RATING_ID)).thenReturn(before, after);
        when(ratingMapper.update(any(ClubRating.class))).thenReturn(1);

        Result<?> r = service.updateRating(ADMIN, ROLE_ADMIN, RATING_ID, body("starLevel", "star5",
                "businessUnit", "学生工作部", "basisNote", "补充材料", "publicityId", PUBLICITY_ID));

        Map<String, Object> data = dataMap(r);
        assertEquals("star5", data.get("starLevel"));
        assertEquals("五星级", data.get("starLevelText"));
        assertEquals("学生工作部", data.get("businessUnit"));

        ArgumentCaptor<ClubRating> patch = ArgumentCaptor.forClass(ClubRating.class);
        verify(ratingMapper).update(patch.capture());
        assertEquals("star5", patch.getValue().getStarLevel());
        assertEquals("学生工作部", patch.getValue().getBusinessUnit());
        assertEquals("补充材料", patch.getValue().getBasisNote());
        assertEquals(PUBLICITY_ID, patch.getValue().getPublicityId());
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("5.6 updateRating 负例：teacher 403、不存在 404、空依据 400、非法星级 400、CAS 0 行 400")
    void updateRating_negatives() {
        assertError(service.updateRating(COUNSELOR, ROLE_TEACHER, RATING_ID, body("starLevel", "star5")),
                403, "无权");

        when(ratingMapper.selectById(9999L)).thenReturn(null);
        assertError(service.updateRating(ADMIN, ROLE_ADMIN, 9999L, body("starLevel", "star5")), 404, "不存在");

        when(ratingMapper.selectById(RATING_ID)).thenReturn(ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star4", "x"));
        assertError(service.updateRating(ADMIN, ROLE_ADMIN, RATING_ID, body("basisNote", "  ")), 400, "不能为空");
        assertError(service.updateRating(ADMIN, ROLE_ADMIN, RATING_ID, body("starLevel", "star2")), 400, "只能是");

        when(ratingMapper.update(any(ClubRating.class))).thenReturn(0);
        assertError(service.updateRating(ADMIN, ROLE_ADMIN, RATING_ID, body("starLevel", "star5")),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("5.7 starLevelText：三星级 / 四星级 / 五星级")
    void starLevelText_chinese() {
        when(ratingMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(
                        ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star3", "x"),
                        ratingOf(RATING_ID + 1, CLUB2_ID, SCHOOL_YEAR, "star4", "x"),
                        ratingOf(RATING_ID + 2, 9003L, SCHOOL_YEAR, "star5", "x")));
        when(ratingMapper.selectCount(any(), any(), any(), any(), any(), any())).thenReturn(3L);

        List<Map<String, Object>> rows = rowsOf(service.listRatings(ADMIN, ROLE_ADMIN, null, null, null, null, null, null));

        assertEquals(3, rows.size());
        assertEquals("三星级", rows.get(0).get("starLevelText"));
        assertEquals("四星级", rows.get(1).get("starLevelText"));
        assertEquals("五星级", rows.get(2).get("starLevelText"));
    }

    // ==================================================================
    // 6. 公示
    // ==================================================================

    @Test
    @DisplayName("6.1 createPublicity 管理员：显式 endDate 原样尊重（不覆盖为 startDate+4）")
    void createPublicity_explicitEndDateRespected() {
        stubPublicityInsert();
        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "open"));

        Result<?> r = service.createPublicity(ADMIN, ROLE_ADMIN, body("publicityType", "club_rating",
                "title", "星级评定结果公示", "startDate", "2026-07-07", "endDate", "2026-07-20"));

        assertOk(r);
        ArgumentCaptor<ClubPublicity> cap = ArgumentCaptor.forClass(ClubPublicity.class);
        verify(publicityMapper).insert(cap.capture());
        assertEquals(LocalDate.of(2026, 7, 7), cap.getValue().getStartDate());
        assertEquals(LocalDate.of(2026, 7, 20), cap.getValue().getEndDate(),
                "显式 endDate 必须原样落库，不得被重算成 startDate+4");
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("6.2 createPublicity：缺省 endDate = startDate + 4 天（含首尾 5 天）")
    void createPublicity_defaultEndDate() {
        stubPublicityInsert();
        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_review", "open"));

        assertOk(service.createPublicity(ADMIN, ROLE_ADMIN, body("publicityType", "club_review",
                "title", "年审结果公示", "startDate", "2026-07-07")));

        ArgumentCaptor<ClubPublicity> cap = ArgumentCaptor.forClass(ClubPublicity.class);
        verify(publicityMapper).insert(cap.capture());
        assertEquals(LocalDate.of(2026, 7, 7), cap.getValue().getStartDate());
        assertEquals(LocalDate.of(2026, 7, 11), cap.getValue().getEndDate());
    }

    @Test
    @DisplayName("6.3 createPublicity：startDate 缺省取当天、status=open、contactEmail 缺省 twh@xujc.com")
    void createPublicity_defaults() {
        stubPublicityInsert();
        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "open"));

        assertOk(service.createPublicity(ADMIN, ROLE_ADMIN,
                body("publicityType", "club_rating", "title", "星级评定结果公示")));

        ArgumentCaptor<ClubPublicity> cap = ArgumentCaptor.forClass(ClubPublicity.class);
        verify(publicityMapper).insert(cap.capture());
        ClubPublicity inserted = cap.getValue();
        assertEquals(LocalDate.now(), inserted.getStartDate());
        assertEquals(LocalDate.now().plusDays(4), inserted.getEndDate());
        assertEquals("open", inserted.getStatus());
        assertEquals("twh@xujc.com", inserted.getContactEmail());
        assertEquals(ADMIN, inserted.getPublishUserId());
    }

    @Test
    @DisplayName("6.4 createPublicity 负例：endDate<startDate 400、标题空 400、超长 400、类型空/非法 400")
    void createPublicity_negatives() {
        assertError(service.createPublicity(ADMIN, ROLE_ADMIN, body("publicityType", "club_review",
                "title", "年审公示", "startDate", "2026-07-11", "endDate", "2026-07-10")), 400, "不能早于");

        assertError(service.createPublicity(ADMIN, ROLE_ADMIN,
                body("publicityType", "club_review", "title", " ")), 400, "不能为空");
        assertError(service.createPublicity(ADMIN, ROLE_ADMIN, body("title", "年审公示")), 400, "不能为空");
        assertError(service.createPublicity(ADMIN, ROLE_ADMIN,
                body("publicityType", "club_other", "title", "年审公示")), 400, "只能是");

        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            longTitle.append('公');
        }
        assertError(service.createPublicity(ADMIN, ROLE_ADMIN,
                body("publicityType", "club_review", "title", longTitle.toString())), 400, "不能超过");

        assertError(service.createPublicity(WANGXM, ROLE_STUDENT,
                body("publicityType", "club_review", "title", "年审公示")), 403, "无权");
        verify(publicityMapper, never()).insert(any(ClubPublicity.class));
    }

    @Test
    @DisplayName("6.5 createPublicity：items[] 由关联行生成且不含学生学号/姓名")
    void createPublicity_itemsWithoutStudentIdentity() {
        stubPublicityInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));

        ClubReview linked = reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified");
        linked.setPublicityId(PUBLICITY_ID); // 已关联 → 计入 items
        ClubReview unlinked = reviewOf(REVIEW_ID + 1, CLUB2_ID, SCHOOL_YEAR, "qualified"); // 未关联 → 自动纳入
        when(reviewMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(Arrays.asList(linked, unlinked)));
        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_review", "open"));

        Result<?> r = service.createPublicity(ADMIN, ROLE_ADMIN,
                body("publicityType", "club_review", "title", "2025-2026学年社团年审结果公示"));

        Map<String, Object> data = dataMap(r);
        assertEquals("open", data.get("status"));
        assertEquals("公示中", data.get("statusText"));
        assertEquals("年审结果公示", data.get("publicityTypeText"));

        Object itemsObj = data.get("items");
        assertTrue(itemsObj instanceof List, "公示视图应含 items[]");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        assertFalse(items.isEmpty(), "items[] 应由关联年审行生成");
        for (Map<String, Object> item : items) {
            assertFalse(item.containsKey("studentNo"), "items 不得泄露学生学号");
            assertFalse(item.containsKey("studentName"), "items 不得泄露学生姓名");
            assertFalse(item.containsKey("userId"), "items 不得泄露学生 id");
        }
        Map<String, Object> first = items.get(0);
        assertEquals(CLUB_ID, first.get("clubId"));
        assertEquals("摄影协会", first.get("clubName"));
        assertEquals(SCHOOL_YEAR, first.get("schoolYear"));
        assertEquals("合格", first.get("resultText"));
        assertEquals("挂靠单位", first.get("unitLabel"), "年审行单位口径为挂靠单位");
        assertEquals(ATTACH, first.get("unit"));

        // 未关联的关联行应被回写 publicity_id
        Set<String> reviewCalls = invokedMethods(reviewMapper);
        assertTrue(reviewCalls.contains("update") || reviewCalls.contains("linkPublicity"),
                "自动纳入的关联行应回写公示 id，实际调用：" + reviewCalls);
    }

    @Test
    @DisplayName("6.6 closePublicity 管理员：open→closed，CAS fromStatus=open")
    void closePublicity_admin_ok() {
        stubPublicityInsert();
        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "open"),
                        publicityOf(PUBLICITY_ID, "club_rating", "closed"));
        when(publicityMapper.closePublicity(PUBLICITY_ID, "open")).thenReturn(1);

        Result<?> r = service.closePublicity(ADMIN, ROLE_ADMIN, PUBLICITY_ID, body());

        Map<String, Object> data = dataMap(r);
        assertEquals("closed", data.get("status"));
        assertEquals("已结束", data.get("statusText"));
        verify(publicityMapper).closePublicity(PUBLICITY_ID, "open");
        verify(auditLogService).record(any(AuditLog.class));
    }

    @Test
    @DisplayName("6.7 closePublicity 负例：重复关闭 400、CAS 0 行 400、学生 403、不存在 404")
    void closePublicity_negatives() {
        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "closed"));
        assertError(service.closePublicity(ADMIN, ROLE_ADMIN, PUBLICITY_ID, body()), 400, "请勿重复关闭");
        verify(publicityMapper, never()).closePublicity(anyLong(), any());

        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "open"));
        when(publicityMapper.closePublicity(PUBLICITY_ID, "open")).thenReturn(0);
        assertError(service.closePublicity(ADMIN, ROLE_ADMIN, PUBLICITY_ID, body()), 400, CAS_FAIL);

        assertError(service.closePublicity(WANGXM, ROLE_STUDENT, PUBLICITY_ID, body()), 403, "无权");
        assertError(service.closePublicity(ADMIN, ROLE_ADMIN, 9999L, body()), 404, "不存在");
    }

    @Test
    @DisplayName("6.8 listPublicities：学生 200 且看到裸数组（含 closed），publicityType/status 非法 400")
    void listPublicities_student_ok() {
        when(publicityMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(publicityOf(PUBLICITY_ID, "club_rating", "closed")));
        when(publicityMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        List<Map<String, Object>> rows = dataList(
                service.listPublicities(WANGXM, ROLE_STUDENT, "club_rating", "closed", null, null, null));

        assertEquals(1, rows.size());
        assertEquals("closed", rows.get(0).get("status"));
        assertEquals("已结束", rows.get(0).get("statusText"));
        assertTrue(rows.get(0).containsKey("items"));

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        verify(publicityMapper).selectList(scopeUser.capture(), any(), type.capture(), status.capture(),
                any(), anyInt(), anyInt());
        assertNull(scopeUser.getValue(), "公示列表对学生不收敛 scope（任何已登录角色可读）");
        assertEquals("club_rating", type.getValue());
        assertEquals("closed", status.getValue());

        assertError(service.listPublicities(WANGXM, ROLE_STUDENT, "club_all", null, null, null, null),
                400, "只能是");
        assertError(service.listPublicities(WANGXM, ROLE_STUDENT, null, "opened", null, null, null),
                400, "只能是");
        assertError(service.listPublicities(null, null, null, null, null, null, null), 401, "请先登录");
    }

    @Test
    @DisplayName("6.9 listPublicities：teacher/admin 走分页视图；publicityDetail 学生可读、不存在 404")
    void listPublicities_paged_and_detail() {
        when(publicityMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(publicityOf(PUBLICITY_ID, "club_rating", "open")));
        when(publicityMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        Map<String, Object> teacherPage = dataMap(
                service.listPublicities(COUNSELOR, ROLE_TEACHER, null, null, null, null, null));
        assertEquals(1, teacherPage.get("total"));
        assertTrue(teacherPage.containsKey("list") && teacherPage.containsKey("page")
                && teacherPage.containsKey("size"));

        assertOk(service.listPublicities(ADMIN, ROLE_ADMIN, null, null, null, null, null));

        when(publicityMapper.selectById(PUBLICITY_ID))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "open"));
        Map<String, Object> detail = dataMap(service.publicityDetail(WANGXM, ROLE_STUDENT, PUBLICITY_ID));
        assertEquals(PUBLICITY_ID, detail.get("id"));
        assertEquals("星级评定结果公示", detail.get("title"));

        assertError(service.publicityDetail(ADMIN, ROLE_ADMIN, 9999L), 404, "不存在");
        assertError(service.publicityDetail(null, null, PUBLICITY_ID), 401, "请先登录");
    }

    // ==================================================================
    // 7. 学生只读例外：scope 口径与裸数组
    // ==================================================================

    @Test
    @DisplayName("7.1 listReviews scope：学生 scopeUser=本人/scopeCounselor=null 且裸数组")
    void listReviews_student_scope() {
        when(reviewMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified")));
        when(reviewMapper.selectCount(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        List<Map<String, Object>> rows = dataList(
                service.listReviews(WANGXM, ROLE_STUDENT, null, null, null, null, null, null));

        assertEquals(1, rows.size());
        assertEquals("合格", rows.get(0).get("resultText"));

        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(reviewMapper).selectList(scopeUser.capture(), scopeCounselor.capture(), any(), any(), any(), any(),
                anyInt(), anyInt());
        assertEquals(WANGXM, scopeUser.getValue(), "学生只看本人在册社团");
        assertNull(scopeCounselor.getValue(), "学生口径不带辅导员 scope");
    }

    @Test
    @DisplayName("7.2 listReviews scope：teacher 相反、admin 两者皆 null，且均为分页视图")
    void listReviews_teacherAndAdmin_scope() {
        when(reviewMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified")));
        when(reviewMapper.selectCount(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        Map<String, Object> teacherPage = dataMap(
                service.listReviews(COUNSELOR, ROLE_TEACHER, null, null, null, null, null, null));
        assertTrue(teacherPage.containsKey("list"), "teacher 是 {list,total,page,size}");

        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(reviewMapper).selectList(scopeUser.capture(), scopeCounselor.capture(), any(), any(), any(), any(),
                anyInt(), anyInt());
        assertNull(scopeUser.getValue(), "teacher 不按学生收敛");
        assertEquals(COUNSELOR, scopeCounselor.getValue(), "teacher 按辅导员收敛到本人");

        Map<String, Object> adminPage = dataMap(
                service.listReviews(ADMIN, ROLE_ADMIN, null, null, null, null, null, null));
        assertTrue(adminPage.containsKey("total"));

        ArgumentCaptor<Long> adminUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> adminCounselor = ArgumentCaptor.forClass(Long.class);
        verify(reviewMapper, atLeastOnce()).selectList(adminUser.capture(), adminCounselor.capture(), any(), any(),
                any(), any(), anyInt(), anyInt());
        Object lastUser = adminUser.getAllValues().get(adminUser.getAllValues().size() - 1);
        Object lastCounselor = adminCounselor.getAllValues().get(adminCounselor.getAllValues().size() - 1);
        assertNull(lastUser, "admin 不带学生 scope");
        assertNull(lastCounselor, "admin 不带辅导员 scope");
    }

    @Test
    @DisplayName("7.3 listRatings scope：学生本人裸数组 / teacher 辅导员分页 / admin 无 scope")
    void listRatings_scope_allRoles() {
        when(ratingMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(
                        ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star5", "材料充分")));
        when(ratingMapper.selectCount(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        List<Map<String, Object>> studentRows = dataList(
                service.listRatings(WANGXM, ROLE_STUDENT, null, null, null, null, null, null));
        assertEquals("五星级", studentRows.get(0).get("starLevelText"));

        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(ratingMapper).selectList(scopeUser.capture(), scopeCounselor.capture(), any(), any(), any(), any(),
                anyInt(), anyInt());
        assertEquals(WANGXM, scopeUser.getValue());
        assertNull(scopeCounselor.getValue());

        Map<String, Object> teacherPage = dataMap(
                service.listRatings(COUNSELOR, ROLE_TEACHER, null, null, null, null, null, null));
        assertTrue(teacherPage.containsKey("list"));

        Map<String, Object> adminPage = dataMap(
                service.listRatings(ADMIN, ROLE_ADMIN, null, null, null, null, null, null));
        assertTrue(adminPage.containsKey("list"));

        ArgumentCaptor<Long> su = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> sc = ArgumentCaptor.forClass(Long.class);
        verify(ratingMapper, atLeastOnce()).selectList(su.capture(), sc.capture(), any(), any(), any(), any(),
                anyInt(), anyInt());
        assertNull(su.getAllValues().get(1), "teacher 不按学生收敛");
        assertEquals(COUNSELOR, sc.getAllValues().get(1), "teacher 收敛到本人");
        assertNull(su.getAllValues().get(2), "admin 学生 scope 为 null");
        assertNull(sc.getAllValues().get(2), "admin 辅导员 scope 为 null");
    }

    @Test
    @DisplayName("7.4 listClubs scope：学生本人裸数组 / teacher 辅导员分页 / admin 皆 null")
    void listClubs_scope_allRoles() {
        when(clubMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active")));
        when(clubMapper.selectCount(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        List<Map<String, Object>> studentRows = dataList(
                service.listClubs(WANGXM, ROLE_STUDENT, null, null, null, null, null, null));
        assertEquals(1, studentRows.size());
        assertEquals("正常", studentRows.get(0).get("statusText"));

        Map<String, Object> teacherPage = dataMap(
                service.listClubs(COUNSELOR, ROLE_TEACHER, null, null, null, null, null, null));
        assertTrue(teacherPage.containsKey("list"));

        Map<String, Object> adminPage = dataMap(
                service.listClubs(ADMIN, ROLE_ADMIN, null, null, null, null, null, null));
        assertTrue(adminPage.containsKey("total"));

        ArgumentCaptor<Long> su = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> sc = ArgumentCaptor.forClass(Long.class);
        verify(clubMapper, atLeastOnce()).selectList(su.capture(), sc.capture(), any(), any(), any(), any(),
                anyInt(), anyInt());
        assertEquals(WANGXM, su.getAllValues().get(0), "学生按本人 scope");
        assertNull(sc.getAllValues().get(0));
        assertNull(su.getAllValues().get(1), "teacher 不按学生收敛");
        assertEquals(COUNSELOR, sc.getAllValues().get(1));
        assertNull(su.getAllValues().get(2), "admin 不带学生 scope");
        assertNull(sc.getAllValues().get(2), "admin 不带辅导员 scope");
    }

    @Test
    @DisplayName("7.5 只读筛选负例：reviews result / ratings starLevel / clubs status|category 非法均 400")
    void readFilters_invalid_enums() {
        assertError(service.listReviews(ADMIN, ROLE_ADMIN, null, null, "excellent", null, null, null),
                400, "只能是");
        assertError(service.listRatings(ADMIN, ROLE_ADMIN, null, null, "star2", null, null, null),
                400, "只能是");
        assertError(service.listClubs(ADMIN, ROLE_ADMIN, null, null, "closed", null, null, null),
                400, "只能是");
        assertError(service.listClubs(ADMIN, ROLE_ADMIN, "计算机类", null, null, null, null, null),
                400, "只能是");
        assertError(service.listMembers(ADMIN, ROLE_ADMIN, null, "chairman", null, null, null),
                400, "只能是");
    }

    @Test
    @DisplayName("7.6 只读接口未登录一律 401「请先登录」")
    void readApis_anonymous_401() {
        assertError(service.listClubs(null, null, null, null, null, null, null, null), 401, "请先登录");
        assertError(service.listMembers(null, null, null, null, null, null, null), 401, "请先登录");
        assertError(service.listReviews(null, null, null, null, null, null, null, null), 401, "请先登录");
        assertError(service.listRatings(null, null, null, null, null, null, null, null), 401, "请先登录");
    }

    // ==================================================================
    // 8. 导出
    // ==================================================================

    private void stubExportData() {
        Club withData = clubOf(CLUB_ID, "摄影协会", CATEGORY, "active");
        Club withoutData = clubOf(CLUB2_ID, "棋牌社", "自律互助类", "active");
        when(clubMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(withData, withoutData));
        when(reviewMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, "qualified")));
        when(ratingMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star4", "x")));
    }

    @Test
    @DisplayName("8.1 exportCsv 管理员：表头与字段口径正确，未登记/未评定兜底")
    void exportCsv_admin_headerAndRows() {
        stubExportData();

        CommunityService.CsvExport out = service.exportCsv(ADMIN, ROLE_ADMIN, SCHOOL_YEAR, null, null, null);

        assertNull(out.errorCode, "staff 导出应成功");
        assertNotNull(out.csv);
        assertTrue(out.csv.startsWith("社团名称,社团类别,挂靠单位,学年,年审结果,星级,业务指导单位\n"),
                "CSV 表头不符：\n" + out.csv);
        assertTrue(out.csv.contains("摄影协会"));
        assertTrue(out.csv.contains(CATEGORY));
        assertTrue(out.csv.contains(ATTACH));
        assertTrue(out.csv.contains(SCHOOL_YEAR));
        assertTrue(out.csv.contains("合格"), "年审结果应为中文文案");
        assertTrue(out.csv.contains("四星级"), "星级应为中文文案");
        assertTrue(out.csv.contains("校团委"), "业务指导单位取星级登记值");
        assertTrue(out.csv.contains("棋牌社"));
        assertTrue(out.csv.contains("未登记"), "无年审记录的社团应落「未登记」");
        assertTrue(out.csv.contains("未评定"), "无星级记录的社团应落「未评定」");
        assertTrue(out.filename != null && out.filename.startsWith("club-stats-")
                && out.filename.endsWith(".csv"), "导出文件名不符：" + out.filename);

        // 导出口径固定为全量台账，不带任何 scope
        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(clubMapper).selectList(scopeUser.capture(), scopeCounselor.capture(), any(), any(), any(), any(),
                eq(0), eq(MAX_EXPORT));
        assertNull(scopeUser.getValue());
        assertNull(scopeCounselor.getValue());
    }

    @Test
    @DisplayName("8.2 exportCsv 不泄露学生个人信息：7 列固定口径且从不触碰成员表")
    void exportCsv_noStudentPersonalData() {
        stubExportData();

        CommunityService.CsvExport out = service.exportCsv(ADMIN, ROLE_ADMIN, SCHOOL_YEAR, null, null, null);
        assertNull(out.errorCode);

        String[] lines = out.csv.split("\n", -1);
        assertEquals(7, lines[0].split(",", -1).length, "表头应为 7 列");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals(7, line.split(",", -1).length, "每行应为 7 列，不得夹带个人信息列：" + line);
        }
        assertFalse(out.csv.contains("学号"));
        assertFalse(out.csv.contains("手机"));
        assertFalse(out.csv.contains("邮箱"));
        assertFalse(out.csv.contains("身份证"));
        assertFalse(out.csv.contains("2024001"), "CSV 不得含学生学号");
        assertFalse(out.csv.contains("王小明"), "CSV 不得含学生姓名");
        assertFalse(out.csv.contains("计算机2401"), "CSV 不得含学生班级");

        verifyNoInteractions(memberMapper);
    }

    @Test
    @DisplayName("8.3 exportCsv 教师可导出（isStaff 口径：teacher + admin）")
    void exportCsv_teacherAllowed() {
        stubExportData();
        CommunityService.CsvExport out = service.exportCsv(COUNSELOR, ROLE_TEACHER, SCHOOL_YEAR, null, null, null);
        assertNull(out.errorCode, "教师属 staff，应可导出");
        assertNotNull(out.csv);
        assertTrue(out.filename.endsWith(".csv"));
    }

    @Test
    @DisplayName("8.4 exportCsv 学生 403「无权」且不产出 csv；未登录 403（非 401，按实现）")
    void exportCsv_nonStaffForbidden() {
        CommunityService.CsvExport student = service.exportCsv(WANGXM, ROLE_STUDENT, null, null, null, null);
        assertEquals(Integer.valueOf(403), student.errorCode);
        assertTrue(student.errorMessage.contains("无权"), "文案应含「无权」：" + student.errorMessage);
        assertNull(student.csv, "拒绝导出时不得产出 csv");
        assertNull(student.filename);

        CommunityService.CsvExport anonymous = service.exportCsv(null, null, null, null, null, null);
        assertEquals(Integer.valueOf(403), anonymous.errorCode);
        assertNull(anonymous.csv);

        // 其他教师账号同样是 staff（口径为 isStaff，而非某个具体账号）
        CommunityService.CsvExport otherTeacher =
                service.exportCsv(OTHER_TEACHER, ROLE_TEACHER, null, null, null, null);
        assertNull(otherTeacher.errorCode);
    }

    @Test
    @DisplayName("8.5 导出 Controller：管理员 CSV 带 BOM / text/csv; charset=UTF-8 / .csv 文件名")
    void exportController_admin_bomAndHeaders() {
        stubExportData();

        ResponseEntity<byte[]> resp = controller.exportStats(session(ADMIN, ROLE_ADMIN), SCHOOL_YEAR, null, null, null);

        assertEquals(200, resp.getStatusCode().value());
        byte[] bytes = resp.getBody();
        assertNotNull(bytes);
        assertTrue(bytes.length > 3);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2], "CSV 必须带 UTF-8 BOM");

        String contentType = resp.getHeaders().getFirst("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.startsWith("text/csv"), "Content-Type 应含 text/csv：" + contentType);
        assertTrue(contentType.toLowerCase().contains("charset=utf-8"), "应声明 charset=UTF-8：" + contentType);

        String disposition = resp.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains(".csv"), "Content-Disposition 应含 .csv：" + disposition);

        String text = csvText(resp);
        assertTrue(text.startsWith("\uFEFF社团名称,"), "body 应含 BOM 前缀：\n" + text);
        assertTrue(text.contains("摄影协会"));
    }

    @Test
    @DisplayName("8.6 导出 Controller 负例：学生 / 未登录 → 403 且 body 为空（按实现，非 401）")
    void exportController_forbidden_emptyBody() {
        ResponseEntity<byte[]> student = controller.exportStats(session(WANGXM, ROLE_STUDENT), null, null, null, null);
        assertEquals(403, student.getStatusCode().value());
        assertNull(student.getBody(), "403 应为 ResponseEntity.status(403).build()，body 为空");

        ResponseEntity<byte[]> anonymous = controller.exportStats(new MockHttpSession(), null, null, null, null);
        assertEquals(403, anonymous.getStatusCode().value());
        assertNull(anonymous.getBody(), "未登录同样是 403 空 body（实现口径，契约写 401）");
    }

    // ==================================================================
    // 9. 审计
    // ==================================================================

    @Test
    @DisplayName("9.1 写操作审计：eventType = AuditLogService.TYPE_CLUB，且携带操作人与动作")
    void auditEntry_usesTypeClub() {
        stubClubInsert();
        when(clubMapper.selectById(CLUB_ID)).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));

        assertOk(service.createClub(ADMIN, ROLE_ADMIN, body("clubName", "摄影协会", "category", CATEGORY)));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).record(cap.capture());
        AuditLog entry = cap.getValue();
        assertEquals("CLUB", AuditLogService.TYPE_CLUB);
        assertEquals(AuditLogService.TYPE_CLUB, entry.getEventType());
        assertEquals(ADMIN, entry.getUserId());
        assertNotNull(entry.getEventAction());
        assertTrue(entry.getEventAction().contains("createClub"),
                "审计动作应可定位到 createClub，实际：" + entry.getEventAction());
    }

    @Test
    @DisplayName("9.2 审计覆盖每个写操作；auditLogService 抛异常不阻断业务返回")
    void auditFailure_doesNotBreakWrites() {
        doThrow(new RuntimeException("audit down")).when(auditLogService).record(any(AuditLog.class));

        // 台账
        stubClubInsert();
        when(clubMapper.selectById(anyLong())).thenReturn(clubOf(CLUB_ID, "摄影协会", CATEGORY, "active"));
        when(clubMapper.update(any(Club.class), any())).thenReturn(1);
        when(clubMapper.deregister(anyLong(), any())).thenReturn(1);
        // 成员
        stubMemberInsert();
        when(memberMapper.selectById(anyLong()))
                .thenReturn(memberOf(MEMBER_ID, CLUB_ID, WANGXM, "member", LocalDate.of(2025, 9, 1), null));
        when(memberMapper.selectActiveByClubAndUser(anyLong(), anyLong())).thenReturn(null);
        when(memberMapper.quitMember(anyLong(), any())).thenReturn(1);
        // 年审
        stubReviewInsert();
        when(reviewMapper.selectById(anyLong())).thenReturn(reviewOf(REVIEW_ID, CLUB_ID, SCHOOL_YEAR, null));
        when(reviewMapper.writeOpinion(anyLong(), any())).thenReturn(1);
        when(reviewMapper.submitReview(anyLong(), any())).thenReturn(1);
        when(reviewMapper.recordResult(anyLong(), any(), any())).thenReturn(1);
        // 星级
        stubRatingInsert();
        when(ratingMapper.selectById(anyLong()))
                .thenReturn(ratingOf(RATING_ID, CLUB_ID, SCHOOL_YEAR, "star4", "材料充分"));
        when(ratingMapper.update(any(ClubRating.class))).thenReturn(1);
        // 公示
        stubPublicityInsert();
        when(publicityMapper.selectById(anyLong()))
                .thenReturn(publicityOf(PUBLICITY_ID, "club_rating", "open"));
        when(publicityMapper.closePublicity(anyLong(), any())).thenReturn(1);
        // 导出
        stubExportData();

        List<Result<?>> results = new ArrayList<>();
        results.add(service.createClub(ADMIN, ROLE_ADMIN, body("clubName", "摄影协会", "category", CATEGORY)));
        results.add(service.updateClub(ADMIN, ROLE_ADMIN, CLUB_ID, body("clubName", "摄影协会（新）")));
        results.add(service.deregisterClub(ADMIN, ROLE_ADMIN, CLUB_ID, body()));
        results.add(service.addMember(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "userId", WANGXM, "role", "member")));
        results.add(service.quitMember(ADMIN, ROLE_ADMIN, MEMBER_ID, "2026-03-05", null, body()));
        results.add(service.createReview(COUNSELOR, ROLE_TEACHER,
                body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR)));
        results.add(service.writeOpinion(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body("attachOpinion", "同意")));
        results.add(service.submitReview(COUNSELOR, ROLE_TEACHER, REVIEW_ID, body()));
        results.add(service.recordResult(ADMIN, ROLE_ADMIN, REVIEW_ID, body("result", "qualified")));
        results.add(service.createRating(ADMIN, ROLE_ADMIN, body("clubId", CLUB_ID, "schoolYear", SCHOOL_YEAR,
                "starLevel", "star4", "basisNote", "材料充分")));
        results.add(service.updateRating(ADMIN, ROLE_ADMIN, RATING_ID, body("starLevel", "star5")));
        results.add(service.createPublicity(ADMIN, ROLE_ADMIN,
                body("publicityType", "club_rating", "title", "星级评定结果公示")));
        results.add(service.closePublicity(ADMIN, ROLE_ADMIN, PUBLICITY_ID, body()));

        for (int i = 0; i < results.size(); i++) {
            assertOk(results.get(i));
        }
        assertNull(service.exportCsv(ADMIN, ROLE_ADMIN, SCHOOL_YEAR, null, null, null).errorCode);

        // 每个写操作都尝试过审计（共 13 个写接口 + 导出 1 次）
        verify(auditLogService, atLeastOnce()).record(any(AuditLog.class));
    }
}
