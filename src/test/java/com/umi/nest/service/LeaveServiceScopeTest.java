package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.LeaveRequest;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.LeaveRequestMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 请假审批的数据级授权回归测试。
 *
 * <p>缺陷（2026-10-26 实测）：收件箱按 counselor_id 过滤，但 {@code decide()} 不校验归属，
 * 辅导员 zhangsan 按 id 直调 {@code /api/leave/{id}/reject} 驳回了 lisi 名下学生的假条。
 * 修复口径：审批范围 = 收件箱范围。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("请假审批 · 数据级授权（审批范围 = 收件箱范围）")
class LeaveServiceScopeTest {

    @Mock
    private LeaveRequestMapper leaveMapper;

    @Mock
    private StudentProfileMapper profileMapper;

    @InjectMocks
    private LeaveService leaveService;

    private static final Long ZHANGSAN = 2L;
    private static final Long LISI = 3L;
    private static final Long ADMIN = 1L;
    private static final Long WANGXM = 4L;   // lisi 名下

    private LeaveRequest pending() {
        LeaveRequest r = new LeaveRequest();
        r.setId(5L);
        r.setStudentId(WANGXM);
        r.setStatus("pending");
        return r;
    }

    private StudentProfile profile(Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setUserId(WANGXM);
        p.setCounselorId(counselorId);
        return p;
    }

    @Test
    @DisplayName("非本人辅导员按 id 直调驳回 → 403，且不写库")
    void otherCounselorCannotDecide() {
        when(leaveMapper.findById(5L)).thenReturn(pending());
        when(profileMapper.countByCounselor(ZHANGSAN)).thenReturn(1);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(LISI));

        Result<?> r = leaveService.decide(ZHANGSAN, "teacher", 5L, "reject", "越权");

        assertEquals(403, r.getCode());
        verify(leaveMapper, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非本人辅导员批准同样 403")
    void otherCounselorCannotApprove() {
        when(leaveMapper.findById(5L)).thenReturn(pending());
        when(profileMapper.countByCounselor(ZHANGSAN)).thenReturn(1);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(LISI));

        assertEquals(403, leaveService.decide(ZHANGSAN, "teacher", 5L, "approve", null).getCode());
        verify(leaveMapper, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("本人辅导员可以审批")
    void ownCounselorCanDecide() {
        when(leaveMapper.findById(5L)).thenReturn(pending());
        when(profileMapper.countByCounselor(LISI)).thenReturn(3);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(LISI));
        when(leaveMapper.updateStatus(eq(5L), eq("pending"), eq("approved"), eq(LISI), any())).thenReturn(1);

        assertEquals(200, leaveService.decide(LISI, "teacher", 5L, "approve", "同意").getCode());
    }

    @Test
    @DisplayName("学生没有档案（不在任何辅导员收件箱）→ 有名下学生的辅导员不能审批")
    void studentWithoutProfileIsOutOfScope() {
        when(leaveMapper.findById(5L)).thenReturn(pending());
        when(profileMapper.countByCounselor(ZHANGSAN)).thenReturn(1);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(null);

        assertEquals(403, leaveService.decide(ZHANGSAN, "teacher", 5L, "approve", null).getCode());
    }

    @Test
    @DisplayName("名下尚无学生的辅导员沿用收件箱「看全部」口径")
    void unassignedCounselorKeepsInboxSemantics() {
        when(leaveMapper.findById(5L)).thenReturn(pending());
        when(profileMapper.countByCounselor(9L)).thenReturn(0);
        when(leaveMapper.updateStatus(eq(5L), eq("pending"), eq("approved"), eq(9L), any())).thenReturn(1);

        assertEquals(200, leaveService.decide(9L, "teacher", 5L, "approve", null).getCode());
    }

    @Test
    @DisplayName("管理员全校可审批，不查档案")
    void adminCanDecideAnything() {
        when(leaveMapper.findById(5L)).thenReturn(pending());
        when(leaveMapper.updateStatus(eq(5L), eq("pending"), eq("approved"), eq(ADMIN), any())).thenReturn(1);

        assertEquals(200, leaveService.decide(ADMIN, "admin", 5L, "approve", null).getCode());
        verifyNoInteractions(profileMapper);
    }

    @Test
    @DisplayName("学生角色仍然 403")
    void studentCannotDecide() {
        assertEquals(403, leaveService.decide(WANGXM, "student", 5L, "approve", null).getCode());
        verifyNoInteractions(leaveMapper);
    }
}
