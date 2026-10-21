package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.entity.HonorApplication;
import com.umi.nest.entity.HonorBatch;
import com.umi.nest.service.HonorService;
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

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 评奖评优在线申报（P12）接口。
 *
 * <p>统一约定（与 P5/P11 一致）：HTTP 一律 200；返回体 {@code {code,message,data}}；
 * 未登录 code=401；越权 code=403；参数/状态非法 code=400 且给中文文案。身份取自 session 的 userId / role。</p>
 *
 * <p>学生端：/api/honor/my、/api/honor/my/check、/api/honor/apply/**<br>
 * 审核端（teacher/admin）：/api/honor/review/**<br>
 * 批次维护（admin）：/api/honor/batches/**<br>
 * 公共：/api/honor/publicity（登录可读）、/api/honor/export（admin/teacher，CSV）、/api/honor/stats（admin/teacher）</p>
 *
 * <p>控制器刻意保持很薄：权限、状态机、CAS 全在 {@link HonorService} 里，
 * 这样"谁能审哪一步"就只有一处答案，不会出现页面能点、接口拒绝的两套口径。</p>
 *
 * <p>审计：/api/honor 前缀已由 {@code AuditLogService.typeOfPath} 归入 TYPE_HONOR。</p>
 */
@RestController
@RequestMapping("/api/honor")
public class HonorController {

    private final HonorService service;

    public HonorController(HonorService service) {
        this.service = service;
    }

    // =====================================================================
    // 一、批次（admin 维护，登录即可读）
    // =====================================================================

    /** 1 GET /api/honor/batches —— 登录即可读；学生/教师看不到 draft（草稿尚未发布）。 */
    @GetMapping("/batches")
    public Result batches(@RequestParam(required = false) String status,
                          @RequestParam(required = false) String keyword,
                          HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.listBatches(uid, role(session), status, keyword);
    }

    /** 2 POST /api/honor/batches —— 仅 admin；新建即草稿。 */
    @PostMapping("/batches")
    public Result createBatch(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        HonorBatch form;
        try {
            form = batchForm(body);
        } catch (DateTimeParseException e) {
            return Result.error(400, "日期格式应为 yyyy-MM-dd");
        }
        return service.createBatch(uid, role(session), form);
    }

    /** 3 PUT /api/honor/batches/{id} —— 仅 admin；增量更新（没传的字段不改）。 */
    @PutMapping("/batches/{id}")
    public Result updateBatch(@PathVariable Long id,
                              @RequestBody(required = false) Map<String, Object> body,
                              HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        HonorBatch form;
        try {
            form = batchForm(body);
        } catch (DateTimeParseException e) {
            return Result.error(400, "日期格式应为 yyyy-MM-dd");
        }
        return service.updateBatch(uid, role(session), id, form);
    }

    /** 4 POST /api/honor/batches/{id}/publish —— draft → recruiting，申报开始。 */
    @PostMapping("/batches/{id}/publish")
    public Result publishBatch(@PathVariable Long id, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.publishBatch(uid, role(session), id);
    }

    /** 5 POST /api/honor/batches/{id}/publicize —— reviewing/recruiting → publicity，写公示起止。 */
    @PostMapping("/batches/{id}/publicize")
    public Result publicizeBatch(@PathVariable Long id, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.publicizeBatch(uid, role(session), id);
    }

    /** 6 POST /api/honor/batches/{id}/finish —— publicity → finished，同时把公示中的申报单置 awarded。 */
    @PostMapping("/batches/{id}/finish")
    public Result finishBatch(@PathVariable Long id, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.finishBatch(uid, role(session), id);
    }

    // =====================================================================
    // 二、学生端
    // =====================================================================

    /** 7 GET /api/honor/my —— 仅 student；可见批次 + 我的申报单（含每一步审核意见）。 */
    @GetMapping("/my")
    public Result my(HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.myHonor(uid, role(session));
    }

    /** 8 GET /api/honor/my/check?batchId= —— 仅 student；自动核验预览（自动项 + 需人工项）。 */
    @GetMapping("/my/check")
    public Result myCheck(@RequestParam(required = false) Long batchId, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.myCheck(uid, role(session), batchId);
    }

    /** 9 POST /api/honor/apply —— 仅 student；落库即 submitted。batchId 收 body 或 query 都行。 */
    @PostMapping("/apply")
    public Result apply(@RequestParam(required = false) Long batchId,
                        @RequestBody(required = false) Map<String, Object> body,
                        HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        Long bid = batchId != null ? batchId : num(body, "batchId");
        return service.apply(uid, role(session), bid, applyForm(body));
    }

    /** 10 PUT /api/honor/apply/{id} —— 仅 student；草稿/已提交/已驳回可在申报期内改后重提。 */
    @PutMapping("/apply/{id}")
    public Result updateApply(@PathVariable Long id,
                              @RequestBody(required = false) Map<String, Object> body,
                              HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.updateApply(uid, role(session), id, applyForm(body));
    }

    /** 11 POST /api/honor/apply/{id}/withdraw —— 仅 student；submitted → draft。 */
    @PostMapping("/apply/{id}/withdraw")
    public Result withdraw(@PathVariable Long id, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.withdraw(uid, role(session), id);
    }

    // =====================================================================
    // 三、审核端（teacher / admin）
    // =====================================================================

    /** 12 GET /api/honor/review/list?node=&status=&keyword=&batchId=&page=&size= —— 待办队列。 */
    @GetMapping("/review/list")
    public Result reviewList(@RequestParam(required = false) String node,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Long batchId,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.reviewList(uid, role(session), node, status, batchId, keyword, page, size);
    }

    /** 13 GET /api/honor/review/detail/{id} —— 申报单 + 全部审核意见 + 批次参评口径。 */
    @GetMapping("/review/detail/{id}")
    public Result reviewDetail(@PathVariable Long id, HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.reviewDetail(uid, role(session), id);
    }

    /** 14 POST /api/honor/review/{id} —— body {verdict:'pass'|'reject', comment}；节点由后端按状态推导。 */
    @PostMapping("/review/{id}")
    public Result review(@PathVariable Long id,
                         @RequestBody(required = false) Map<String, Object> body,
                         HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.review(uid, role(session), id, str(body, "verdict"), str(body, "comment"));
    }

    // =====================================================================
    // 四、公共
    // =====================================================================

    /** 15 GET /api/honor/publicity?batchId= —— 必须登录；脱敏（学号只出后四位）。 */
    @GetMapping("/publicity")
    public Result publicity(@RequestParam(required = false) Long batchId, HttpSession session) {
        if (userId(session) == null) {
            return Result.error(401, "未登录");
        }
        return service.publicity(userId(session), batchId);
    }

    /**
     * 16 GET /api/honor/export?batchId= —— 仅 admin/teacher，CSV 下载。
     *
     * <p>首部写 UTF-8 BOM：Excel 不认 content-type，没有 BOM 中文表头会乱码。
     * 无权时返回裸 403（与 P11 的导出接口一致，下载场景给 JSON 反而让浏览器存成一个损坏的 csv）。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long batchId, HttpSession session) {
        String csv = service.exportCsv(userId(session), role(session), batchId);
        if (csv == null) {
            return ResponseEntity.status(403).build();
        }
        byte[] bytes = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"honor-applications.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    /** 17 GET /api/honor/stats —— 审核台计数（admin/teacher）。 */
    @GetMapping("/stats")
    public Result stats(HttpSession session) {
        Long uid = userId(session);
        if (uid == null) {
            return Result.error(401, "未登录");
        }
        return service.stats(uid, role(session));
    }

    // =====================================================================
    // 请求解析（不用 DTO，与 P5/P11 的写法保持一致）
    // =====================================================================

    private static Long userId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object id = session.getAttribute("userId");
        if (id == null) {
            return null;
        }
        if (id instanceof Number) {
            return ((Number) id).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String role(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }

    /** 批次表单 → 实体；日期解析失败上抛，由调用处统一转 400。 */
    private static HonorBatch batchForm(Map<String, Object> body) {
        HonorBatch b = new HonorBatch();
        b.setBatchName(str(body, "batchName"));
        b.setHonorType(str(body, "honorType"));
        b.setAcademicYear(str(body, "academicYear"));
        b.setEvalTerm(str(body, "evalTerm"));
        b.setQuota(integer(body, "quota"));
        b.setQuotaNote(str(body, "quotaNote"));
        b.setCriteria(str(body, "criteria"));
        b.setMaterials(str(body, "materials"));
        b.setApplyStart(date(body, "applyStart"));
        b.setApplyEnd(date(body, "applyEnd"));
        b.setReviewEnd(date(body, "reviewEnd"));
        b.setPublicityDays(integer(body, "publicityDays"));
        b.setNoticeTitle(str(body, "noticeTitle"));
        b.setNoticeUrl(str(body, "noticeUrl"));
        return b;
    }

    /** 申报表单 → 实体。 */
    private static HonorApplication applyForm(Map<String, Object> body) {
        HonorApplication a = new HonorApplication();
        a.setSelfStatement(str(body, "selfStatement"));
        a.setHonorsText(str(body, "honorsText"));
        a.setScholarshipsText(str(body, "scholarshipsText"));
        a.setPeScore(dec(body, "peScore"));
        a.setNoFailDeclared(bool(body, "noFailDeclared"));
        a.setNoViolationDeclared(bool(body, "noViolationDeclared"));
        a.setMaterialNote(str(body, "materialNote"));
        a.setContactPhone(str(body, "contactPhone"));
        return a;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long num(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Map<String, Object> body, String key) {
        Long v = num(body, key);
        return v == null ? null : v.intValue();
    }

    /** 体测分这类小数：解析不出来就当没填（服务端会按"未填报"处理，不会编造分数）。 */
    private static BigDecimal dec(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 日期：前端日期控件偶尔会带时间（yyyy-MM-dd HH:mm:ss），取前 10 位再解析。 */
    private static LocalDate date(Map<String, Object> body, String key) {
        String s = str(body, key);
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String v = s.trim();
        if (v.length() > 10) {
            v = v.substring(0, 10);
        }
        return LocalDate.parse(v);
    }

    private static Boolean bool(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
