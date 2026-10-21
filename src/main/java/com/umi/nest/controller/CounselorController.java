package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.CounselorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;

/**
 * 学工平台·辅导员工作台接口（P7）。
 *
 * <p>全部是读接口，权限由 service 层按角色判定（teacher / admin）；写操作一律走各业务模块自己的接口：</p>
 * <ul>
 *   <li>GET /api/counselor/overview —— 所辖人数、学籍/班级分布、待办与预警分级计数</li>
 *   <li>GET /api/counselor/todo —— 待办队列（type = all | leave | aid | eval）</li>
 *   <li>GET /api/counselor/alerts —— 异动预警（level = high | medium | low）</li>
 *   <li>GET /api/counselor/students/{id}/portrait —— 学生画像</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/counselor")
public class CounselorController {

    @Autowired
    private CounselorService counselorService;

    /** 工作台首页数据 */
    @GetMapping("/overview")
    public Result<?> overview(@RequestParam(required = false) String term, HttpSession session) {
        return counselorService.overview(userId(session), role(session), term);
    }

    /** 待办队列 */
    @GetMapping("/todo")
    public Result<?> todo(@RequestParam(required = false) String type,
                          @RequestParam(required = false) String term,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size,
                          HttpSession session) {
        return counselorService.todo(userId(session), role(session), type, term, page, size);
    }

    /** 异动预警 */
    @GetMapping("/alerts")
    public Result<?> alerts(@RequestParam(required = false) String level,
                            @RequestParam(required = false) String term,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size,
                            HttpSession session) {
        return counselorService.alerts(userId(session), role(session), level, term, page, size);
    }

    /** 学生画像（学生 userId） */
    @GetMapping("/students/{id}/portrait")
    public Result<?> portrait(@PathVariable Long id, HttpSession session) {
        return counselorService.portrait(userId(session), role(session), id);
    }

    private static Long userId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private static String role(HttpSession session) {
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }
}
