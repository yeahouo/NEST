package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告实体
 */
@Data
public class Announcement {
    private Long id;
    private Long authorId;
    private String title;
    private String content;
    private Integer deleted;  // 逻辑删除 0正常 1删除

    @JsonIgnore
    private LocalDateTime createTime;
    @JsonIgnore
    private LocalDateTime updateTime;

    // 关联字段（不存储在数据库）
    private String authorName;   // 作者昵称
    private String authorAvatar; // 作者头像缩写
    /**
     * 创建时间的展示文本，格式 yyyy-MM-dd'T'HH:mm:ss。
     *
     * createTime 本身带 @JsonIgnore：LocalDateTime 的默认序列化形如
     * [2026,10,9,14,30]（数组）或依赖 Jackson 配置的 ISO 串，前端拿到之后
     * 还得猜格式。这里改成由服务端显式格式化成字符串，前端可以直接
     * new Date(...) 解析，也免得将来有人改了 Jackson 配置就把页面弄坏。
     */
    private String createTimeIso;
}
