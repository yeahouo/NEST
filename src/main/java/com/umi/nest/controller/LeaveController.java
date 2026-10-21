package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.LeaveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 学工平台·请假 / 离校 / 外出接口。
 *
 * <p>学生端：/api/leave/my、/api/leave/my/stats、POST /api/leave、POST /api/leave/{id}/cancel。
 * 辅导员与管理端：/api/leave/inbox、/api/leave/{id}/approve|reject|close。</p>
 */
@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    @Autowired
    private LeaveService leaveService;

    /** 学生提交申请 */
    @PostMapping("")
    public Result<?> submit(@RequestBody Map<String, String> body, HttpSession session) {
        return leaveService.submit(userId(session), body);
    }

    /** 我的假条 */
    @GetMapping("/my")
    public Result<?> my(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size,
                        HttpSession session) {
        return leaveService.myList(userId(session), status, page, size);
    }

    /** 我的状态统计 */
    @GetMapping("/my/stats")
    public Result<?> myStats(HttpSession session) {
        return leaveService.myStats(userId(session));
    }

    /** 撤回 */
    @PostMapping("/{id}/cancel")
    public Result<?> cancel(@PathVariable Long id, HttpSession session) {
        return leaveService.cancel(userId(session), id);
    }

    /** 收件箱（辅导员 / 管理端） */
    @GetMapping("/inbox")
    public Result<?> inbox(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           HttpSession session) {
        return leaveService.inbox(userId(session), role(session), status, keyword, page, size);
    }

    /** 审批：批准 */
    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id,
                             @RequestBody(required = false) Map<String, String> body,
                             HttpSession session) {
        return leaveService.decide(userId(session), role(session), id, "approve", note(body));
    }

    /** 审批：驳回（必须写原因） */
    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body,
                            HttpSession session) {
        return leaveService.decide(userId(session), role(session), id, "reject", note(body));
    }

    /** 销假 */
    @PostMapping("/{id}/close")
    public Result<?> close(@PathVariable Long id,
                           @RequestBody(required = false) Map<String, String> body,
                           HttpSession session) {
        return leaveService.decide(userId(session), role(session), id, "close", note(body));
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
