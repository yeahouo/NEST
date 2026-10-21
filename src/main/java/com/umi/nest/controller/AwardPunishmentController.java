package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.AwardPunishmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 学工平台·奖励与处分接口（P5）。
 *
 * <p>学生端：/api/award-punishment/my（只读自己的记录）。
 * 辅导员与管理端：/api/award-punishment（列表 / 录入 / 修改 / 删除）。</p>
 */
@RestController
@RequestMapping("/api/award-punishment")
public class AwardPunishmentController {

    @Autowired
    private AwardPunishmentService awardService;

    /** 我的奖惩记录 */
    @GetMapping("/my")
    public Result<?> my(@RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size,
                        HttpSession session) {
        return awardService.mine(userId(session), page, size);
    }

    /** 奖惩记录列表（辅导员 / 管理端） */
    @GetMapping("")
    public Result<?> list(@RequestParam(required = false) String kind,
                          @RequestParam(required = false) String level,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(required = false) Long studentId,
                          @RequestParam(required = false) String scope,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size,
                          HttpSession session) {
        return awardService.list(userId(session), role(session), kind, level, keyword,
                studentId, scope, page, size);
    }

    /** 录入一条奖惩记录 */
    @PostMapping("")
    public Result<?> create(@RequestBody Map<String, String> body, HttpSession session) {
        return awardService.create(userId(session), role(session), body);
    }

    /** 修改一条奖惩记录 */
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id,
                            @RequestBody Map<String, String> body,
                            HttpSession session) {
        return awardService.update(userId(session), role(session), id, body);
    }

    /** 删除一条奖惩记录（软删） */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id, HttpSession session) {
        return awardService.delete(userId(session), role(session), id);
    }

    private static Long userId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private static String role(HttpSession session) {
        Object r = session.getAttribute("role");
        return r == null ? null : String.valueOf(r);
    }
}
