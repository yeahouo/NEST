package com.umi.nest.dorm.web;

import com.umi.nest.common.Result;
import com.umi.nest.dorm.service.AppealTrialService;
import com.umi.nest.dorm.service.DormService;
import com.umi.nest.dorm.service.MissingnessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿舍域 REST 接口。
 *
 * <p>只做参数搬运，业务全在 {@link DormService}。异常交给既有的
 * {@code GlobalExceptionHandler} 统一包装成 {@code Result.error}。
 *
 * <p>路径设计原则：<b>写操作全部走 POST</b>，读操作是 GET 且可重复调用；
 * 批次（batch）是"一次分配运行"的快照，所有查询都挂在 {@code /batches/{id}} 下，
 * 这样"我看到的这个结果属于哪次运行"永远不用猜。
 */
@RestController
@RequestMapping("/api/dorm")
public class DormController {

    private final DormService service;
    private final AppealTrialService trialService;
    private final MissingnessService missingnessService;

    public DormController(DormService service, AppealTrialService trialService,
                          MissingnessService missingnessService) {
        this.service = service;
        this.trialService = trialService;
        this.missingnessService = missingnessService;
    }

    // ---------------- 健康检查 ----------------

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", "dorm");
        m.put("status", "UP");
        m.put("endpoints", List.of(
                "GET  /api/dorm/summary",
                "POST /api/dorm/data/synthesize",
                "POST /api/dorm/data/import",
                "POST /api/dorm/data/preflight",
                "POST /api/dorm/data/clear",
                "POST /api/dorm/assign",
                "GET  /api/dorm/batches",
                "GET  /api/dorm/batches/{id}",
                "GET  /api/dorm/batches/{id}/assignments?roomId=",
                "GET  /api/dorm/batches/{id}/rooms",
                "GET  /api/dorm/batches/{id}/explain/{studentId}",
                "GET  /api/dorm/batches/{id}/room/{roomId}",
                "GET  /api/dorm/batches/{id}/audit",
                "GET  /api/dorm/missingness/summary?batchId=",
                "GET  /api/dorm/missingness/priority?limit=",
                "POST /api/dorm/batches/{id}/appeal",
                "GET  /api/dorm/batches/{id}/appeals",
                "POST /api/dorm/appeals/{appealId}/apply",
                "POST /api/dorm/batches/{id}/appeals/{appealId}/cross",
                "GET  /api/dorm/appeals/cross/{jobId}"));
        return Result.success(m);
    }

    // ---------------- 数据 ----------------

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.success(service.summary());
    }

    /** 生成带隐真值的合成数据并入库（演示与实验两用）。 */
    @PostMapping("/data/synthesize")
    public Result<Map<String, Object>> synthesize(@RequestBody(required = false) DormRequests.Synth req) {
        return Result.success(service.synthesize(req == null ? new DormRequests.Synth(
                null, null, null, null, null, null, null, null) : req));
    }

    /** 导入真实学生与房间数据（只写观测值，不写真值）。 */
    @PostMapping("/data/import")
    public Result<Map<String, Object>> importData(@RequestBody DormRequests.ImportData req) {
        return Result.success(service.importData(req));
    }

    /**
     * 只体检、不导入。真实名单一定会有脏数据，先在这里看清楚再决定要不要写库。
     *
     * <p>同一个请求体，返回 {@code ok / errors / warnings / issues / markdown}。
     * {@code ok=false} 时 {@code POST /data/import} 一定会被拒绝并给出同样的原因。
     */
    @PostMapping("/data/preflight")
    public Result<Map<String, Object>> preflight(@RequestBody DormRequests.ImportData req) {
        return Result.success(service.preflight(req));
    }

    @PostMapping("/data/clear")
    public Result<Map<String, Object>> clear() {
        return Result.success(service.clear());
    }

    // ---------------- 分配 ----------------

    @PostMapping("/assign")
    public Result<Map<String, Object>> assign(@RequestBody(required = false) DormRequests.Assign req) {
        return Result.success(service.assign(req == null ? new DormRequests.Assign(null, null, null) : req));
    }

    @GetMapping("/batches")
    public Result<List<Map<String, Object>>> batches(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(service.batches(limit));
    }

    @GetMapping("/batches/{id}")
    public Result<Map<String, Object>> batch(@PathVariable long id) {
        return Result.success(service.batch(id));
    }

    @GetMapping("/batches/{id}/assignments")
    public Result<List<Map<String, Object>>> assignments(@PathVariable long id,
                                                         @RequestParam(required = false) Long roomId) {
        return Result.success(service.assignments(id, roomId));
    }

    @GetMapping("/batches/{id}/rooms")
    public Result<List<Map<String, Object>>> rooms(@PathVariable long id) {
        Map<String, Object> b = service.batch(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) b.get("rooms");
        return Result.success(rooms);
    }

    // ---------------- 解释 ----------------

    /** 四维解释：优先读分配时预算好的，没有就现场算这一个学生。 */
    @GetMapping("/batches/{id}/explain/{studentId}")
    public Result<Map<String, Object>> explain(@PathVariable long id, @PathVariable long studentId) {
        return Result.success(service.explain(id, studentId));
    }

    /** 房间视角：住谁 + 房内每对室友的相容度分解。 */
    @GetMapping("/batches/{id}/room/{roomId}")
    public Result<Map<String, Object>> roomView(@PathVariable long id, @PathVariable long roomId) {
        return Result.success(service.roomView(id, roomId));
    }

    /** 公平性审计：按专业大类 / 年级分组的平均满意度。 */
    @GetMapping("/batches/{id}/audit")
    public Result<List<Map<String, Object>>> audit(@PathVariable long id) {
        return Result.success(service.audit(id));
    }

    // ---------------- 缺失感知决策支持（MAA） ----------------

    /**
     * 可评估性报告：这份分配的相容度有多少证据撑着。
     *
     * <p>不传 {@code batchId} 就用最近一次分配。没有分配结果时返回 {@code pairs=0}
     * 并在 {@code reasons} 里说明，而不是报错——管理端「刚导完数据还没跑分配」是正常状态。
     */
    @GetMapping("/missingness/summary")
    public Result<Map<String, Object>> missingnessSummary(@RequestParam(required = false) Long batchId) {
        return Result.success(missingnessService.summary(batchId));
    }

    /**
     * 催填优先级：同样的人力，先问谁、问哪道题。
     *
     * <p>排序依据是「补齐这一格后期望相容度增益」的估计值（口径见
     * {@code docs/创新点-不确定感知的宿舍分配.md} §2 H3）。默认 20 条，最多 100 条。
     */
    @GetMapping("/missingness/priority")
    public Result<Map<String, Object>> missingnessPriority(
            @RequestParam(required = false) Integer limit) {
        return Result.success(missingnessService.priority(limit));
    }

    // ---------------- 申诉 ----------------

    @PostMapping("/batches/{id}/appeal")
    public Result<Map<String, Object>> appeal(@PathVariable long id, @RequestBody DormRequests.Appeal req) {
        return Result.success(service.appeal(id, req));
    }

    @GetMapping("/batches/{id}/appeals")
    public Result<List<Map<String, Object>>> appeals(@PathVariable long id) {
        return Result.success(service.appeals(id));
    }

    @PostMapping("/appeals/{appealId}/apply")
    public Result<Map<String, Object>> applyAppeal(@PathVariable long appealId,
                                                   @RequestBody(required = false) DormRequests.Apply req) {
        String by = req == null || req.handledBy() == null ? "admin" : req.handledBy();
        return Result.success(service.applyAppeal(appealId, by));
    }

    // ---------------- 申诉的跨块试算（第三级，异步） ----------------

    /**
     * 提交一次跨块试算。
     *
     * <p>这一级要把「同专业大类集中」这类政策关掉再重新分块，规模是「同性别的整个年级」，
     * 所以要跑几十秒，只能在后台线程里做。返回的是一个任务快照，用返回的 {@code jobId}
     * 轮询 {@code GET /api/dorm/appeals/cross/{jobId}}；同一条申诉重复提交会复用正在跑的那个任务。
     */
    @PostMapping("/batches/{id}/appeals/{appealId}/cross")
    public Result<Map<String, Object>> crossTrial(@PathVariable long id, @PathVariable long appealId) {
        return Result.success(trialService.submit(id, appealId));
    }

    /** 查询跨块试算的进度。任务不在内存里（重启过/被淘汰）时返回 state=UNKNOWN 并指路到 cross_json。 */
    @GetMapping("/appeals/cross/{jobId}")
    public Result<Map<String, Object>> crossTrialStatus(@PathVariable long jobId) {
        return Result.success(trialService.status(jobId));
    }
}
