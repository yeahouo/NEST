package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.AnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 公告控制器（全校公告，不再按课程定向）
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    @Autowired
    private AnnouncementService announcementService;

    /**
     * 获取公告列表
     */
    @GetMapping
    public Result<?> getAnnouncements(@RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size,
                                      HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        return announcementService.getAnnouncements(page, size);
    }

    /**
     * 发布公告（教师/管理员）
     */
    @PostMapping
    public Result<?> createAnnouncement(@RequestBody Map<String, String> body,
                                        HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String role = (String) session.getAttribute("role");
        String title = body.get("title");
        String content = body.get("content");
        return announcementService.createAnnouncement(userId, role, title, content);
    }

    /**
     * 编辑公告（作者本人或管理员）
     */
    @PutMapping("/{announcementId}")
    public Result<?> updateAnnouncement(@PathVariable Long announcementId,
                                        @RequestBody Map<String, String> body,
                                        HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String role = (String) session.getAttribute("role");
        String title = body.get("title");
        String content = body.get("content");
        return announcementService.updateAnnouncement(announcementId, userId, role, title, content);
    }

    /**
     * 删除公告（作者本人或管理员）
     */
    @DeleteMapping("/{announcementId}")
    public Result<?> deleteAnnouncement(@PathVariable Long announcementId, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String role = (String) session.getAttribute("role");
        return announcementService.deleteAnnouncement(announcementId, userId, role);
    }
}
