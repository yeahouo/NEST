package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.LoanService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * P19 生源地信用助学贷款登记接口（13 条）。
 *
 * <p>学生端：我的登记 / 汇总 / 提交 / 改写 / 撤回 / 改到账 / 详情。
 * 员工端：名单 / 统计 / 辅导员核验 / 学校确认 / CSV 导出 / 健康检查。</p>
 *
 * <p>返回约定与全站一致：一律 HTTP 200 + {@code {code,message,data}}；唯一例外是导出，
 * 拒绝时 HTTP 403 + 空 body（对齐 ClubService / VolunteerService / SecondClassController）。</p>
 */
@RestController
@RequestMapping("/api/loan")
public class LoanController {

    @Autowired
    private LoanService loanService;

    /** 1. 我的登记（GET /api/loan/my?status=&academicYear=&page=&size=）。 */
    @GetMapping("/my")
    public Result<?> my(HttpSession session,
                        @RequestParam(name = "status", required = false) String status,
                        @RequestParam(name = "academicYear", required = false) String academicYear,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "20") int size) {
        return loanService.mine(userId(session), status, academicYear, page, size);
    }

    /** 2. 我的汇总（各状态计数 + 已确认金额 + 学年清单 + 口径说明）。 */
    @GetMapping("/summary")
    public Result<?> summary(HttpSession session) {
        return loanService.summary(userId(session));
    }

    /** 3. 提交登记。 */
    @PostMapping("")
    public Result<?> create(HttpSession session, @RequestBody(required = false) Map<String, String> body) {
        return loanService.create(userId(session), body);
    }

    /** 4. 改写自己的登记（仅待核验 / 被退回）。 */
    @PutMapping("/{id}")
    public Result<?> update(HttpSession session, @PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body) {
        return loanService.update(userId(session), id, body);
    }

    /** 5. 撤回。 */
    @PostMapping("/{id}/withdraw")
    public Result<?> withdraw(HttpSession session, @PathVariable Long id) {
        return loanService.withdraw(userId(session), id);
    }

    /** 6. 学生自助改「是否到账」。 */
    @PostMapping("/{id}/disbursed")
    public Result<?> disbursed(HttpSession session, @PathVariable Long id,
                               @RequestBody(required = false) Map<String, String> body) {
        return loanService.updateDisbursed(userId(session), id, body);
    }

    /** 7. 详情（本人或员工）。 */
    @GetMapping("/{id}")
    public Result<?> detail(HttpSession session, @PathVariable Long id) {
        return loanService.detail(userId(session), role(session), id);
    }

    /** 8. 员工名单（辅导员按名下学生，admin 全校）。 */
    @GetMapping("/review")
    public Result<?> review(HttpSession session,
                            @RequestParam(name = "status", required = false) String status,
                            @RequestParam(name = "academicYear", required = false) String academicYear,
                            @RequestParam(name = "keyword", required = false) String keyword,
                            @RequestParam(name = "page", defaultValue = "1") int page,
                            @RequestParam(name = "size", defaultValue = "20") int size) {
        return loanService.review(userId(session), role(session), status, academicYear, keyword, page, size);
    }

    /** 9. 员工统计。 */
    @GetMapping("/review/stats")
    public Result<?> reviewStats(HttpSession session,
                                 @RequestParam(name = "academicYear", required = false) String academicYear) {
        return loanService.reviewStats(userId(session), role(session), academicYear);
    }

    /** 10. 辅导员核验（approve / return / reject）。 */
    @PostMapping("/{id}/counselor-review")
    public Result<?> counselorReview(HttpSession session, @PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        String action = body == null ? null : body.get("action");
        String reason = body == null ? null : body.get("reason");
        return loanService.counselorReview(userId(session), role(session), id, action, reason);
    }

    /** 11. 学校确认（仅 admin）。 */
    @PostMapping("/{id}/confirm")
    public Result<?> confirm(HttpSession session, @PathVariable Long id) {
        return loanService.confirm(userId(session), role(session), id);
    }

    /**
     * 12. CSV 导出（GET /api/loan/export?status=&academicYear=）。
     * UTF-8 带 BOM、text/csv;charset=UTF-8、文件名 student-loan-yyyyMMdd.csv；拒绝 403 + 空 body。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session,
                                         @RequestParam(name = "status", required = false) String status,
                                         @RequestParam(name = "academicYear", required = false) String academicYear) {
        LoanService.CsvExport out = loanService.exportCsv(userId(session), role(session), status, academicYear);
        if (out.errorCode != 0) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /** 13. 健康检查（免登录，部署自检用）。 */
    @GetMapping("/health")
    public Result<?> health() {
        return loanService.health();
    }

    // =========================================================================
    // 会话工具（与 SecondClassController 一致）
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
