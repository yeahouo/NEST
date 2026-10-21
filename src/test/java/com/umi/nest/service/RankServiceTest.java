package com.umi.nest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.RankBatch;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalAcademicMapper;
import com.umi.nest.mapper.EvalBonusMapper;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.RankBatchMapper;
import com.umi.nest.mapper.RankObjectionMapper;
import com.umi.nest.mapper.RankRowMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 综测明细（{@code GET /api/rank/detail/{studentId}}）的<b>数据级授权</b>回归。
 *
 * <p>这个测试类只盯一件事：P13 的排名域在列表页做了辅导员范围，明细页原来<b>只判了角色</b>
 * （是教师就放行）——任何教师猜到 userId 就能读任意学生的综测明细。修复口径是
 * 「与列表页的 {@code scopeCounselor} 完全一致」：admin 全校；有分派学生的辅导员只看名下；
 * 未分派学生的辅导员仍按全校区口径（照 AidService / HonorService 的既有约定，不另立规则）。
 *
 * <p>不测排序、等级落位与公示——那些由 {@code target\e2e-p13.mjs} 真机覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RankServiceTest {

    private static final long ADMIN = 1L;
    private static final long ZHANG_SAN = 2L;
    private static final long LI_SI = 3L;
    private static final long WANG_XM = 4L;
    private static final long LIU_SQ = 7L;
    private static final long BATCH_ID = 9001L;
    private static final String TERM = "2026-2027-1";

    @Mock
    private RankBatchMapper batchMapper;
    @Mock
    private RankRowMapper rowMapper;
    @Mock
    private RankObjectionMapper objectionMapper;
    @Mock
    private EvalAcademicMapper academicMapper;
    @Mock
    private EvalBonusMapper bonusMapper;
    @Mock
    private EvalBonusService bonusService;
    @Mock
    private StudentProfileMapper profileMapper;
    @Mock
    private EvalSummaryMapper evalSummaryMapper;

    @InjectMocks
    private RankService service;

    @BeforeEach
    void setUp() {
        // detail 路径不碰 objectMapper / @Value 注入的权重字段；需要时由各用例单独打桩。
    }

    // ---------------------------------------------------------------- 数据级授权

    @Test
    @DisplayName("未登录 → 401（先判登录，不把登录问题伪装成权限问题）")
    void detailRequiresLogin() {
        Result<?> r = service.detail(null, "teacher", BATCH_ID, WANG_XM);
        assertEquals(401, r.getCode());
        assertTrue(r.getMessage().contains("未登录"), r.getMessage());
    }

    @Test
    @DisplayName("学生角色 → 403「无权限查看综测明细」")
    void detailRejectsStudentRole() {
        Result<?> r = service.detail(WANG_XM, "student", BATCH_ID, WANG_XM);
        assertEquals(403, r.getCode());
        assertTrue(r.getMessage().contains("无权限"), r.getMessage());
    }

    @Test
    @DisplayName("跨辅导员读明细 → 403（本轮修复的越权口子）")
    void detailCrossCounselorForbidden() {
        stubBatch();
        // 学生 7 号的辅导员是张三（2），李四（3）不该看到
        when(profileMapper.findByUserId(LIU_SQ)).thenReturn(profileOf(LIU_SQ, ZHANG_SAN));
        when(profileMapper.countByCounselor(LI_SI)).thenReturn(12);

        Result<?> r = service.detail(LI_SI, "teacher", BATCH_ID, LIU_SQ);

        assertEquals(403, r.getCode());
        assertTrue(r.getMessage().contains("该学生不在你的名下"), r.getMessage());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("名下学生 → 200（同一批次、同一 term 取到明细）")
    void detailOwnStudentOk() {
        stubBatch();
        when(profileMapper.findByUserId(WANG_XM)).thenReturn(profileOf(WANG_XM, LI_SI));
        when(profileMapper.countByCounselor(LI_SI)).thenReturn(12);
        when(rowMapper.findByBatchAndStudent(BATCH_ID, WANG_XM)).thenReturn(null);
        when(bonusMapper.listMine(WANG_XM, TERM)).thenReturn(Collections.emptyList());
        when(academicMapper.findByStudentTerm(WANG_XM, TERM)).thenReturn(null);

        Result<?> r = service.detail(LI_SI, "teacher", BATCH_ID, WANG_XM);

        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
    }

    @Test
    @DisplayName("admin → 200（全校口径，不看 counselor_id）")
    void detailAdminSeesAll() {
        stubBatch();
        when(profileMapper.findByUserId(LIU_SQ)).thenReturn(profileOf(LIU_SQ, ZHANG_SAN));
        when(bonusMapper.listMine(LIU_SQ, TERM)).thenReturn(Collections.emptyList());
        when(academicMapper.findByStudentTerm(LIU_SQ, TERM)).thenReturn(null);

        Result<?> r = service.detail(ADMIN, "admin", BATCH_ID, LIU_SQ);

        assertEquals(200, r.getCode());
    }

    @Test
    @DisplayName("未分派学生的辅导员 → 仍按全校区口径放行（与列表页一致，不另立规则）")
    void detailUnassignedCounselorSeesAll() {
        stubBatch();
        when(profileMapper.findByUserId(LIU_SQ)).thenReturn(profileOf(LIU_SQ, ZHANG_SAN));
        when(profileMapper.countByCounselor(LI_SI)).thenReturn(0);
        when(bonusMapper.listMine(LIU_SQ, TERM)).thenReturn(Collections.emptyList());
        when(academicMapper.findByStudentTerm(LIU_SQ, TERM)).thenReturn(null);

        Result<?> r = service.detail(LI_SI, "teacher", BATCH_ID, LIU_SQ);

        assertEquals(200, r.getCode());
    }

    @Test
    @DisplayName("学生不存在 → 404（不是 200 空行，也不是 403 掩盖事实）")
    void detailUnknownStudent404() {
        stubBatch();
        when(profileMapper.findByUserId(9999L)).thenReturn(null);
        when(profileMapper.countByCounselor(ADMIN)).thenReturn(0);

        Result<?> r = service.detail(ADMIN, "admin", BATCH_ID, 9999L);

        assertEquals(404, r.getCode());
        assertTrue(r.getMessage().contains("学生档案不存在"), r.getMessage());
    }

    @Test
    @DisplayName("缺批次 / 缺学生 → 400，且批次不存在是 404")
    void detailMissingParams() {
        assertEquals(400, service.detail(ADMIN, "admin", null, WANG_XM).getCode());
        assertEquals(400, service.detail(ADMIN, "admin", BATCH_ID, null).getCode());

        when(batchMapper.findById(anyLong())).thenReturn(null);
        assertEquals(404, service.detail(ADMIN, "admin", 4321L, WANG_XM).getCode());
    }

    // ---------------------------------------------------------------- 桩

    private void stubBatch() {
        RankBatch batch = new RankBatch();
        batch.setId(BATCH_ID);
        batch.setAcademicTerm(TERM);
        batch.setStatus(RankService.B_COMPUTED);
        when(batchMapper.findById(BATCH_ID)).thenReturn(batch);
        // detail 里没有直接读 term 参数，但 fillText 需要 bonus 列表，这里统一给空表
        when(bonusMapper.listMine(anyLong(), anyString())).thenReturn(Collections.emptyList());
        when(academicMapper.findByStudentTerm(anyLong(), anyString())).thenReturn(null);
        when(rowMapper.findByBatchAndStudent(anyLong(), anyLong())).thenReturn(null);
    }

    private static StudentProfile profileOf(long userId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setUserId(userId);
        p.setName("学生" + userId);
        p.setStudentNo(String.format("S%06d", userId));
        p.setCounselorId(counselorId);
        return p;
    }
}
