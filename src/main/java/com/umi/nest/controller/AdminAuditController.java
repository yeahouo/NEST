package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.service.AuditLogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 审计日志管理端接口（差距清单 P0-1）：等保要求审计记录可查、可导出、可留存。
 *
 * <p>所有接口都要求会话角色为 {@code admin}——审计日志本身就是「谁做了什么」的账本，
 * 让它可被学生读取等于把账本交出去。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AuditLogService auditLogService;

    public AdminAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    private Result<?> checkAdmin(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String role = (String) session.getAttribute("role");
        if (role == null || !"admin".equalsIgnoreCase(role)) {
            return Result.error(403, "无权限访问");
        }
        return null;
    }

    /** 分页查询：eventType / username / success / from / to / page / size。 */
    @GetMapping
    public Result<?> list(@RequestParam(required = false) String eventType,
                          @RequestParam(required = false) String username,
                          @RequestParam(required = false) Boolean success,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "50") int size,
                          HttpSession session) {
        Result<?> guard = checkAdmin(session);
        if (guard != null) {
            return guard;
        }
        return Result.success(auditLogService.query(eventType, username, success, from, to, page, size));
    }

    /** 按事件类型汇总最近 N 天（默认 7 天）。 */
    @GetMapping("/summary")
    public Result<?> summary(@RequestParam(defaultValue = "7") int days, HttpSession session) {
        Result<?> guard = checkAdmin(session);
        if (guard != null) {
            return guard;
        }
        return Result.success(auditLogService.summary(days));
    }

    /** 导出 CSV（带 UTF-8 BOM，Excel 直接双击不乱码）。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String eventType,
                                         @RequestParam(required = false) String username,
                                         @RequestParam(required = false) Boolean success,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         HttpSession session) {
        Result<?> guard = checkAdmin(session);
        if (guard != null) {
            return ResponseEntity.status(403).build();
        }
        List<AuditLog> rows = auditLogService.exportRows(eventType, username, success, from, to);
        byte[] body = ("\uFEFF" + auditLogService.toCsv(rows)).getBytes(StandardCharsets.UTF_8);
        String filename = "audit-log-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /** 留存期清理：删除 N 天前的记录（默认 180 天，最少 1 天）。 */
    @PostMapping("/purge")
    public Result<?> purge(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Result<?> guard = checkAdmin(session);
        if (guard != null) {
            return guard;
        }
        int days = 180;
        if (body != null && body.get("days") != null) {
            try {
                days = Integer.parseInt(String.valueOf(body.get("days")));
            } catch (NumberFormatException ignored) {
                // 非法值就用默认留存期
            }
        }
        int removed = auditLogService.purgeBeforeDays(days);
        return Result.success("已清理 " + removed + " 条 " + days + " 天前的审计记录", removed);
    }
}
