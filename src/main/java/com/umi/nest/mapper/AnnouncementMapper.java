package com.umi.nest.mapper;

import com.umi.nest.entity.Announcement;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 公告 Mapper（全校公告，已去掉课程定向）
 */
@Mapper
public interface AnnouncementMapper {

    /**
     * 插入公告（全校公告，不再有 course_id 列 —— P8 已用
     * sql/nest_p8_teaching_offline.sql 把该列删除）。
     */
    @Insert("INSERT INTO announcements (author_id, title, content) " +
            "VALUES (#{authorId}, #{title}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Announcement announcement);

    @Select("SELECT a.*, COALESCE(u.nickname, u.username) as authorName " +
            "FROM announcements a " +
            "LEFT JOIN users u ON a.author_id = u.id " +
            "WHERE a.id = #{id}")
    Announcement findById(Long id);

    /**
     * 全部有效公告，最新在前（不再按课程过滤）。
     */
    @Select("SELECT a.*, COALESCE(u.nickname, u.username) as authorName " +
            "FROM announcements a " +
            "LEFT JOIN users u ON a.author_id = u.id " +
            "WHERE a.deleted = 0 " +
            "ORDER BY a.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Announcement> findAll(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 有效公告总数（分页用）。
     */
    @Select("SELECT COUNT(*) FROM announcements WHERE deleted = 0")
    int countAll();

    @Update("UPDATE announcements SET title = #{title}, content = #{content} WHERE id = #{id}")
    int update(@Param("id") Long id, @Param("title") String title, @Param("content") String content);

    @Update("UPDATE announcements SET deleted = 1 WHERE id = #{id}")
    int softDelete(Long id);
}
