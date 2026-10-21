package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.StudentProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 学工平台·学生档案接口（P3）。
 *
 * <p>学生端：GET /api/student/profile（我的档案）、PUT /api/student/profile（填家庭联系人/政治面貌）。</p>
 * <p>辅导员与管理端：GET /api/counselor/students（名册，含统计与班级下拉）、
 * GET /api/counselor/students/{id}（档案 + 最近假条）、PUT /api/counselor/students/{id}（改档案）、
 * POST /api/counselor/students/sync（admin 补建缺失档案）。</p>
 */
@RestController
@RequestMapping("/api")
public class StudentProfileController {

    @Autowired
    private StudentProfileService studentProfileService;

    /** 我的档案（学生端） */
    @GetMapping("/student/profile")
    public Result<?> mine(HttpSession session) {
        return studentProfileService.mine(userId(session));
    }

    /** 学生自助保存 */
    @PutMapping("/student/profile")
    public Result<?> saveMine(@RequestBody Map<String, String> body, HttpSession session) {
        return studentProfileService.saveMine(userId(session), body);
    }

    /** 学生名册（辅导员 / 管理端） */
    @GetMapping("/counselor/students")
    public Result<?> students(@RequestParam(required = false) String className,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String scope,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              HttpSession session) {
        return studentProfileService.list(userId(session), role(session), className, status, keyword, scope, page, size);
    }

    /** 档案详情 + 最近假条 */
    @GetMapping("/counselor/students/{id}")
    public Result<?> detail(@PathVariable Long id, HttpSession session) {
        return studentProfileService.detail(userId(session), role(session), id);
    }

    /** 改档案 */
    @PutMapping("/counselor/students/{id}")
    public Result<?> update(@PathVariable Long id,
                            @RequestBody Map<String, String> body,
                            HttpSession session) {
        return studentProfileService.update(userId(session), role(session), id, body);
    }

    /** 补建缺失档案（仅 admin） */
    @PostMapping("/counselor/students/sync")
    public Result<?> sync(HttpSession session) {
        return studentProfileService.syncMissing(userId(session), role(session));
    }

    private static Long userId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private static String role(HttpSession session) {
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }
}
