SELECT a.id,a.user_id,u.real_name,s.student_no,a.branch_name,a.apply_date,a.talk_date,a.status FROM party_application a JOIN users u ON u.id=a.user_id LEFT JOIN student_profile s ON s.user_id=a.user_id;
SELECT id,user_id,stage,branch_name,applicant_date,activist_date,candidate_date,probationary_date,full_date,stage_publicity_id FROM party_stage;
SELECT id,user_id,stage,material_name,required,submitted FROM party_material;
SELECT id,node_type,title,status,start_date,end_date,workdays,user_id FROM party_publicity;
SELECT id,training_name,status,start_date,end_date,days,hours,quota FROM party_training;
SELECT id,training_id,user_id,status,complete_result,complete_date,cert_no,cert_valid_until FROM party_training_member;
SELECT id,user_id,type,submitted_at,review_status,period_label FROM party_observation;
