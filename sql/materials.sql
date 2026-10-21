-- 课程资料表
-- 请在 MySQL 中执行此脚本来创建 materials 表

CREATE TABLE IF NOT EXISTS materials (
    id bigint primary key auto_increment,
    course_id bigint not null comment '课程ID',
    folder_id bigint comment '父文件夹id，null 表示根目录',
    name varchar(100) not null comment '文件/文件夹名称',
    type enum('file', 'folder') not null default 'file' comment '类型',
    file_path varchar(500) comment '存储路径',
    file_size bigint comment '文件大小(字节)',
    mime_type varchar(100) comment 'MIME类型',
    uploader_id bigint comment '上传者id',
    deleted tinyint(1) default 0 comment '逻辑删除',
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    index idx_course_folder (course_id, folder_id),
    index idx_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程资料表';
