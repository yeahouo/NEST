package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.AidService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 学工平台·资助申请接口（P5）。
 *
 * <p>学生端：/api/aid/my、POST /api/aid（提交）、POST /api/aid/{id}/cancel（撤回）。
 * 辅导员与管理端：/api/aid/inbox、POST /api/aid/{id}/approve|reject。</p>
 */
@RestController
@RequestMapping("/api/aid")
public class AidController {

    @Autowired
    private AidService aidService;

    /** 我的资助申请 */
    @GetMapping("/my")
    public Result<?> my(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size,
                        HttpSession session) {
        return aidService.mine(userId(session), status, page, size);
    }

    /** 提交资助申请 */
    @PostMapping("")
    public Result<?> apply(@RequestBody Map<String, String> body, HttpSession session) {
        return aidService.apply(userId(session), body);
    }

    /** 撤回自己的待审申请 */
    @PostMapping("/{id}/cancel")
    public Result<?> cancel(@PathVariable Long id, HttpSession session) {
        return aidService.cancel(userId(session), id);
    }

    /** 审批收件箱（辅导员 / 管理端） */
    @GetMapping("/inbox")
    public Result<?> inbox(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String aidType,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String scope,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           HttpSession session) {
        return aidService.inbox(userId(session), role(session), status, aidType, keyword,
                scope, page, size);
    }

    /** 审批：批准（可核定金额） */
    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id,
                             @RequestBody(required = false) Map<String, String> body,
                             HttpSession session) {
        return aidService.decide(userId(session), role(session), id, "approve",
                note(body), body == null ? null : body.get("amount"));
    }

    /** 审批：驳回（必须写原因） */
    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body,
                            HttpSession session) {
        return aidService.decide(userId(session), role(session), id, "reject", note(body), null);
    }

    private static Long userId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private static String role(HttpSession session) {
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }

    private static String note(Map<String, String> body) {
        return body == null ? null : body.get("note");
    }
}
