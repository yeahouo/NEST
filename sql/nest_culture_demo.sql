-- =============================================================================
-- 学工平台 · P17 校园文化 · 演示数据（sql/nest_culture_demo.sql）
--
-- 依赖：先执行 sql/nest_volunteer.sql 与 sql/nest_club.sql（本文件 DROP 由那两个文件负责）。
-- 幂等：全部使用固定 id 区间，文件开头先 DELETE 再 INSERT，可反复执行。
-- 时间：志愿侧的演示时间一律相对 NOW()/CURDATE() 生成（DATE_ADD / DATE_SUB），不会随时间过期；
--       **社团侧 S25b 星级公示的起止日期是逐字原文（2026-07-07 ~ 2026-07-11），固定写死不相对化**。
--
-- 数据来源纪律（spec §12.1 / plan §2）：
--   · **凡来自 sources 缓存的字段一律逐字照抄**，不加工、不缩写、不改字：
--       社团名 / 类别 / 挂靠单位 / 年审结果 —— target/p17-sources.md L345-419（S25a 年审附件）；
--       社团名 / 评定星级 / 业务指导单位 —— target/p17-sources.md L446-458（S25b 星级公示）；
--       优秀社团负责人姓名/所属社团 —— L466-470（只取了「汤一川 / 篮球协会」这一条，见下）；
--       异议邮箱 twh@xujc.com —— S25a / S25b 逐字。
--   · 凡**无逐字来源**的文本（项目名称、地点、演示正文、材料说明）一律标注「演示数据」，
--     不得读作学校口径。
--   · 诚实清单（spec §12.2-13）：不生成附件 1/2 官方表格、不做自动资格判定、不与志愿汇对接、
--     不给年审「合格/不合格/注销」的判定建议、不登记社团经费金额。
--
-- id 区间：
--   volunteer_project      90001-90009
--   volunteer_record       90011-90019
--   volunteer_enrollment   90021-90029
--   volunteer_blacklist    90031-90039
--   volunteer_appeal       90041-90049
--   club                   91001-91008
--   club_rating            91009-91019
--   club_review            91021-91029
--   club_publicity         91031-91039
--   club_member            91041-91049
--
-- 真实库事实（写入前已连库 SELECT users 确认，2026-10-16）：
--   users.id：1 admin 系统管理员 / 2 zhangsan 张三 teacher / 3 lisi 李四 teacher /
--             4 wangxm 王小明 student / 5 zhaoxh 赵小红 student / 6 chendw 陈大伟 student /
--             7 liusq 刘思琪 student / 8 zhoujie 周杰 student
--   本机 users 表**只有 id 4-8 五个可用学生账号**（id 9-19 是历史探针残留账号 vprobe*，
--   real_name 为 NULL，故不使用）=> 「6 条 volunteer_record 覆盖 6 名学生」无法满足，
--   实际为 6 条记录覆盖 **5 名学生**（王小明 id=4 有 2 条）。详见交付报告「与契约的偏差」。
--
-- 覆盖的演示场景：
--   · 志愿项目 4 态各 1：approved(90001) / pending(90002) / rejected(90003) / draft(90004)，
--     类别取自 plan §3.3（校园公益 / 环境保护 / 大型赛会活动 / 社区治理与邻里守望）
--   · 时长记录 6 条：credit 4 + honor 2；approved 4 + pending 1 + rejected 1；
--     source = platform 4 + makeup 2（含 1 条漏签走荣誉时数补录）
--   · 报名 6 条：attended / confirmed / cancelled / absent / pending 全覆盖
--   · 黑名单 2 条：1 条 active 且 expire_at 未到；1 条 expire_at 已过（状态 expired）
--   · 申诉 2 条：1 条在 5 个工作日内（within_window=1）；1 条超窗（within_window=0）
--   · 社团 8 条：6 条覆盖 S25a 全部 6 个类别（含 1 条 unqualified、1 条 deregistered）+
--     2 条（CAD协会 / 篮球协会）作为星级登记的社团外键目标
--   · 年审 3 条：qualified / unqualified / deregistered 三值各 1
--   · 星级 3 条：star5(CAD协会) / star4(绿野协会) / star3(篮球协会)，业务指导单位逐字照抄
--   · 公示 2 条：1 条 open（年审，5 天）+ 1 条 closed（星级，逐字 2026-07-07~07-11）
--   · 成员 4 条：绿野协会 / 创无限 / 篮球协会 的在册成员（名册无来源，纯演示）
--
-- 执行（Windows / cmd）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_culture_demo.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 清理本文件的 id 区间（幂等的前提）
-- -----------------------------------------------------------------------------
DELETE FROM volunteer_appeal      WHERE id BETWEEN 90041 AND 90049;
DELETE FROM volunteer_blacklist   WHERE id BETWEEN 90031 AND 90039;
DELETE FROM volunteer_record      WHERE id BETWEEN 90011 AND 90019;
DELETE FROM volunteer_enrollment  WHERE id BETWEEN 90021 AND 90029;
DELETE FROM volunteer_project     WHERE id BETWEEN 90001 AND 90009;
DELETE FROM club_member           WHERE id BETWEEN 91041 AND 91049;
DELETE FROM club_review           WHERE id BETWEEN 91021 AND 91029;
DELETE FROM club_rating           WHERE id BETWEEN 91009 AND 91019;
DELETE FROM club_publicity        WHERE id BETWEEN 91031 AND 91039;
DELETE FROM club                  WHERE id BETWEEN 91001 AND 91099;


-- -----------------------------------------------------------------------------
-- 1. volunteer_project —— 志愿服务项目立项（4 条，四种状态各 1）
--    90001 approved：发起组织 = 青年志愿服务指导中心（center，S21 第二十八条①：中心审核 + 录入）
--    90002 pending ：院系志愿服务组织（department，第二十八条②：院系录入、中心审核），由辅导员提报
--    90003 rejected：校内公益类组织、社团（club，第二十八条③），驳回原因引 S21 第十五条(一) 立项前置
--    90004 draft   ：学生活动组织单位（student_activity，第二十八条④），未提交
--    类别取值全部来自 plan §3.3（S21 第十四条 15 类）。
-- -----------------------------------------------------------------------------
INSERT INTO volunteer_project
    (id, project_name, category, organizer, organizer_type, status,
     start_time, end_time, location, duration_note, recruit_limit,
     submit_user_id, review_user_id, review_time, reject_reason,
     created_at, updated_at, deleted)
VALUES
    (90001, '图书馆图书整理志愿服务', '校园公益',
     '厦门大学嘉庚学院青年志愿服务指导中心', 'center', 'approved',
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 40 DAY), INTERVAL 9 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 40 DAY), INTERVAL 13 HOUR),
     '图书馆（演示地点）',
     '认定口径：S21 第十九~二十七条 —— 以签到/签退时间为起止点（含在服务场地休息的时间）；每天上限 8 小时，超出须提供活动策划书等材料由中心审核评定（只提示不硬拒）；单程交通与培训耗时不足 30 分钟不予计入。补录窗口 = 项目结束起 6 个月（第二十六条）。',
     40, 1, 1, DATE_SUB(NOW(), INTERVAL 45 DAY), NULL, NOW(), NOW(), 0),
    (90002, '校园环境清洁与垃圾分类宣传', '环境保护',
     '环境科学与工程学院志愿服务组织', 'department', 'pending',
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL -10 DAY), INTERVAL 8 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL -10 DAY), INTERVAL 12 HOUR),
     '校园各公共区域（演示地点）',
     '演示数据：由院系志愿服务组织发起，按 S21 第二十八条②由院系录入、中心审核。计量口径同 S21 第十九~二十七条。',
     60, 3, NULL, NULL, NULL, NOW(), NOW(), 0),
    (90003, '校运动会大型赛会志愿服务', '大型赛会活动',
     '体育教学部学生公益类社团', 'club', 'rejected',
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 5 DAY), INTERVAL 8 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 3 DAY), INTERVAL 18 HOUR),
     '田径场（演示地点）',
     '演示数据：按 S21 第二十八条③，校内公益类组织、社团发起时由中心审核 + 录入。',
     120, 3, 1, DATE_SUB(NOW(), INTERVAL 18 DAY),
     '依据 S21 第十五条(一)：校内学生社团、学生组织如申请志愿服务时长认定，应在活动开展前向中心申请立项，审批通过后方可进行志愿者招募。本立项提交时活动已临近且安全预案等材料不齐，不予立项（演示数据）。',
     NOW(), NOW(), 0),
    (90004, '社区邻里守望·敬老探访服务', '社区治理与邻里守望',
     '学工部学生活动组织单位', 'student_activity', 'draft',
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL -20 DAY), INTERVAL 9 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL -20 DAY), INTERVAL 16 HOUR),
     '校区周边社区（演示地点）',
     '演示数据：按 S21 第二十八条④，部分服务性质较强的学生活动由相关活动组织单位登记、中心审核 + 录入。草稿未提交。',
     30, 3, NULL, NULL, NULL, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 2. volunteer_record —— 志愿服务时长记录（6 条）
--    90011 王小明(4) 信用时数 4.00 平台打卡 已通过
--    90012 王小明(4) 信用时数 3.50 平台打卡 已通过
--    90013 赵小红(5) 信用时数 5.00 平台打卡 已通过
--    90014 陈大伟(6) 荣誉时数 2.00 补录     待审核（漏签忘签 -> S21 第二十一条荣誉时数补录）
--    90015 刘思琪(7) 信用时数 6.00 平台打卡 已驳回（项目为空 = 校外自行参加，引 S21 第二十五条）
--    90016 周杰(8)   荣誉时数 6.00 补录     已通过
--    hour_type 信用 credit 4 / 荣誉 honor 2；status 已通过 4 / 待审核 1 / 已驳回 1；
--    source 平台打卡 platform 4 / 补录 makeup 2 —— 契约要求的两个维度全覆盖。
--    补录说明里的附件 1/2 与邮箱、交表地点均为 S21 第二十一条原文转述，**平台不生成官方表格**。
-- -----------------------------------------------------------------------------
INSERT INTO volunteer_record
    (id, user_id, project_id, service_date, sign_in, sign_out, hours,
     hour_type, source, recorder_id, recorder_subject, status,
     review_user_id, review_time, reject_reason, makeup_note,
     created_at, updated_at, deleted)
VALUES
    (90011, 4, 90001, DATE_SUB(CURDATE(), INTERVAL 30 DAY),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 30 DAY), INTERVAL 9 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 30 DAY), INTERVAL 13 HOUR),
     4.00, 'credit', 'platform', 1, 'center', 'approved', 1,
     DATE_SUB(NOW(), INTERVAL 28 DAY), NULL, NULL, NOW(), NOW(), 0),
    (90012, 4, 90001, DATE_SUB(CURDATE(), INTERVAL 16 DAY),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 16 DAY), INTERVAL 14 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 16 DAY), INTERVAL 17 HOUR) + INTERVAL 30 MINUTE,
     3.50, 'credit', 'platform', 1, 'club', 'approved', 1,
     DATE_SUB(NOW(), INTERVAL 15 DAY), NULL, NULL, NOW(), NOW(), 0),
    (90013, 5, 90001, DATE_SUB(CURDATE(), INTERVAL 30 DAY),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 30 DAY), INTERVAL 9 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 30 DAY), INTERVAL 14 HOUR),
     5.00, 'credit', 'platform', 1, 'center', 'approved', 1,
     DATE_SUB(NOW(), INTERVAL 28 DAY), NULL, NULL, NOW(), NOW(), 0),
    (90014, 6, 90001, DATE_SUB(CURDATE(), INTERVAL 9 DAY),
     NULL, NULL, 2.00, 'honor', 'makeup', 1, 'center', 'pending',
     NULL, NULL, NULL,
     '演示数据：志愿者漏签忘签，依据 S21 第二十一条按荣誉时数走补录，须提供证明并填写《志愿服务活动工时补录申请表》（附件 1 发 jgsssq@126.com，纸质附件 2 同步交主三#事务大厅 6 号桌），审批通过后方可添加。本平台只登记补录说明，不生成官方表格。',
     NOW(), NOW(), 0),
    (90015, 7, NULL, DATE_SUB(CURDATE(), INTERVAL 20 DAY),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 20 DAY), INTERVAL 8 HOUR),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 20 DAY), INTERVAL 14 HOUR),
     6.00, 'credit', 'platform', 3, 'department', 'rejected', 1,
     DATE_SUB(NOW(), INTERVAL 18 DAY),
     '依据 S21 第二十五条：个人在校外自行参加的活动暂不通过校内各级志愿服务组织认定，本记录不予认定（演示数据）。',
     NULL, NOW(), NOW(), 0),
    (90016, 8, 90001, DATE_SUB(CURDATE(), INTERVAL 16 DAY),
     NULL, NULL, 6.00, 'honor', 'makeup', 1, 'department', 'approved', 1,
     DATE_SUB(NOW(), INTERVAL 14 DAY), NULL,
     '演示数据：活动现场无法打卡，依据 S21 第二十一条按荣誉时数补录，附服务证明。平台只登记说明文本。',
     NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 3. volunteer_enrollment —— 报名（6 条，UNIQUE(project_id, user_id) 不冲突）
--    90021 项目90001 王小明(4) 已参加 attended
--    90022 项目90001 赵小红(5) 已参加 attended
--    90023 项目90001 陈大伟(6) 已确认 confirmed
--    90024 项目90001 刘思琪(7) 已取消 cancelled
--    90025 项目90002 刘思琪(7) 待确认 pending（另一项目，故不与 90024 撞唯一键）
--    90026 项目90001 周杰(8)   无故缺席 absent —— 黑名单 90031 情形 c34_1 的事实来源
-- -----------------------------------------------------------------------------
INSERT INTO volunteer_enrollment
    (id, project_id, user_id, apply_time, status, remark, created_at, updated_at, deleted)
VALUES
    (90021, 90001, 4, DATE_SUB(NOW(), INTERVAL 46 DAY), 'attended',
     '演示数据：已参加并完成签到签退。', NOW(), NOW(), 0),
    (90022, 90001, 5, DATE_SUB(NOW(), INTERVAL 46 DAY), 'attended',
     '演示数据：已参加。', NOW(), NOW(), 0),
    (90023, 90001, 6, DATE_SUB(NOW(), INTERVAL 44 DAY), 'confirmed',
     '演示数据：已确认，待参加。', NOW(), NOW(), 0),
    (90024, 90001, 7, DATE_SUB(NOW(), INTERVAL 45 DAY), 'cancelled',
     '演示数据：学生自行取消报名（S21：已取消报名不得登记出勤）。', NOW(), NOW(), 0),
    (90025, 90002, 7, DATE_SUB(NOW(), INTERVAL 3 DAY), 'pending',
     '演示数据：项目仍为待审批状态，报名为待确认（服务层：仅 approved 项目可报名）。', NOW(), NOW(), 0),
    (90026, 90001, 8, DATE_SUB(NOW(), INTERVAL 44 DAY), 'absent',
     '演示数据：无故缺席已确认参加的活动，对应 S21 第三十四条(一) 情形 c34_1。', NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 4. volunteer_blacklist —— 黑名单（2 条）
--    90031 周杰(8)  c34_1 列入 = 20 天前，到期 = 列入 + 6 个月 => **未到期**，status active
--    90032 刘思琪(7) c35  列入 = 8 个月前，到期 = 列入 + 6 个月 => **已过期**，status expired
--    到期日按 S21 第三十六条「六个月内不再被录用」由服务层推算（不做定时任务），此处直接落算好的日期。
-- -----------------------------------------------------------------------------
INSERT INTO volunteer_blacklist
    (id, user_id, clause, reason, listed_at, expire_at, notify_time,
     status, operator_id, created_at, updated_at, deleted)
VALUES
    (90031, 8, 'c34_1',
     '演示数据：无故缺席已确认参加的志愿服务活动，致活动无法正常开展并造成恶劣影响（S21 第三十四条(一)）。',
     DATE_SUB(CURDATE(), INTERVAL 20 DAY),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 20 DAY), INTERVAL 6 MONTH),
     DATE_SUB(NOW(), INTERVAL 19 DAY), 'active', 1, NOW(), NOW(), 0),
    (90032, 7, 'c35',
     '演示数据：未按要求开展工作、存在懈怠，受警告处分（S21 第三十五条）。',
     DATE_SUB(CURDATE(), INTERVAL 8 MONTH),
     DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 8 MONTH), INTERVAL 6 MONTH),
     DATE_SUB(NOW(), INTERVAL 8 MONTH), 'expired', 1, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 5. volunteer_appeal —— 申诉（2 条）
--    90041 周杰(8)   对黑名单 90031 申诉：提交时间 = 告知时间 + 3 天 => 在 5 个工作日内，within_window=1，待受理
--    90042 刘思琪(7) 对黑名单 90032 申诉：提交时间 = 告知时间 + 60 天 => 超窗，within_window=0，已驳回
--    受理组织按 S21 第三十七条默认「团委」；超窗口径引第三十七条原文。
-- -----------------------------------------------------------------------------
INSERT INTO volunteer_appeal
    (id, user_id, record_id, blacklist_id, reason, submit_time, accept_org,
     within_window, status, ruling, ruler_id, ruling_time,
     created_at, updated_at, deleted)
VALUES
    (90041, 8, NULL, 90031,
     '演示数据：本人已提前向组织说明情况并提交请假材料，对「无故缺席」的认定与六个月不再录用的处理有异议。',
     DATE_SUB(NOW(), INTERVAL 16 DAY), '团委', 1, 'pending',
     NULL, NULL, NULL, NOW(), NOW(), 0),
    (90042, 7, NULL, 90032,
     '演示数据：对第三十五条情形认定有异议，申请复核。',
     DATE_SUB(NOW(), INTERVAL 6 MONTH), '团委', 0, 'rejected',
     '依据 S21 第三十七条：可在接到通知起的 5 个工作日内向相关志愿服务组织提出申诉，由志愿服务组织所在的团委受理并裁定。本申诉提交时间已超过 5 个工作日窗口，不予受理（演示数据）。',
     1, DATE_SUB(NOW(), INTERVAL 5 MONTH), NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 6. club —— 社团台账（8 条）
--    ── 6 条覆盖 S25a 全部 6 个类别（含 1 条 unqualified、1 条 deregistered），
--       社团名/类别/挂靠单位/年审结果**逐字照抄 sources L345-419**：
--       91001 创无限            L345 创新创业类 国际商务学院       合格 -> active
--       91002 青廉社            L346 思想政治类 法学院             合格 -> active
--       91003 BBox&说唱协会     L418 文化体育类 国际商务学院       注销 -> deregistered
--       91004 ETA金融投资协会   L415 学术科技类 会计与金融学院     不合格 -> active
--       91005 绿野协会          L409 志愿公益类 环境科学与工程学院 合格 -> active
--       91006 军鹰协会          L413 自律互助类 学工部、团委       合格 -> active
--    ── 另 2 条为星级登记（club_rating）的社团外键目标，同样逐字来自 L345-419：
--       91007 CAD协会           L389 学术科技类 机电工程与自动化学院 合格 -> active
--       91008 篮球协会          L365 文化体育类 体育教学部           合格 -> active
--           负责人姓名「汤一川」（S25b「优秀社团负责人」名单 L467：汤一川 / 篮球协会），逐字照抄；
--           其余社团负责人姓名与全部指导老师**无来源**，一律 NULL（诚实清单 ⑧）。
-- -----------------------------------------------------------------------------
INSERT INTO club
    (id, club_name, category, attach_unit, found_date, leader_name, advisor_name,
     status, created_at, updated_at, deleted)
VALUES
    (91001, '创无限', '创新创业类', '国际商务学院', NULL, NULL, NULL, 'active', NOW(), NOW(), 0),
    (91002, '青廉社', '思想政治类', '法学院', NULL, NULL, NULL, 'active', NOW(), NOW(), 0),
    (91003, 'BBox&说唱协会', '文化体育类', '国际商务学院', NULL, NULL, NULL, 'deregistered', NOW(), NOW(), 0),
    (91004, 'ETA金融投资协会', '学术科技类', '会计与金融学院', NULL, NULL, NULL, 'active', NOW(), NOW(), 0),
    (91005, '绿野协会', '志愿公益类', '环境科学与工程学院', NULL, NULL, NULL, 'active', NOW(), NOW(), 0),
    (91006, '军鹰协会', '自律互助类', '学工部、团委', NULL, NULL, NULL, 'active', NOW(), NOW(), 0),
    (91007, 'CAD协会', '学术科技类', '机电工程与自动化学院', NULL, NULL, NULL, 'active', NOW(), NOW(), 0),
    (91008, '篮球协会', '文化体育类', '体育教学部', NULL, '汤一川', NULL, 'active', NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 7. club_rating —— 社团星级评定登记（3 条，star5 / star4 / star3 各 1）
--    社团名 / 评定星级 / 业务指导单位**逐字照抄 sources L446-458**（S25b 星级公示）：
--       91009 CAD协会  L446 五星级 业务指导单位 机电工程与自动化学院（社团类别 学术科技类）
--       91010 绿野协会 L450 四星级 业务指导单位 环境科学与工程学院（志愿公益类）
--       91011 篮球协会 L457 三星级 业务指导单位 体育教学部（文化体育类）
--    注意：**业务指导单位（S25b）不等于挂靠单位（S25a）**，两者字段名不同、不得互换
--          （CAD协会挂靠「机电工程与自动化学院」、绿野协会挂靠「环境科学与工程学院」、
--           篮球协会挂靠「体育教学部」——本次公示两者恰好同名，仍严格分列）。
--    basis_note（评定依据说明）为必填：S25b 聚焦校园文化传承 / 活动策划执行 / 社会实践参与 3 项，
--    原文未给出逐社团评语 => 只写口径说明并标注「演示数据」，不编造评语。
--    星级由 admin 登记，平台**不自动评级**（spec §12.2-1）。
-- -----------------------------------------------------------------------------
INSERT INTO club_rating
    (id, club_id, school_year, star_level, business_unit, basis_note,
     publicity_id, register_user_id, register_time, created_at, updated_at, deleted)
VALUES
    (91009, 91007, '2025-2026', 'star5', '机电工程与自动化学院',
     '演示数据：依据 S25b 公示「五星级」。S25b 评定聚焦校园文化传承、活动策划执行、社会实践参与 3 项；原文未给出逐社团评语，本平台只登记星级与业务指导单位，不编造评语、不做自动评级。',
     91031, 1, DATE_SUB(NOW(), INTERVAL 100 DAY), NOW(), NOW(), 0),
    (91010, 91005, '2025-2026', 'star4', '环境科学与工程学院',
     '演示数据：依据 S25b 公示「四星级」。评定聚焦 3 项（校园文化传承 / 活动策划执行 / 社会实践参与），平台只登记结果，不做自动评级。',
     91031, 1, DATE_SUB(NOW(), INTERVAL 100 DAY), NOW(), NOW(), 0),
    (91011, 91008, '2025-2026', 'star3', '体育教学部',
     '演示数据：依据 S25b 公示「三星级」。评定聚焦 3 项（校园文化传承 / 活动策划执行 / 社会实践参与），平台只登记结果，不做自动评级。',
     91031, 1, DATE_SUB(NOW(), INTERVAL 100 DAY), NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 8. club_review —— 社团年审（3 条，qualified / unqualified / deregistered 三值各 1）
--    年审结果**逐字照抄 sources L345-419**：
--       91021 创无限(91001)           L345 合格 -> qualified
--       91022 ETA金融投资协会(91004)  L415 不合格 -> unqualified
--       91023 BBox&说唱协会(91003)    L418 注销 -> deregistered
--    四项聚焦材料（S25a：组织架构合理性 / 骨干成员胜任力 / 学期活动运行质量 / 经费使用合规性）
--    与挂靠单位意见为演示文本（原文附件只给结论表，不给材料正文）；**不含经费金额**，
--    **不给「合格/不合格/注销」的判定建议**（判定标准原文未规定，诚实清单 ④）。
--    publicity_id=91032 指向本学年年审结果公示（公示中）。
-- -----------------------------------------------------------------------------
INSERT INTO club_review
    (id, club_id, school_year, architecture_material, backbone_material,
     activity_material, fund_material, attach_opinion, result,
     submit_time, review_user_id, review_time, publicity_id,
     created_at, updated_at, deleted)
VALUES
    (91021, 91001, '2025-2026',
     '演示数据：四项聚焦①组织架构合理性材料（S25a）。',
     '演示数据：四项聚焦②骨干成员胜任力材料（S25a）。',
     '演示数据：四项聚焦③学期活动运行质量材料（S25a）。',
     '演示数据：四项聚焦④经费使用合规性材料（S25a）；平台只登记材料文本，不登记经费金额。',
     '演示数据：挂靠单位（国际商务学院）意见文本（S25a 表头「挂靠单位」）。',
     'qualified', DATE_SUB(NOW(), INTERVAL 110 DAY), 1, DATE_SUB(NOW(), INTERVAL 105 DAY),
     91032, NOW(), NOW(), 0),
    (91022, 91004, '2025-2026',
     '演示数据：四项聚焦①组织架构合理性材料（S25a）。',
     '演示数据：四项聚焦②骨干成员胜任力材料（S25a）。',
     '演示数据：四项聚焦③学期活动运行质量材料（S25a）。',
     '演示数据：四项聚焦④经费使用合规性材料（S25a）；平台只登记材料文本，不登记经费金额。',
     '演示数据：挂靠单位（会计与金融学院）意见文本（S25a 表头「挂靠单位」）。',
     'unqualified', DATE_SUB(NOW(), INTERVAL 110 DAY), 1, DATE_SUB(NOW(), INTERVAL 105 DAY),
     91032, NOW(), NOW(), 0),
    (91023, 91003, '2025-2026',
     '演示数据：四项聚焦①组织架构合理性材料（S25a）。',
     '演示数据：四项聚焦②骨干成员胜任力材料（S25a）。',
     '演示数据：四项聚焦③学期活动运行质量材料（S25a）。',
     '演示数据：四项聚焦④经费使用合规性材料（S25a）；平台只登记材料文本，不登记经费金额。',
     '演示数据：挂靠单位（国际商务学院）意见文本（S25a 表头「挂靠单位」）。',
     'deregistered', DATE_SUB(NOW(), INTERVAL 110 DAY), 1, DATE_SUB(NOW(), INTERVAL 105 DAY),
     91032, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 9. club_publicity —— 公示（2 条：1 条 closed + 1 条 open，公示期均为 5 天）
--    91031 星级评定公示 —— **逐字原文**：标题与公示期 2026-07-07 至 2026-07-11（S25b 原文
--          「公示时间为2026年7月7日至7月11日」），故状态为 closed（今日 2026-10-16 已过公示期）。
--    91032 年审结果公示 —— 演示数据（S25a 原文只给「本次公示至 7 月 28 日」，未给起始日，
--          无法派生 5 天窗口）=> 使用相对当前日期的 5 天窗口，状态 open（公示中）。
--    异议邮箱一律 twh@xujc.com（S25a / S25b 逐字）。
-- -----------------------------------------------------------------------------
INSERT INTO club_publicity
    (id, publicity_type, title, content, start_date, end_date, contact_email,
     status, publish_user_id, created_at, updated_at, deleted)
VALUES
    (91031, 'club_rating', '关于2025-2026学年学生社团星级评定结果的公示',
     '演示数据（正文）：「学工部、团委于6月组织开展了本学年学生社团星级评定工作。此次评选聚焦于社团在校园文化传承、活动策划执行以及社会实践参与等方面的卓越表现」——引自 S25b 原文；公示时间为2026年7月7日至7月11日，公示期 5 天。如有异议，请在公示期内向校团委反映；异议邮箱 twh@xujc.com。',
     '2026-07-07', '2026-07-11', 'twh@xujc.com', 'closed', 1, NOW(), NOW(), 0),
    (91032, 'club_review', '关于2025-2026学年学生社团年审结果的公示（演示）',
     '演示数据（正文）：依据 S25a《厦门大学嘉庚学院学生社团建设管理办法》，校团委社团管理部组织开展本学年学生社团年度审查，审查聚焦组织架构合理性、骨干成员胜任力、学期活动运行质量、经费使用合规性 4 项；年审结果分合格 / 不合格 / 注销三值。S25a 原文未给公示起始日（只写「本次公示至 7 月 28 日」），本行起止日期为演示用 5 天窗口，不代表学校口径。如有异议，请在公示期内向校团委反映；异议邮箱 twh@xujc.com。',
     CURDATE(), DATE_ADD(CURDATE(), INTERVAL 4 DAY), 'twh@xujc.com', 'open', 1, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 10. club_member —— 社团成员（4 条）
--     成员名册构成**无任何来源**（诚实清单 ⑧）=> 全部为演示数据，只用于验证
--     「我的社团」与 role 枚举（leader/member）；由于本机无「汤一川」等负责人账号，
--     **不伪造 leader 行**（club.leader_name 已单独登记 91008 篮球协会的负责人「汤一川」）。
-- -----------------------------------------------------------------------------
INSERT INTO club_member
    (id, club_id, user_id, role, join_date, quit_date, created_at, updated_at, deleted)
VALUES
    (91041, 91005, 4, 'member', DATE_SUB(CURDATE(), INTERVAL 200 DAY), NULL, NOW(), NOW(), 0),
    (91042, 91005, 7, 'member', DATE_SUB(CURDATE(), INTERVAL 180 DAY), NULL, NOW(), NOW(), 0),
    (91043, 91001, 6, 'member', DATE_SUB(CURDATE(), INTERVAL 150 DAY), NULL, NOW(), NOW(), 0),
    (91044, 91008, 8, 'member', DATE_SUB(CURDATE(), INTERVAL 120 DAY), NULL, NOW(), NOW(), 0);
