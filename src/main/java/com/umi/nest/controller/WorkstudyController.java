package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.WorkstudyService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 勤工助学（学工 P10）接口。
 *
 * <p>全站约定：HTTP 一律 200，响应体 {code,message,data}；未登录 code=401。</p>
 */
@RestController
@RequestMapping("/api/workstudy")
public class WorkstudyController {

    @Autowired
    private WorkstudyService workstudyService;

    // ---------------- 学生端 ----------------

    @GetMapping("/posts")
    public Result<?> posts(@RequestParam(required = false) String type,
                           @RequestParam(required = false) String term,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           HttpSession session) {
        return workstudyService.posts(userId(session), role(session), page, size, type, term, keyword);
    }

    @GetMapping("/posts/{id}")
    public Result<?> postDetail(@PathVariable Long id, HttpSession session) {
        return workstudyService.postDetail(userId(session), role(session), id);
    }

    @PostMapping("/posts/{id}/apply")
    public Result<?> apply(@PathVariable Long id,
                           @RequestBody(required = false) Map<String, String> body,
                           HttpSession session) {
        return workstudyService.apply(userId(session), role(session), id, body);
    }

    @GetMapping("/my")
    public Result<?> mine(HttpSession session) {
        return workstudyService.mine(userId(session), role(session));
    }

    @GetMapping("/my/timesheets")
    public Result<?> myTimesheets(@RequestParam(required = false) String month, HttpSession session) {
        return workstudyService.myTimesheets(userId(session), role(session), month);
    }

    @PostMapping("/my/applications/{id}/resign")
    public Result<?> resign(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body,
                            HttpSession session) {
        return workstudyService.resign(userId(session), role(session), id, body);
    }

    // ---------------- 用工单位 / 资助中心：岗位 ----------------

    @GetMapping("/manage/posts")
    public Result<?> managePosts(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) Integer mine,
                                 @RequestParam(required = false) String term,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 HttpSession session) {
        return workstudyService.managePosts(userId(session), role(session), page, size, status, mine, term);
    }

    @PostMapping("/manage/posts")
    public Result<?> createPost(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        return workstudyService.createPost(userId(session), role(session), body);
    }

    @PutMapping("/manage/posts/{id}")
    public Result<?> updatePost(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, String> body,
                                HttpSession session) {
        return workstudyService.updatePost(userId(session), role(session), id, body);
    }

    @PostMapping("/manage/posts/{id}/submit")
    public Result<?> submitPost(@PathVariable Long id, HttpSession session) {
        return workstudyService.submitPost(userId(session), role(session), id);
    }

    @PostMapping("/manage/posts/{id}/review")
    public Result<?> reviewPost(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, String> body,
                                HttpSession session) {
        return workstudyService.reviewPost(userId(session), role(session), id, body);
    }

    @PostMapping("/manage/posts/{id}/close")
    public Result<?> closePost(@PathVariable Long id,
                               @RequestBody(required = false) Map<String, String> body,
                               HttpSession session) {
        return workstudyService.closePost(userId(session), role(session), id, body);
    }

    // ---------------- 用工单位 / 资助中心：申请 ----------------

    @GetMapping("/manage/applications")
    public Result<?> manageApplications(@RequestParam(required = false) Long postId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        HttpSession session) {
        return workstudyService.manageApplications(userId(session), role(session), postId, status, page, size);
    }

    @PostMapping("/manage/applications/{id}/interview")
    public Result<?> interview(@PathVariable Long id,
                               @RequestBody(required = false) Map<String, String> body,
                               HttpSession session) {
        return workstudyService.interview(userId(session), role(session), id, body);
    }

    @PostMapping("/manage/applications/{id}/hire")
    public Result<?> hire(@PathVariable Long id,
                          @RequestBody(required = false) Map<String, String> body,
                          HttpSession session) {
        return workstudyService.hire(userId(session), role(session), id, body);
    }

    @PostMapping("/manage/applications/{id}/reject")
    public Result<?> reject(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body,
                            HttpSession session) {
        return workstudyService.reject(userId(session), role(session), id, body);
    }

    /** 单位确认辞职（resigning → resigned）；契约未列出，为闭环状态机补齐 */
    @PostMapping("/manage/applications/{id}/confirm-resign")
    public Result<?> confirmResign(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, String> body,
                                   HttpSession session) {
        return workstudyService.confirmResign(userId(session), role(session), id, body);
    }

    /** 前端冻结契约路径：确认辞职（与 confirm-resign 等价，保留两条以便对接） */
    @PostMapping("/manage/applications/{id}/resign-confirm")
    public Result<?> resignConfirm(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, String> body,
                                   HttpSession session) {
        return workstudyService.confirmResign(userId(session), role(session), id, body);
    }

    // ---------------- 用工单位 / 资助中心：工时 ----------------

    @GetMapping("/manage/timesheets")
    public Result<?> manageTimesheets(@RequestParam(required = false) String month,
                                      @RequestParam(required = false) Long postId,
                                      @RequestParam(required = false) Long studentId,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      HttpSession session) {
        return workstudyService.manageTimesheets(userId(session), role(session), month, postId,
                studentId, page, size);
    }

    @PostMapping("/manage/timesheets")
    public Result<?> createTimesheet(@RequestBody(required = false) Map<String, String> body,
                                     HttpSession session) {
        return workstudyService.createTimesheet(userId(session), role(session), body);
    }

    @PostMapping("/manage/timesheets/{id}/confirm")
    public Result<?> confirmTimesheet(@PathVariable Long id, HttpSession session) {
        return workstudyService.confirmTimesheet(userId(session), role(session), id);
    }

    // ---------------- 用工单位 / 资助中心：酬金 ----------------

    @PostMapping("/manage/payroll/generate")
    public Result<?> generatePayroll(@RequestBody(required = false) Map<String, String> body,
                                     HttpSession session) {
        return workstudyService.generatePayroll(userId(session), role(session), body);
    }

    @GetMapping("/manage/payroll")
    public Result<?> listPayroll(@RequestParam(required = false) String workMonth,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 HttpSession session) {
        return workstudyService.listPayroll(userId(session), role(session), workMonth, status, page, size);
    }

    @PostMapping("/manage/payroll/{id}/pay")
    public Result<?> payPayroll(@PathVariable Long id, HttpSession session) {
        return workstudyService.payPayroll(userId(session), role(session), id);
    }

    // ---------------- 概览 ----------------

    @GetMapping("/overview")
    public Result<?> overview(HttpSession session) {
        return workstudyService.overview(userId(session), role(session));
    }

    // ---------------- 会话工具 ----------------

    private static Long userId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private static String role(HttpSession session) {
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }
}
