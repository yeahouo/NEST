package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.EvalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 学工平台·综合素质测评接口。
 *
 * <p>任何登录用户：/api/eval/indicators、/api/eval/mine。
 * 学生：POST /api/eval/submit（自评）。
 * 辅导员与管理员：/api/eval/summary（按学期/班级的汇总与排名）、/api/eval/student/{userId}（明细与辅导员评分）、
 * POST /api/eval/compute（重算总分与排名）。</p>
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    @Autowired
    private EvalService evalService;

    /** 指标表 + 维度权重 + 可选学期（学生自评、辅导员评分都要用） */
    @GetMapping("/indicators")
    public Result<?> indicators(HttpSession session) {
        return evalService.indicators(userId(session));
    }

    /** 我的综测（不传 term 用当前学期） */
    @GetMapping("/mine")
    public Result<?> mine(@RequestParam(required = false) String term, HttpSession session) {
        return evalService.mine(userId(session), term);
    }

    /** 学生提交自评 */
    @PostMapping("/submit")
    public Result<?> submit(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        return evalService.submit(userId(session), role(session), body);
    }

    /** 学期汇总列表（按班级排名） */
    @GetMapping("/summary")
    public Result<?> summary(@RequestParam(required = false) String term,
                             @RequestParam(required = false) String className,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String scope,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size,
                             HttpSession session) {
        return evalService.summary(userId(session), role(session), term, className, keyword, scope, page, size);
    }

    /** 某学生的综测明细 */
    @GetMapping("/student/{userId}")
    public Result<?> detail(@PathVariable Long userId,
                            @RequestParam(required = false) String term,
                            HttpSession session) {
        return evalService.detail(userId(session), role(session), userId, term);
    }

    /** 辅导员/管理员给某学生打分（写入来源固定 counselor） */
    @PutMapping("/student/{userId}")
    public Result<?> score(@PathVariable Long userId,
                           @RequestBody(required = false) Map<String, Object> body,
                           HttpSession session) {
        return evalService.score(userId(session), role(session), userId, body);
    }

    /** 重算总分、等级与班级排名 */
    @PostMapping("/compute")
    public Result<?> compute(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        String term = body == null ? null : str(body.get("term"));
        String className = body == null ? null : str(body.get("className"));
        return evalService.compute(userId(session), role(session), term, className);
    }

    private static Long userId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private static String role(HttpSession session) {
        Object value = session.getAttribute("role");
        return value == null ? null : String.valueOf(value);
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
