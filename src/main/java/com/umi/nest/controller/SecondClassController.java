package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.CommunityService;
import com.umi.nest.service.SecondClassService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.Map;

/**
 * 第二课堂成绩单（学工 P18）：/api/second-class/**，共 21 条方法级路由。
 *
 * <p>学生 7 + 辅导员/管理员审核 5 + 管理端配置 7 + 健康检查 1 + 导出 1 = 21。
 * 口径见 {@code target/p18-spec.md} §3–§5 与《学工平台功能对齐说明》S26（转载非一手，无量值）/ S27
 * （校内是否实施未能核实 ⇒ 板块与换算全部可配置、未设置即 null）。</p>
 *
 * <p>返回口径：除 /export（CSV 字节流，失败时回该状态码且空 body）外，所有接口一律
 * HTTP 200 + {code,message,data}；业务失败用 Result.error（默认 400，未登录 401、越权 403、不存在 404），
 * message 是可直接展示给学校的中文句子。</p>
 *
 * <p>登录态沿用会话键 userId / role（与 PartyController、CommunityController、ClubController 一致）。
 * 权限：认定、板块与活动维护——仅 admin；初审、审核列表、统计、导出——teacher / admin；
 * 申报、撤回、改后重交、我的成绩单、可见活动与板块——任何已登录用户（学生自助）。</p>
 */
@RestController("secondClassController")
@RequestMapping("/api/second-class")
public class SecondClassController {

    @Autowired
    private SecondClassService secondClassService;

    // =========================================================================
    // 1. 学生自助（7 条）
    // =========================================================================

    /** 1. 启用板块（GET /api/second-class/categories）。 */
    @GetMapping("/categories")
    public Result<?> listCategories(HttpSession session) {
        return secondClassService.listCategories(userId(session));
    }

    /** 2. 可申报活动（GET /api/second-class/activities?categoryId=&keyword=&page=&size=）。只回已发布。 */
    @GetMapping("/activities")
    public Result<?> listActivities(HttpSession session,
                                    @RequestParam(name = "categoryId", required = false) Long categoryId,
                                    @RequestParam(name = "keyword", required = false) String keyword,
                                    @RequestParam(name = "page", required = false) Integer page,
                                    @RequestParam(name = "size", required = false) Integer size) {
        return secondClassService.listActivities(userId(session), categoryId, keyword, page, size);
    }

    /** 3. 我的申报（GET /api/second-class/my?year=&status=&page=&size=）。 */
    @GetMapping("/my")
    public Result<?> listMine(HttpSession session,
                              @RequestParam(name = "year", required = false) Integer year,
                              @RequestParam(name = "status", required = false) String status,
                              @RequestParam(name = "page", required = false) Integer page,
                              @RequestParam(name = "size", required = false) Integer size) {
        return secondClassService.listMine(userId(session), year, status, page, size);
    }

    /** 4. 我的第二课堂成绩单（GET /api/second-class/summary?year=）。按板块汇总 + 合计 + 上限提示。 */
    @GetMapping("/summary")
    public Result<?> summary(HttpSession session,
                             @RequestParam(name = "year", required = false) Integer year) {
        return secondClassService.summary(userId(session), year);
    }

    /** 5. 申报（POST /api/second-class/records）。挂活动（activityId）或自报（categoryId + title + evidenceUrl）。 */
    @PostMapping("/records")
    public Result<?> createRecord(HttpSession session,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.createRecord(userId(session), body);
    }

    /** 6. 改后退回重交（PUT /api/second-class/records/{id}）。只对 returned 生效。 */
    @PutMapping("/records/{id}")
    public Result<?> updateRecord(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.updateRecord(userId(session), id, body);
    }

    /** 7. 撤回（POST /api/second-class/records/{id}/withdraw）。只对本人 submitted 生效。 */
    @PostMapping("/records/{id}/withdraw")
    public Result<?> withdraw(HttpSession session, @PathVariable("id") Long id) {
        return secondClassService.withdraw(userId(session), id);
    }

    // =========================================================================
    // 2. 辅导员初审 / 管理员认定（5 条）
    // =========================================================================

    /** 8. 审核列表（GET /api/second-class/review）。按学生档案辅导员范围过滤。 */
    @GetMapping("/review")
    public Result<?> reviewList(HttpSession session,
                                @RequestParam(name = "status", required = false) String status,
                                @RequestParam(name = "categoryId", required = false) Long categoryId,
                                @RequestParam(name = "keyword", required = false) String keyword,
                                @RequestParam(name = "page", required = false) Integer page,
                                @RequestParam(name = "size", required = false) Integer size) {
        return secondClassService.reviewList(userId(session), role(session), status, categoryId, keyword, page, size);
    }

    /** 9. 审核统计（GET /api/second-class/review/stats）。按范围返回各状态条数。 */
    @GetMapping("/review/stats")
    public Result<?> reviewStats(HttpSession session) {
        return secondClassService.reviewStats(userId(session), role(session));
    }

    /** 10. 辅导员初审（POST /api/second-class/records/{id}/counselor-review）。body {action: approve|return, reason}。 */
    @PostMapping("/records/{id}/counselor-review")
    public Result<?> counselorReview(HttpSession session, @PathVariable("id") Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.counselorReview(userId(session), role(session), id, body);
    }

    /** 11. 管理员认定（POST /api/second-class/records/{id}/confirm）。body {action: confirm|reject, reason?, credit?}。 */
    @PostMapping("/records/{id}/confirm")
    public Result<?> confirm(HttpSession session, @PathVariable("id") Long id,
                             @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.confirm(userId(session), role(session), id, body);
    }

    /**
     * 12. 申报导出（GET /api/second-class/export?year=&status=）。
     * CSV 字节流：UTF-8 带 BOM、text/csv;charset=UTF-8、文件名 second-class-yyyyMMdd.csv。
     * 未登录或非 staff → 403 且空 body。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session,
                                         @RequestParam(name = "year", required = false) Integer year,
                                         @RequestParam(name = "status", required = false) String status) {
        CommunityService.CsvExport out = secondClassService.exportCsv(userId(session), role(session), year, status);
        if (out.errorCode != null) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // =========================================================================
    // 3. 管理端配置（7 条）
    // =========================================================================

    /** 13. 板块台账（GET /api/second-class/admin/categories）。含停用；**仅 admin**。 */
    @GetMapping("/admin/categories")
    public Result<?> adminListCategories(HttpSession session) {
        return secondClassService.adminListCategories(userId(session), role(session));
    }

    /** 14. 新增板块（POST /api/second-class/admin/categories）。三列口径可空 = 学校未设置。 */
    @PostMapping("/admin/categories")
    public Result<?> createCategory(HttpSession session,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.createCategory(userId(session), role(session), body);
    }

    /** 15. 修改板块（PUT /api/second-class/admin/categories/{id}）。三列口径整体覆盖（传 null 即清空为未设置）。 */
    @PutMapping("/admin/categories/{id}")
    public Result<?> updateCategory(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.updateCategory(userId(session), role(session), id, body);
    }

    /** 16. 活动台账（GET /api/second-class/admin/activities?status=&categoryId=&keyword=&page=&size=）。staff 可看。 */
    @GetMapping("/admin/activities")
    public Result<?> adminListActivities(HttpSession session,
                                         @RequestParam(name = "status", required = false) String status,
                                         @RequestParam(name = "categoryId", required = false) Long categoryId,
                                         @RequestParam(name = "keyword", required = false) String keyword,
                                         @RequestParam(name = "page", required = false) Integer page,
                                         @RequestParam(name = "size", required = false) Integer size) {
        return secondClassService.adminListActivities(userId(session), role(session), status, categoryId,
                keyword, page, size);
    }

    /** 17. 新建活动（POST /api/second-class/admin/activities）。publish=true 直接发布，否则存草稿。 */
    @PostMapping("/admin/activities")
    public Result<?> createActivity(HttpSession session,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.createActivity(userId(session), role(session), body);
    }

    /** 18. 修改活动（PUT /api/second-class/admin/activities/{id}）。只有草稿可改。 */
    @PutMapping("/admin/activities/{id}")
    public Result<?> updateActivity(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.updateActivity(userId(session), role(session), id, body);
    }

    /** 19. 活动状态流转（POST /api/second-class/admin/activities/{id}/status）。action publish|close。 */
    @PostMapping("/admin/activities/{id}/status")
    public Result<?> changeActivityStatus(HttpSession session, @PathVariable("id") Long id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return secondClassService.changeActivityStatus(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 4. 详情与健康检查
    // =========================================================================

    /** 20. 申报详情（GET /api/second-class/records/{id}）。本人 / 范围内员工 / 管理员。 */
    @GetMapping("/records/{id}")
    public Result<?> recordDetail(HttpSession session, @PathVariable("id") Long id) {
        return secondClassService.detail(userId(session), role(session), id);
    }

    /** 21. 健康检查（GET /api/second-class/health）。列出本域路由，便于上线核对。 */
    @GetMapping("/health")
    public Result<?> health() {
        return Result.success(Map.of(
                "module", "second-class",
                "routes", List.of(
                        "GET /api/second-class/categories",
                        "GET /api/second-class/activities",
                        "GET /api/second-class/my",
                        "GET /api/second-class/summary",
                        "POST /api/second-class/records",
                        "PUT /api/second-class/records/{id}",
                        "POST /api/second-class/records/{id}/withdraw",
                        "POST /api/second-class/records/{id}/counselor-review",
                        "POST /api/second-class/records/{id}/confirm",
                        "GET /api/second-class/records/{id}",
                        "GET /api/second-class/review",
                        "GET /api/second-class/review/stats",
                        "GET /api/second-class/export",
                        "GET /api/second-class/admin/categories",
                        "POST /api/second-class/admin/categories",
                        "PUT /api/second-class/admin/categories/{id}",
                        "GET /api/second-class/admin/activities",
                        "POST /api/second-class/admin/activities",
                        "PUT /api/second-class/admin/activities/{id}",
                        "POST /api/second-class/admin/activities/{id}/status"),
                "policy", SecondClassService.policyNote()));
    }

    // =========================================================================
    // 会话工具（与 ClubController / CommunityController 一致）
    // =========================================================================

    private static Long userId(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("userId");
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String role(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("role");
        return value == null ? null : String.valueOf(value);
    }
}
