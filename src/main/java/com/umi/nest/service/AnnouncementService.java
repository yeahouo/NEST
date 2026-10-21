package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.Announcement;
import com.umi.nest.mapper.AnnouncementMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告服务
 *
 * <p>公告已不再按课程定向：全部是全校公告，登录用户都能查看；
 * 发布限教师/管理员，编辑与删除限作者本人或管理员。
 */
@Service
public class AnnouncementService {

    /** 给前端用的时间格式：能被 new Date(...) 直接解析，并且不带时区歧义。 */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 列表默认每页条数。 */
    private static final int DEFAULT_SIZE = 20;

    /** 列表每页条数上限，防止前端传个离谱的值把库拖死。 */
    private static final int MAX_SIZE = 100;

    @Autowired
    private AnnouncementMapper announcementMapper;

    /**
     * 补上不落库的展示字段（作者缩写、创建时间文本）。
     * 两处返回公告的地方都走这里，避免只有一处补齐。
     */
    private static void fillDerived(Announcement a) {
        if (a == null) {
            return;
        }
        String name = a.getAuthorName();
        if (name != null && !name.isEmpty()) {
            a.setAuthorAvatar(name.length() >= 2 ? name.substring(0, 2).toUpperCase() : name.toUpperCase());
        }
        LocalDateTime created = a.getCreateTime();
        a.setCreateTimeIso(created == null ? null : ISO.format(created));
    }

    /** 是否具备发布公告的角色。 */
    private static boolean canPublish(String role) {
        return "teacher".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
    }

    /** 作者本人或管理员才能改/删。 */
    private static boolean canManage(Announcement announcement, Long userId, String role) {
        return "admin".equalsIgnoreCase(role)
                || (userId != null && userId.equals(announcement.getAuthorId()));
    }

    /**
     * 获取公告列表（全校公告，最新在前，分页）。
     */
    public Result<?> getAnnouncements(Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        // 获取公告列表（Mapper 里已按 create_time DESC 排序）
        List<Announcement> announcements = announcementMapper.findAll((p - 1) * s, s);
        for (Announcement a : announcements) {
            fillDerived(a);
        }
        int total = announcementMapper.countAll();

        Map<String, Object> data = new HashMap<>();
        data.put("items", announcements);
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        data.put("pages", (total + s - 1) / s);
        return Result.success(data);
    }

    /**
     * 发布公告（教师/管理员）
     */
    @Transactional
    public Result<?> createAnnouncement(Long userId, String role, String title, String content) {
        // 验证权限：教师或管理员可以发布
        if (!canPublish(role)) {
            return Result.error(403, "无权限发布公告");
        }

        // 验证内容
        if (title == null || title.trim().isEmpty()) {
            return Result.error("标题不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.error("内容不能为空");
        }

        // 创建公告
        Announcement announcement = new Announcement();
        announcement.setAuthorId(userId);
        announcement.setTitle(title.trim());
        announcement.setContent(content.trim());

        announcementMapper.insert(announcement);

        // 重新读一次：create_time 是数据库默认值（CURRENT_TIMESTAMP），插入后实体里
        // 还是 null，而前端要拿它显示「刚刚发布」，所以补一次带 JOIN 的查询。
        Announcement saved = announcementMapper.findById(announcement.getId());
        if (saved != null) {
            announcement = saved;
        }
        fillDerived(announcement);

        return Result.success("公告发布成功", announcement);
    }

    /**
     * 编辑公告（作者本人或管理员）
     */
    @Transactional
    public Result<?> updateAnnouncement(Long announcementId, Long userId, String role,
                                        String title, String content) {
        // 查询公告
        Announcement announcement = announcementMapper.findById(announcementId);
        if (announcement == null || announcement.getDeleted() == 1) {
            return Result.error(404, "公告不存在");
        }

        // 验证权限
        if (!canManage(announcement, userId, role)) {
            return Result.error(403, "无权修改此公告");
        }

        // 验证内容
        if (title == null || title.trim().isEmpty()) {
            return Result.error("标题不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.error("内容不能为空");
        }

        // 更新公告
        announcementMapper.update(announcementId, title.trim(), content.trim());

        return Result.success("公告更新成功");
    }

    /**
     * 删除公告（作者本人或管理员）
     */
    @Transactional
    public Result<?> deleteAnnouncement(Long announcementId, Long userId, String role) {
        // 查询公告
        Announcement announcement = announcementMapper.findById(announcementId);
        if (announcement == null || announcement.getDeleted() == 1) {
            return Result.error(404, "公告不存在");
        }

        // 验证权限
        if (!canManage(announcement, userId, role)) {
            return Result.error(403, "无权删除此公告");
        }

        // 软删除
        announcementMapper.softDelete(announcementId);

        return Result.success("公告已删除");
    }
}
