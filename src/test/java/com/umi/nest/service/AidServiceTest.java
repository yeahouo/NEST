package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AidApplication;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.AidApplicationMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资助申请（学工 P5）的状态机、权限边界与重复提交闸。
 *
 * <p>这里盯的是三类在真机上「不报错但做错事」的缺陷：审批收件箱越权看到别人名下学生、
 * 同一学年重复提交拿两份钱、并发下状态已被改过却仍按旧状态批准。
 * 真机 E2E（68 项全 PASS）已端到端验过一次，这里把同样的口径钉进单测。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AidService - 资助申请状态机与权限")
class AidServiceTest {

    private static final Long ADMIN = 1L;
    private static final Long OTHER_TEACHER = 2L;
    private static final Long CHE_WU = 3L;   // 有分派的辅导员
    private static final Long STUDENT = 7L;  // 学生 users.id
    private static final Long RECORD = 11L;
    private static final String YEAR = "2026-2027";

    @Mock
    private AidApplicationMapper aidMapper;

    @Mock
    private StudentProfileMapper profileMapper;

    @InjectMocks
    private AidService service;

    // ------------------------------------------------------------------ 夹具

    private AidApplication app(Long id, Long studentId, String status, String aidType, String year) {
        AidApplication a = new AidApplication();
        a.setId(id);
        a.setStudentId(studentId);
        a.setStatus(status);
        a.setAidType(aidType);
        a.setAcademicYear(year);
        a.setReason("家庭经济困难");
        a.setDeleted(0);
        return a;
    }

    private StudentProfile profile(Long userId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setId(userId);
        p.setUserId(userId);
        p.setCounselorId(counselorId);
        p.setStudentNo("202400" + userId);
        p.setClassName("软工2401");
        p.setStatus("active");
        return p;
    }

    private Map<String, String> body(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /** 一条合法申请的最小 body（内容申请，默认学年）。 */
    private Map<String, String> validApply() {
        return body("aidType", "grant", "reason", "家庭经济困难，需要助学金");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(Result<?> r) {
        return (Map<String, Object>) data(r).get("stats");
    }

    // ------------------------------------------------------------------ 权限

    @Nested
    @DisplayName("权限闸")
    class Permission {

        @Test
        @DisplayName("未登录一律 401，先于 403")
        void unauthenticated() {
            assertEquals(401, service.mine(null, null, 1, 20).getCode());
            assertEquals(401, service.apply(null, validApply()).getCode());
            assertEquals(401, service.cancel(null, RECORD).getCode());
            assertEquals(401, service.inbox(null, null, null, null, null, null, 1, 20).getCode());
            assertEquals(401, service.decide(null, null, RECORD, "approve", null, null).getCode());
            assertEquals("未登录", service.inbox(null, "teacher", null, null, null, null, 1, 20).getMessage());
        }

        @Test
        @DisplayName("学生读审批箱 → 403「无权限查看资助审批列表」")
        void studentCannotReadInbox() {
            Result<?> r = service.inbox(STUDENT, "student", null, null, null, null, 1, 20);

            assertEquals(403, r.getCode());
            assertEquals("无权限查看资助审批列表", r.getMessage());
            verify(aidMapper, never()).listInbox(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("学生审批 → 403")
        void studentCannotDecide() {
            Result<?> r = service.decide(STUDENT, "student", RECORD, "approve", null, null);

            assertEquals(403, r.getCode());
            assertEquals("无权限审批", r.getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("审批别人名下学生的申请 → 403，且状态机不动")
        void teacherCannotDecideOthers() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(profileMapper.findByUserId(STUDENT)).thenReturn(profile(STUDENT, OTHER_TEACHER));

            Result<?> r = service.decide(CHE_WU, "teacher", RECORD, "approve", "同意", null);

            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }
    }

    // ------------------------------------------------------------------ 审批箱

    @Nested
    @DisplayName("GET /api/counselor/aid/inbox")
    class Inbox {

        @Test
        @DisplayName("admin 看全校：scopeCounselorId 传 null，且不查分派数")
        void adminSeesAll() {
            Result<?> r = service.inbox(ADMIN, "admin", null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(aidMapper).listInbox(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
            verify(profileMapper, never()).countByCounselor(anyLong());
        }

        @Test
        @DisplayName("有分派的辅导员只看名下学生")
        void teacherScopedToOwn() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(2);

            Result<?> r = service.inbox(CHE_WU, "teacher", null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("mine", data(r).get("scope"));
            verify(aidMapper).listInbox(isNull(), isNull(), isNull(), eq(CHE_WU), eq(0), eq(20));
            verify(aidMapper).countInbox(isNull(), isNull(), isNull(), eq(CHE_WU));
        }

        @Test
        @DisplayName("未分派的辅导员看全校；scope=all 也能显式切全校")
        void unassignedOrExplicitAll() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(0);
            Result<?> r1 = service.inbox(CHE_WU, "teacher", null, null, null, null, 1, 20);
            assertEquals("all", data(r1).get("scope"));

            Result<?> r2 = service.inbox(CHE_WU, "teacher", null, null, null, "all", 1, 20);
            assertEquals("all", data(r2).get("scope"));
            verify(aidMapper, times(2)).listInbox(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("size 上界是 200，page<1 归一，offset 按页算")
        void paging() {
            Result<?> r = service.inbox(ADMIN, "admin", null, null, null, null, 0, 999);

            assertEquals(200, r.getCode());
            assertEquals(1, data(r).get("page"));
            assertEquals(200, data(r).get("size"));
            verify(aidMapper).listInbox(isNull(), isNull(), isNull(), isNull(), eq(0), eq(200));
        }

        @Test
        @DisplayName("状态/项目筛选白名单之外的取值被拒")
        void badFilters() {
            Result<?> s = service.inbox(ADMIN, "admin", "banana", null, null, null, 1, 20);
            assertEquals(400, s.getCode());
            assertEquals("状态筛选不正确", s.getMessage());

            Result<?> t = service.inbox(ADMIN, "admin", null, "banana", null, null, 1, 20);
            assertEquals(400, t.getCode());
            assertEquals("资助项目筛选不正确", t.getMessage());

            verify(aidMapper, never()).listInbox(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("项目筛选不再接受 need：困难认定不归 P5 列表/计数管")
        void needNotFilterable() {
            Result<?> r = service.inbox(ADMIN, "admin", null, "need", null, null, 1, 20);

            assertEquals(400, r.getCode());
            assertEquals("资助项目筛选不正确", r.getMessage());
            verify(aidMapper, never()).listInbox(any(), any(), any(), any(), anyInt(), anyInt());
            verify(aidMapper, never()).countInbox(any(), any(), any(), any());
        }

        @Test
        @DisplayName("审批箱带 stats 与 scope")
        void carriesStats() {
            when(aidMapper.countGroupByStatus(isNull(), isNull()))
                    .thenReturn(Collections.singletonList(row("status", "pending", "cnt", 5)));

            Result<?> r = service.inbox(ADMIN, "admin", null, null, null, null, 1, 20);

            assertEquals(5, stats(r).get("pending"));
            assertEquals(5, stats(r).get("total"));
            assertEquals("all", data(r).get("scope"));
        }
    }

    // ------------------------------------------------------------------ 申请

    @Nested
    @DisplayName("POST /api/student/aid")
    class Apply {

        @Test
        @DisplayName("没有内容 → 没有要提交的内容")
        void emptyBody() {
            assertEquals("没有要提交的内容", service.apply(STUDENT, null).getMessage());
            assertEquals("没有要提交的内容", service.apply(STUDENT, new HashMap<>()).getMessage());
            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("资助项目非法 → 请选择资助项目（下拉只剩助学金/临时困难补助/勤工助学）")
        void badAidType() {
            Result<?> r = service.apply(STUDENT, body("aidType", "banana", "reason", "家庭经济困难"));

            assertEquals(400, r.getCode());
            assertEquals("请选择资助项目（助学金 / 临时困难补助 / 勤工助学）", r.getMessage());
            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("困难认定（need）不再走 P5 → 400 指到困难认定页七步流程，且不碰重复提交闸")
        void needTypeRejected() {
            Result<?> en = service.apply(STUDENT, body("aidType", "need", "reason", "家庭经济困难"));
            assertEquals(400, en.getCode());
            assertEquals("困难认定请到「困难认定」页走七步流程", en.getMessage());

            // 历史数据里的中文写法（aid_type='困难认定'）同样归 P11，P5 一律不收
            Result<?> zh = service.apply(STUDENT, body("aidType", "困难认定", "reason", "家庭经济困难"));
            assertEquals(400, zh.getCode());
            assertEquals("困难认定请到「困难认定」页走七步流程", zh.getMessage());

            verify(aidMapper, never()).insert(any(AidApplication.class));
            verify(aidMapper, never()).countOccupying(anyLong(), any(), any());
        }

        @Test
        @DisplayName("困难档次非法 → 困难档次不正确")
        void badLevel() {
            Result<?> r = service.apply(STUDENT,
                    body("aidType", "grant", "level", "banana", "reason", "家庭经济困难"));

            assertEquals(400, r.getCode());
            assertEquals("困难档次不正确（可选：一般困难 / 困难 / 特别困难）", r.getMessage());
            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("理由不足 4 个字（含全空白）→ 至少 4 个字")
        void reasonTooShort() {
            Result<?> r1 = service.apply(STUDENT, body("aidType", "grant", "reason", "太短"));
            assertEquals(400, r1.getCode());
            assertEquals("请把申请理由写清楚（至少 4 个字）", r1.getMessage());

            Result<?> r2 = service.apply(STUDENT, body("aidType", "grant", "reason", "abc"));
            assertEquals(400, r2.getCode());
            assertEquals("请把申请理由写清楚（至少 4 个字）", r2.getMessage());

            Result<?> r3 = service.apply(STUDENT, body("aidType", "grant", "reason", "     "));
            assertEquals(400, r3.getCode());
            assertEquals("请把申请理由写清楚（至少 4 个字）", r3.getMessage());

            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("学年写成 2026学年 → 学年格式不正确（应为 2026-2027）")
        void badAcademicYear() {
            Result<?> r = service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "academicYear", "2026学年"));

            assertEquals(400, r.getCode());
            assertEquals("学年格式不正确（应为 2026-2027）", r.getMessage());
            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("金额不是数 / 负数 / 超上限 → 金额不正确")
        void badAmount() {
            assertEquals("金额不正确", service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "amount", "abc")).getMessage());
            assertEquals("金额不正确", service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "amount", "-1")).getMessage());
            assertEquals("金额不正确", service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "amount", "10000000")).getMessage());

            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("同一学年同一项目已有 pending → 400 重复提交")
        void duplicatePending() {
            when(aidMapper.countOccupying(STUDENT, "grant", YEAR)).thenReturn(1);

            Result<?> r = service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "academicYear", YEAR));

            assertEquals(400, r.getCode());
            assertEquals("本学年该项目已有待审或已批准的申请，请勿重复提交", r.getMessage());
            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("已有 approved 同样算占用名额")
        void duplicateApproved() {
            when(aidMapper.countOccupying(STUDENT, "grant", YEAR)).thenReturn(1);

            Result<?> r = service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "academicYear", YEAR));

            assertEquals(400, r.getCode());
            assertEquals("本学年该项目已有待审或已批准的申请，请勿重复提交", r.getMessage());
            verify(aidMapper, never()).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("往年 / 已驳回 / 已撤回 / 换项目的旧申请不占名额（重复闸在 SQL 里按 学年+项目+在办状态 判定）")
        void oldRecordsDoNotBlock() {
            when(aidMapper.countOccupying(STUDENT, "grant", YEAR)).thenReturn(0);
            when(aidMapper.insert(any(AidApplication.class))).thenReturn(1);

            Result<?> r = service.apply(STUDENT,
                    body("aidType", "grant", "reason", "家庭经济困难", "academicYear", YEAR));

            assertEquals(200, r.getCode());
            assertEquals("已提交，等待辅导员审批", r.getMessage());
            verify(aidMapper).insert(any(AidApplication.class));
        }

        @Test
        @DisplayName("正常提交：状态置 pending、注明学年、金额与档次落库")
        void happyPath() {
            when(aidMapper.insert(any(AidApplication.class))).thenReturn(1);

            Result<?> r = service.apply(STUDENT, body("aidType", "temporary", "level", "difficult",
                    "reason", "突发疾病导致家庭困难", "amount", "800", "academicYear", YEAR));

            assertEquals(200, r.getCode());
            ArgumentCaptor<AidApplication> captor = ArgumentCaptor.forClass(AidApplication.class);
            verify(aidMapper).insert(captor.capture());
            AidApplication saved = captor.getValue();
            assertEquals(STUDENT, saved.getStudentId());
            assertEquals("temporary", saved.getAidType());
            assertEquals("difficult", saved.getLevel());
            assertEquals(YEAR, saved.getAcademicYear());
            assertEquals("pending", saved.getStatus());
            assertEquals(0, saved.getDeleted());
            assertEquals(0, new BigDecimal("800").compareTo(saved.getAmount()));
        }

        @Test
        @DisplayName("学年留空 → 用 currentAcademicYear() 补齐（9 月为界）")
        void blankYearUsesCurrentAcademicYear() {
            when(aidMapper.insert(any(AidApplication.class))).thenReturn(1);

            Result<?> r = service.apply(STUDENT, validApply());

            assertEquals(200, r.getCode());
            ArgumentCaptor<AidApplication> captor = ArgumentCaptor.forClass(AidApplication.class);
            verify(aidMapper).insert(captor.capture());
            String year = captor.getValue().getAcademicYear();
            assertEquals(AidService.currentAcademicYear(), year);
            assertTrue(year.matches("\\d{4}-\\d{4}"), "学年应形如 2026-2027，实际 " + year);
        }

        @Test
        @DisplayName("学年以 9 月为界：当前日期落在哪个学年要和实现口径一致")
        void academicYearBoundary() {
            LocalDate now = LocalDate.now();
            int start = now.getMonthValue() >= 9 ? now.getYear() : now.getYear() - 1;
            assertEquals(start + "-" + (start + 1), AidService.currentAcademicYear());
        }

        @Test
        @DisplayName("档次留空 → 存 null；材料说明与理由两侧空白被 trim")
        void trimmingAndNullLevel() {
            when(aidMapper.insert(any(AidApplication.class))).thenReturn(1);

            Result<?> r = service.apply(STUDENT, body("aidType", "grant",
                    "reason", "  家庭经济困难  ", "evidence", "  低保证明  "));

            assertEquals(200, r.getCode());
            ArgumentCaptor<AidApplication> captor = ArgumentCaptor.forClass(AidApplication.class);
            verify(aidMapper).insert(captor.capture());
            assertNull(captor.getValue().getLevel());
            assertEquals("家庭经济困难", captor.getValue().getReason());
            assertEquals("低保证明", captor.getValue().getEvidence());
        }
    }

    // ------------------------------------------------------------------ 撤回

    @Nested
    @DisplayName("POST /api/student/aid/{id}/cancel")
    class Cancel {

        @Test
        @DisplayName("申请不存在 → 404")
        void notFound() {
            Result<?> r = service.cancel(STUDENT, RECORD);

            assertEquals(404, r.getCode());
            assertEquals("申请不存在", r.getMessage());
            verify(aidMapper, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("撤回不是本人的申请 → 403「无权限操作该申请」")
        void notOwner() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, OTHER_TEACHER, "pending", "grant", YEAR));

            Result<?> r = service.cancel(STUDENT, RECORD);

            assertEquals(403, r.getCode());
            assertEquals("无权限操作该申请", r.getMessage());
            verify(aidMapper, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("P5 撤回碰到 need 行 → 400，学生不能从资助页打断认定流程")
        void cannotCancelReviewRow() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "need", YEAR));

            Result<?> r = service.cancel(STUDENT, RECORD);

            assertEquals(400, r.getCode());
            assertEquals("困难认定请到「困难认定」页走七步流程", r.getMessage());
            verify(aidMapper, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("待审批撤回成功：cancel(id, userId) 命中 1 行")
        void pendingCancelled() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(aidMapper.cancel(RECORD, STUDENT)).thenReturn(1);

            Result<?> r = service.cancel(STUDENT, RECORD);

            assertEquals(200, r.getCode());
            assertEquals("已撤回", r.getMessage());
            verify(aidMapper).cancel(RECORD, STUDENT);
        }

        @Test
        @DisplayName("非 pending 撤回失败：文案按 statusText 说明当前状态")
        void onlyPendingCanBeCancelled() {
            when(aidMapper.cancel(RECORD, STUDENT)).thenReturn(0);

            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "approved", "grant", YEAR));
            Result<?> approved = service.cancel(STUDENT, RECORD);
            assertEquals(400, approved.getCode());
            assertEquals("只有待审批的申请可以撤回（当前：已批准）", approved.getMessage());

            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "rejected", "grant", YEAR));
            assertEquals("只有待审批的申请可以撤回（当前：已驳回）", service.cancel(STUDENT, RECORD).getMessage());

            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "cancelled", "grant", YEAR));
            assertEquals("只有待审批的申请可以撤回（当前：已撤回）", service.cancel(STUDENT, RECORD).getMessage());

            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "banana", "grant", YEAR));
            assertEquals("只有待审批的申请可以撤回（当前：未知）", service.cancel(STUDENT, RECORD).getMessage());
        }
    }

    // ------------------------------------------------------------------ 审批

    @Nested
    @DisplayName("POST /api/counselor/aid/{id}/decide")
    class Decide {

        @Test
        @DisplayName("action 不是 approve/reject → 操作不正确，且不碰状态机")
        void badAction() {
            assertEquals("操作不正确", service.decide(ADMIN, "admin", RECORD, "cancel", null, null).getMessage());
            assertEquals("操作不正确", service.decide(ADMIN, "admin", RECORD, "", null, null).getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("申请不存在 → 404")
        void notFound() {
            Result<?> r = service.decide(ADMIN, "admin", RECORD, "approve", null, null);

            assertEquals(404, r.getCode());
            assertEquals("申请不存在", r.getMessage());
        }

        @Test
        @DisplayName("P5 审批碰到 need 行 → 400，状态机不动（认定行归 P11 七步流程）")
        void cannotDecideReviewRow() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "need", YEAR));

            Result<?> r = service.decide(ADMIN, "admin", RECORD, "approve", "同意", null);

            assertEquals(400, r.getCode());
            assertEquals("困难认定请到「困难认定」页走七步流程", r.getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("驳回必须填原因：空 / 空白 / 1 个字都拒")
        void rejectNeedsNote() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));

            assertEquals("驳回必须填写原因", service.decide(ADMIN, "admin", RECORD, "reject", null, null).getMessage());
            assertEquals("驳回必须填写原因", service.decide(ADMIN, "admin", RECORD, "reject", "   ", null).getMessage());
            assertEquals("驳回必须填写原因", service.decide(ADMIN, "admin", RECORD, "reject", "好", null).getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("审批意见超过 255 个字被拒")
        void noteTooLong() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));

            Result<?> r = service.decide(ADMIN, "admin", RECORD, "approve", "x".repeat(256), null);

            assertEquals(400, r.getCode());
            assertEquals("审批意见不能超过 255 个字", r.getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("批准可带核定金额：金额透传到 updateStatus")
        void approveWithAmount() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(aidMapper.updateStatus(any(), any(), any(), any(), any(), any())).thenReturn(1);

            Result<?> r = service.decide(CHE_WU, "teacher", RECORD, "approve", "同意", "800");

            assertEquals(200, r.getCode());
            assertEquals("已批准", r.getMessage());
            verify(aidMapper).updateStatus(eq(RECORD), eq("pending"), eq("approved"), eq(CHE_WU),
                    eq("同意"), eq(new BigDecimal("800")));
        }

        @Test
        @DisplayName("批准不填金额 → amount 传 null（不写 0）；空意见存 null")
        void approveWithoutAmount() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(aidMapper.updateStatus(any(), any(), any(), any(), any(), any())).thenReturn(1);

            Result<?> r = service.decide(ADMIN, "admin", RECORD, "approve", "", "");

            assertEquals(200, r.getCode());
            verify(aidMapper).updateStatus(eq(RECORD), eq("pending"), eq("approved"), eq(ADMIN), isNull(), isNull());
        }

        @Test
        @DisplayName("核定金额不是数 / 负数 → 核定金额不正确")
        void badApprovedAmount() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));

            assertEquals("核定金额不正确", service.decide(ADMIN, "admin", RECORD, "approve", "同意", "abc").getMessage());
            assertEquals("核定金额不正确", service.decide(ADMIN, "admin", RECORD, "approve", "同意", "-5").getMessage());
            verify(aidMapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("驳回成功：from=pending to=rejected，原因落库")
        void rejectHappyPath() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(aidMapper.updateStatus(any(), any(), any(), any(), any(), any())).thenReturn(1);

            Result<?> r = service.decide(ADMIN, "admin", RECORD, "reject", "材料不齐全", null);

            assertEquals(200, r.getCode());
            assertEquals("已驳回", r.getMessage());
            verify(aidMapper).updateStatus(RECORD, "pending", "rejected", ADMIN, "材料不齐全", null);
        }

        @Test
        @DisplayName("状态机命中 0 行（并发冲突）→ 说明当前状态，不假装批准")
        void conflictOnZeroRows() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "approved", "grant", YEAR));
            when(aidMapper.updateStatus(any(), any(), any(), any(), any(), any())).thenReturn(0);

            Result<?> r = service.decide(ADMIN, "admin", RECORD, "approve", "同意", null);

            assertEquals(400, r.getCode());
            assertEquals("该申请当前是「已批准」，不能执行此操作", r.getMessage());

            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "rejected", "grant", YEAR));
            assertEquals("该申请当前是「已驳回」，不能执行此操作",
                    service.decide(ADMIN, "admin", RECORD, "approve", "同意", null).getMessage());

            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "cancelled", "grant", YEAR));
            assertEquals("该申请当前是「已撤回」，不能执行此操作",
                    service.decide(ADMIN, "admin", RECORD, "approve", "同意", null).getMessage());
        }

        @Test
        @DisplayName("审批成功：批准/驳回都回读最新记录")
        void rereadsAfterUpdate() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(aidMapper.updateStatus(any(), any(), any(), any(), any(), any())).thenReturn(1);

            service.decide(ADMIN, "admin", RECORD, "approve", "同意", null);

            verify(aidMapper, times(2)).findById(RECORD);
        }

        @Test
        @DisplayName("未分派学生的申请，任何辅导员都能审")
        void unassignedStudentDecidable() {
            when(aidMapper.findById(RECORD)).thenReturn(app(RECORD, STUDENT, "pending", "grant", YEAR));
            when(profileMapper.findByUserId(STUDENT)).thenReturn(profile(STUDENT, null));
            when(aidMapper.updateStatus(any(), any(), any(), any(), any(), any())).thenReturn(1);

            Result<?> r = service.decide(CHE_WU, "teacher", RECORD, "approve", "同意", null);

            assertEquals(200, r.getCode());
        }
    }

    // -------------------------------------------------------------- 学生自查

    @Nested
    @DisplayName("GET /api/student/aid")
    class Mine {

        @Test
        @DisplayName("学生只见自己：size 钳到 100，offset 按页算")
        void scopedToSelf() {
            Result<?> r = service.mine(STUDENT, null, 0, 999);

            assertEquals(200, r.getCode());
            assertEquals(1, data(r).get("page"));
            assertEquals(100, data(r).get("size"));
            verify(aidMapper).listMine(eq(STUDENT), isNull(), eq(0), eq(100));
            verify(aidMapper).countMine(eq(STUDENT), isNull());
        }

        @Test
        @DisplayName("状态筛选白名单之外的取值被拒")
        void badStatus() {
            Result<?> r = service.mine(STUDENT, "banana", 1, 20);

            assertEquals(400, r.getCode());
            assertEquals("状态筛选不正确", r.getMessage());
            verify(aidMapper, never()).listMine(any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("状态筛选透传 + 返回体带 stats")
        void statusFilterAndStats() {
            when(aidMapper.countGroupByStatus(eq(STUDENT), isNull()))
                    .thenReturn(Collections.singletonList(row("status", "pending", "cnt", 2)));

            Result<?> r = service.mine(STUDENT, "pending", 2, 20);

            assertEquals(200, r.getCode());
            assertEquals(2, stats(r).get("pending"));
            assertEquals(2, stats(r).get("total"));
            verify(aidMapper).listMine(eq(STUDENT), eq("pending"), eq(20), eq(20));
        }
    }

    // ------------------------------------------------------------------ 汇总

    @Nested
    @DisplayName("资助态势 statsOf")
    class Stats {

        @Test
        @DisplayName("四个状态 + total 都在，countGroupByStatus 的 status/cnt 键被正确读入")
        void counts() {
            when(aidMapper.countGroupByStatus(any(), any())).thenReturn(Arrays.asList(
                    row("status", "pending", "cnt", 2),
                    row("status", "approved", "cnt", 1L),
                    row("status", "rejected", "cnt", 1)));

            Map<String, Object> s = service.statsOf(null, CHE_WU);

            assertEquals(2, s.get("pending"));
            assertEquals(1, s.get("approved"));
            assertEquals(1, s.get("rejected"));
            assertEquals(0, s.get("cancelled"));
            assertEquals(4, s.get("total"));
        }

        @Test
        @DisplayName("未知状态只进 total，不新增键")
        void unknownStatus() {
            when(aidMapper.countGroupByStatus(any(), any()))
                    .thenReturn(Collections.singletonList(row("status", "banana", "cnt", 3)));

            Map<String, Object> s = service.statsOf(null, null);

            assertEquals(0, s.get("pending"));
            assertEquals(3, s.get("total"));
            assertEquals(5, s.size());
        }

        @Test
        @DisplayName("没有申请时各状态为 0")
        void empty() {
            Map<String, Object> s = service.statsOf(STUDENT, null);

            assertEquals(0, s.get("pending"));
            assertEquals(0, s.get("approved"));
            assertEquals(0, s.get("rejected"));
            assertEquals(0, s.get("cancelled"));
            assertEquals(0, s.get("total"));
        }
    }

    // --------------------------------------------------------------- SQL 分工

    @Nested
    @DisplayName("mapper 分工：P5 读路径排除 need 行，P11 语句保留 IN ('need','困难认定')")
    class MapperScope {

        @Test
        @DisplayName("P5 列表/计数不含 need 行：五条语句的 WHERE 都带 aid_type != 'need'（XML 里不能写裸 '<'）")
        void p5StatementsExcludeNeedRows() throws IOException {
            String xml = mapperXml();

            for (String id : new String[]{"listMine", "countMine", "listInbox", "countInbox", "countGroupByStatus"}) {
                assertTrue(statement(xml, id).contains("d.aid_type != 'need'"),
                        id + " 的 WHERE 应排除 aid_type='need' 的认定行");
            }

            for (String id : new String[]{"listMineReview", "countCertApplications", "listPublicity", "listRecheck"}) {
                assertTrue(statement(xml, id).contains("IN ('need', '困难认定')"),
                        id + " 是 P11 语句，aid_type IN ('need','困难认定') 不能被改掉");
            }
            assertTrue(statement(xml, "Review_Filters").contains("IN ('need', '困难认定')"),
                    "Review_Filters 是 P11 共用筛选片段，必须保留 IN ('need','困难认定')");
        }

        /**
         * 字符串断言查不出 XML 语法错：`AND d.aid_type &lt;&gt; 'need'` 这种裸 '<' 在文本层面完全
         * “看着对”，但 MyBatis 启动时 parse 直接 {@code SAXParseException}，应用起不来（本轮真实事故）。
         * 所以这里对 classpath 上**全部** mapper XML 做一次良构性解析，把这类错钉在单测里。
         */
        @Test
        @DisplayName("所有 mapper XML 都是良构 XML（裸 '<' 会让应用启动时 parse 失败，字符串断言查不出）")
        void allMapperXmlWellFormed() throws Exception {
            URL dir = AidServiceTest.class.getResource("/mapper");
            assertNotNull(dir, "找不到 /mapper 目录（应随 src/main/resources 进 target/classes）");
            File[] files = new File(dir.toURI()).listFiles((d, n) -> n.endsWith(".xml"));
            assertNotNull(files, "/mapper 下没有 xml");
            assertTrue(files.length >= 17, "/mapper 下 mapper XML 数量偏少：" + files.length);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setValidating(false);
            factory.setNamespaceAware(false);
            try {
                // 离线内网 + 无法访问 mybatis 官方 DTD：禁止拉外部 DTD，用空 resolver 兜底
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {
                // 解析器不支持该特性时靠下面的空 EntityResolver 兜底
            }

            List<String> bad = new ArrayList<>();
            for (File f : files) {
                try {
                    javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
                    builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
                    builder.parse(f);
                } catch (Exception e) {
                    bad.add(f.getName() + " -> " + e.getMessage());
                }
            }
            assertTrue(bad.isEmpty(), "以下 mapper XML 不是良构 XML（应用启动会 parse 失败）：" + bad);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 读 classpath 上的 mapper XML（src/main/resources/mapper 会编译到 target/classes/mapper）。 */
    private static String mapperXml() throws IOException {
        try (InputStream in = AidServiceTest.class.getResourceAsStream("/mapper/AidApplicationMapper.xml")) {
            assertNotNull(in, "找不到 /mapper/AidApplicationMapper.xml（应随 src/main/resources 进 target/classes）");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 截出 id="X" 到最近一个闭合标签之间的语句原文。 */
    private static String statement(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "mapper 里找不到语句 id=" + id);
        int end = xml.length();
        for (String close : new String[]{"</select>", "</update>", "</insert>", "</sql>"}) {
            int index = xml.indexOf(close, start);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return xml.substring(start, end);
    }
}
