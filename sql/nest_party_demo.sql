-- =============================================================================
-- 学工平台 · P16 学生党建 · 演示数据（sql/nest_party_demo.sql）
--
-- 幂等：全部使用固定 id 区间，文件开头先 DELETE 再 INSERT，可反复执行。
-- 时间：一律相对 NOW() 生成（DATE_ADD / DATE_SUB），演示数据不会随时间过期失效。
--
-- 本文件里的**文字**全部是平台自述口径（「演示数据」「以学校公布为准」），
-- 不含任何编造的联系方式/及格线/表格字段（§11.1 诚实清单 ①-⑥）。
-- 涉及条款的字段一律写条款引用（rule_ref），不写平台自己的判断结论。
--
-- id 区间：
--   party_application      89001-89009
--   party_stage            89011-89019
--   party_observation      89021-89029
--   party_training         89031-89039
--   party_training_member  89041-89049
--   party_publicity        89051-89059
--   party_material         89061-89079
--
-- 真实库事实（写入前已连库 SELECT 确认 users / student_profile）：
--   users.id：1 admin 系统管理员 / 2 zhangsan 张三 teacher / 3 lisi 李四 teacher /
--             4 wangxm 王小明 student 2024001（软工2401，counselor_id 3 = 李四）/
--             5 zhaoxh 赵小红 student 2024002（软工2401，counselor_id 3 = 李四）/
--             6 chendw 陈大伟 student 2024003（软工2402，counselor_id 3 = 李四）/
--             7 liusq  刘思琪 student 2024004（计科2401，counselor_id 2 = 张三）/
--             8 zhoujie 周杰  student 2024005（计科2401，counselor_id 2 = 张三）
--   => 辅导员张三(2) 名下：刘思琪(7)、周杰(8)；辅导员李四(3) 名下：王小明(4)、赵小红(5)、陈大伟(6)
--
-- 覆盖的演示场景：
--   · 2 名学生完整台账：王小明(4) 五阶段走完 applicant -> full；
--                       赵小红(5) 走到发展对象 candidate（其公示【公示中】）
--   · 3 条思想汇报/考察（1 条已审核思想汇报、1 条待审核思想汇报、1 条已审核考察记录）
--   · 1 个党校班次（报名中）+ 3 名学员（1 名已结业含证书编号与有效期、1 名审核通过、1 名已报名）
--   · 3 条公示：1 条已结束（closed）+ 1 条公示中（publicizing）+ 1 条草稿（draft）
--   · 6 条材料清单（含已交与未交）
--
-- 执行（Windows / cmd）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_party_demo.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 清理本文件的 id 区间（幂等的前提；先删子表再删父表不必要，各表相互独立）
-- -----------------------------------------------------------------------------
DELETE FROM party_application      WHERE id BETWEEN 89001 AND 89009;
DELETE FROM party_stage            WHERE id BETWEEN 89011 AND 89019;
DELETE FROM party_observation      WHERE id BETWEEN 89021 AND 89029;
DELETE FROM party_training_member  WHERE id BETWEEN 89041 AND 89049;
DELETE FROM party_training         WHERE id BETWEEN 89031 AND 89039;
DELETE FROM party_publicity        WHERE id BETWEEN 89051 AND 89059;
DELETE FROM party_material         WHERE id BETWEEN 89061 AND 89079;


-- -----------------------------------------------------------------------------
-- 1. party_application —— 入党申请登记（2 条）
--    两条都落「申请后一个月内谈话」：谈话日期 = 申请日期 + 20 天（在 1 个月内）。
-- -----------------------------------------------------------------------------
INSERT INTO party_application
    (id, user_id, branch_name, apply_date, talk_date, talk_by, talk_summary,
     internal_remark, status, created_by, created_at, updated_at, deleted)
VALUES
    (89001, 4, '软件工程系本科生第一党支部', DATE_SUB(CURDATE(), INTERVAL 8 MONTH),
     DATE_SUB(CURDATE(), INTERVAL 8 MONTH) + INTERVAL 20 DAY, '李四（支部书记）',
     '已于递交申请书后一个月内完成谈话，申请人入党动机与基本情况已了解。',
     '演示数据：支部内部备注，学生端（/api/party/my/**）服务层抹掉，仅 staff 可见。',
     'active', 3, NOW(), NOW(), 0),
    (89002, 5, '软件工程系本科生第一党支部', DATE_SUB(CURDATE(), INTERVAL 7 MONTH),
     DATE_SUB(CURDATE(), INTERVAL 7 MONTH) + INTERVAL 21 DAY, '李四（支部书记）',
     '已于递交申请书后一个月内完成谈话。',
     '演示数据：支部内部备注，学生端不下发。',
     'active', 3, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 2. party_stage —— 阶段台账（2 条）
--    王小明(4)：applicant -> activist -> candidate -> probationary -> full 五步全走完，
--               各步日期形成合理解释链（1 个月谈话 / 2 个月以上培养教育 / 1 年以上 / 预备期 1 年）。
--    赵小红(5)：走到 candidate（发展对象），公示 party_publicity 89052（公示中）。
--    内部字段 internal_note / internal_discussion 都填了演示文案，用于验证「学生端抹掉」。
-- -----------------------------------------------------------------------------
INSERT INTO party_stage
    (id, user_id, branch_name, stage,
     applicant_date, applicant_ref,
     activist_date, activist_ref,
     candidate_date, candidate_ref,
     probationary_date, probationary_ref,
     full_date, full_ref,
     internal_note, internal_discussion, stage_publicity_id,
     rollback_reason, rollback_at, created_at, updated_at, deleted)
VALUES
    (89011, 4, '软件工程系本科生第一党支部', 'full',
     DATE_SUB(CURDATE(), INTERVAL 8 MONTH), 'S24 第七条 / S23：递交入党申请书后一个月内派人谈话',
     DATE_SUB(CURDATE(), INTERVAL 6 MONTH), 'S23 第二条：入党申请人应经党支部两个月以上培养教育，才可确定为入党积极分子',
     DATE_SUB(CURDATE(), INTERVAL 3 MONTH), 'S24 第十三条 / S23：经过一年以上培养教育和考察，可列为发展对象',
     DATE_SUB(CURDATE(), INTERVAL 2 MONTH), 'S24 第三十二条：预备期从支部（党员）大会通过其为预备党员之日算起',
     DATE_SUB(CURDATE(), INTERVAL 1 MONTH), 'S24 第三十四条：党龄从预备期满转为正式党员之日算起',
     '演示数据：支部内部考察意见（学生端抹掉）。',
     '演示数据：推优讨论内容（学生端抹掉）。',
     89051, NULL, NULL, NOW(), NOW(), 0),
    (89012, 5, '软件工程系本科生第一党支部', 'candidate',
     DATE_SUB(CURDATE(), INTERVAL 7 MONTH), 'S24 第七条 / S23：递交入党申请书后一个月内派人谈话',
     DATE_SUB(CURDATE(), INTERVAL 5 MONTH), 'S23 第二条：党支部两个月以上培养教育',
     DATE_SUB(CURDATE(), INTERVAL 1 MONTH), 'S24 第十三条 / S23：一年以上培养教育和考察',
     NULL, NULL, NULL, NULL,
     '演示数据：支部内部考察意见（学生端抹掉）。',
     '演示数据：推优讨论内容（学生端抹掉）。',
     89052, NULL, NULL, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 3. party_observation —— 考察与思想汇报（3 条）
--    89021 思想汇报：王小明，已审核（reviewed）
--    89022 思想汇报：赵小红，待审核（pending）
--    89023 考察记录：赵小红，类型 observation，已审核
--    时限口径：校规「至少每季度……书面思想汇报」「超过三个月不交思想汇报可能取消资格」；
--             国标第十七条培训时长另见 party_training。只写条款引用，平台不下结论。
-- -----------------------------------------------------------------------------
INSERT INTO party_observation
    (id, user_id, type, submitted_at, period_label, content,
     review_status, review_by, review_at, review_opinion, internal_note, rule_ref,
     created_by, created_at, updated_at, deleted)
VALUES
    (89021, 4, 'thought_report', DATE_SUB(NOW(), INTERVAL 4 MONTH), '2026 年第一季度',
     '演示数据：本季度思想汇报正文（学生本人提交）。',
     'reviewed', 3, DATE_SUB(NOW(), INTERVAL 4 MONTH) + INTERVAL 3 DAY,
     '已阅，认识清晰，继续加强理论学习。',
     '演示数据：支部内部考察意见（学生端抹掉）。',
     'S23：至少每季度提交书面思想汇报；超过三个月不交可能取消入党积极分子资格',
     4, NOW(), NOW(), 0),
    (89022, 5, 'thought_report', DATE_SUB(NOW(), INTERVAL 20 DAY), '2026 年第二季度',
     '演示数据：本季度思想汇报正文（学生本人提交）。',
     'pending', NULL, NULL, NULL,
     '演示数据：支部内部考察意见（学生端抹掉）。',
     'S23：至少每季度提交书面思想汇报',
     5, NOW(), NOW(), 0),
    (89023, 5, 'observation', DATE_SUB(NOW(), INTERVAL 15 DAY), '2026 年第二季度',
     '演示数据：本季度考察情况记录（支部登记）。',
     'reviewed', 3, DATE_SUB(NOW(), INTERVAL 14 DAY),
     '考察合格，同意继续培养。',
     '演示数据：支部内部考察意见（学生端抹掉）。',
     'S24 第十一条：党支部每半年对入党积极分子进行一次考察 / S23：每季度考察（两条来源并存）',
     3, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 4. party_training —— 党校培训班次（1 条，报名中）
--    培训时长两条标准并存、平台不替学校选（§11.2-3）：
--      国标 S24 第十七条：不少于三天（或不少于二十四个学时）
--      校规 S23：五至七天（或不少于四十个学时）
--    本演示班次按**校规**口径填 6 天 / 40 学时，并在 content 里同时标注国标口径。
--    §11.1-③：党校结业标准（及格线、考核权重）无任何来源 => 不建分数/及格线字段。
-- -----------------------------------------------------------------------------
INSERT INTO party_training
    (id, training_name, term, start_date, end_date, days, hours, location, organizer, quota,
     status, content, note, created_by, created_at, updated_at, deleted)
VALUES
    (89031, '2026 年第一期入党积极分子培训班', '2026-1',
     DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_SUB(CURDATE(), INTERVAL 24 DAY),
     6, 40, '学校党委党校（演示地点）', '学校党委组织部', 60, 'signup',
     '演示数据：培训内容与课程安排。培训时长口径：国标 S24 第十七条「不少于三天（或不少于二十四个学时）」；校规 S23「五至七天（或不少于四十个学时）」。本演示班次按校规口径登记 6 天 / 40 学时，平台不替学校选择口径。',
     '演示数据：结业标准（及格线、考核权重）无公开来源，本平台不登记分数、不判定及格。',
     1, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 5. party_training_member —— 党校学员报名与结业（3 条）
--    89041 王小明(4)：completed 已结业，证书 89041 号，颁发日期 = 24 天前，
--                      有效期至 = 颁发日期 + 1 年（校规：结业证书自颁发之日起一年内有效）。
--    89042 赵小红(5)：approved 审核通过，未结业。
--    89043 陈大伟(6)：signed 已报名，待审核。
-- -----------------------------------------------------------------------------
INSERT INTO party_training_member
    (id, training_id, user_id, student_snapshot, class_snapshot, signup_at,
     status, review_by, review_at, reject_reason, complete_result, complete_date,
     cert_no, cert_issue_date, cert_valid_until, note, created_at, updated_at, deleted)
VALUES
    (89041, 89031, 4, '2024001', '软工2401', DATE_SUB(NOW(), INTERVAL 35 DAY),
     'completed', 3, DATE_SUB(NOW(), INTERVAL 34 DAY), NULL, 'pass',
     DATE_SUB(CURDATE(), INTERVAL 24 DAY), 'PX2026-89041',
     DATE_SUB(CURDATE(), INTERVAL 24 DAY), DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 24 DAY), INTERVAL 1 YEAR),
     '演示数据：结业结论由辅导员/管理员人工登记；有效期按「自颁发之日起一年内有效」推算，可人工覆盖。',
     NOW(), NOW(), 0),
    (89042, 89031, 5, '2024002', '软工2401', DATE_SUB(NOW(), INTERVAL 35 DAY),
     'approved', 3, DATE_SUB(NOW(), INTERVAL 34 DAY), NULL, NULL,
     NULL, NULL, NULL, NULL,
     '演示数据：审核通过，尚未结业。', NOW(), NOW(), 0),
    (89043, 89031, 6, '2024003', '软工2402', DATE_SUB(NOW(), INTERVAL 10 DAY),
     'signed', NULL, NULL, NULL, NULL,
     NULL, NULL, NULL, NULL,
     '演示数据：已报名，等待辅导员审核。', NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 6. party_publicity —— 公示留痕（3 条）
--    node_type 只登记 §11.1 有来源的三类节点（S22 第 11 / 14 / 25 步）。
--    89051 training_student  王小明  确定党校学员公示 —— 【已结束 closed】（已关闭）
--    89052 develop_party     赵小红  发展党员公示       —— 【公示中 publicizing】
--    89053 probation_convert 王小明  拟转正对象公示     —— 【草稿 draft】（不可 close）
--    异议入口只写校级受理渠道，**不写支部个人联系方式**（§11.2-6）；
--    「发展党员公示」的载体与受理主体无公开来源（§11.1-④）=> 只登记文本、不虚构邮箱电话。
--    公示工作日数 5：S22 三个节点均为「公示（5 个工作日）」；校规另有「公示 5-7 天」表述。
-- -----------------------------------------------------------------------------
INSERT INTO party_publicity
    (id, user_id, node_type, title, stage, start_date, end_date, workdays,
     notice_text, objection_channel, status, result, published_at, closed_at,
     created_by, created_at, updated_at, deleted)
VALUES
    (89051, 4, 'training_student', '关于确定王小明等同志为党校学员的公示', 'activist',
     DATE_SUB(CURDATE(), INTERVAL 40 DAY), DATE_SUB(CURDATE(), INTERVAL 34 DAY), 5,
     '依据 S22 第 11 步：确定党校学员后进行公示，公示 5 个工作日。演示数据。',
     '对公示对象有异议的，请向学校学生工作部（处）反映；受理方式以学校公布的校级渠道为准（本平台不登记支部个人联系方式）。',
     'closed', '公示期内未收到异议，公示通过。',
     DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 34 DAY),
     3, NOW(), NOW(), 0),
    (89052, 5, 'develop_party', '关于赵小红同志列为发展对象的公示', 'candidate',
     DATE_SUB(CURDATE(), INTERVAL 2 DAY), DATE_ADD(CURDATE(), INTERVAL 5 DAY), 5,
     '依据 S22 第 14 步：发展党员公示，公示 5 个工作日。依据 S24 第十三条 / S23：经过一年以上培养教育和考察，可列为发展对象。演示数据。',
     '对公示对象有异议的，请向学校学生工作部（处）反映；受理方式以学校公布的校级渠道为准（本平台不登记支部个人联系方式）。',
     'publicizing', NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL,
     3, NOW(), NOW(), 0),
    (89053, 4, 'probation_convert', '关于王小明同志拟转为正式党员的公示（草稿）', 'probationary',
     DATE_ADD(CURDATE(), INTERVAL 3 DAY), DATE_ADD(CURDATE(), INTERVAL 10 DAY), 5,
     '依据 S22 第 25 步：拟转正对象公示，公示 5 个工作日。依据 S24 第三十三条：本人书面转正申请 -> 党小组意见 -> 征求党员和群众意见 -> 支部委员会审查 -> 支部大会讨论表决 -> 报上级党委审批。演示数据。',
     '对公示对象有异议的，请向学校学生工作部（处）反映；受理方式以学校公布的校级渠道为准。',
     'draft', NULL, NULL, NULL,
     3, NOW(), NOW(), 0);


-- -----------------------------------------------------------------------------
-- 7. party_material —— 材料清单（6 条）
--    §11.2-8：只登记「名称 + 应交阶段 + 已交与否 + 备注 + 附件文件名」；
--             平台不生成官方表格 PDF（三张考察表字段结构未获全文，§11.1-②）。
--    材料名目取自 §11.1 有来源的表述：入党申请书、入党积极分子推优登记表（S23 表样）、
--    入党积极分子考察表、预备党员考察表、入党志愿书（S22 三张表名）、思想汇报、党校结业证书。
-- -----------------------------------------------------------------------------
INSERT INTO party_material
    (id, user_id, material_name, stage, required, submitted, submit_date,
     attachment_name, note, rule_ref, created_by, created_at, updated_at, deleted)
VALUES
    (89061, 4, '入党申请书', 'applicant', 1, 1, DATE_SUB(CURDATE(), INTERVAL 8 MONTH),
     '入党申请书_王小明.pdf', '演示数据：只登记文件名，附件体由学校自存。',
     'S23：入党申请人应递交入党申请书', 3, NOW(), NOW(), 0),
    (89062, 4, '入党积极分子推优登记表', 'activist', 1, 1, DATE_SUB(CURDATE(), INTERVAL 6 MONTH),
     '推优登记表_王小明.pdf', '演示数据：表样字段见 S23（姓名/性别/出生年月/照片/民族/籍贯/入党申请时间/院系/专业班级/所在支部/担任职务/奖惩情况/家庭主要成员及重要社会关系/团支部意见/分团委意见/填表人签名）；平台不生成该表 PDF。',
     'S23：附件《入党积极分子推优登记表》', 3, NOW(), NOW(), 0),
    (89063, 4, '入党积极分子考察表', 'activist', 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 MONTH),
     '积极分子考察表_王小明.pdf', '演示数据：表样字段结构未获全文，平台只登记名称与状态，不生成官方表格。',
     'S22：涉及《入党积极分子考察表》', 3, NOW(), NOW(), 0),
    (89064, 4, '入党志愿书', 'candidate', 1, 1, DATE_SUB(CURDATE(), INTERVAL 2 MONTH),
     '入党志愿书_王小明.pdf', '演示数据：表样字段结构未获全文，平台只登记名称与状态。',
     'S22：涉及《入党志愿书》', 3, NOW(), NOW(), 0),
    (89065, 4, '预备党员考察表', 'probationary', 1, 0, NULL,
     NULL, '演示数据：未交。表样字段结构未获全文，平台只登记名称与状态。',
     'S22：涉及《预备党员考察表》', 3, NOW(), NOW(), 0),
    (89066, 5, '入党积极分子考察表', 'activist', 1, 1, DATE_SUB(CURDATE(), INTERVAL 5 MONTH),
     '积极分子考察表_赵小红.pdf', '演示数据：已交。',
     'S22：涉及《入党积极分子考察表》', 3, NOW(), NOW(), 0);
