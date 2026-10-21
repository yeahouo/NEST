package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.StudentLoan;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.StudentLoanMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P19 生源地信用助学贷款登记服务单测（纯 Mockito，不起 Spring、不连库）。
 *
 * <p>覆盖四块：权限（谁是本人/名下学生/admin）、提交校验（学年、必填、金额、重复学年）、
 * 状态机（submitted → counselor_approved → confirmed，以及退回/不予登记/撤回与 CAS 冲突）、
 * 导出（脱敏与 403）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P19 生源地贷款登记 · LoanService")
class LoanServiceTest {

    @Mock
    private StudentLoanMapper loanMapper;

    @Mock
    private StudentProfileMapper studentProfileMapper;

    @InjectMocks
    private LoanService loanService;

    private static final Long STUDENT = 8L;
    private static final Long TEACHER = 3L;
    private static final Long ADMIN = 1L;

    @BeforeEach
    void setUp() {
        // 大多数用例不需要打桩，个别用例里再补
    }

    // =========================================================================
    // 0. 工具
    // =========================================================================

    private StudentLoan record(Long id, Long userId, String status) {
        StudentLoan r = new StudentLoan();
        r.setId(id);
        r.setUserId(userId);
        r.setAcademicYear("2026-2027");
        r.setOriginProvince("福建省");
        r.setOriginCity("厦门市");
        r.setOriginCounty("翔安区");
        r.setBankName("国家开发银行");
        r.setContractNo("HZ2026001");
        r.setAmount(new BigDecimal("8000"));
        r.setDisbursed(0);
        r.setStatus(status);
        r.setDeleted(0);
        r.setStudentName("王小明");
        r.setStudentNo("2024010101");
        r.setClassName("计算机2401");
        r.setSubmittedAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        return r;
    }

    private Map<String, String> body() {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("academicYear", "2026-2027");
        b.put("originProvince", "福建省");
        b.put("originCity", "厦门市");
        b.put("originCounty", "翔安区");
        b.put("bankName", "国家开发银行");
        b.put("contractNo", "HZ2026001");
        b.put("amount", "8000");
        return b;
    }

    private void stubInsert() {
        when(loanMapper.insert(any(StudentLoan.class))).thenAnswer(inv -> {
            StudentLoan r = inv.getArgument(0);
            r.setId(900L);
            return 1;
        });
        when(loanMapper.findById(900L)).thenReturn(record(900L, STUDENT, "submitted"));
    }

    private void stubProfile(Long studentId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setUserId(studentId);
        p.setCounselorId(counselorId);
        when(studentProfileMapper.findByUserId(studentId)).thenReturn(p);
    }

    private static int code(Result<?> result) {
        return result.getCode() == null ? -1 : result.getCode();
    }

    // =========================================================================
    // 1. 权限
    // =========================================================================

    @Test
    @DisplayName("未登录：我的登记返回 401")
    void mineWithoutLoginIsUnauthorized() {
        assertEquals(401, code(loanService.mine(null, null, null, 1, 20)));
        verifyNoInteractions(loanMapper);
    }

    @Test
    @DisplayName("未登录：汇总返回 401")
    void summaryWithoutLoginIsUnauthorized() {
        assertEquals(401, code(loanService.summary(null)));
    }

    @Test
    @DisplayName("未登录：提交返回 401 且不落库")
    void createWithoutLoginIsUnauthorized() {
        assertEquals(401, code(loanService.create(null, body())));
        verify(loanMapper, never()).insert(any());
    }

    @Test
    @DisplayName("未登录：改写返回 401")
    void updateWithoutLoginIsUnauthorized() {
        assertEquals(401, code(loanService.update(null, 1L, body())));
    }

    @Test
    @DisplayName("未登录：撤回返回 401")
    void withdrawWithoutLoginIsUnauthorized() {
        assertEquals(401, code(loanService.withdraw(null, 1L)));
    }

    @Test
    @DisplayName("学生看别人的登记：403")
    void detailOfOthersIsForbidden() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, 9L, "submitted"));
        Result<?> r = loanService.detail(STUDENT, "student", 5L);
        assertEquals(403, code(r));
    }

    @Test
    @DisplayName("学生看自己的登记：200")
    void detailOfOwnIsOk() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        assertEquals(200, code(loanService.detail(STUDENT, "student", 5L)));
    }

    @Test
    @DisplayName("学生调员工名单：403")
    void reviewAsStudentIsForbidden() {
        Result<?> r = loanService.review(STUDENT, "student", null, null, null, 1, 20);
        assertEquals(403, code(r));
        assertTrue(r.getMessage().contains("没有"));
        verify(loanMapper, never()).listForReview(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("辅导员只能看自己名下学生：scope 传自己的 id")
    void reviewScopeIsCounselor() {
        when(studentProfileMapper.countByCounselor(TEACHER)).thenReturn(3);
        when(loanMapper.listForReview(any(), any(), any(), eq(TEACHER), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(loanMapper.countForReview(any(), any(), any(), eq(TEACHER))).thenReturn(0);
        when(loanMapper.listAcademicYears()).thenReturn(Collections.singletonList("2026-2027"));
        Result<?> r = loanService.review(TEACHER, "teacher", null, null, null, 1, 20);
        assertEquals(200, code(r));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("counselor", data.get("scope"));
    }

    @Test
    @DisplayName("管理员看全校：scope 为 null")
    void reviewScopeIsAllForAdmin() {
        when(loanMapper.listForReview(any(), any(), any(), isNull(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(loanMapper.countForReview(any(), any(), any(), isNull())).thenReturn(0);
        when(loanMapper.listAcademicYears()).thenReturn(Collections.emptyList());
        Result<?> r = loanService.review(ADMIN, "admin", null, null, null, 1, 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("all", data.get("scope"));
    }

    @Test
    @DisplayName("辅导员核验不属于自己的学生：403")
    void counselorReviewOutsideScopeIsForbidden() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        stubProfile(STUDENT, 9L); // 这个学生的辅导员不是 TEACHER
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "approve", null);
        assertEquals(403, code(r));
        verify(loanMapper, never()).counselorReview(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("确认（confirmed）只有 admin 能做，教师 403")
    void confirmByTeacherIsForbidden() {
        Result<?> r = loanService.confirm(TEACHER, "teacher", 5L);
        assertEquals(403, code(r));
        assertTrue(r.getMessage().contains("资助中心"));
        verify(loanMapper, never()).confirm(any(), any());
    }

    @Test
    @DisplayName("确认未登录：401")
    void confirmWithoutLoginIsUnauthorized() {
        assertEquals(401, code(loanService.confirm(null, null, 5L)));
    }

    // =========================================================================
    // 2. 提交校验
    // =========================================================================

    @Test
    @DisplayName("提交成功：状态为 submitted，返回记录")
    void createOk() {
        when(loanMapper.listMine(eq(STUDENT), isNull(), eq("2026-2027"), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        stubInsert();
        Result<?> r = loanService.create(STUDENT, body());
        assertEquals(200, code(r));
        verify(loanMapper).insert(argThat(rec ->
                "submitted".equals(rec.getStatus())
                        && rec.getUserId().equals(STUDENT)
                        && new BigDecimal("8000").compareTo(rec.getAmount()) == 0));
    }

    @Test
    @DisplayName("空 body：学年不能为空")
    void createWithoutBodyFails() {
        Result<?> r = loanService.create(STUDENT, null);
        assertEquals(400, code(r));
        assertEquals("学年不能为空", r.getMessage());
    }

    @Test
    @DisplayName("学年格式不对：2026/2027 → 400")
    void createBadYearFormat() {
        Map<String, String> b = body();
        b.put("academicYear", "2026/2027");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("2026-2027"));
    }

    @Test
    @DisplayName("学年不连续：2026-2028 → 400")
    void createNonConsecutiveYear() {
        Map<String, String> b = body();
        b.put("academicYear", "2026-2028");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("大 1"));
    }

    @Test
    @DisplayName("省份缺失：400 生源地省份不能为空")
    void createWithoutProvince() {
        Map<String, String> b = body();
        b.remove("originProvince");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertEquals("生源地省份不能为空", r.getMessage());
    }

    @Test
    @DisplayName("县/区缺失：400（办理地在县里）")
    void createWithoutCounty() {
        Map<String, String> b = body();
        b.remove("originCounty");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("县/区"));
    }

    @Test
    @DisplayName("经办机构缺失：400")
    void createWithoutBank() {
        Map<String, String> b = body();
        b.remove("bankName");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertEquals("经办机构不能为空", r.getMessage());
    }

    @Test
    @DisplayName("金额非数字：400")
    void createWithNonNumericAmount() {
        Map<String, String> b = body();
        b.put("amount", "八千");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("必须是数字"));
    }

    @Test
    @DisplayName("金额为 0 / 负数：400 必须大于 0")
    void createWithNonPositiveAmount() {
        Map<String, String> b = body();
        b.put("amount", "0");
        assertEquals("登记金额必须大于 0", loanService.create(STUDENT, b).getMessage());
        b.put("amount", "-1");
        assertEquals("登记金额必须大于 0", loanService.create(STUDENT, b).getMessage());
    }

    @Test
    @DisplayName("金额超过防误填上限：400，且文案说明「不是政策上限」")
    void createWithHugeAmount() {
        Map<String, String> b = body();
        b.put("amount", "80000000");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("防误填"));
        assertTrue(r.getMessage().contains("不设政策上限"));
    }

    @Test
    @DisplayName("金额三位小数：400")
    void createWithTooManyDecimals() {
        Map<String, String> b = body();
        b.put("amount", "8000.123");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("两位小数"));
    }

    @Test
    @DisplayName("到账日期格式不对：400")
    void createWithBadDisbursedDate() {
        Map<String, String> b = body();
        b.put("disbursed", "1");
        b.put("disbursedAt", "2026/10/01");
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("yyyy-MM-dd"));
    }

    @Test
    @DisplayName("备注超长：400")
    void createWithTooLongRemark() {
        Map<String, String> b = body();
        b.put("remark", repeat("备", 256));
        Result<?> r = loanService.create(STUDENT, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("备注"));
    }

    @Test
    @DisplayName("同一学年已有未撤回登记：400 不能重复提交")
    void createDuplicateForSameYear() {
        when(loanMapper.listMine(eq(STUDENT), isNull(), eq("2026-2027"), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(record(1L, STUDENT, "counselor_approved")));
        Result<?> r = loanService.create(STUDENT, body());
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("本学年已有贷款登记"));
        assertTrue(r.getMessage().contains("辅导员已核"));
        verify(loanMapper, never()).insert(any());
    }

    @Test
    @DisplayName("上学年被撤回的登记不挡新学年")
    void createAfterWithdrawnIsAllowed() {
        when(loanMapper.listMine(eq(STUDENT), isNull(), eq("2026-2027"), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(record(1L, STUDENT, "withdrawn")));
        stubInsert();
        assertEquals(200, code(loanService.create(STUDENT, body())));
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    // =========================================================================
    // 3. 改写 / 撤回 / 到账
    // =========================================================================

    @Test
    @DisplayName("改写别人的登记：403")
    void updateOthersIsForbidden() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, 9L, "submitted"));
        Result<?> r = loanService.update(STUDENT, 5L, body());
        assertEquals(403, code(r));
        assertTrue(r.getMessage().contains("自己的"));
    }

    @Test
    @DisplayName("已确认的登记不能改：400")
    void updateConfirmedIsRejected() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "confirmed"));
        Result<?> r = loanService.update(STUDENT, 5L, body());
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("学校已确认"));
    }

    @Test
    @DisplayName("改写成功：CAS 命中 1 行，重新回到 submitted")
    void updateOk() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "returned"));
        when(loanMapper.updateByStudent(any(StudentLoan.class))).thenReturn(1);
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "returned"), record(5L, STUDENT, "submitted"));
        Result<?> r = loanService.update(STUDENT, 5L, body());
        assertEquals(200, code(r));
        assertTrue(r.getMessage().contains("重新提交"));
    }

    @Test
    @DisplayName("改写时状态已被改：CAS 0 行 → 提示刷新")
    void updateCasConflict() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        when(loanMapper.updateByStudent(any(StudentLoan.class))).thenReturn(0);
        Result<?> r = loanService.update(STUDENT, 5L, body());
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("刷新"));
    }

    @Test
    @DisplayName("撤回失败（不是自己的或状态已变）：400")
    void withdrawCasConflict() {
        when(loanMapper.withdraw(5L, STUDENT)).thenReturn(0);
        Result<?> r = loanService.withdraw(STUDENT, 5L);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("撤回失败"));
    }

    @Test
    @DisplayName("撤回成功")
    void withdrawOk() {
        when(loanMapper.withdraw(5L, STUDENT)).thenReturn(1);
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "withdrawn"));
        assertEquals(200, code(loanService.withdraw(STUDENT, 5L)));
    }

    @Test
    @DisplayName("改到账：非本人 403")
    void disbursedOthersIsForbidden() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, 9L, "confirmed"));
        Map<String, String> b = new HashMap<>();
        b.put("disbursed", "1");
        assertEquals(403, code(loanService.updateDisbursed(STUDENT, 5L, b)));
    }

    @Test
    @DisplayName("改到账：值只能填是/否")
    void disbursedBadValue() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "confirmed"));
        Map<String, String> b = new HashMap<>();
        b.put("disbursed", "maybe");
        Result<?> r = loanService.updateDisbursed(STUDENT, 5L, b);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("是 / 否"));
    }

    @Test
    @DisplayName("改到账成功：disbursed=1 且带上日期")
    void disbursedOk() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "confirmed"));
        when(loanMapper.updateDisbursed(eq(5L), eq(STUDENT), eq(1), eq(LocalDate.of(2026, 10, 1)))).thenReturn(1);
        Map<String, String> b = new HashMap<>();
        b.put("disbursed", "是");
        b.put("disbursedAt", "2026-10-01");
        assertEquals(200, code(loanService.updateDisbursed(STUDENT, 5L, b)));
    }

    // =========================================================================
    // 4. 状态机
    // =========================================================================

    @Test
    @DisplayName("辅导员核验通过：submitted → counselor_approved")
    void counselorApproveOk() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        stubProfile(STUDENT, TEACHER);
        when(loanMapper.counselorReview(5L, "submitted", "counselor_approved", null, TEACHER)).thenReturn(1);
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"), record(5L, STUDENT, "counselor_approved"));
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "approve", null);
        assertEquals(200, code(r));
        assertTrue(r.getMessage().contains("等待学校确认"));
    }

    @Test
    @DisplayName("退回必须填原因：400")
    void counselorReturnNeedsReason() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        stubProfile(STUDENT, TEACHER);
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "return", "  ");
        assertEquals(400, code(r));
        assertEquals("退回必须填写原因", r.getMessage());
        verify(loanMapper, never()).counselorReview(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("不予登记必须填原因：400")
    void counselorRejectNeedsReason() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        stubProfile(STUDENT, TEACHER);
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "reject", null);
        assertEquals(400, code(r));
        assertEquals("不予登记必须填写原因", r.getMessage());
    }

    @Test
    @DisplayName("核验动作只能是通过/退回/不予登记")
    void counselorBadAction() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        stubProfile(STUDENT, TEACHER);
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "confirm", null);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("通过 / 退回 / 不予登记"));
    }

    @Test
    @DisplayName("已确认的登记不能再核验：400")
    void counselorReviewWrongState() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "confirmed"));
        stubProfile(STUDENT, TEACHER);
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "approve", null);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("不能核验"));
    }

    @Test
    @DisplayName("核验 CAS 冲突：0 行 → 提示刷新")
    void counselorReviewCasConflict() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        stubProfile(STUDENT, TEACHER);
        when(loanMapper.counselorReview(eq(5L), any(), any(), any(), eq(TEACHER))).thenReturn(0);
        Result<?> r = loanService.counselorReview(TEACHER, "teacher", 5L, "approve", null);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("刷新"));
    }

    @Test
    @DisplayName("学校确认成功：counselor_approved → confirmed")
    void confirmOk() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "counselor_approved"));
        when(loanMapper.confirm(5L, ADMIN)).thenReturn(1);
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "counselor_approved"), record(5L, STUDENT, "confirmed"));
        Result<?> r = loanService.confirm(ADMIN, "admin", 5L);
        assertEquals(200, code(r));
        assertEquals("已确认", r.getMessage());
    }

    @Test
    @DisplayName("未核验的登记不能直接确认：400")
    void confirmWrongState() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "submitted"));
        Result<?> r = loanService.confirm(ADMIN, "admin", 5L);
        assertEquals(400, code(r));
        assertTrue(r.getMessage().contains("需先由辅导员核验"));
        verify(loanMapper, never()).confirm(any(), any());
    }

    @Test
    @DisplayName("登记不存在：400")
    void confirmMissingRecord() {
        when(loanMapper.findById(404L)).thenReturn(null);
        assertEquals(400, code(loanService.confirm(ADMIN, "admin", 404L)));
    }

    // =========================================================================
    // 5. 汇总与列表
    // =========================================================================

    @Test
    @DisplayName("我的汇总：各状态计数 + 已确认金额合计")
    void summaryCounts() {
        when(loanMapper.listMine(eq(STUDENT), isNull(), isNull(), anyInt(), anyInt())).thenReturn(Arrays.asList(
                record(1L, STUDENT, "confirmed"),
                record(2L, STUDENT, "submitted"),
                record(3L, STUDENT, "withdrawn")));
        when(loanMapper.listAcademicYears()).thenReturn(Arrays.asList("2026-2027", "2025-2026"));
        Result<?> r = loanService.summary(STUDENT);
        assertEquals(200, code(r));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(3, data.get("total"));
        assertEquals("8000", data.get("confirmedAmount"));
        assertNotNull(data.get("policy"));
        assertTrue(String.valueOf(data.get("policy")).contains("学校自定"));
    }

    @Test
    @DisplayName("我的登记：非法状态筛选 400")
    void mineBadStatus() {
        assertEquals(400, code(loanService.mine(STUDENT, "paid", null, 1, 20)));
    }

    @Test
    @DisplayName("我的登记：分页参数兜底（page<1、size>100）")
    void mineClampsPaging() {
        when(loanMapper.listMine(eq(STUDENT), isNull(), isNull(), eq(0), eq(100)))
                .thenReturn(Collections.emptyList());
        when(loanMapper.countMine(STUDENT, null, null)).thenReturn(0);
        when(loanMapper.listAcademicYears()).thenReturn(Collections.emptyList());
        Result<?> r = loanService.mine(STUDENT, null, null, 0, 1000);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, data.get("page"));
        assertEquals(100, data.get("size"));
    }

    @Test
    @DisplayName("列表视图带状态中文与可操作标记")
    void viewCarriesStateText() {
        when(loanMapper.findById(5L)).thenReturn(record(5L, STUDENT, "returned"));
        Result<?> r = loanService.detail(STUDENT, "student", 5L);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("退回待改", data.get("statusText"));
        assertEquals(true, data.get("editable"));
        assertEquals(true, data.get("canWithdraw"));
        assertEquals(false, data.get("canConfirm"));
        assertEquals("福建省/厦门市/翔安区", data.get("origin"));
    }

    // =========================================================================
    // 6. 导出
    // =========================================================================

    @Test
    @DisplayName("导出：未登录 403 且不查库")
    void exportWithoutLoginIsForbidden() {
        LoanService.CsvExport out = loanService.exportCsv(null, null, null, null);
        assertEquals(403, out.errorCode);
        verify(loanMapper, never()).listForExport(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("导出：学生 403")
    void exportAsStudentIsForbidden() {
        LoanService.CsvExport out = loanService.exportCsv(STUDENT, "student", null, null);
        assertEquals(403, out.errorCode);
        assertNotNull(out.errorMessage);
    }

    @Test
    @DisplayName("导出：CSV 脱敏（姓名首字 + 学号后 4 位）与表头")
    void exportMasksPii() {
        when(studentProfileMapper.countByCounselor(TEACHER)).thenReturn(2);
        when(loanMapper.listForExport(isNull(), isNull(), eq(TEACHER), anyInt()))
                .thenReturn(Collections.singletonList(record(5L, STUDENT, "confirmed")));
        LoanService.CsvExport out = loanService.exportCsv(TEACHER, "teacher", null, null);
        assertEquals(0, out.errorCode);
        assertTrue(out.filename.startsWith("student-loan-"));
        assertTrue(out.filename.endsWith(".csv"));
        String csv = out.csv;
        assertTrue(csv.contains("学号,姓名,班级,学年,生源地,经办机构"));
        assertTrue(csv.contains("0101"));       // 学号后 4 位
        assertFalse(csv.contains("2024010101")); // 完整学号不许出现
        assertTrue(csv.contains("王*"));
        assertFalse(csv.contains("王小明"));
        assertTrue(csv.contains("学校已确认"));
        assertTrue(csv.contains("未到账"));
    }

    @Test
    @DisplayName("导出：超过 5000 条上限 → 400 提示缩小学年")
    void exportTooManyRows() {
        when(loanMapper.listForExport(isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.nCopies(5001, record(5L, STUDENT, "confirmed")));
        LoanService.CsvExport out = loanService.exportCsv(ADMIN, "admin", null, null);
        assertEquals(400, out.errorCode);
        assertTrue(out.errorMessage.contains("5000"));
    }

    // =========================================================================
    // 7. 口径与健康检查
    // =========================================================================

    @Test
    @DisplayName("口径说明：明确写「学校自定」且不含任何政策数字")
    void policyNoteHasNoFakeStandards() {
        String note = LoanService.policyNote();
        assertTrue(note.contains("学校自定"));
        assertTrue(note.contains("不校验也不代填"));
        assertFalse(note.matches(".*\\d{4,}.*")); // 不许出现任何金额/文号数字
    }

    @Test
    @DisplayName("健康检查：13 条路由与状态中文")
    void healthListsRoutes() {
        Result<?> r = loanService.health();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("student-loan", data.get("module"));
        @SuppressWarnings("unchecked")
        List<String> routes = (List<String>) data.get("routes");
        assertEquals(13, routes.size());
        assertTrue(routes.contains("GET /api/loan/export"));
        assertTrue(routes.contains("POST /api/loan/{id}/confirm"));
    }
}
