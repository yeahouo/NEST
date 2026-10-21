-- =============================================================================
-- 学工平台 · P19 生源地信用助学贷款 · 演示数据（可选，可重复执行）
-- sql/nest_loan_demo.sql
--
-- 用途：把「贷款登记」区块的空状态填满，供试用剧本演示「提交 → 辅导员核验 →
--       学校确认 → 退回」这条流程。
--
-- 口径声明（重要）：
--   1) 这是**演示数据**，字段口径为学校自定（P19 的字段表无官方出处），
--      正式部署时以学校实际要登记的字段为准，别把这文件当标准。
--   2) 学生账号沿用 sql/nest_xuegong_demo.sql 里的 chendw/liusq/wangxm/zhaoxh。
--   3) 辅导员分派与真实 scoping 一致：chendw(6)/wangxm(4)/zhaoxh(5) → 李四(3)，
--      liusq(7) → 张三(2)。
--   4) 固定 id 区间 97001-97099，开头先 DELETE 再 INSERT，可反复执行。
-- =============================================================================

DELETE FROM student_loan WHERE id BETWEEN 97001 AND 97099;

INSERT INTO student_loan
  (id, user_id, academic_year, origin_province, origin_city, origin_county,
   bank_name, contract_no, receipt_no, amount, disbursed, disbursed_at,
   status, return_reason, submitted_at, counselor_id, counselor_at,
   reviewer_id, reviewed_at, remark)
VALUES
  -- 待核验：刘思琪刚提交，还没人看
  (97001, 7, '2025-2026', '福建省', '泉州市', '安溪县',
   '国家开发银行', '35052420250901', NULL, 12000.00, 0, NULL,
   'submitted', NULL, NOW(), NULL, NULL, NULL, NULL, '首贷，随录取通知书办理'),

  -- 辅导员已核：陈德文已过辅导员核验，等学校资助中心确认
  (97002, 6, '2025-2026', '福建省', '厦门市', '集美区',
   '国家开发银行', '35021120250902', 'R20250902001', 8000.00, 0, NULL,
   'counselor_approved', NULL, NOW() - INTERVAL 1 DAY, 3, NOW() - INTERVAL 1 DAY,
   NULL, NULL, '回执已交辅导员'),

  -- 学校已确认且已到账：王小明上一学年的贷款已完成
  (97003, 4, '2024-2025', '江西省', '赣州市', '于都县',
   '国家开发银行', '36073120240903', 'R20240903002', 8000.00, 1, '2025-02-20',
   'confirmed', NULL, NOW() - INTERVAL 30 DAY, 3, NOW() - INTERVAL 30 DAY,
   1, NOW() - INTERVAL 20 DAY, '已放款'),

  -- 退回待改：赵小红缺合同编号被退回
  (97004, 5, '2024-2025', '河南省', '洛阳市', '宜阳县',
   '国家开发银行', NULL, NULL, 8000.00, 0, NULL,
   'returned', '回执信息不完整，请补充合同编号后重新提交',
   NOW() - INTERVAL 2 DAY, 3, NOW() - INTERVAL 2 DAY, NULL, NULL, NULL);
