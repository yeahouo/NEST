package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.PsycheAppointmentService;
import com.umi.nest.service.PsycheService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 学工 P14 心理健康接口（量表 / 普查批次 / 名单与作答 / 结果回填 / 关注台账 / 预约咨询 / 转介 / 统计导出）。
 *
 * <p>HTTP 状态码一律 200，业务结果放在 {@code {code,message,data}} 里：401 未登录、403 越权、
 * 400 参数非法（中文文案）、404 资源不存在；唯一的例外是 /export（CSV 字节流，失败时回该状态码，
 * 与 RankController / AidReviewController 的导出口径一致）。会话里的 userId/role 由本类解包后传进 service。</p>
 *
 * <p>保密（S16 保密性原则）：列表接口一律在服务层脱敏（姓名保留姓、学号只留后四位）；
 * 关注台账与转介只给 admin 与该生辅导员；学生只看自己。学生端不下发结果等级原文与中心内部备注。</p>
 */
// 显式 bean 名：com.umi.nest.dorm.web.PsycheController（宿舍分配心理特征提取，/api/dorm/psyche）与本类
// 默认 bean 名同为 psycheController，组件扫描期直接抛 ConflictingBeanDefinitionException、应用起不来（2026-10-16 踩到）。
@RestController("psycheWorkController")
@RequestMapping("/api/psyche")
public class PsycheController {

    private final PsycheService psycheService;

    private final PsycheAppointmentService appointmentService;

    public PsycheController(PsycheService psycheService, PsycheAppointmentService appointmentService) {
        this.psycheService = psycheService;
        this.appointmentService = appointmentService;
    }

    // ---------------------------------------------------------------- 量表

    /** 量表列表（登录可读）：只回启用中的量表；学生端不下发题本与阈值明细。 */
    @GetMapping("/instruments")
    public Result<?> instruments(HttpSession session) {
        return psycheService.listInstruments(userId(session), role(session));
    }

    /** 新建量表（admin）：code 唯一，重复 400。 */
    @PostMapping("/instruments")
    public Result<?> createInstrument(HttpSession session,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.createInstrument(userId(session), role(session), body);
    }

    // ---------------------------------------------------------------- 批次

    /** 批次列表（登录可读；非 admin 自动隐藏 draft）。 */
    @GetMapping("/batches")
    public Result<?> batches(HttpSession session,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword) {
        return psycheService.listBatches(userId(session), role(session), status, keyword);
    }

    /** 新建批次（admin）：新批次落到 draft。 */
    @PostMapping("/batches")
    public Result<?> createBatch(HttpSession session,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.createBatch(userId(session), role(session), body);
    }

    /** 增量修改批次（admin）：没传的字段不动；CAS 校验状态没被改过。 */
    @PutMapping("/batches/{id}")
    public Result<?> updateBatch(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.updateBatch(userId(session), role(session), id, body);
    }

    /** 发布批次（admin，CAS draft→open，写 published_at）。 */
    @PostMapping("/batches/{id}/publish")
    public Result<?> publishBatch(HttpSession session, @PathVariable Long id) {
        return psycheService.publishBatch(userId(session), role(session), id);
    }

    /** 关闭批次（admin，CAS open→closed，写 closed_at；未作答名单置 absent）。 */
    @PostMapping("/batches/{id}/close")
    public Result<?> closeBatch(HttpSession session, @PathVariable Long id) {
        return psycheService.closeBatch(userId(session), role(session), id);
    }

    // ---------------------------------------------------------------- 名单与进度

    /** 批次名单（admin/teacher，脱敏）：data = {list,total,page,size,stats}。 */
    @GetMapping("/batches/{id}/tasks")
    public Result<?> tasks(HttpSession session, @PathVariable Long id,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String className,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return psycheService.listTasks(userId(session), role(session), id, status, className, keyword, page, size);
    }

    /** 导入名单（admin/teacher）：body {studentIds:[...]} 或 {csv:"学号,姓名\n…"}。 */
    @PostMapping("/batches/{id}/tasks/import")
    public Result<?> importTasks(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.importTasks(userId(session), role(session), id, body);
    }

    /** 批次统计（admin/teacher）：只回聚合数（各状态数 / 完成率 / 班级与专业完成率 / 关注等级分布）。 */
    @GetMapping("/batches/{id}/stats")
    public Result<?> batchStats(HttpSession session, @PathVariable Long id) {
        return psycheService.batchStats(userId(session), role(session), id);
    }

    /** 抽查访谈记录（admin/teacher，teacher 只能是该生辅导员）：body {interviewed,note}。 */
    @PostMapping("/tasks/{id}/interview")
    public Result<?> interview(HttpSession session, @PathVariable Long id,
                               @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.interview(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 学生端

    /** 我的心理健康（学生）：{batches,appointments,focusAdvice}，不含 result_level 原文。 */
    @GetMapping("/my")
    public Result<?> my(HttpSession session) {
        return psycheService.my(userId(session), role(session));
    }

    /** 名单详情（学生本人 / admin / 该生辅导员）：{task,batch,instrument,answer}。 */
    @GetMapping("/tasks/{id}")
    public Result<?> taskDetail(HttpSession session, @PathVariable Long id) {
        return psycheService.taskDetail(userId(session), role(session), id);
    }

    /** 提交整卷（学生本人）：必须答完全部题目且在普查时间窗内。 */
    @PostMapping("/tasks/{id}/submit")
    public Result<?> submit(HttpSession session, @PathVariable Long id,
                            @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.submit(userId(session), role(session), id, body);
    }

    /** 结果查看（学生本人 / admin / 该生辅导员）：学生端 resultLevel 由前端控制不展示。 */
    @GetMapping("/tasks/{id}/result")
    public Result<?> taskResult(HttpSession session, @PathVariable Long id) {
        return psycheService.taskResult(userId(session), role(session), id);
    }

    /** 回填结果等级（admin/teacher，teacher 只能是该生辅导员）：normal / attention / warning。 */
    @PutMapping("/tasks/{id}/result")
    public Result<?> updateResult(HttpSession session, @PathVariable Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.updateResult(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 关注台账

    /** 关注台账列表（admin/teacher，teacher 只看名下学生）：列表脱敏。 */
    @GetMapping("/focus")
    public Result<?> focusList(HttpSession session,
                               @RequestParam(required = false) String level,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String className,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return psycheService.listFocus(userId(session), role(session), level, status, className,
                keyword, page, size);
    }

    /** 建关注台账（admin/teacher，且只能给名下学生建）。 */
    @PostMapping("/focus")
    public Result<?> createFocus(HttpSession session,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.createFocus(userId(session), role(session), body);
    }

    /** 改关注台账（admin/teacher，且只能是名下学生）：可改 level/summary/measures/counselorId/status。 */
    @PutMapping("/focus/{id}")
    public Result<?> updateFocus(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.updateFocus(userId(session), role(session), id, body);
    }

    /** 登记跟进（admin/teacher，且只能是名下学生）：写跟进流水并把台账 open → following。 */
    @PostMapping("/focus/{id}/followups")
    public Result<?> addFollowup(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.addFollowup(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 预约咨询

    /** 申请预约（学生本人）：工作日提前量 + 周一至周五 8:00-17:00 + 默认 50 分钟 + 不允许重复建。 */
    @PostMapping("/appointments")
    public Result<?> createAppointment(HttpSession session,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return appointmentService.create(userId(session), role(session), body);
    }

    /** 我的预约（学生本人）：不下发 note（中心内部备注）。 */
    @GetMapping("/appointments/my")
    public Result<?> myAppointments(HttpSession session) {
        return appointmentService.myList(userId(session), role(session));
    }

    /** 预约列表（admin；teacher 只看 counselor_id 是自己或自己名下学生）：脱敏，不含 main_issue 与 note。 */
    @GetMapping("/appointments")
    public Result<?> appointments(HttpSession session,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String mode,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return appointmentService.list(userId(session), role(session), status, mode, keyword, page, size);
    }

    /** 确认预约（admin/teacher，CAS requested→confirmed）：不足半天时成功但 message 提示 S17。 */
    @PostMapping("/appointments/{id}/confirm")
    public Result<?> confirmAppointment(HttpSession session, @PathVariable Long id) {
        return appointmentService.confirm(userId(session), role(session), id);
    }

    /** 完成预约（admin/teacher，CAS confirmed→done）。 */
    @PostMapping("/appointments/{id}/complete")
    public Result<?> completeAppointment(HttpSession session, @PathVariable Long id) {
        return appointmentService.complete(userId(session), role(session), id);
    }

    /** 取消预约（学生本人 或 admin/teacher，CAS → cancelled，写 cancel_reason）。 */
    @PostMapping("/appointments/{id}/cancel")
    public Result<?> cancelAppointment(HttpSession session, @PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return appointmentService.cancel(userId(session), role(session), id, body);
    }

    /** 续约（学生本人）：沿用原预约的 counselor_id 与 mode，新建 requested 并写 renewed_from。 */
    @PostMapping("/appointments/{id}/renew")
    public Result<?> renewAppointment(HttpSession session, @PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return appointmentService.renew(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 转介

    /** 转介列表（admin/teacher，teacher 只看名下学生）：列表脱敏。 */
    @GetMapping("/referrals")
    public Result<?> referrals(HttpSession session,
                               @RequestParam(required = false) String target,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return psycheService.listReferrals(userId(session), role(session), target, status, page, size);
    }

    /** 建转介（admin/teacher，且只能给名下学生建）：target ∈ campus / hospital / family。 */
    @PostMapping("/referrals")
    public Result<?> createReferral(HttpSession session,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.createReferral(userId(session), role(session), body);
    }

    /** 改转介（admin/teacher，且只能是名下学生）：可改 target/reason/status/followNote。 */
    @PutMapping("/referrals/{id}")
    public Result<?> updateReferral(HttpSession session, @PathVariable Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return psycheService.updateReferral(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 统计与导出

    /** 全局统计（admin/teacher）：只回聚合数（批次完成率 / 班级与专业完成率 / 关注等级分布 / 预约量与完成率）。 */
    @GetMapping("/stats")
    public Result<?> stats(HttpSession session) {
        return psycheService.stats(userId(session), role(session));
    }

    /**
     * 导出 CSV（admin/teacher）：type = tasks | focus | appointments（可选 batchId 限定批次）。
     *
     * <p>text/csv;charset=UTF-8 + UTF-8 BOM（Excel 打开不乱码）；列表脱敏；
     * appointments 不含 main_issue 全文与 note。未登录或学生调用回 403（与 P13 导出口径一致）。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) Long batchId) {
        PsycheService.CsvExport out = psycheService.export(userId(session), role(session), type, batchId);
        if (out.errorCode != null) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    // ---------------------------------------------------------------- 会话

    private static Long userId(HttpSession session) {
        Object value = session.getAttribute("userId");
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String role(HttpSession session) {
        Object value = session.getAttribute("role");
        return value == null ? null : String.valueOf(value);
    }
}
