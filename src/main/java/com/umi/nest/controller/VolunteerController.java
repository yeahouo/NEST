package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.CommunityService;
import com.umi.nest.service.VolunteerService;
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
import java.util.Map;

/**
 * 学工平台 P17 志愿服务模块控制器（S21），前缀 {@code /api/volunteer}，共 26 条路由。
 *
 * <p>分层与既有模块一致：控制器只做会话取值与参数透传，权限/状态机/CAS/审计全在
 * {@link VolunteerService}，返回统一 {@link Result}（成功 code=200、校验失败 code=400 且 message 为中文）。</p>
 *
 * <p>路由顺序：字面量路由（{@code /records/mine}、{@code /summary/mine}、{@code /stats/export}）
 * 一律声明在同段 {@code /{id}} 之前，避免被路径变量抢占。</p>
 *
 * <p>bean 名显式声明为 {@code volunteerController}，防止与同名简单类冲突引发启动期
 * {@code ConflictingBeanDefinitionException}（见 {@code SpringBeanNameCollisionTest}）。</p>
 */
@RestController("volunteerController")
@RequestMapping("/api/volunteer")
public class VolunteerController {

    @Autowired
    private VolunteerService volunteerService;

    // ==================== 项目立项 ====================

    /** GET /api/volunteer/projects：立项列表（admin 全量 / teacher 名下 / student 已批准）。 */
    @GetMapping("/projects")
    public Result<?> listProjects(HttpSession session,
                                  @RequestParam(name = "status", required = false) String status,
                                  @RequestParam(name = "category", required = false) String category,
                                  @RequestParam(name = "organizerType", required = false) String organizerType,
                                  @RequestParam(name = "keyword", required = false) String keyword,
                                  @RequestParam(name = "page", required = false, defaultValue = "1") int page,
                                  @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return volunteerService.listProjects(userId(session), role(session), status, category,
                organizerType, keyword, page, size);
    }

    /** GET /api/volunteer/projects/{id}：项目详情。 */
    @GetMapping("/projects/{id}")
    public Result<?> projectDetail(HttpSession session, @PathVariable("id") Long id) {
        return volunteerService.projectDetail(userId(session), role(session), id);
    }

    /** POST /api/volunteer/projects：提报立项（默认 draft，submit=true → pending）。 */
    @PostMapping("/projects")
    public Result<?> createProject(HttpSession session,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.createProject(userId(session), role(session), body);
    }

    /** PUT /api/volunteer/projects/{id}：修改立项（仅 draft/rejected；submit=true 或 status=pending → pending）。 */
    @PutMapping("/projects/{id}")
    public Result<?> updateProject(HttpSession session, @PathVariable("id") Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.updateProject(userId(session), role(session), id, body);
    }

    /** POST /api/volunteer/projects/{id}/review：立项审批（仅 admin）。 */
    @PostMapping("/projects/{id}/review")
    public Result<?> reviewProject(HttpSession session, @PathVariable("id") Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.reviewProject(userId(session), role(session), id, body);
    }

    /** POST /api/volunteer/projects/{id}/enroll：报名（仅 approved 项目，成功即 confirmed）。 */
    @PostMapping("/projects/{id}/enroll")
    public Result<?> enrollProject(HttpSession session, @PathVariable("id") Long id) {
        return volunteerService.enrollProject(userId(session), role(session), id);
    }

    // ==================== 报名与出勤 ====================

    /** GET /api/volunteer/enrollments：报名管理列表（admin / teacher）。 */
    @GetMapping("/enrollments")
    public Result<?> listEnrollments(HttpSession session,
                                     @RequestParam(name = "projectId", required = false) Long projectId,
                                     @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "keyword", required = false) String keyword,
                                     @RequestParam(name = "page", required = false, defaultValue = "1") int page,
                                     @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return volunteerService.listEnrollments(userId(session), role(session), projectId, status,
                keyword, page, size);
    }

    /** POST /api/volunteer/enrollments/{id}/cancel：取消报名（本人 / admin）。 */
    @PostMapping("/enrollments/{id}/cancel")
    public Result<?> cancelEnrollment(HttpSession session, @PathVariable("id") Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.cancelEnrollment(userId(session), role(session), id, body);
    }

    /** POST /api/volunteer/enrollments/{id}/attendance：出勤登记（仅 admin，前置 confirmed）。 */
    @PostMapping("/enrollments/{id}/attendance")
    public Result<?> markAttendance(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.markAttendance(userId(session), role(session), id, body);
    }

    // ==================== 时长记录 ====================

    /** GET /api/volunteer/records/mine：我的时长记录（字面量路由，必须声明在 /{id} 之前）。 */
    @GetMapping("/records/mine")
    public Result<?> listMyRecords(HttpSession session,
                                   @RequestParam(name = "projectId", required = false) Long projectId,
                                   @RequestParam(name = "status", required = false) String status,
                                   @RequestParam(name = "hourType", required = false) String hourType,
                                   @RequestParam(name = "source", required = false) String source,
                                   @RequestParam(name = "startDate", required = false) String startDate,
                                   @RequestParam(name = "endDate", required = false) String endDate,
                                   @RequestParam(name = "keyword", required = false) String keyword,
                                   @RequestParam(name = "page", required = false, defaultValue = "1") int page,
                                   @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return volunteerService.listMyRecords(userId(session), role(session), projectId, status, hourType,
                source, startDate, endDate, keyword, page, size);
    }

    /** GET /api/volunteer/records：时长记录管理列表（admin / teacher）。 */
    @GetMapping("/records")
    public Result<?> listRecords(HttpSession session,
                                 @RequestParam(name = "projectId", required = false) Long projectId,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "hourType", required = false) String hourType,
                                 @RequestParam(name = "source", required = false) String source,
                                 @RequestParam(name = "startDate", required = false) String startDate,
                                 @RequestParam(name = "endDate", required = false) String endDate,
                                 @RequestParam(name = "keyword", required = false) String keyword,
                                 @RequestParam(name = "page", required = false, defaultValue = "1") int page,
                                 @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return volunteerService.listRecords(userId(session), role(session), projectId, status, hourType,
                source, startDate, endDate, keyword, page, size);
    }

    /** GET /api/volunteer/records/{id}：记录详情。 */
    @GetMapping("/records/{id}")
    public Result<?> recordDetail(HttpSession session, @PathVariable("id") Long id) {
        return volunteerService.recordDetail(userId(session), role(session), id);
    }

    /** POST /api/volunteer/records：录入时长（teacher / admin 为学生录入；学生本人提交则 source 置 makeup）。 */
    @PostMapping("/records")
    public Result<?> createRecord(HttpSession session,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.createRecord(userId(session), role(session), body);
    }

    /** POST /api/volunteer/records/batch：批量录入（items / records 两个键都接受）。 */
    @PostMapping("/records/batch")
    public Result<?> batchCreateRecords(HttpSession session,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.batchCreateRecords(userId(session), role(session), body);
    }

    /** POST /api/volunteer/records/{id}/review：时长审核（仅 admin，前置 pending）。 */
    @PostMapping("/records/{id}/review")
    public Result<?> reviewRecord(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.reviewRecord(userId(session), role(session), id, body);
    }

    /** POST /api/volunteer/records/{id}/makeup：补录（仅 rejected，生成新记录，6 个月窗口）。 */
    @PostMapping("/records/{id}/makeup")
    public Result<?> makeupRecord(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.makeupRecord(userId(session), role(session), id, body);
    }

    // ==================== 时长汇总与星级 ====================

    /** GET /api/volunteer/summary/mine：本人时长汇总与星级推算（字面量路由）。 */
    @GetMapping("/summary/mine")
    public Result<?> summaryMine(HttpSession session) {
        return volunteerService.summaryMine(userId(session), role(session));
    }

    /** GET /api/volunteer/summary/{userId}：单人时长汇总（admin / 名下辅导员 / 本人）。 */
    @GetMapping("/summary/{userId}")
    public Result<?> summaryForUser(HttpSession session, @PathVariable("userId") Long userId) {
        return volunteerService.summaryForUser(userId(session), role(session), userId);
    }

    // ==================== 统计与导出 ====================

    /** GET /api/volunteer/stats/export：CSV 导出（UTF-8 带 BOM，脱敏；字面量路由）。 */
    @GetMapping("/stats/export")
    public ResponseEntity<byte[]> exportStats(HttpSession session,
                                              @RequestParam(name = "projectId", required = false) Long projectId,
                                              @RequestParam(name = "status", required = false) String status,
                                              @RequestParam(name = "hourType", required = false) String hourType,
                                              @RequestParam(name = "source", required = false) String source,
                                              @RequestParam(name = "startDate", required = false) String startDate,
                                              @RequestParam(name = "endDate", required = false) String endDate,
                                              @RequestParam(name = "keyword", required = false) String keyword) {
        CommunityService.CsvExport out = volunteerService.exportStats(userId(session), role(session),
                projectId, status, hourType, source, startDate, endDate, keyword);
        if (out.errorCode != null) {
            // 队长裁定（2026-09-22）：被拒时回该状态码且 body 为空（与 ClubController 同口径），
            // 便于 e2e 断言「导出被拒不回任何数据」。
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] raw = (out.csv == null ? "" : out.csv).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[raw.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(raw, 0, bytes, 3, raw.length);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /** GET /api/volunteer/stats：志愿服务统计（admin / teacher）。 */
    @GetMapping("/stats")
    public Result<?> stats(HttpSession session) {
        return volunteerService.stats(userId(session), role(session));
    }

    // ==================== 黑名单 ====================

    /** GET /api/volunteer/blacklist：黑名单列表（admin 全量 / teacher 名下学生 / student 仅本人）。 */
    @GetMapping("/blacklist")
    public Result<?> listBlacklist(HttpSession session,
                                   @RequestParam(name = "clause", required = false) String clause,
                                   @RequestParam(name = "status", required = false) String status,
                                   @RequestParam(name = "keyword", required = false) String keyword,
                                   @RequestParam(name = "page", required = false, defaultValue = "1") int page,
                                   @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return volunteerService.listBlacklist(userId(session), role(session), clause, status, keyword,
                page, size);
    }

    /** POST /api/volunteer/blacklist：列入黑名单（仅 admin，期限 6 个月）。 */
    @PostMapping("/blacklist")
    public Result<?> createBlacklist(HttpSession session,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.createBlacklist(userId(session), role(session), body);
    }

    /** POST /api/volunteer/blacklist/{id}/revoke：撤销黑名单（仅 admin，前置 active）。 */
    @PostMapping("/blacklist/{id}/revoke")
    public Result<?> revokeBlacklist(HttpSession session, @PathVariable("id") Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.revokeBlacklist(userId(session), role(session), id, body);
    }

    // ==================== 申诉 ====================

    /** GET /api/volunteer/appeals：申诉列表（admin 全量 / teacher 名下学生 / student 仅本人）。 */
    @GetMapping("/appeals")
    public Result<?> listAppeals(HttpSession session,
                                 @RequestParam(name = "recordId", required = false) Long recordId,
                                 @RequestParam(name = "blacklistId", required = false) Long blacklistId,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "keyword", required = false) String keyword,
                                 @RequestParam(name = "page", required = false, defaultValue = "1") int page,
                                 @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return volunteerService.listAppeals(userId(session), role(session), recordId, blacklistId, status,
                keyword, page, size);
    }

    /** POST /api/volunteer/appeals：提交申诉（本人；recordId / blacklistId 二选一，5 个工作日窗口）。 */
    @PostMapping("/appeals")
    public Result<?> createAppeal(HttpSession session,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.createAppeal(userId(session), role(session), body);
    }

    /** POST /api/volunteer/appeals/{id}/rule：申诉裁定（仅 admin，前置 pending）。 */
    @PostMapping("/appeals/{id}/rule")
    public Result<?> ruleAppeal(HttpSession session, @PathVariable("id") Long id,
                                @RequestBody(required = false) Map<String, Object> body) {
        return volunteerService.ruleAppeal(userId(session), role(session), id, body);
    }

    // ==================== 会话工具（与 PartyController / CommunityController 同口径） ====================

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
