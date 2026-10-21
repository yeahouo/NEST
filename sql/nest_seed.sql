-- ============================================================
-- nest 测试数据
-- 执行前请先运行 init.sql 创建表结构
-- 密码统一为 123456 (BCrypt)
-- ============================================================

USE nest;

SET FOREIGN_KEY_CHECKS = 0;

-- 清空旧数据
TRUNCATE quiz_answers;
TRUNCATE quiz_submissions;
TRUNCATE quiz_questions;
TRUNCATE quizzes;
TRUNCATE attendance_records;
TRUNCATE attendance_sessions;
TRUNCATE submissions;
TRUNCATE assignments;
TRUNCATE announcements;
TRUNCATE materials;
TRUNCATE course_members;
TRUNCATE courses;
TRUNCATE teacher_invitation_codes;
TRUNCATE users;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 1. 用户
-- ============================================================
INSERT INTO users (id, username, password_hash, nickname, email, phone, role, real_name, student_id, school, department, major, class_name, grade) VALUES
-- 管理员
(1,  'admin',    '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '管理员',   'admin@nest.cn',    '13800000000', 'admin',   '系统管理员', NULL,      '示例大学', NULL,      NULL,   NULL,   NULL),
-- 教师
(2,  'zhangsan', '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '张老师',   'zhangsan@nest.cn', '13800000001', 'teacher', '张三',     NULL,      '示例大学', '计算机学院', '软件工程', NULL,   NULL),
(3,  'lisi',     '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '李老师',   'lisi@nest.cn',     '13800000002', 'teacher', '李四',     NULL,      '示例大学', '信息学院',   '计算机科学', NULL, NULL),
-- 学生
(4,  'wangxm',   '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '小明',     'wangxm@nest.cn',   '13800000004', 'student', '王小明',   '2024001', '示例大学', '计算机学院', '软件工程', '软工2401', '2024级'),
(5,  'zhaoxh',   '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '小红',     'zhaoxh@nest.cn',   '13800000005', 'student', '赵小红',   '2024002', '示例大学', '计算机学院', '软件工程', '软工2401', '2024级'),
(6,  'chendw',   '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '大伟',     'chendw@nest.cn',   '13800000006', 'student', '陈大伟',   '2024003', '示例大学', '计算机学院', '软件工程', '软工2402', '2024级'),
(7,  'liusq',    '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '思琪',     'liusq@nest.cn',    '13800000007', 'student', '刘思琪',   '2024004', '示例大学', '信息学院',   '计算机科学', '计科2401', '2024级'),
(8,  'zhoujie',  '$2a$10$oeYEUHFyMIA2xGfDsUnA2ORKfsubJSwdlPHaq07brJGofUMewEINm', '小杰',     'zhoujie@nest.cn',  '13800000008', 'student', '周杰',     '2024005', '示例大学', '信息学院',   '计算机科学', '计科2401', '2024级');

-- ============================================================
-- 2. 课程
-- ============================================================
INSERT INTO courses (id, course_code, name, description, cover_index, teacher_id, student_count) VALUES
(1, 'JAVA01', 'Java程序设计',   'Java语言基础与面向对象编程',              '1', 2, 5),
(2, 'DB01',   '数据库原理',     '关系型数据库设计与SQL编程',               '3', 2, 3),
(3, 'WEB01',  'Web前端开发',    'HTML/CSS/JavaScript 全栈开发实践',        '5', 3, 3);

-- ============================================================
-- 3. 课程成员
-- ============================================================
INSERT INTO course_members (course_id, user_id, role) VALUES
-- Java程序设计: 全部5个学生
(1, 4, 'student'),
(1, 5, 'student'),
(1, 6, 'student'),
(1, 7, 'student'),
(1, 8, 'student'),
-- 数据库原理: 3个学生
(2, 4, 'student'),
(2, 5, 'student'),
(2, 6, 'student'),
-- Web前端开发: 3个学生
(3, 6, 'student'),
(3, 7, 'student'),
(3, 8, 'student');

-- ============================================================
-- 4. 作业
-- ============================================================
INSERT INTO assignments (id, course_id, title, description, type, total_score, start_time, end_time, allow_late, late_penalty, allow_resubmit, status) VALUES
(1, 1, 'Java基础语法练习',       '完成变量、循环、数组相关编程题',                     1, 100, '2026-05-20 00:00:00', '2026-06-05 23:59:59', 1, 10, 1, 2),
(2, 1, '面向对象编程实践',       '设计一个学生管理系统，运用封装/继承/多态',           1, 100, '2026-05-25 00:00:00', '2026-06-10 23:59:59', 1, 10, 1, 2),
(3, 2, 'SQL查询练习',           '完成多表联查、子查询、聚合函数相关练习',              1, 100, '2026-05-22 00:00:00', '2026-06-01 23:59:59', 0,  0, 0, 2),
(4, 3, 'HTML/CSS个人主页制作',  '使用HTML+CSS制作一个响应式个人主页',                  1, 100, '2026-05-28 00:00:00', '2026-06-15 23:59:59', 1,  5, 1, 2),
(5, 1, 'Java集合框架实验',      'ArrayList/HashMap/LinkedList 实际应用',              1, 100, '2026-06-01 00:00:00', '2026-06-20 23:59:59', 0,  0, 1, 1);

-- ============================================================
-- 5. 作业提交
-- ============================================================
INSERT INTO submissions (id, assignment_id, user_id, submit_no, is_latest, is_late, content, file_url, score, feedback, status, submit_time, grade_time, grader_id) VALUES
-- 作业1: Java基础语法 (4人提交, 3人已批改)
(1,  1, 4, 1, 1, 0, '已完成所有编程题，代码见附件', '/uploads/assignments/java_basic_wangxm.zip', 92, '代码规范，第3题边界条件处理可优化', 2, '2026-05-28 14:30:00', '2026-05-29 10:00:00', 2),
(2,  1, 5, 1, 1, 0, '作业已完成',                   '/uploads/assignments/java_basic_zhaoxh.zip', 88, '整体不错，数组排序部分有改进空间',   2, '2026-05-29 09:15:00', '2026-05-30 08:00:00', 2),
(3,  1, 6, 1, 1, 1, '迟交，抱歉',                   '/uploads/assignments/java_basic_chendw.zip', 78, '迟交扣10分，代码逻辑基本正确',       2, '2026-06-06 10:00:00', '2026-06-06 15:00:00', 2),
(4,  1, 7, 1, 1, 0, '已完成练习',                   '/uploads/assignments/java_basic_liusq.zip',  NULL, NULL, 1, '2026-05-30 16:00:00', NULL, NULL),
-- 作业3: SQL查询 (2人提交)
(5,  3, 4, 1, 1, 0, 'SQL脚本见附件',                '/uploads/assignments/sql_wangxm.sql',          95, '查询语句写得很规范，索引使用合理',   2, '2026-05-27 11:00:00', '2026-05-28 09:00:00', 2),
(6,  3, 5, 1, 1, 0, '已完成所有SQL题目',             '/uploads/assignments/sql_zhaoxh.sql',          NULL, NULL, 1, '2026-05-28 20:30:00', NULL, NULL);

-- ============================================================
-- 6. 签到会话
-- ============================================================
INSERT INTO attendance_sessions (id, course_id, session_name, code, status, created_by, start_time, end_time) VALUES
(1, 1, '第10周周一课堂',   '123456', 0, 2, '2026-05-25 08:00:00', '2026-05-25 08:30:00'),
(2, 1, '第11周周一课堂',   '654321', 0, 2, '2026-06-01 08:00:00', '2026-06-01 08:30:00'),
(3, 2, '数据库实验课签到', '789012', 1, 2, '2026-06-02 14:00:00', NULL),
(4, 3, '前端开发实训',     '345678', 1, 3, '2026-06-02 10:00:00', NULL);

-- ============================================================
-- 7. 签到记录
-- ============================================================
INSERT INTO attendance_records (session_id, user_id, status, check_time) VALUES
-- 第10周签到: 全勤
(1, 4, 'present', '2026-05-25 08:05:00'),
(1, 5, 'present', '2026-05-25 08:08:00'),
(1, 6, 'present', '2026-05-25 08:12:00'),
(1, 7, 'late',    '2026-05-25 08:22:00'),
(1, 8, 'present', '2026-05-25 08:10:00'),
-- 第11周签到: 4人已签, 1人迟到
(2, 4, 'present', '2026-06-01 08:03:00'),
(2, 5, 'present', '2026-06-01 08:07:00'),
(2, 6, 'late',    '2026-06-01 08:18:00'),
(2, 7, 'present', '2026-06-01 08:11:00'),
-- 数据库实验课: 进行中
(3, 4, 'present', '2026-06-02 14:02:00'),
(3, 5, 'present', '2026-06-02 14:05:00'),
(3, 6, 'present', '2026-06-02 14:01:00'),
-- 前端实训: 进行中
(4, 6, 'present', '2026-06-02 10:04:00'),
(4, 7, 'present', '2026-06-02 10:02:00'),
(4, 8, 'present', '2026-06-02 10:06:00');

-- ============================================================
-- 8. 课程资料
-- ============================================================
INSERT INTO materials (id, course_id, folder_id, name, type, file_path, file_size, mime_type, uploader_id) VALUES
-- Java课程资料
(1,  1, NULL, '课件',                     'folder', NULL,    NULL,  NULL,          2),
(2,  1, NULL, '实验指导书',               'folder', NULL,    NULL,  NULL,          2),
(3,  1, 1,    '第1章-Java概述.pdf',       'file',   '/uploads/materials/java/ch01.pdf',  2048000,  'application/pdf', 2),
(4,  1, 1,    '第2章-基本语法.pdf',       'file',   '/uploads/materials/java/ch02.pdf',  3174400,  'application/pdf', 2),
(5,  1, 2,    '实验1-JDK安装与配置.pdf',  'file',   '/uploads/materials/java/lab01.pdf', 1024000,  'application/pdf', 2),
(6,  1, 2,    '实验2-面向对象编程.pdf',   'file',   '/uploads/materials/java/lab02.pdf', 1536000,  'application/pdf', 2),
-- 数据库课程资料
(7,  2, NULL, '课程讲义',                 'folder', NULL,    NULL,  NULL,          2),
(8,  2, 7,    'MySQL安装指南.pdf',        'file',   '/uploads/materials/db/mysql_guide.pdf', 768000, 'application/pdf', 2),
(9,  2, 7,    'SQL语法速查手册.pdf',      'file',   '/uploads/materials/db/sql_ref.pdf',    512000, 'application/pdf', 2),
-- Web课程资料
(10, 3, NULL, '前端项目模板.zip',         'file',   '/uploads/materials/web/template.zip',  10240000, 'application/zip', 3);

-- ============================================================
-- 9. 公告
-- ============================================================
INSERT INTO announcements (id, course_id, author_id, title, content) VALUES
(1, 1, 2, '期末考试安排通知',     'Java程序设计期末考试定于2026年6月25日，地点待通知。考试范围：第1-12章全部内容。请同学们认真复习。'),
(2, 1, 2, '第11周实验课调整',     '本周五的Java实验课调整至下周同一时间，请知悉。'),
(3, 2, 2, '数据库课程设计任务书', '课程设计要求：3人一组，设计并实现一个完整的数据库应用系统。截止日期：2026年6月30日。'),
(4, 3, 3, '前端项目评审标准',     '项目评审将从代码质量(30%)、页面设计(30%)、交互体验(20%)、答辩表现(20%)四个维度评分。');

-- ============================================================
-- 10. 教师邀请码
-- ============================================================
INSERT INTO teacher_invitation_codes (code, description, status, created_by) VALUES
('TEACHER2024', '默认教师邀请码',   1, 1),
('NEWTEACHER',  '新教师专用邀请码', 1, 1);

-- ============================================================
-- 11. 测验
-- ============================================================
INSERT INTO quizzes (id, course_id, title, description, time_limit, total_score, due_time, allow_retake, max_attempts, shuffle_questions, show_result, status) VALUES
(1, 1, 'Java基础测验',     '测试Java基本语法掌握情况',       30, 100, '2026-06-10 23:59:59', 0, 1, 1, 1, 2),
(2, 2, 'SQL语法小测',      'SQL查询与数据操作测验',          20, 100, '2026-06-08 23:59:59', 1, 2, 0, 1, 2),
(3, 1, '面向对象概念测验', '封装/继承/多态/抽象',             15, 50,  '2026-06-15 23:59:59', 0, 1, 0, 2, 1);

-- ============================================================
-- 12. 测验题目
-- ============================================================
INSERT INTO quiz_questions (id, quiz_id, question_text, question_type, score, options, correct_answer, sort_order) VALUES
-- Java基础测验 (5题)
(1,  1, 'Java中哪个关键字用于定义类？',                              1, 20, '["class","struct","define","type"]',           '["class"]',            1),
(2,  1, '以下哪个是Java的基本数据类型？',                            1, 20, '["String","int","Array","List"]',              '["int"]',              2),
(3,  1, 'Java中数组的下标从___开始',                                2, 20, NULL,                                          '["0"]',                3),
(4,  1, '以下哪些是Java的访问修饰符？（多选）',                     3, 20, '["public","private","static","protected"]',    '["public","private","protected"]', 4),
(5,  1, '简述Java中==和equals()的区别',                            4, 20, NULL,                                          NULL,                   5),
-- SQL语法小测 (4题)
(6,  2, 'SQL中用于查询数据的关键字是？',                            1, 25, '["GET","SELECT","FETCH","FIND"]',              '["SELECT"]',           1),
(7,  2, 'SQL中用于删除表中所有数据但保留表结构的语句是___',          2, 25, NULL,                                          '["TRUNCATE"]',         2),
(8,  2, '以下哪些是SQL聚合函数？（多选）',                          3, 25, '["COUNT","CONCAT","SUM","AVG"]',               '["COUNT","SUM","AVG"]',3),
(9,  2, '编写一个SQL查询：查询成绩表(scores)中分数大于80的学生姓名和分数', 5, 25, NULL, NULL, 4);

-- ============================================================
-- 13. 测验提交
-- ============================================================
INSERT INTO quiz_submissions (id, quiz_id, user_id, attempt_no, is_latest, auto_score, manual_score, total_score, is_graded, start_time, submit_time, grade_time, grader_id) VALUES
-- Java基础测验: 3人已提交
(1, 1, 4, 1, 1, 60,  18, 78, 1, '2026-06-02 09:00:00', '2026-06-02 09:25:00', '2026-06-02 14:00:00', 2),
(2, 1, 5, 1, 1, 40,  NULL, NULL, 0, '2026-06-02 10:00:00', '2026-06-02 10:18:00', NULL, NULL),
(3, 1, 6, 1, 1, 80,  15, 95, 1, '2026-06-02 11:00:00', '2026-06-02 11:20:00', '2026-06-02 14:30:00', 2),
-- SQL小测: 1人提交第1次, 重考了第2次
(4, 2, 4, 1, 0, 50,  NULL, NULL, 0, '2026-06-01 14:00:00', '2026-06-01 14:15:00', NULL, NULL),
(5, 2, 4, 2, 1, 75,  20,  95, 1, '2026-06-01 15:00:00', '2026-06-01 15:12:00', '2026-06-01 16:00:00', 2);

-- ============================================================
-- 14. 测验答题记录
-- ============================================================
INSERT INTO quiz_answers (submission_id, question_id, student_answer, is_correct, auto_score, manual_score, feedback) VALUES
-- 王小明 Java测验 (submission 1)
(1, 1, '["class"]',                          1, 20, NULL, NULL),
(1, 2, '["int"]',                            1, 20, NULL, NULL),
(1, 3, '["0"]',                              1, 20, NULL, NULL),
(1, 4, '["public","private"]',               0, 10, NULL, NULL),
(1, 5, '"==比较引用地址，equals比较内容值"',  NULL, NULL, 18,  '回答基本正确，建议补充null处理的情况'),
-- 赵小红 Java测验 (submission 2)
(2, 1, '["class"]',                          1, 20, NULL, NULL),
(2, 2, '["String"]',                         0, 0,  NULL, NULL),
(2, 3, '["1"]',                              0, 0,  NULL, NULL),
(2, 4, '["public"]',                         0, 10, NULL, NULL),
(2, 5, '"equals是比较内容的"',               NULL, NULL, NULL, NULL),
-- 陈大伟 Java测验 (submission 3)
(3, 1, '["class"]',                          1, 20, NULL, NULL),
(3, 2, '["int"]',                            1, 20, NULL, NULL),
(3, 3, '["0"]',                              1, 20, NULL, NULL),
(3, 4, '["public","private","protected"]',   1, 20, NULL, NULL),
(3, 5, '"==是引用比较，equals是值比较"',      NULL, NULL, 15, '正确，可以再深入说明hashCode的约定'),
-- 王小明 SQL小测 第2次 (submission 5)
(5, 6, '["SELECT"]',                         1, 25, NULL, NULL),
(5, 7, '["TRUNCATE"]',                       1, 25, NULL, NULL),
(5, 8, '["COUNT","SUM","AVG"]',              1, 25, NULL, NULL),
(5, 9, '"SELECT name, score FROM scores WHERE score > 80"', NULL, NULL, 20, '正确，语句规范');

-- ============================================================
-- 完成
-- ============================================================
SELECT '测试数据插入完成' AS message;
