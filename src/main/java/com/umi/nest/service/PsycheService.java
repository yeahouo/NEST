package com.umi.nest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.PsycheAnswer;
import com.umi.nest.entity.PsycheAppointment;
import com.umi.nest.entity.PsycheBatch;
import com.umi.nest.entity.PsycheFocus;
import com.umi.nest.entity.PsycheFollowup;
import com.umi.nest.entity.PsycheInstrument;
import com.umi.nest.entity.PsycheReferral;
import com.umi.nest.entity.PsycheTask;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.PsycheAnswerMapper;
import com.umi.nest.mapper.PsycheAppointmentMapper;
import com.umi.nest.mapper.PsycheBatchMapper;
import com.umi.nest.mapper.PsycheFocusMapper;
import com.umi.nest.mapper.PsycheFollowupMapper;
import com.umi.nest.mapper.PsycheInstrumentMapper;
import com.umi.nest.mapper.PsycheReferralMapper;
import com.umi.nest.mapper.PsycheTaskMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学工 P14 心理健康 · 普查主服务（量表 / 批次 / 名单 / 作答 / 结果回填 / 关注台账 / 跟进 / 转介 / 统计 / 导出）。
 *
 * <p>硬性口径（出处见《学工平台功能对齐说明》§9 与 sql/nest_psyche_work.sql 文件头）：</p>
 * <ul>
 *   <li>平台**不做量表计分诊断**：score_band 只按 band_json 折算标签，result_level 只由中心/辅导员回填；</li>
 *   <li>状态变更一律 CAS（WHERE id=? AND status=?），影响行数 ≠ 1 → 400「状态已变化，请刷新后重试」；</li>
 *   <li>S16 保密性：列表姓名打码（保留姓，其余 *）、学号只留后四位；关注台账与转介只给 admin 与该生辅导员；</li>
 *   <li>学生端不给结果等级原文与任何解释文案（学生只看自己）；</li>
 *   <li>所有 now() 用 {@code LocalDateTime.now().withNano(0)}（MySQL DATETIME(0) 会四舍五入小数秒）。</li>
 * </ul>
 */
// 显式 bean 名：com.umi.nest.dorm.service.PsycheService（宿舍心理特征提取）与本类默认 bean 名同为
// psycheService，组件扫描期会抛 ConflictingBeanDefinitionException（2026-10-16 与 PsycheController 同批踩到）。
@Service("psycheWorkService")
public class PsycheService {

    /** 员工角色（与 P12/P13 一致）。 */
    private static final Set<String> STAFF_ROLES = Set.of("teacher", "admin");

    /** 结果等级取值域（回填字段，平台不自动判定）。 */
    private static final Set<String> RESULT_LEVELS = Set.of("normal", "attention", "warning");

    /** 关注等级取值域。 */
    private static final Set<String> FOCUS_LEVELS = Set.of("general", "key", "risk");

    /** 关注来源取值域。 */
    private static final Set<String> FOCUS_SOURCES =
            Set.of("task", "apply", "counselor", "appointment", "referral", "other");

    /** 转介去向取值域。 */
    private static final Set<String> REFERRAL_TARGETS = Set.of("campus", "hospital", "family");

    /** 转介来源取值域。 */
    private static final Set<String> REFERRAL_SOURCES = Set.of("task", "focus", "appointment", "other");

    /** 关注台账与转介共用的状态取值域：open 待跟进 / following 跟进中 / closed 已关闭（已结案）。 */
    private static final Set<String> FOCUS_STATUSES = Set.of("open", "following", "closed");

    private static final String CAS_FAIL = "状态已变化，请刷新后重试";
    private static final String OUT_OF_WINDOW = "当前不在普查时间内";
    private static final String NOT_ALL_ANSWERED = "请完成全部题目后再提交";

    /** 导出最多一次的取数上限（不分页，交给 CSV 一次性拉全）。 */
    private static final int MAX_EXPORT = 100000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private PsycheInstrumentMapper instrumentMapper;

    @Autowired
    private PsycheBatchMapper batchMapper;

    @Autowired
    private PsycheTaskMapper taskMapper;

    @Autowired
    private PsycheAnswerMapper answerMapper;

    @Autowired
    private PsycheFocusMapper focusMapper;

    @Autowired
    private PsycheFollowupMapper followupMapper;

    @Autowired
    private PsycheReferralMapper referralMapper;

    @Autowired
    private PsycheAppointmentMapper appointmentMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------------- 量表

    /**
     * 量表列表（登录可读）：只回启用中的量表。
     *
     * <p>员工拿到完整实体（含题本）；学生只拿到公开字段——学生取题走 /tasks/{id}，
     * 避免一次下发全部题本。</p>
     */
    public Result<?> listInstruments(Long actorId, String role) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        List<PsycheInstrument> list = instrumentMapper.list(1);
        if (!isStudent(role)) {
            return Result.success(list);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PsycheInstrument it : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("code", it.getCode());
            m.put("name", it.getName());
            m.put("guide_text", it.getGuideText());
            m.put("guideText", it.getGuideText());
            m.put("item_count", it.getItemCount());
            m.put("itemCount", it.getItemCount());
            out.add(m);
        }
        return Result.success(out);
    }

    /** 新建量表（admin）：code 唯一，重复 400；item_json / band_json 必须能解析成数组。 */
    public Result<?> createInstrument(Long actorId, String role, Map<String, Object> body) {
        Result<?> deny = guardAdmin(actorId, role);
        if (deny != null) {
            return deny;
        }
        Map<String, Object> b = bodyOf(body);
        String code = trimToNull(text(pick(b, "code")));
        String name = trimToNull(text(pick(b, "name")));
        if (code == null) {
            return Result.error("请填写量表编码");
        }
        if (code.length() > 64) {
            return Result.error("量表编码不能超过 64 个字符");
        }
        if (name == null) {
            return Result.error("请填写量表名称");
        }
        if (name.length() > 128) {
            return Result.error("量表名称不能超过 128 个字符");
        }
        String sourceNote = trimToNull(text(pick(b, "sourceNote", "source_note")));
        if (sourceNote != null && sourceNote.length() > 500) {
            return Result.error("出处说明不能超过 500 个字符");
        }
        String itemJson = trimToNull(text(pick(b, "itemJson", "item_json")));
        if (itemJson != null) {
            JsonNode node = readJson(itemJson);
            if (node == null || !node.isArray()) {
                return Result.error("题本 JSON 必须是题目数组");
            }
        }
        String bandJson = trimToNull(text(pick(b, "bandJson", "band_json")));
        if (bandJson != null) {
            JsonNode node = readJson(bandJson);
            if (node == null || !node.isArray()) {
                return Result.error("分档阈值 JSON 必须是数组");
            }
        }
        String guideText = trimToNull(text(pick(b, "guideText", "guide_text")));
        if (guideText != null && guideText.length() > 1000) {
            return Result.error("作答提示不能超过 1000 个字符");
        }
        if (instrumentMapper.findByCode(code) != null) {
            return Result.error("量表编码已存在");
        }
        Integer active = flag(pick(b, "active"));
        PsycheInstrument instrument = new PsycheInstrument();
        instrument.setCode(code);
        instrument.setName(name);
        instrument.setSourceNote(sourceNote);
        instrument.setItemJson(itemJson);
        instrument.setBandJson(bandJson);
        instrument.setItemCount(itemCountOf(itemJson));
        instrument.setGuideText(guideText);
        instrument.setActive(active == null ? 1 : active);
        instrument.setCreatedBy(actorId);
        instrumentMapper.insert(instrument);
        return Result.success(instrumentMapper.findById(instrument.getId()));
    }

    // ---------------------------------------------------------------- 批次

    /** 批次列表（登录可读）：非 admin 自动隐藏 status='draft'（与 P12/P13 同口径）。 */
    public Result<?> listBatches(Long actorId, String role, String status, String keyword) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        return Result.success(batchMapper.list(!isAdmin(role), trimToNull(status), trimToNull(keyword)));
    }

    /** 新建批次（admin）：新批次一律 draft，发布走 /publish。 */
    public Result<?> createBatch(Long actorId, String role, Map<String, Object> body) {
        Result<?> deny = guardAdmin(actorId, role);
        if (deny != null) {
            return deny;
        }
        Map<String, Object> b = bodyOf(body);
        String batchName = trimToNull(text(pick(b, "batchName", "batch_name")));
        String grade = trimToNull(text(pick(b, "grade")));
        if (batchName == null) {
            return Result.error("请填写批次名称");
        }
        if (batchName.length() > 128) {
            return Result.error("批次名称不能超过 128 个字符");
        }
        if (grade == null) {
            return Result.error("请填写普查对象年级");
        }
        if (grade.length() > 16) {
            return Result.error("年级不能超过 16 个字符");
        }
        String academicYear = trimToNull(text(pick(b, "academicYear", "academic_year")));
        if (academicYear != null && academicYear.length() > 16) {
            return Result.error("学年不能超过 16 个字符");
        }
        Long instrumentId = longOf(pick(b, "instrumentId", "instrument_id"));
        if (instrumentId != null && instrumentMapper.findById(instrumentId) == null) {
            return Result.error("量表不存在");
        }
        LocalDateTime openAt = parseDateTime(pick(b, "openAt", "open_at"));
        if (pick(b, "openAt", "open_at") != null && openAt == null) {
            return Result.error("开放时间格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        LocalDateTime closeAt = parseDateTime(pick(b, "closeAt", "close_at"));
        if (pick(b, "closeAt", "close_at") != null && closeAt == null) {
            return Result.error("关闭时间格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        if (openAt != null && closeAt != null && closeAt.isBefore(openAt)) {
            return Result.error("关闭时间不能早于开放时间");
        }
        String interviewNote = trimToNull(text(pick(b, "interviewNote", "interview_note")));
        if (interviewNote != null && interviewNote.length() > 500) {
            return Result.error("抽查访谈说明不能超过 500 个字符");
        }
        String noticeTitle = trimToNull(text(pick(b, "noticeTitle", "notice_title")));
        if (noticeTitle != null && noticeTitle.length() > 255) {
            return Result.error("通知标题不能超过 255 个字符");
        }
        String noticeUrl = trimToNull(text(pick(b, "noticeUrl", "notice_url")));
        if (noticeUrl != null && noticeUrl.length() > 500) {
            return Result.error("通知链接不能超过 500 个字符");
        }
        PsycheBatch batch = new PsycheBatch();
        batch.setBatchName(batchName);
        batch.setGrade(grade);
        batch.setAcademicYear(academicYear);
        batch.setInstrumentId(instrumentId);
        batch.setOpenAt(openAt);
        batch.setCloseAt(closeAt);
        Integer interviewRequired = flag(pick(b, "interviewRequired", "interview_required"));
        batch.setInterviewRequired(interviewRequired == null ? 0 : interviewRequired);
        batch.setInterviewNote(interviewNote);
        batch.setNoticeTitle(noticeTitle);
        batch.setNoticeUrl(noticeUrl);
        batch.setStatus("draft");
        batch.setCreatedBy(actorId);
        batchMapper.insert(batch);
        return Result.success(batchMapper.findById(batch.getId()));
    }

    /** 增量修改批次（admin）：没传的字段保持现值；CAS 校验 status 没被别人改过。 */
    public Result<?> updateBatch(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> deny = guardAdmin(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        Map<String, Object> b = bodyOf(body);
        String batchName = has(b, "batchName", "batch_name")
                ? trimToNull(text(pick(b, "batchName", "batch_name"))) : exist.getBatchName();
        String grade = has(b, "grade") ? trimToNull(text(pick(b, "grade"))) : exist.getGrade();
        if (batchName == null) {
            return Result.error("请填写批次名称");
        }
        if (batchName.length() > 128) {
            return Result.error("批次名称不能超过 128 个字符");
        }
        if (grade == null) {
            return Result.error("请填写普查对象年级");
        }
        if (grade.length() > 16) {
            return Result.error("年级不能超过 16 个字符");
        }
        String academicYear = has(b, "academicYear", "academic_year")
                ? trimToNull(text(pick(b, "academicYear", "academic_year"))) : exist.getAcademicYear();
        if (academicYear != null && academicYear.length() > 16) {
            return Result.error("学年不能超过 16 个字符");
        }
        Long instrumentId = has(b, "instrumentId", "instrument_id")
                ? longOf(pick(b, "instrumentId", "instrument_id")) : exist.getInstrumentId();
        if (instrumentId != null && instrumentMapper.findById(instrumentId) == null) {
            return Result.error("量表不存在");
        }
        LocalDateTime openAt = exist.getOpenAt();
        if (has(b, "openAt", "open_at")) {
            openAt = parseDateTime(pick(b, "openAt", "open_at"));
            if (openAt == null) {
                return Result.error("开放时间格式不正确（yyyy-MM-dd HH:mm:ss）");
            }
        }
        LocalDateTime closeAt = exist.getCloseAt();
        if (has(b, "closeAt", "close_at")) {
            closeAt = parseDateTime(pick(b, "closeAt", "close_at"));
            if (closeAt == null) {
                return Result.error("关闭时间格式不正确（yyyy-MM-dd HH:mm:ss）");
            }
        }
        if (openAt != null && closeAt != null && closeAt.isBefore(openAt)) {
            return Result.error("关闭时间不能早于开放时间");
        }
        String interviewNote = has(b, "interviewNote", "interview_note")
                ? trimToNull(text(pick(b, "interviewNote", "interview_note"))) : exist.getInterviewNote();
        if (interviewNote != null && interviewNote.length() > 500) {
            return Result.error("抽查访谈说明不能超过 500 个字符");
        }
        String noticeTitle = has(b, "noticeTitle", "notice_title")
                ? trimToNull(text(pick(b, "noticeTitle", "notice_title"))) : exist.getNoticeTitle();
        if (noticeTitle != null && noticeTitle.length() > 255) {
            return Result.error("通知标题不能超过 255 个字符");
        }
        String noticeUrl = has(b, "noticeUrl", "notice_url")
                ? trimToNull(text(pick(b, "noticeUrl", "notice_url"))) : exist.getNoticeUrl();
        if (noticeUrl != null && noticeUrl.length() > 500) {
            return Result.error("通知链接不能超过 500 个字符");
        }
        PsycheBatch merged = new PsycheBatch();
        merged.setId(id);
        merged.setBatchName(batchName);
        merged.setGrade(grade);
        merged.setAcademicYear(academicYear);
        merged.setInstrumentId(instrumentId);
        merged.setOpenAt(openAt);
        merged.setCloseAt(closeAt);
        if (has(b, "interviewRequired", "interview_required")) {
            Integer flagValue = flag(pick(b, "interviewRequired", "interview_required"));
            merged.setInterviewRequired(flagValue == null ? exist.getInterviewRequired() : flagValue);
        } else {
            merged.setInterviewRequired(exist.getInterviewRequired());
        }
        merged.setInterviewNote(interviewNote);
        merged.setNoticeTitle(noticeTitle);
        merged.setNoticeUrl(noticeUrl);
        // 状态前置校验：已结束的批次不可再改。
        // 注意 CAS 的 fromStatus 取自「刚读到的状态」，单靠它挡不住非法迁移（WHERE status=? 恒成立），
        // 所以每次迁移都必须先显式校验一遍 from 状态。
        if ("closed".equals(exist.getStatus())) {
            return Result.error(CAS_FAIL);
        }
        int rows = batchMapper.update(merged, exist.getStatus());
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success(batchMapper.findById(id));
    }

    /** 发布批次（admin，只允许 draft→open，写 published_at；非 draft → 400「状态已变化，请刷新后重试」）。 */
    public Result<?> publishBatch(Long actorId, String role, Long id) {
        Result<?> deny = guardAdmin(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        if (exist.getStatus() != null && !"draft".equals(exist.getStatus())) {
            return Result.error(CAS_FAIL);
        }
        int rows = batchMapper.publish(id, exist.getStatus(), LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success(batchMapper.findById(id));
    }

    /**
     * 关闭批次（admin，只允许 open→closed，写 closed_at；非 open → 400「状态已变化，请刷新后重试」）。
     *
     * <p>S8：必须完成所有题目，否则测试无效、我处将按照未参加测试处理
     * ⇒ 关窗时把该批次 status IN ('pending','in_progress') 的名单置为 absent。</p>
     */
    public Result<?> closeBatch(Long actorId, String role, Long id) {
        Result<?> deny = guardAdmin(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        if (exist.getStatus() != null && !"open".equals(exist.getStatus())) {
            return Result.error(CAS_FAIL);
        }
        int rows = batchMapper.close(id, exist.getStatus(), LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        int absent = taskMapper.markAbsentByBatch(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch", batchMapper.findById(id));
        data.put("absent", absent);
        return Result.success("已关闭批次，未作答名单已按未参加处理", data);
    }

    // ---------------------------------------------------------------- 名单与进度

    /**
     * 批次名单（admin/teacher）：分页 + 状态计数；**列表脱敏**（姓名保留姓、学号只留后四位）。
     */
    public Result<?> listTasks(Long actorId, String role, Long batchId, String status, String className,
                               String keyword, int page, int size) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        if (batchId == null) {
            return Result.error("请指定批次");
        }
        if (batchMapper.findById(batchId) == null) {
            return Result.error(404, "批次不存在");
        }
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        String keywordValue = trimToNull(keyword);
        String statusValue = trimToNull(status);
        String classValue = trimToNull(className);
        List<PsycheTask> rows = taskMapper.list(batchId, statusValue, classValue, keywordValue, (p - 1) * s, s);
        int total = taskMapper.count(batchId, statusValue, classValue, keywordValue);
        for (PsycheTask row : rows) {
            row.setStudentName(maskName(row.getStudentName()));
            row.setStudentNo(tail4(row.getStudentNo()));
        }
        Map<String, Object> data = pageOf(rows, total, p, s);
        data.put("stats", taskStats(batchId));
        return Result.success(data);
    }

    /**
     * 导入普查名单（admin/teacher）：body 二选一 {studentIds:[...]} 或 {csv:"学号,姓名\n…"}。
     *
     * <p>按 users.student_id / users.username 匹配**学生**；已存在的名单跳过；返回
     * {created,skipped,notFound}。</p>
     */
    public Result<?> importTasks(Long actorId, String role, Long batchId, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        if (batchId == null) {
            return Result.error("请指定批次");
        }
        PsycheBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        Map<String, Object> b = bodyOf(body);
        List<String> tokens = new ArrayList<>();
        Object ids = pick(b, "studentIds", "student_ids");
        if (ids instanceof List<?>) {
            for (Object one : (List<?>) ids) {
                String token = trimToNull(text(one));
                if (token != null) {
                    tokens.add(token);
                }
            }
        } else if (ids != null) {
            for (String one : String.valueOf(ids).split("[,，;；\\s]+")) {
                String token = trimToNull(one);
                if (token != null) {
                    tokens.add(token);
                }
            }
        }
        String csv = trimToNull(text(pick(b, "csv")));
        if (csv != null) {
            for (String line : csv.split("\\r?\\n")) {
                String row = line.replace("\uFEFF", "").trim();
                if (row.isEmpty()) {
                    continue;
                }
                String first = row.split("[,，\\t]")[0].trim();
                if (first.isEmpty()) {
                    continue;
                }
                // 跳过表头（学号,姓名）
                if (first.contains("学号") || "student_id".equalsIgnoreCase(first)
                        || "studentId".equalsIgnoreCase(first)) {
                    continue;
                }
                tokens.add(first);
            }
        }
        if (tokens.isEmpty()) {
            return Result.error("请提供 studentIds 或 csv 文本");
        }
        Integer totalItems = instrumentItemsOf(batch);
        int created = 0;
        int skipped = 0;
        List<String> notFound = new ArrayList<>();
        for (String token : tokens) {
            User student = resolveStudent(token);
            if (student == null) {
                if (!notFound.contains(token)) {
                    notFound.add(token);
                }
                continue;
            }
            if (taskMapper.findByBatchAndStudent(batchId, student.getId()) != null) {
                skipped++;
                continue;
            }
            PsycheTask task = new PsycheTask();
            task.setBatchId(batchId);
            task.setStudentId(student.getId());
            task.setStatus("pending");
            task.setAnswerCount(0);
            task.setTotalItems(totalItems);
            task.setInterviewed(0);
            taskMapper.insert(task);
            created++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("skipped", skipped);
        data.put("notFound", notFound);
        return Result.success(data);
    }

    /** 批次统计（admin/teacher）：只回聚合数，不给可反查个人的明细。 */
    public Result<?> batchStats(Long actorId, String role, Long batchId) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        if (batchId == null) {
            return Result.error("请指定批次");
        }
        PsycheBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch", batch);
        data.put("batchId", batch.getId());
        data.put("batchName", batch.getBatchName());
        data.putAll(taskStats(batchId));
        data.put("byClass", groupRows(taskMapper.countGroupByClass(batchId), "class_name"));
        data.put("byMajor", groupRows(taskMapper.countGroupByMajor(batchId), "major"));
        data.put("focusLevels", focusLevels(null, batchId));
        return Result.success(data);
    }

    /** 抽查面对面访谈记录（S8）：teacher 只能填该生辅导员是自己名下的名单。 */
    public Result<?> interview(Long actorId, String role, Long taskId, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheTask task = taskMapper.findById(taskId);
        if (task == null) {
            return Result.error(404, "名单不存在");
        }
        if (!canTouch(actorId, role, task.getStudentId())) {
            return Result.error(403, "只能填写名下学生的访谈记录");
        }
        Map<String, Object> b = bodyOf(body);
        Integer interviewed = flag(pick(b, "interviewed"));
        if (interviewed == null) {
            interviewed = 1;
        }
        String note = trimToNull(text(pick(b, "note", "interviewNote", "interview_note")));
        if (note != null && note.length() > 500) {
            return Result.error("访谈记录不能超过 500 个字符");
        }
        LocalDateTime at = parseDateTime(pick(b, "interviewedAt", "interviewed_at"));
        if (at == null) {
            at = LocalDateTime.now().withNano(0);
        }
        int rows = taskMapper.updateInterview(taskId, interviewed, note, actorId, at);
        if (rows != 1) {
            return Result.error("访谈记录写入失败，请刷新后重试");
        }
        return Result.success(taskMapper.findById(taskId));
    }

    // ---------------------------------------------------------------- 学生端

    /**
     * 我的心理健康（学生）：{batches,appointments,focusAdvice}。
     *
     * <p>**不含 result_level 原文**（§9 口径 7：自愿性与保密性原则下不给学生贴标签），
     * 只回一张建议卡文本；预约不下发中心内部备注 note。</p>
     */
    public Result<?> my(Long actorId, String role) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可用");
        }
        List<Map<String, Object>> batches = new ArrayList<>();
        for (PsycheTask task : taskMapper.listByStudent(actorId)) {
            PsycheBatch batch = batchMapper.findById(task.getBatchId());
            if (batch == null || "draft".equals(batch.getStatus())) {
                continue;
            }
            PsycheInstrument instrument = batch.getInstrumentId() == null
                    ? null : instrumentMapper.findById(batch.getInstrumentId());
            task.setResultLevel(null);
            task.setResultNote(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch", batch);
            row.put("instrumentName", instrument == null ? "" : instrument.getName());
            row.put("myTask", task);
            batches.add(row);
        }
        List<com.umi.nest.entity.PsycheAppointment> appointments = appointmentRows(actorId);
        for (com.umi.nest.entity.PsycheAppointment one : appointments) {
            one.setNote(null);
        }
        boolean show = false;
        for (PsycheFocus focus : focusMapper.listByStudent(actorId)) {
            if (!"closed".equals(focus.getStatus())) {
                show = true;
                break;
            }
        }
        Map<String, Object> advice = new LinkedHashMap<>();
        advice.put("show", show);
        advice.put("text", "心理服务中心建议你与辅导员或中心联系");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batches", batches);
        data.put("appointments", appointments);
        data.put("focusAdvice", advice);
        return Result.success(data);
    }

    /**
     * 名单详情（学生本人 / admin / 该生辅导员）：{task,batch,instrument,answer}。
     *
     * <p>窗口外：已完成的结果仍可查看；未开始且窗口外 → 400「当前不在普查时间内」。
     * 窗口内第一次拉题把 pending 推进到 in_progress 并落 started_at。</p>
     *
     * <p>**学生端抹掉 result_level / result_note**（保密口径 §9.2-7：服务端不下发，不靠前端不渲染）。</p>
     */
    public Result<?> taskDetail(Long actorId, String role, Long taskId) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        PsycheTask task = taskMapper.findById(taskId);
        if (task == null) {
            return Result.error(404, "名单不存在");
        }
        Result<?> denied = requireTaskRead(actorId, role, task);
        if (denied != null) {
            return denied;
        }
        PsycheBatch batch = batchMapper.findById(task.getBatchId());
        PsycheInstrument instrument = batch == null || batch.getInstrumentId() == null
                ? null : instrumentMapper.findById(batch.getInstrumentId());
        boolean inside = inWindow(batch);
        if (!"completed".equals(task.getStatus()) && !inside) {
            return Result.error(OUT_OF_WINDOW);
        }
        if (inside && "pending".equals(task.getStatus())) {
            Integer total = instrument == null ? null : instrument.getItemCount();
            int rows = taskMapper.startTask(taskId, total, LocalDateTime.now().withNano(0));
            if (rows == 1) {
                task.setStatus("in_progress");
                task.setTotalItems(total);
                task.setStartedAt(LocalDateTime.now().withNano(0));
            }
        }
        if (isStudent(role)) {
            task.setResultLevel(null);
            task.setResultNote(null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("batch", batch);
        data.put("instrument", instrumentView(instrument));
        data.put("answer", answerMapper.findByTaskId(taskId));
        return Result.success(data);
    }

    /**
     * 提交整卷（学生本人）。
     *
     * <p>必须答完全部题目（answer_count == 总量表题数）→ 否则 400「请完成全部题目后再提交」；
     * 窗口校验 open_at ≤ now ≤ close_at → 否则 400「当前不在普查时间内」；
     * CAS 把名单置 completed 并写 score_total / score_band（只按 band_json 折算标签，不做临床解释）。</p>
     */
    public Result<?> submit(Long actorId, String role, Long taskId, Map<String, Object> body) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        PsycheTask task = taskMapper.findById(taskId);
        if (task == null) {
            return Result.error(404, "名单不存在");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生本人可提交普查问卷");
        }
        if (task.getStudentId() == null || !task.getStudentId().equals(actorId)) {
            return Result.error(403, "只能提交自己的普查问卷");
        }
        if ("completed".equals(task.getStatus())) {
            return Result.error("该量表已提交，不能重复提交");
        }
        PsycheBatch batch = batchMapper.findById(task.getBatchId());
        PsycheInstrument instrument = batch == null || batch.getInstrumentId() == null
                ? null : instrumentMapper.findById(batch.getInstrumentId());
        if (instrument == null) {
            return Result.error("该批次未绑定量表，无法作答");
        }
        Integer total = instrument.getItemCount();
        if (total == null || total <= 0) {
            return Result.error("该量表题本未导入，无法作答");
        }
        if (!inWindow(batch)) {
            return Result.error(OUT_OF_WINDOW);
        }
        Map<String, Object> b = bodyOf(body);
        Object raw = pick(b, "answers");
        List<Map<String, Object>> answers = new ArrayList<>();
        Set<Integer> answered = new HashSet<>();
        BigDecimal score = BigDecimal.ZERO;
        if (raw instanceof List<?>) {
            for (Object one : (List<?>) raw) {
                if (!(one instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) one;
                Integer no = integer(row.get("no"));
                Integer value = integer(row.get("value"));
                if (no == null || value == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("no", no);
                item.put("value", value);
                answers.add(item);
                answered.add(no);
                score = score.add(BigDecimal.valueOf(value));
            }
        }
        if (answers.size() != total || answered.size() != total) {
            return Result.error(NOT_ALL_ANSWERED);
        }
        String band = bandOf(instrument.getBandJson(), score);
        Integer duration = integer(pick(b, "durationSeconds", "duration_seconds"));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = taskMapper.submit(taskId, task.getStatus(), answers.size(), total, score, band, duration, now);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PsycheAnswer answer = new PsycheAnswer();
        answer.setTaskId(taskId);
        answer.setStudentId(actorId);
        answer.setAnswerJson(toJson(answers));
        answer.setAnswerCount(answers.size());
        answer.setTotalItems(total);
        answer.setScoreTotal(score);
        answer.setScoreBand(band);
        answer.setDurationSeconds(duration);
        answer.setSubmittedAt(now);
        answerMapper.insert(answer);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", taskMapper.findById(taskId));
        data.put("scoreTotal", score);
        data.put("scoreBand", band);
        return Result.success(data);
    }

    /**
     * 结果查看（学生本人 / admin / 该生辅导员）：**学生端由服务端抹掉 resultLevel / resultNote**
     * （保密口径 §9.2-7，不再依赖前端不渲染）。
     */
    public Result<?> taskResult(Long actorId, String role, Long taskId) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        PsycheTask task = taskMapper.findById(taskId);
        if (task == null) {
            return Result.error(404, "名单不存在");
        }
        Result<?> denied = requireTaskRead(actorId, role, task);
        if (denied != null) {
            return denied;
        }
        if (isStudent(role)) {
            task.setResultLevel(null);
            task.setResultNote(null);
        }
        return Result.success(resultView(task, isStudent(role)));
    }

    /** 回填结果等级（admin/teacher，teacher 只能是该生辅导员）：只接受 normal / attention / warning。 */
    public Result<?> updateResult(Long actorId, String role, Long taskId, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheTask task = taskMapper.findById(taskId);
        if (task == null) {
            return Result.error(404, "名单不存在");
        }
        if (!canTouch(actorId, role, task.getStudentId())) {
            return Result.error(403, "只能回填名下学生的结果等级");
        }
        Map<String, Object> b = bodyOf(body);
        String level = trimToNull(text(pick(b, "resultLevel", "result_level")));
        if (level == null || !RESULT_LEVELS.contains(level)) {
            return Result.error("结果等级只接受 normal / attention / warning");
        }
        String note = trimToNull(text(pick(b, "resultNote", "result_note")));
        if (note != null && note.length() > 500) {
            return Result.error("结果备注不能超过 500 个字符");
        }
        int rows = taskMapper.updateResult(taskId, level, note, actorId);
        if (rows != 1) {
            return Result.error("结果回填失败，请刷新后重试");
        }
        return Result.success(resultView(taskMapper.findById(taskId), false));
    }

    // ---------------------------------------------------------------- 关注台账

    /** 关注台账列表（admin/teacher；teacher 只能看名下学生或自己负责的台账）：列表脱敏。 */
    public Result<?> listFocus(Long actorId, String role, String level, String status, String className,
                               String keyword, int page, int size) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        String levelValue = trimToNull(level);
        String statusValue = trimToNull(status);
        String classValue = trimToNull(className);
        String keywordValue = trimToNull(keyword);
        Long scope = listScope(actorId, role);
        List<PsycheFocus> rows = focusMapper.list(levelValue, statusValue, classValue, keywordValue,
                scope, (p - 1) * s, s);
        int total = focusMapper.count(levelValue, statusValue, classValue, keywordValue, scope);
        for (PsycheFocus row : rows) {
            row.setStudentName(maskName(row.getStudentName()));
            row.setStudentNo(tail4(row.getStudentNo()));
        }
        return Result.success(pageOf(rows, total, p, s));
    }

    /** 建关注台账（admin/teacher，且只能给名下学生建）。 */
    public Result<?> createFocus(Long actorId, String role, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        Map<String, Object> b = bodyOf(body);
        Long studentId = longOf(pick(b, "studentId", "student_id"));
        if (studentId == null) {
            return Result.error("请指定学生");
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error("学生不存在");
        }
        if (!canTouch(actorId, role, studentId)) {
            return Result.error(403, "只能给自己名下的学生建关注台账");
        }
        String level = trimToNull(text(pick(b, "level")));
        if (level == null) {
            level = "general";
        }
        if (!FOCUS_LEVELS.contains(level)) {
            return Result.error("关注等级只接受 general / key / risk");
        }
        String source = trimToNull(text(pick(b, "source")));
        if (source == null) {
            source = "task";
        }
        if (!FOCUS_SOURCES.contains(source)) {
            return Result.error("关注来源只接受 task / apply / counselor / appointment / referral / other");
        }
        String summary = trimToNull(text(pick(b, "summary")));
        if (summary != null && summary.length() > 500) {
            return Result.error("关注缘由不能超过 500 个字符");
        }
        String measures = trimToNull(text(pick(b, "measures")));
        if (measures != null && measures.length() > 1000) {
            return Result.error("已采取措施不能超过 1000 个字符");
        }
        Long counselorId = longOf(pick(b, "counselorId", "counselor_id"));
        if (counselorId == null) {
            counselorId = profile.getCounselorId();
        }
        if (counselorId == null) {
            counselorId = actorId;
        }
        PsycheFocus focus = new PsycheFocus();
        focus.setStudentId(studentId);
        focus.setBatchId(longOf(pick(b, "batchId", "batch_id")));
        focus.setLevel(level);
        focus.setSource(source);
        focus.setSourceId(longOf(pick(b, "sourceId", "source_id")));
        focus.setSummary(summary);
        focus.setMeasures(measures);
        focus.setCounselorId(counselorId);
        focus.setStatus("open");
        focus.setOpenedAt(LocalDateTime.now().withNano(0));
        focus.setCreatedBy(actorId);
        focusMapper.insert(focus);
        return Result.success(focusMapper.findById(focus.getId()));
    }

    /** 改关注台账（admin/teacher，且只能改名下学生的）：可改 level/summary/measures/counselorId/status。 */
    public Result<?> updateFocus(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheFocus exist = focusMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "关注台账不存在");
        }
        if (!canTouch(actorId, role, exist.getStudentId())) {
            return Result.error(403, "只能改自己名下学生的关注台账");
        }
        Map<String, Object> b = bodyOf(body);
        String level = exist.getLevel();
        if (has(b, "level")) {
            level = trimToNull(text(pick(b, "level")));
            if (level == null || !FOCUS_LEVELS.contains(level)) {
                return Result.error("关注等级只接受 general / key / risk");
            }
        }
        String summary = has(b, "summary") ? trimToNull(text(pick(b, "summary"))) : exist.getSummary();
        if (summary != null && summary.length() > 500) {
            return Result.error("关注缘由不能超过 500 个字符");
        }
        String measures = has(b, "measures") ? trimToNull(text(pick(b, "measures"))) : exist.getMeasures();
        if (measures != null && measures.length() > 1000) {
            return Result.error("已采取措施不能超过 1000 个字符");
        }
        Long counselorId = has(b, "counselorId", "counselor_id")
                ? longOf(pick(b, "counselorId", "counselor_id")) : exist.getCounselorId();
        String status = exist.getStatus();
        if (has(b, "status")) {
            status = trimToNull(text(pick(b, "status")));
            if (status == null || !FOCUS_STATUSES.contains(status)) {
                return Result.error("台账状态只接受 open / following / closed");
            }
        }
        LocalDateTime closedAt = exist.getClosedAt();
        if ("closed".equals(status) && closedAt == null) {
            closedAt = LocalDateTime.now().withNano(0);
        }
        PsycheFocus merged = new PsycheFocus();
        merged.setId(id);
        merged.setLevel(level);
        merged.setSummary(summary);
        merged.setMeasures(measures);
        merged.setCounselorId(counselorId);
        merged.setStatus(status);
        merged.setClosedAt(closedAt);
        int rows = focusMapper.update(merged, exist.getStatus());
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success(focusMapper.findById(id));
    }

    /** 登记跟进（admin/teacher，且只能是名下学生）：写 psyche_followup 并把台账 open → following。 */
    public Result<?> addFollowup(Long actorId, String role, Long focusId, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheFocus focus = focusMapper.findById(focusId);
        if (focus == null) {
            return Result.error(404, "关注台账不存在");
        }
        if (!canTouch(actorId, role, focus.getStudentId())) {
            return Result.error(403, "只能给自己名下学生的台账登记跟进");
        }
        Map<String, Object> b = bodyOf(body);
        String content = trimToNull(text(pick(b, "content")));
        if (content == null) {
            return Result.error("请填写跟进内容");
        }
        if (content.length() > 1000) {
            return Result.error("跟进内容不能超过 1000 个字符");
        }
        String measures = trimToNull(text(pick(b, "measures")));
        if (measures != null && measures.length() > 1000) {
            return Result.error("本次措施不能超过 1000 个字符");
        }
        String nextPlan = trimToNull(text(pick(b, "nextPlan", "next_plan")));
        if (nextPlan != null && nextPlan.length() > 500) {
            return Result.error("下一步计划不能超过 500 个字符");
        }
        LocalDateTime happenedAt = parseDateTime(pick(b, "happenedAt", "happened_at"));
        if (happenedAt == null) {
            happenedAt = LocalDateTime.now().withNano(0);
        }
        PsycheFollowup followup = new PsycheFollowup();
        followup.setFocusId(focusId);
        followup.setContent(content);
        followup.setMeasures(measures);
        followup.setNextPlan(nextPlan);
        followup.setOperatorId(actorId);
        followup.setHappenedAt(happenedAt);
        followupMapper.insert(followup);
        // open → following（CAS，已是 following/closed 时 0 行也无所谓）
        focusMapper.markFollowing(focusId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("followup", followup);
        data.put("focus", focusMapper.findById(focusId));
        data.put("followups", followupMapper.listByFocus(focusId));
        return Result.success(data);
    }

    // ---------------------------------------------------------------- 转介

    /** 转介列表（admin/teacher，teacher 只看名下学生）：列表脱敏。 */
    public Result<?> listReferrals(Long actorId, String role, String target, String status,
                                   int page, int size) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        String targetValue = trimToNull(target);
        String statusValue = trimToNull(status);
        Long scope = listScope(actorId, role);
        List<PsycheReferral> rows = referralMapper.list(targetValue, statusValue, scope, (p - 1) * s, s);
        int total = referralMapper.count(targetValue, statusValue, scope);
        for (PsycheReferral row : rows) {
            row.setStudentName(maskName(row.getStudentName()));
            row.setStudentNo(tail4(row.getStudentNo()));
        }
        return Result.success(pageOf(rows, total, p, s));
    }

    /** 建转介（admin/teacher，且只能给名下学生建）：target ∈ campus|hospital|family。 */
    public Result<?> createReferral(Long actorId, String role, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        Map<String, Object> b = bodyOf(body);
        Long studentId = longOf(pick(b, "studentId", "student_id"));
        if (studentId == null) {
            return Result.error("请指定学生");
        }
        if (profileMapper.findByUserId(studentId) == null) {
            return Result.error("学生不存在");
        }
        if (!canTouch(actorId, role, studentId)) {
            return Result.error(403, "只能给自己名下的学生登记转介");
        }
        String sourceType = trimToNull(text(pick(b, "sourceType", "source_type")));
        if (sourceType == null) {
            sourceType = "focus";
        }
        if (!REFERRAL_SOURCES.contains(sourceType)) {
            return Result.error("转介来源只接受 task / focus / appointment / other");
        }
        String target = trimToNull(text(pick(b, "target")));
        if (target == null) {
            target = "campus";
        }
        if (!REFERRAL_TARGETS.contains(target)) {
            return Result.error("转介去向只接受 campus / hospital / family");
        }
        String reason = trimToNull(text(pick(b, "reason")));
        if (reason != null && reason.length() > 1000) {
            return Result.error("转介原因不能超过 1000 个字符");
        }
        PsycheReferral referral = new PsycheReferral();
        referral.setStudentId(studentId);
        referral.setSourceType(sourceType);
        referral.setSourceId(longOf(pick(b, "sourceId", "source_id")));
        referral.setTarget(target);
        referral.setReason(reason);
        referral.setReferredBy(actorId);
        referral.setReferredAt(LocalDateTime.now().withNano(0));
        referral.setStatus("open");
        referralMapper.insert(referral);
        return Result.success(referralMapper.findById(referral.getId()));
    }

    /** 改转介（admin/teacher，且只能是名下学生）：可改 target/reason/status/followNote，closed 写 closed_at。 */
    public Result<?> updateReferral(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheReferral exist = referralMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "转介记录不存在");
        }
        if (!canTouch(actorId, role, exist.getStudentId())) {
            return Result.error(403, "只能改自己名下学生的转介记录");
        }
        Map<String, Object> b = bodyOf(body);
        String target = exist.getTarget();
        if (has(b, "target")) {
            target = trimToNull(text(pick(b, "target")));
            if (target == null || !REFERRAL_TARGETS.contains(target)) {
                return Result.error("转介去向只接受 campus / hospital / family");
            }
        }
        String reason = has(b, "reason") ? trimToNull(text(pick(b, "reason"))) : exist.getReason();
        if (reason != null && reason.length() > 1000) {
            return Result.error("转介原因不能超过 1000 个字符");
        }
        String followNote = has(b, "followNote", "follow_note")
                ? trimToNull(text(pick(b, "followNote", "follow_note"))) : exist.getFollowNote();
        if (followNote != null && followNote.length() > 1000) {
            return Result.error("跟踪记录不能超过 1000 个字符");
        }
        String status = exist.getStatus();
        if (has(b, "status")) {
            status = trimToNull(text(pick(b, "status")));
            if (status == null || !FOCUS_STATUSES.contains(status)) {
                return Result.error("转介状态只接受 open / following / closed");
            }
        }
        LocalDateTime closedAt = exist.getClosedAt();
        if ("closed".equals(status) && closedAt == null) {
            closedAt = LocalDateTime.now().withNano(0);
        }
        PsycheReferral merged = new PsycheReferral();
        merged.setId(id);
        merged.setTarget(target);
        merged.setReason(reason);
        merged.setStatus(status);
        merged.setFollowNote(followNote);
        merged.setClosedAt(closedAt);
        int rows = referralMapper.update(merged, exist.getStatus());
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success(referralMapper.findById(id));
    }

    // ---------------------------------------------------------------- 统计与导出

    /** 全局统计（admin/teacher）：批次完成率 + 各班级/专业完成率 + 关注等级分布 + 预约量与完成率，只回聚合数。 */
    public Result<?> stats(Long actorId, String role) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        List<Map<String, Object>> batches = new ArrayList<>();
        for (PsycheBatch batch : batchMapper.list(!isAdmin(role), null, null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", batch.getId());
            row.put("batchName", batch.getBatchName());
            row.put("grade", batch.getGrade());
            row.put("status", batch.getStatus());
            row.putAll(taskStats(batch.getId()));
            batches.add(row);
        }
        Long scope = listScope(actorId, role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batches", batches);
        data.put("byClass", groupRows(taskMapper.countGroupByClass(null), "class_name"));
        data.put("byMajor", groupRows(taskMapper.countGroupByMajor(null), "major"));
        data.put("focusLevels", focusLevels(scope, null));
        data.put("appointments", appointmentStats(scope));
        return Result.success(data);
    }

    /**
     * 导出 CSV（admin/teacher）：type = tasks | focus | appointments。
     *
     * <p>列表脱敏；appointments **不含 main_issue 全文与 note**；focus 只含 summary（关注缘由），
     * 不含其它敏感细节。批量数据不受分页限制。</p>
     */
    public CsvExport export(Long actorId, String role, String type, Long batchId) {
        CsvExport out = new CsvExport();
        if (actorId == null) {
            out.errorCode = 401;
            return out;
        }
        if (!isStaff(role)) {
            out.errorCode = 403;
            return out;
        }
        String kind = trimToNull(type);
        if (kind == null) {
            out.errorCode = 400;
            out.errorMessage = "请指定导出类型";
            return out;
        }
        StringBuilder csv = new StringBuilder();
        if ("tasks".equals(kind)) {
            csv.append("姓名,学号后四位,班级,专业,批次,状态,结果等级,是否已访谈\r\n");
            for (PsycheTask row : taskMapper.list(batchId, null, null, null, 0, MAX_EXPORT)) {
                csv.append(csvCell(maskName(row.getStudentName()))).append(',')
                        .append(csvCell(tail4(row.getStudentNo()))).append(',')
                        .append(csvCell(row.getClassName())).append(',')
                        .append(csvCell(row.getMajor())).append(',')
                        .append(csvCell(row.getBatchName())).append(',')
                        .append(csvCell(row.getStatus())).append(',')
                        .append(csvCell(row.getResultLevel())).append(',')
                        .append(csvCell(isYes(row.getInterviewed()) ? "是" : "否")).append("\r\n");
            }
            out.filename = "psyche-tasks.csv";
        } else if ("focus".equals(kind)) {
            Long scope = listScope(actorId, role);
            csv.append("姓名,学号后四位,班级,等级,来源,关注缘由,状态,跟进辅导员,登记时间\r\n");
            for (PsycheFocus row : focusMapper.list(null, null, null, null, scope, 0, MAX_EXPORT)) {
                csv.append(csvCell(maskName(row.getStudentName()))).append(',')
                        .append(csvCell(tail4(row.getStudentNo()))).append(',')
                        .append(csvCell(row.getClassName())).append(',')
                        .append(csvCell(row.getLevel())).append(',')
                        .append(csvCell(row.getSource())).append(',')
                        .append(csvCell(row.getSummary())).append(',')
                        .append(csvCell(row.getStatus())).append(',')
                        .append(csvCell(row.getCounselorName())).append(',')
                        .append(csvCell(stamp(row.getOpenedAt()))).append("\r\n");
            }
            out.filename = "psyche-focus.csv";
        } else if ("appointments".equals(kind)) {
            Long scope = listScope(actorId, role);
            csv.append("姓名,学号后四位,班级,方式,期望开始,期望结束,时长(分钟),状态,咨询师,确认时间,完成时间,续约来源\r\n");
            for (com.umi.nest.entity.PsycheAppointment row
                    : appointmentMapper.list(null, null, null, scope, 0, MAX_EXPORT)) {
                csv.append(csvCell(maskName(row.getStudentName()))).append(',')
                        .append(csvCell(tail4(row.getStudentNo()))).append(',')
                        .append(csvCell(row.getClassName())).append(',')
                        .append(csvCell(row.getMode())).append(',')
                        .append(csvCell(stamp(row.getExpectStart()))).append(',')
                        .append(csvCell(stamp(row.getExpectEnd()))).append(',')
                        .append(csvCell(row.getDurationMinutes() == null ? "" : String.valueOf(row.getDurationMinutes()))).append(',')
                        .append(csvCell(row.getStatus())).append(',')
                        .append(csvCell(row.getCounselorName())).append(',')
                        .append(csvCell(stamp(row.getConfirmedAt()))).append(',')
                        .append(csvCell(stamp(row.getFinishedAt()))).append(',')
                        .append(csvCell(row.getRenewedFrom() == null ? "" : String.valueOf(row.getRenewedFrom()))).append("\r\n");
            }
            out.filename = "psyche-appointments.csv";
        } else {
            out.errorCode = 400;
            out.errorMessage = "导出类型只支持 tasks / focus / appointments";
            return out;
        }
        out.csv = csv.toString();
        return out;
    }

    // ---------------------------------------------------------------- 内部：装配

    private List<com.umi.nest.entity.PsycheAppointment> appointmentRows(Long studentId) {
        return appointmentMapper.listByStudent(studentId);
    }

    /** 兼容 body 里 camelCase / snake_case 两种键（§9 契约里部分键写成列名）。 */
    private static Object pick(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key) && body.get(key) != null) {
                return body.get(key);
            }
        }
        return null;
    }

    private static boolean has(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> bodyOf(Map<String, Object> body) {
        return body == null ? Collections.emptyMap() : body;
    }

    private static Map<String, Object> pageOf(List<?> list, int total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    /** 结果视图（老师端与 admin 用）；masked=true 时（学生端）**不放** resultLevel / resultNote 两个键。 */
    private static Map<String, Object> resultView(PsycheTask task, boolean masked) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("scoreTotal", task == null ? null : task.getScoreTotal());
        data.put("scoreBand", task == null ? null : task.getScoreBand());
        if (!masked) {
            data.put("resultLevel", task == null ? null : task.getResultLevel());
            data.put("resultNote", task == null ? null : task.getResultNote());
        }
        return data;
    }

    /** 学生取题用的量表视图：题本与提示（key 同时给 snake_case 与 camelCase，契约两种写法都出现过）。 */
    private static Map<String, Object> instrumentView(PsycheInstrument instrument) {
        if (instrument == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", instrument.getId());
        m.put("name", instrument.getName());
        m.put("item_json", instrument.getItemJson());
        m.put("itemJson", instrument.getItemJson());
        m.put("guide_text", instrument.getGuideText());
        m.put("guideText", instrument.getGuideText());
        m.put("item_count", instrument.getItemCount());
        m.put("itemCount", instrument.getItemCount());
        return m;
    }

    /** 名单状态计数与完成率（只回聚合数）。 */
    private Map<String, Object> taskStats(Long batchId) {
        Map<String, Integer> counts = emptyStatusCounts();
        int total = 0;
        for (Map<String, Object> row : taskMapper.countGroupByStatus(batchId)) {
            String status = text(row.get("status"));
            int value = toInt(row.get("cnt"));
            if (status != null && counts.containsKey(status)) {
                counts.put(status, value);
            }
            total += value;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("completed", counts.get("completed"));
        stats.put("in_progress", counts.get("in_progress"));
        stats.put("pending", counts.get("pending"));
        stats.put("absent", counts.get("absent"));
        stats.put("completionRate", rate(counts.get("completed"), total));
        stats.put("completionPercent", percent(counts.get("completed"), total));
        return stats;
    }

    private Map<String, Object> focusLevels(Long scope, Long batchId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("general", 0);
        counts.put("key", 0);
        counts.put("risk", 0);
        int total = 0;
        for (Map<String, Object> row : focusMapper.countGroupByLevel(scope, batchId)) {
            String level = text(row.get("level"));
            int value = toInt(row.get("cnt"));
            if (level != null && counts.containsKey(level)) {
                counts.put(level, value);
            }
            total += value;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(counts);
        out.put("total", total);
        return out;
    }

    private Map<String, Object> appointmentStats(Long scope) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("requested", 0);
        counts.put("confirmed", 0);
        counts.put("done", 0);
        counts.put("cancelled", 0);
        counts.put("no_show", 0);
        int total = 0;
        for (Map<String, Object> row : appointmentMapper.countGroupByStatus(scope)) {
            String status = text(row.get("status"));
            int value = toInt(row.get("cnt"));
            if (status != null && counts.containsKey(status)) {
                counts.put(status, value);
            }
            total += value;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(counts);
        out.put("total", total);
        out.put("completionRate", rate(counts.get("done"), total));
        out.put("completionPercent", percent(counts.get("done"), total));
        return out;
    }

    private static Map<String, Integer> emptyStatusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("pending", 0);
        counts.put("in_progress", 0);
        counts.put("completed", 0);
        counts.put("absent", 0);
        return counts;
    }

    private static List<Map<String, Object>> groupRows(List<Map<String, Object>> rows, String keyName) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            String name = trimToNull(text(row.get(keyName)));
            int total = toInt(row.get("cnt"));
            int done = toInt(row.get("done"));
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", name == null ? "未填" : name);
            one.put("total", total);
            one.put("completed", done);
            one.put("completionRate", rate(done, total));
            one.put("completionPercent", percent(done, total));
            out.add(one);
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部：口径与校验

    /** 完成率（0~1，保留 4 位小数）；分母 0 → 0。 */
    private static BigDecimal rate(int done, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(done).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /** 完成率百分比（0~100，保留 2 位小数）。 */
    private static BigDecimal percent(int done, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(done).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /**
     * 姓名脱敏：保留姓（首个字符），其余打码成 *（S16 保密性原则）。
     */
    private static String maskName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= 1) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, 1)) + "*";
    }

    /** 学号只留后四位。 */
    private static String tail4(String studentNo) {
        if (studentNo == null) {
            return "";
        }
        String value = studentNo.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }

    private static String stamp(LocalDateTime time) {
        return time == null ? "" : time.format(STAMP);
    }

    private static boolean isYes(Integer flagValue) {
        return flagValue != null && flagValue == 1;
    }

    private static boolean inWindow(PsycheBatch batch) {
        if (batch == null || batch.getOpenAt() == null || batch.getCloseAt() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return !now.isBefore(batch.getOpenAt()) && !now.isAfter(batch.getCloseAt());
    }

    /**
     * 只按 band_json 折算档位标签，**不做任何临床解释**：按数组顺序取第一个 max ≥ 总分的 band；
     * 都比不过就取最后一个 band（阈值由学校导入，平台不判断严重程度）。
     */
    private String bandOf(String bandJson, BigDecimal score) {
        JsonNode root = readJson(bandJson);
        if (root == null || !root.isArray() || root.isEmpty() || score == null) {
            return null;
        }
        String last = null;
        for (JsonNode node : root) {
            String band = trimToNull(text(node.path("band").asText(null)));
            if (band == null) {
                continue;
            }
            last = band;
            JsonNode max = node.path("max");
            if (max.isNumber() || (max.isTextual() && !max.asText().trim().isEmpty())) {
                try {
                    if (score.compareTo(new BigDecimal(max.asText())) <= 0) {
                        return band;
                    }
                } catch (NumberFormatException ignored) {
                    // 阈值不是数字 → 不参与比较
                }
            }
        }
        return last;
    }

    private Integer itemCountOf(String itemJson) {
        JsonNode root = readJson(itemJson);
        if (root == null || !root.isArray()) {
            return 0;
        }
        return root.size();
    }

    private Integer instrumentItemsOf(PsycheBatch batch) {
        if (batch == null || batch.getInstrumentId() == null) {
            return null;
        }
        PsycheInstrument instrument = instrumentMapper.findById(batch.getInstrumentId());
        return instrument == null ? null : instrument.getItemCount();
    }

    private JsonNode readJson(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private User resolveStudent(String token) {
        String value = trimToNull(token);
        if (value == null) {
            return null;
        }
        User user = userMapper.findByStudentId(value);
        if (user == null) {
            user = userMapper.findByUsername(value);
        }
        if (user == null) {
            Long id = longOf(value);
            if (id != null) {
                user = userMapper.findById(id);
            }
        }
        if (user == null) {
            return null;
        }
        if (!"student".equals(user.getRole())) {
            return null;
        }
        if (user.getDeleted() != null && user.getDeleted() != 0) {
            return null;
        }
        return user;
    }

    /** 名单读写范围：学生只能看自己；teacher 只能是该生 student_profile.counselor_id == 自己。 */
    private Result<?> requireTaskRead(Long actorId, String role, PsycheTask task) {
        if (isStudent(role)) {
            if (task.getStudentId() != null && task.getStudentId().equals(actorId)) {
                return null;
            }
            return Result.error(403, "只能查看自己的普查名单");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权访问");
        }
        return canTouch(actorId, role, task.getStudentId()) ? null : Result.error(403, "只能查看名下学生的普查记录");
    }

    /**
     * 台账读写范围（S16 保密性）：admin 全校；teacher 只碰 student_profile.counselor_id 是自己的学生。
     *
     * <p>与 P13 的 scopeCounselor 口径不同——那里「没有分派学生的辅导员看全校」，心理台账不能这么放开。</p>
     */
    private boolean canTouch(Long actorId, String role, Long studentId) {
        if (isAdmin(role)) {
            return true;
        }
        if (studentId == null) {
            return false;
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        Long counselorId = profile == null ? null : profile.getCounselorId();
        return counselorId != null && counselorId.equals(actorId);
    }

    /** 列表范围：admin → null（全校）；teacher → 自己（名下学生 或 自己负责的台账/咨询）。 */
    private static Long listScope(Long actorId, String role) {
        return isAdmin(role) ? null : actorId;
    }

    // ---------------------------------------------------------------- 内部：小工具

    private static Result<?> guardLogin(Long actorId) {
        return actorId == null ? Result.error(401, "请先登录") : null;
    }

    private static Result<?> guardStaff(Long actorId, String role) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        return isStaff(role) ? null : Result.error(403, "无权访问");
    }

    private static Result<?> guardAdmin(Long actorId, String role) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        return isAdmin(role) ? null : Result.error(403, "仅管理员可操作");
    }

    private static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    private static boolean isStaff(String role) {
        return STAFF_ROLES.contains(role);
    }

    private static boolean isStudent(String role) {
        return "student".equals(role);
    }

    private static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Integer flag(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        if (value instanceof Number) {
            int n = ((Number) value).intValue();
            return (n == 0 || n == 1) ? Integer.valueOf(n) : null;
        }
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "是".equals(s)) {
            return 1;
        }
        if ("false".equals(s) || "0".equals(s) || "否".equals(s)) {
            return 0;
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(Object value) {
        Integer parsed = integer(value);
        return parsed == null ? 0 : parsed;
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 支持 "yyyy-MM-dd HH:mm:ss" / ISO 的 "yyyy-MM-ddTHH:mm:ss" / "yyyy-MM-dd"；解析失败返回 null。 */
    private static LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replace('T', ' ');
        if (s.length() == 10) {
            s = s + " 00:00:00";
        } else if (s.length() == 16) {
            s = s + ":00";
        }
        if (s.length() < 19) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.substring(0, 19), STAMP).withNano(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** CSV 导出结果（与 RankService.CsvExport 同形：errorCode 非空 → 控制器直接回该状态码）。 */
    public static class CsvExport {

        public Integer errorCode;

        public String errorMessage;

        public String csv;

        public String filename;
    }
}
