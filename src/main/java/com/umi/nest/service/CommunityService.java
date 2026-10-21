package com.umi.nest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.CommunityBatch;
import com.umi.nest.entity.CommunityCivilRoom;
import com.umi.nest.entity.CommunityInspection;
import com.umi.nest.entity.CommunityLate;
import com.umi.nest.entity.CommunityViolation;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.CommunityActivityMapper;
import com.umi.nest.mapper.CommunityBatchMapper;
import com.umi.nest.mapper.CommunityCivilRoomMapper;
import com.umi.nest.mapper.CommunityInspectionMapper;
import com.umi.nest.mapper.CommunityLateMapper;
import com.umi.nest.mapper.CommunityViolationMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 社区管理（学工 P15）主服务：检查批次 / 检查记录 / 整改闭环 / 文明宿舍 / 统计导出。
 *
 * <p>口径见《学工平台功能对齐说明》§10：7 张 community_* 表、评分项由学校录入（平台自带演示项）、
 * 晚归照 S19 第七条（6:00-23:30 之外）、处理只到教育惩戒与建议（必须带 rule_ref）、
 * 文明宿舍按学期已发布批次汇总并按楼栋内排名出候选（名额不自动截断，awarded 人工确认）。</p>
 *
 * <p>状态迁移一律 CAS：WHERE id=? AND deleted=0 AND status=fromStatus。CAS 的 fromStatus 取自
 * **刚读到的状态**，单靠它挡不住非法迁移（WHERE 恒成立），所以每个迁移入口都先做一次显式前置校验，
 * 校验不过直接 400 + 可读文案（P14 踩过的坑：draft 批次被 closed）。</p>
 *
 * <p>晚归 / 违规在 {@link CommunityLateService}，活动 / 报名在 {@link CommunityActivityService}；
 * 本类还持有三者共用的静态工具（校验、解析、脱敏、CSV、分页封装），不额外新增 util 文件。</p>
 */
@Service("communityService")
public class CommunityService {

    /** 通用 CAS 失败文案。 */
    public static final String CAS_FAIL = "状态已变化，请刷新后重试";

    /** 辅导员与管理员。 */
    public static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));

    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 导出上限，避免一次拉全表把内网机器打爆。 */
    public static final int MAX_EXPORT = 100000;

    /** 批次状态机（PUT 只允许在这一段里逐级推进，公布/结束走专门接口）。 */
    public static final List<String> BATCH_FLOW = Arrays.asList("draft", "checking", "scored");

    /** 已发布批次的两种状态（文明宿舍汇总只看这两种）。 */
    public static final List<String> PUBLISHED_STATUSES = Arrays.asList("published", "finished");

    /** 检查等级阈值（服务端计算，不采信客户端传值）：excellent >= 90 / good 80-89 / pass 60-79 / fail < 60。 */
    public static final BigDecimal GRADE_EXCELLENT = new BigDecimal("90");
    public static final BigDecimal GRADE_GOOD = new BigDecimal("80");
    public static final BigDecimal GRADE_PASS = new BigDecimal("60");

    /** 整改闭环状态。 */
    public static final Set<String> RECTIFY_SUBMITTABLE = new HashSet<>(Arrays.asList("pending", "rejected"));

    /** 晚归正常出入时段（S19 第七条）：6:00-23:30 之外才算晚归。 */
    public static final LocalTime NORMAL_IN = LocalTime.of(6, 0);
    public static final LocalTime NORMAL_OUT = LocalTime.of(23, 30);

    /**
     * 平台自带的演示评分项（五项各 20 分，合计 100）。
     * 学校未录入 criteria_json 时用它兜底，并在批次说明里标注「演示评分项，非学校发布口径」。
     */
    public static final String DEFAULT_CRITERIA_JSON =
            "[{\"key\":\"power\",\"name\":\"用电安全\",\"max\":20,\"ruleRef\":\"园区管理规定第十条\"},"
            + "{\"key\":\"fire\",\"name\":\"消防通道\",\"max\":20,\"ruleRef\":\"园区管理规定第十一条\"},"
            + "{\"key\":\"hygiene\",\"name\":\"内务卫生\",\"max\":20,\"ruleRef\":\"园区管理规定第十二条\"},"
            + "{\"key\":\"property\",\"name\":\"财物与家具\",\"max\":20,\"ruleRef\":\"园区管理规定第十三条\"},"
            + "{\"key\":\"civil\",\"name\":\"文明行为\",\"max\":20,\"ruleRef\":\"园区管理规定第十四条\"}]";

    public static final String DEMO_CRITERIA_MARK = "（评分项为演示评分项，非学校发布口径）";

    @Autowired
    private CommunityBatchMapper batchMapper;

    @Autowired
    private CommunityInspectionMapper inspectionMapper;

    @Autowired
    private CommunityCivilRoomMapper civilRoomMapper;

    @Autowired
    private CommunityLateMapper lateMapper;

    @Autowired
    private CommunityViolationMapper violationMapper;

    @Autowired
    private CommunityActivityMapper activityMapper;

    @Autowired
    private StudentProfileMapper studentProfileMapper;

    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================================
    // 批次：GET /batches、POST /batches、PUT /batches/{id}、
    //       POST /batches/{id}/publish、POST /batches/{id}/close、GET /batches/{id}/stats
    // =========================================================================

    /** 批次列表（仅管理员 / 辅导员）。 */
    public Result<?> listBatches(Long actorId, String role, String term, String checkType, String status, String keyword) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        List<CommunityBatch> rows = batchMapper.list(trimToNull(term), trimToNull(checkType),
                trimToNull(status), trimToNull(keyword), false);
        return Result.success(rows);
    }

    /** 新建批次：默认 draft；未给 criteria_json 时落演示评分项并标注非学校口径。 */
    public Result<?> createBatch(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        String batchName = trimToNull(text(pick(body, "batchName", "batch_name")));
        if (batchName == null) {
            return Result.error("请填写批次名称");
        }
        if (batchName.length() > 128) {
            return Result.error("批次名称不能超过 128 字");
        }
        String term = trimToNull(text(pick(body, "term")));
        if (term == null) {
            return Result.error("请填写学期（如 2025-2026-1）");
        }
        String checkType = trimToNull(text(pick(body, "checkType", "check_type")));
        if (checkType == null) {
            checkType = "hygiene";
        }
        if (!Arrays.asList("hygiene", "safety", "joint").contains(checkType)) {
            return Result.error("检查类型只支持 hygiene（卫生）/ safety（安全）/ joint（联合）");
        }
        String criteriaJson = trimToNull(text(pick(body, "criteriaJson", "criteria_json")));
        boolean demoCriteria = criteriaJson == null;
        if (demoCriteria) {
            criteriaJson = DEFAULT_CRITERIA_JSON;
        }
        String criteriaError = validateCriteria(criteriaJson);
        if (criteriaError != null) {
            return Result.error(criteriaError);
        }
        LocalDate startDate = parseDate(pick(body, "startDate", "start_date"));
        LocalDate endDate = parseDate(pick(body, "endDate", "end_date"));
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            return Result.error("检查结束日期不能早于开始日期");
        }
        String note = trimToNull(text(pick(body, "note")));
        if (note != null && note.length() > 500) {
            return Result.error("批次说明不能超过 500 字");
        }
        if (demoCriteria) {
            note = (note == null ? "" : note + "；") + DEMO_CRITERIA_MARK;
        }
        CommunityBatch batch = new CommunityBatch();
        batch.setBatchName(batchName);
        batch.setTerm(term);
        batch.setCheckType(checkType);
        batch.setScopeBuilding(trimToNull(text(pick(body, "scopeBuilding", "scope_building"))));
        batch.setStartDate(startDate);
        batch.setEndDate(endDate);
        batch.setCriteriaJson(criteriaJson);
        batch.setStatus("draft");
        batch.setNote(note);
        batch.setCreatedBy(actorId);
        batchMapper.insert(batch);
        return Result.success(batchMapper.findById(batch.getId()));
    }

    /**
     * 修改批次。状态前置校验：已公布 / 已结束的批次不可再改；状态只能沿 draft → checking → scored
     * 逐级推进（公布与结束各有专门接口，避免把非法迁移塞进普通修改）。
     */
    public Result<?> updateBatch(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        CommunityBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        String current = exist.getStatus();
        if (!BATCH_FLOW.contains(current)) {
            return Result.error("批次已公布或已结束，不能再修改（当前 " + statusLabel(current) + "）");
        }
        String batchName = trimToNull(text(pick(body, "batchName", "batch_name")));
        if (batchName != null && batchName.length() > 128) {
            return Result.error("批次名称不能超过 128 字");
        }
        String checkType = trimToNull(text(pick(body, "checkType", "check_type")));
        if (checkType != null && !Arrays.asList("hygiene", "safety", "joint").contains(checkType)) {
            return Result.error("检查类型只支持 hygiene / safety / joint");
        }
        String criteriaJson = trimToNull(text(pick(body, "criteriaJson", "criteria_json")));
        if (criteriaJson == null) {
            criteriaJson = exist.getCriteriaJson();
        }
        String criteriaError = validateCriteria(criteriaJson);
        if (criteriaError != null) {
            return Result.error(criteriaError);
        }
        String note = trimToNull(text(pick(body, "note")));
        if (note != null && note.length() > 500) {
            return Result.error("批次说明不能超过 500 字");
        }
        String target = trimToNull(text(pick(body, "status")));
        if (target == null) {
            target = current;
        }
        if (!target.equals(current)) {
            if ("published".equals(target)) {
                return Result.error("批次公布请用 POST /api/community/batches/" + id + "/publish");
            }
            if ("finished".equals(target)) {
                return Result.error("批次结束请用 POST /api/community/batches/" + id + "/close");
            }
            if (!BATCH_FLOW.contains(target)) {
                return Result.error("批次状态只支持 draft / checking / scored");
            }
            int from = BATCH_FLOW.indexOf(current);
            int to = BATCH_FLOW.indexOf(target);
            if (to != from + 1) {
                return Result.error("批次状态只能逐级推进 draft → checking → scored（当前 "
                        + statusLabel(current) + "，目标 " + statusLabel(target) + "）");
            }
        }
        LocalDate startDate = has(body, "startDate", "start_date") ? parseDate(pick(body, "startDate", "start_date")) : exist.getStartDate();
        LocalDate endDate = has(body, "endDate", "end_date") ? parseDate(pick(body, "endDate", "end_date")) : exist.getEndDate();
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            return Result.error("检查结束日期不能早于开始日期");
        }
        CommunityBatch merged = new CommunityBatch();
        merged.setId(exist.getId());
        merged.setBatchName(batchName == null ? exist.getBatchName() : batchName);
        merged.setTerm(has(body, "term") ? orDefault(trimToNull(text(pick(body, "term"))), exist.getTerm()) : exist.getTerm());
        merged.setCheckType(checkType == null ? exist.getCheckType() : checkType);
        merged.setScopeBuilding(has(body, "scopeBuilding", "scope_building")
                ? trimToNull(text(pick(body, "scopeBuilding", "scope_building"))) : exist.getScopeBuilding());
        merged.setStartDate(startDate);
        merged.setEndDate(endDate);
        merged.setCriteriaJson(criteriaJson);
        merged.setStatus(target);
        merged.setNote(note == null ? exist.getNote() : note);
        merged.setPublishDate(exist.getPublishDate());
        int rows = batchMapper.update(merged, current);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success(batchMapper.findById(id));
    }

    /** 批次公布：draft / checking / scored → published，写 publish_date。 */
    public Result<?> publishBatch(Long actorId, String role, Long id) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        String current = exist.getStatus();
        if (!BATCH_FLOW.contains(current)) {
            return Result.error("只有草稿 / 检查中 / 已评分的批次可以公布（当前 " + statusLabel(current) + "）");
        }
        int rows = batchMapper.publish(id, current, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success("批次已公布", batchMapper.findById(id));
    }

    /** 批次结束：published / scored → finished（移入历史）。 */
    public Result<?> closeBatch(Long actorId, String role, Long id) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        String current = exist.getStatus();
        // 结束只接受 published / scored；注意这里**不能**用 PUBLISHED_STATUSES（它是 {published, finished}，
        // 用于「已公布批次即锁定」的判定），否则已 finished 的批次会被再次「结束」成幂等 200（E2E D2）。
        if (!"published".equals(current) && !"scored".equals(current)) {
            return Result.error("只有已公布 / 已评分的批次可以结束（当前 " + statusLabel(current) + "）");
        }
        int rows = batchMapper.finish(id, current);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success("批次已结束", batchMapper.findById(id));
    }

    /** 批次统计：评分项、等级分布、整改分布、汇总指标。 */
    public Result<?> batchStats(Long actorId, String role, Long id) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityBatch exist = batchMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "批次不存在");
        }
        Long counselorId = scopeCounselor(actorId, role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch", exist);
        data.put("criteria", criteriaOf(exist.getCriteriaJson()));
        data.put("criteriaDemo", isDemoCriteria(exist.getCriteriaJson()));
        data.put("summary", inspectionMapper.summary(id, null, null, counselorId));
        data.put("gradeCount", inspectionMapper.countGroupByGrade(id, null, null, counselorId));
        data.put("rectifyCount", inspectionMapper.countGroupByRectify(id, null, null, counselorId));
        return Result.success(data);
    }

    // =========================================================================
    // 检查记录：GET /inspections、POST /inspections、GET /inspections/{id}、
    //   DELETE /inspections/{id}、POST /inspections/import、POST /inspections/{id}/recheck、
    //   GET /my/room
    // =========================================================================

    /** 检查记录分页列表（管理员全量；辅导员只看名下学生所在宿舍）。 */
    public Result<?> listInspections(Long actorId, String role, Long batchId, String term, String building,
                                     String roomNo, String grade, String rectifyStatus, String source,
                                     String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        boolean student = !isStaff(role);
        boolean publishedOnly = false;
        Long counselorId = null;
        if (student) {
            // 学生只能看本宿舍、且已公布批次的检查结果（楼栋 / 房号由档案强制覆盖，忽略入参）
            StudentProfile profile = studentProfileMapper.findByUserId(actorId);
            if (profile == null) {
                return Result.error(403, "仅学生本人可查看本宿舍检查结果");
            }
            if (isBlank(profile.getDormBuilding()) || isBlank(profile.getDormRoom())) {
                return Result.success(pageOf(new ArrayList<CommunityInspection>(), 0, p, s));
            }
            building = profile.getDormBuilding();
            roomNo = profile.getDormRoom();
            publishedOnly = true;
        } else {
            counselorId = scopeCounselor(actorId, role);
        }
        List<CommunityInspection> rows = inspectionMapper.list(batchId, trimToNull(term), trimToNull(building),
                trimToNull(roomNo), trimToNull(grade), trimToNull(rectifyStatus), trimToNull(source),
                trimToNull(keyword), counselorId, publishedOnly, false, (p - 1) * s, s);
        int total = inspectionMapper.countList(batchId, trimToNull(term), trimToNull(building), trimToNull(roomNo),
                trimToNull(grade), trimToNull(rectifyStatus), trimToNull(source), trimToNull(keyword),
                counselorId, publishedOnly, false);
        return Result.success(pageOf(rows, total, p, s));
    }

    /** 单条检查记录详情（管理员 / 辅导员，辅导员受名下学生范围限制；学生仅本宿舍且已公布）。 */
    public Result<?> inspectionDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        CommunityInspection exist = inspectionMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "检查记录不存在");
        }
        if (!isStaff(role)) {
            StudentProfile profile = studentProfileMapper.findByUserId(actorId);
            if (profile == null
                    || !orDefault(profile.getDormBuilding(), "").equals(orDefault(exist.getBuilding(), ""))
                    || !orDefault(profile.getDormRoom(), "").equals(orDefault(exist.getRoomNo(), ""))) {
                return Result.error(403, "只能查看本宿舍的检查结果");
            }
            if (!PUBLISHED_STATUSES.contains(exist.getBatchStatus())) {
                return Result.error(403, "该批次检查结果尚未公布");
            }
            return Result.success(exist);
        }
        Result<?> scope = checkRoomScope(actorId, role, exist.getBuilding(), exist.getRoomNo());
        if (scope != null) {
            return scope;
        }
        return Result.success(exist);
    }

    /** 录入检查记录：批次必须在 checking / scored；等级与整改要求由服务端推导。 */
    public Result<?> createInspection(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        Long batchId = longOf(pick(body, "batchId", "batch_id"));
        if (batchId == null) {
            return Result.error("请指定检查批次 batchId");
        }
        CommunityBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        if (!Arrays.asList("checking", "scored").contains(batch.getStatus())) {
            return Result.error("批次不在检查中 / 已评分状态，不能录入检查记录（当前 "
                    + statusLabel(batch.getStatus()) + "）");
        }
        Result<?> prepared = prepareInspection(actorId, role, batch, body);
        if (prepared.getCode() == null || prepared.getCode() != 200) {
            return prepared;
        }
        CommunityInspection inspection = (CommunityInspection) prepared.getData();
        if (inspectionMapper.findByBatchRoom(batchId, inspection.getBuilding(), inspection.getRoomNo()) != null) {
            return Result.error("该批次此房间已有检查记录（同一批次同一房间只允许一条），请改用复查或删除后重建");
        }
        inspectionMapper.insert(inspection);
        return Result.success(inspectionMapper.findById(inspection.getId()));
    }

    /**
     * CSV / 行数组导入检查记录。body 支持：
     * <pre>{"batchId":1,"csv":"building,roomNo,score,problems,checkDate\nA 栋,A-305,88,,2026-03-01"}</pre>
     * 或 <pre>{"batchId":1,"rows":[{"building":"A 栋","roomNo":"A-305","score":88}]}</pre>
     */
    public Result<?> importInspections(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        Long batchId = longOf(pick(body, "batchId", "batch_id"));
        if (batchId == null) {
            return Result.error("请指定检查批次 batchId");
        }
        CommunityBatch batch = batchMapper.findById(batchId);
        if (batch == null) {
            return Result.error(404, "批次不存在");
        }
        if (!Arrays.asList("checking", "scored").contains(batch.getStatus())) {
            return Result.error("批次不在检查中 / 已评分状态，不能导入检查记录（当前 "
                    + statusLabel(batch.getStatus()) + "）");
        }
        List<Map<String, Object>> rawRows = new ArrayList<>();
        Object rowsObj = pick(body, "rows", "items", "list");
        String csv = trimToNull(text(pick(body, "csv", "csvText", "content")));
        if (rowsObj instanceof List && !((List<?>) rowsObj).isEmpty()) {
            for (Object o : (List<?>) rowsObj) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    rawRows.add(m);
                }
            }
        } else if (csv != null) {
            rawRows = parseInspectionCsv(csv);
        }
        if (rawRows.isEmpty()) {
            return Result.error("没有可导入的数据：请传 rows 数组或 csv 文本（表头 building,roomNo,score,problems,checkDate）");
        }
        if (rawRows.size() > 2000) {
            return Result.error("单次导入不超过 2000 行，当前 " + rawRows.size() + " 行");
        }
        int inserted = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> raw = new LinkedHashMap<>(rawRows.get(i));
            raw.put("batchId", batchId);
            String roomKey = text(raw.get("building")) + "|" + text(pick(raw, "roomNo", "room_no", "roomno"));
            if (!seen.add(roomKey)) {
                errors.add(errorRow(i, "同一份数据里重复的房间：" + roomKey));
                continue;
            }
            Result<?> prepared = prepareInspection(actorId, role, batch, raw);
            if (prepared.getCode() == null || prepared.getCode() != 200) {
                errors.add(errorRow(i, prepared.getMessage()));
                continue;
            }
            CommunityInspection inspection = (CommunityInspection) prepared.getData();
            if (inspectionMapper.findByBatchRoom(batchId, inspection.getBuilding(), inspection.getRoomNo()) != null) {
                errors.add(errorRow(i, "该批次此房间已有检查记录：" + inspection.getBuilding() + " " + inspection.getRoomNo()));
                continue;
            }
            inspection.setSource("csv");
            inspectionMapper.insert(inspection);
            inserted++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("total", rawRows.size());
        data.put("inserted", inserted);
        data.put("failed", errors.size());
        data.put("errors", errors);
        if (inserted == 0) {
            return Result.error("导入失败：共 " + rawRows.size() + " 行全部未导入（首条原因："
                    + (errors.isEmpty() ? "无有效数据" : errors.get(0).get("message")) + "）");
        }
        return Result.success("导入完成：成功 " + inserted + " 行，失败 " + errors.size() + " 行", data);
    }

    /** 删除检查记录（逻辑删除）：只有批次仍在检查中 / 已评分时才允许，SQL 里同样带批次状态条件。 */
    public Result<?> deleteInspection(Long actorId, String role, Long id) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityInspection exist = inspectionMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "检查记录不存在");
        }
        Result<?> scope = checkRoomScope(actorId, role, exist.getBuilding(), exist.getRoomNo());
        if (scope != null) {
            return scope;
        }
        if (!Arrays.asList("checking", "scored").contains(exist.getBatchStatus())) {
            return Result.error("批次已公布或已结束，检查记录不能再删除（批次状态 "
                    + statusLabel(exist.getBatchStatus()) + "）");
        }
        int rows = inspectionMapper.logicalDelete(id);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success("已删除检查记录", null);
    }

    /** 复查：只有 submitted（学生已提交整改说明）可以复查；pass → accepted，fail → rejected（可重新提交）。 */
    public Result<?> recheckInspection(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityInspection exist = inspectionMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "检查记录不存在");
        }
        Result<?> scope = checkRoomScope(actorId, role, exist.getBuilding(), exist.getRoomNo());
        if (scope != null) {
            return scope;
        }
        if (!"submitted".equals(exist.getRectifyStatus())) {
            return Result.error("只有学生已提交整改说明的记录可以复查（当前 "
                    + rectifyLabel(exist.getRectifyStatus()) + "）");
        }
        String result = trimToNull(text(pick(body, "recheckResult", "recheck_result", "result")));
        if (result == null) {
            return Result.error("请给出复查结论 recheckResult（pass 通过 / fail 未通过）");
        }
        if (!Arrays.asList("pass", "fail").contains(result)) {
            return Result.error("复查结论只支持 pass / fail");
        }
        String toStatus = "pass".equals(result) ? "accepted" : "rejected";
        int rows = inspectionMapper.recheck(id, "submitted", toStatus, result, actorId, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success("pass".equals(result) ? "复查通过，整改闭环" : "复查未通过，已退回整改", inspectionMapper.findById(id));
    }

    /** 学生查看本宿舍检查结果（只下发非 draft 批次的记录；学生看不到其他房间）。 */
    public Result<?> myRoom(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可查看本宿舍检查结果");
        }
        if (isBlank(profile.getDormBuilding()) || isBlank(profile.getDormRoom())) {
            return Result.success(new ArrayList<CommunityInspection>());
        }
        List<CommunityInspection> rows = inspectionMapper.list(null, null, profile.getDormBuilding(),
                profile.getDormRoom(), null, null, null, null, null, true, false, 0, 200);
        return Result.success(rows);
    }

    // =========================================================================
    // 整改：POST /inspections/{id}/rectify、GET /my/rectifications
    // =========================================================================

    /** 学生提交整改说明：本宿舍记录、且状态为待整改 / 复查未通过（rejected 可重新提交）。 */
    public Result<?> submitRectify(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可提交整改说明");
        }
        CommunityInspection exist = inspectionMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "检查记录不存在");
        }
        if (!orDefault(profile.getDormBuilding(), "").equals(orDefault(exist.getBuilding(), ""))
                || !orDefault(profile.getDormRoom(), "").equals(orDefault(exist.getRoomNo(), ""))) {
            return Result.error(403, "只能提交本宿舍检查记录的整改说明");
        }
        String current = exist.getRectifyStatus();
        if (!RECTIFY_SUBMITTABLE.contains(current)) {
            return Result.error("只有待整改 / 复查未通过的记录可以提交整改说明（当前 " + rectifyLabel(current) + "）");
        }
        String note = trimToNull(text(pick(body, "rectifyNote", "rectify_note", "note")));
        if (note == null) {
            return Result.error("请填写整改说明");
        }
        if (note.length() > 500) {
            return Result.error("整改说明不能超过 500 字");
        }
        int rows = inspectionMapper.submitRectify(id, current, note, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success("整改说明已提交，等待复查", inspectionMapper.findById(id));
    }

    /** 学生查看本宿舍需要整改的记录。 */
    public Result<?> myRectifications(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可查看本宿舍整改记录");
        }
        if (isBlank(profile.getDormBuilding()) || isBlank(profile.getDormRoom())) {
            return Result.success(new ArrayList<CommunityInspection>());
        }
        List<CommunityInspection> rows = inspectionMapper.list(null, null, profile.getDormBuilding(),
                profile.getDormRoom(), null, null, null, null, null, true, true, 0, 200);
        return Result.success(rows);
    }

    // =========================================================================
    // 文明宿舍：GET /civil-rooms、POST /civil-rooms/compute、
    //           POST /civil-rooms/{id}/publish、GET /civil-rooms/publicity
    // =========================================================================

    /** 文明宿舍候选 / 结果列表（管理员 / 辅导员）。 */
    public Result<?> listCivilRooms(Long actorId, String role, String term, String building, Integer awarded) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        List<CommunityCivilRoom> rows = civilRoomMapper.list(trimToNull(term), trimToNull(building), awarded, false);
        return Result.success(rows);
    }

    /**
     * 按学期汇总文明宿舍候选：只汇总**已发布批次**（published / finished）的检查均分，
     * 排名只按**楼栋内**算；名额不自动截断，awarded 留给人工确认。
     */
    public Result<?> computeCivilRooms(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        String term = trimToNull(text(pick(body, "term")));
        if (term == null) {
            return Result.error("请指定学期 term（如 2025-2026-1）");
        }
        List<CommunityBatch> batches = batchMapper.listByTermAndStatuses(term, PUBLISHED_STATUSES);
        if (batches.isEmpty()) {
            return Result.error("该学期没有已公布 / 已结束的检查批次，无法汇总文明宿舍（公示口径只认已发布批次）");
        }
        List<Map<String, Object>> aggregates = civilRoomMapper.aggregate(term, PUBLISHED_STATUSES);
        if (aggregates.isEmpty()) {
            return Result.error("该学期已发布批次里没有检查记录，无法汇总文明宿舍");
        }
        civilRoomMapper.deleteDraftByTerm(term);
        Map<String, List<BigDecimal>> byBuilding = new LinkedHashMap<>();
        for (Map<String, Object> row : aggregates) {
            String building = text(row.get("building"));
            byBuilding.computeIfAbsent(building, k -> new ArrayList<>()).add(scale2(row.get("avg_score")));
        }
        int created = 0;
        int refreshed = 0;
        for (Map<String, Object> row : aggregates) {
            String building = text(row.get("building"));
            String roomNo = text(row.get("room_no"));
            BigDecimal avgScore = scale2(row.get("avg_score"));
            BigDecimal minScore = scale2(row.get("min_score"));
            int memberCount = toInt(row.get("member_count"), 0);
            int rank = rankInBuilding(byBuilding.get(building), avgScore);
            String batchIds = toJsonArray(text(row.get("batch_ids")));
            CommunityCivilRoom exist = civilRoomMapper.findByTermRoom(term, building, roomNo);
            if (exist != null) {
                exist.setBatchIds(batchIds);
                exist.setMemberCount(memberCount);
                exist.setAvgScore(avgScore);
                exist.setMinScore(minScore);
                exist.setRankInBuilding(rank);
                int rows = civilRoomMapper.refresh(exist);
                if (rows != 1) {
                    return Result.error(CAS_FAIL);
                }
                refreshed++;
            } else {
                CommunityCivilRoom room = new CommunityCivilRoom();
                room.setTerm(term);
                room.setBatchIds(batchIds);
                room.setBuilding(building);
                room.setRoomNo(roomNo);
                room.setMemberCount(memberCount);
                room.setAvgScore(avgScore);
                room.setMinScore(minScore);
                room.setRankInBuilding(rank);
                room.setAwarded(0);
                civilRoomMapper.insert(room);
                created++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", term);
        data.put("batchCount", batches.size());
        data.put("created", created);
        data.put("refreshed", refreshed);
        data.put("list", civilRoomMapper.list(term, null, null, false));
        return Result.success("已汇总 " + (created + refreshed) + " 间宿舍（新建 " + created + "，刷新 " + refreshed + "）", data);
    }

    /** 文明宿舍公示 / 人工确认获评：published_at IS NULL 才能写（CAS），重复公示 400。 */
    public Result<?> publishCivilRoom(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityCivilRoom exist = civilRoomMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "文明宿舍候选不存在");
        }
        if (exist.getPublishedAt() != null) {
            return Result.error("该宿舍已公示，不能重复公示");
        }
        Integer awarded = integer(pick(body, "awarded"));
        if (awarded == null) {
            awarded = 1;
        }
        if (awarded != 0 && awarded != 1) {
            return Result.error("awarded 只能是 0（候选）或 1（已评文明宿舍）");
        }
        String awardLevel = trimToNull(text(pick(body, "awardLevel", "award_level")));
        if (awardLevel == null) {
            awardLevel = awarded == 1 ? "college" : null;
        }
        if (awardLevel != null && !Arrays.asList("school", "college").contains(awardLevel)) {
            return Result.error("获奖等级只支持 school（校级）/ college（院级）");
        }
        int rows = civilRoomMapper.publish(id, awarded, awardLevel, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success("已公示", civilRoomMapper.findById(id));
    }

    /**
     * 文明宿舍公示（登录必需，匿名 401）。只下发布栋 / 房号 / 均分 / 等级，
     * 不含姓名、学号、成员数、名次等内部信息。
     */
    public Result<?> civilPublicity(Long actorId, String role, String term, String building) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        List<CommunityCivilRoom> rows = civilRoomMapper.list(trimToNull(term), trimToNull(building), null, true);
        List<Map<String, Object>> data = new ArrayList<>();
        for (CommunityCivilRoom room : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("building", room.getBuilding());
            item.put("roomNo", room.getRoomNo());
            item.put("avgScore", room.getAvgScore());
            item.put("awardLevel", room.getAwardLevel());
            data.add(item);
        }
        return Result.success(data);
    }

    // =========================================================================
    // 统计与导出：GET /stats、GET /export
    // =========================================================================

    /** 社区总览统计（辅导员按名下学生裁剪）。 */
    public Result<?> stats(Long actorId, String role, String term, String startDate, String endDate) {
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        String t = trimToNull(term);
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        Long counselorId = scopeCounselor(actorId, role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", t);
        data.put("startDate", start);
        data.put("endDate", end);
        data.put("batches", batchMapper.summary(t));
        data.put("batchStatus", batchMapper.countGroupByStatus(t));
        data.put("inspections", inspectionMapper.summary(null, t, null, counselorId));
        data.put("inspectionGrade", inspectionMapper.countGroupByGrade(null, t, null, counselorId));
        data.put("inspectionRectify", inspectionMapper.countGroupByRectify(null, t, null, counselorId));
        data.put("lates", lateMapper.summary(counselorId, start, end));
        data.put("lateType", lateMapper.countGroupByType(counselorId, start, end));
        data.put("lateHandling", lateMapper.countGroupByHandling(counselorId, start, end));
        data.put("violations", violationMapper.summary(counselorId, start, end));
        data.put("violationType", violationMapper.countGroupByType(counselorId, start, end));
        data.put("violationAdvice", violationMapper.countGroupByAdvice(counselorId, start, end));
        data.put("activities", activityMapper.summary(t));
        data.put("activityStatus", activityMapper.countGroupByStatus(t));
        data.put("civilRooms", civilRoomMapper.summary(t));
        return Result.success(data);
    }

    /**
     * 导出 CSV（UTF-8 BOM，脱敏）。type = inspections / lates / violations / civil-rooms。
     * 姓名首字保留其余打星、学号只留后 4 位；内部处理备注（handling_note / result_note）一律不进导出。
     * 控制器直接拿 CsvExport 里的 csv / filename 落字节；失败时用 errorCode 回该状态码。
     */
    public CsvExport exportCsv(Long actorId, String role, String type, String term, String building,
                               String grade, String rectifyStatus, String startDate, String endDate) {
        CsvExport out = new CsvExport();
        Result<?> guard = guardStaff(actorId, role);
        if (guard != null) {
            out.errorCode = guard.getCode();
            out.errorMessage = guard.getMessage();
            return out;
        }
        String t = trimToNull(type);
        if (t == null) {
            out.errorCode = 400;
            out.errorMessage = "请指定导出类型 type（inspections / lates / violations / civil-rooms）";
            return out;
        }
        Long counselorId = scopeCounselor(actorId, role);
        StringBuilder sb = new StringBuilder();
        if ("inspections".equals(t)) {
            List<CommunityInspection> rows = inspectionMapper.list(null, trimToNull(term), trimToNull(building),
                    null, trimToNull(grade), trimToNull(rectifyStatus), null, null, counselorId, false, false,
                    0, MAX_EXPORT + 1);
            if (rows.size() > MAX_EXPORT) {
                return tooMany(out);
            }
            sb.append("批次,学期,楼栋,房号,检查日期,总分,等级,整改状态,整改截止,问题描述,检查人,来源\r\n");
            for (CommunityInspection r : rows) {
                sb.append(csvCell(r.getBatchName())).append(',')
                        .append(csvCell(r.getTerm())).append(',')
                        .append(csvCell(r.getBuilding())).append(',')
                        .append(csvCell(r.getRoomNo())).append(',')
                        .append(csvCell(r.getCheckDate() == null ? null : DAY.format(r.getCheckDate()))).append(',')
                        .append(csvCell(r.getScore() == null ? null : r.getScore().toPlainString())).append(',')
                        .append(csvCell(gradeLabel(r.getGrade()))).append(',')
                        .append(csvCell(rectifyLabel(r.getRectifyStatus()))).append(',')
                        .append(csvCell(r.getRectifyDeadline() == null ? null : DAY.format(r.getRectifyDeadline()))).append(',')
                        .append(csvCell(r.getProblems())).append(',')
                        .append(csvCell(r.getInspectorName())).append(',')
                        .append(csvCell(r.getSource())).append("\r\n");
            }
            out.filename = "community-inspections.csv";
        } else if ("lates".equals(t)) {
            LocalDate start = parseDate(startDate);
            LocalDate end = parseDate(endDate);
            List<CommunityLate> rows = lateMapper.list(null, trimToNull(building), null, null, null, null,
                    counselorId, start, end, 0, MAX_EXPORT + 1);
            if (rows.size() > MAX_EXPORT) {
                return tooMany(out);
            }
            sb.append("发生日期,类型,姓名,学号,楼栋,房号,外出时间,回园区时间,来源,原因说明,是否核实,处理状态\r\n");
            for (CommunityLate r : rows) {
                sb.append(csvCell(r.getOccurDate() == null ? null : DAY.format(r.getOccurDate()))).append(',')
                        .append(csvCell("absent".equals(r.getType()) ? "夜不归宿" : "晚归")).append(',')
                        .append(csvCell(maskName(r.getStudentName()))).append(',')
                        .append(csvCell(tail4(r.getStudentNo()))).append(',')
                        .append(csvCell(r.getBuilding())).append(',')
                        .append(csvCell(r.getRoomNo())).append(',')
                        .append(csvCell(r.getOutTime() == null ? null : CLOCK.format(r.getOutTime()))).append(',')
                        .append(csvCell(r.getInTime() == null ? null : CLOCK.format(r.getInTime()))).append(',')
                        .append(csvCell(r.getSource())).append(',')
                        .append(csvCell(r.getReasonNote())).append(',')
                        .append(csvCell(r.getConfirmed() != null && r.getConfirmed() == 1 ? "已核实" : "未核实")).append(',')
                        .append(csvCell(statusLabel(r.getHandling()))).append("\r\n");
            }
            out.filename = "community-lates.csv";
        } else if ("violations".equals(t)) {
            LocalDate start = parseDate(startDate);
            LocalDate end = parseDate(endDate);
            List<CommunityViolation> rows = violationMapper.list(null, trimToNull(building), null, null, null,
                    null, counselorId, start, end, 0, MAX_EXPORT + 1);
            if (rows.size() > MAX_EXPORT) {
                return tooMany(out);
            }
            sb.append("发生时间,类型,姓名,学号,楼栋,房号,违规事实,依据条文,处理建议,处理状态\r\n");
            for (CommunityViolation r : rows) {
                sb.append(csvCell(r.getOccurredAt() == null ? null : STAMP.format(r.getOccurredAt()))).append(',')
                        .append(csvCell(violationTypeLabel(r.getType()))).append(',')
                        .append(csvCell(maskName(r.getStudentName()))).append(',')
                        .append(csvCell(tail4(r.getStudentNo()))).append(',')
                        .append(csvCell(r.getBuilding())).append(',')
                        .append(csvCell(r.getRoomNo())).append(',')
                        .append(csvCell(r.getDetail())).append(',')
                        .append(csvCell(r.getRuleRef())).append(',')
                        .append(csvCell(adviceLabel(r.getAdvice()))).append(',')
                        .append(csvCell(statusLabel(r.getHandling()))).append("\r\n");
            }
            out.filename = "community-violations.csv";
        } else if ("civil-rooms".equals(t)) {
            List<CommunityCivilRoom> rows = civilRoomMapper.list(trimToNull(term), trimToNull(building), null, false);
            if (rows.size() > MAX_EXPORT) {
                return tooMany(out);
            }
            sb.append("学期,楼栋,房号,学期均分,最低分,楼栋内名次,是否获评,获奖等级,公示时间\r\n");
            for (CommunityCivilRoom r : rows) {
                sb.append(csvCell(r.getTerm())).append(',')
                        .append(csvCell(r.getBuilding())).append(',')
                        .append(csvCell(r.getRoomNo())).append(',')
                        .append(csvCell(r.getAvgScore() == null ? null : r.getAvgScore().toPlainString())).append(',')
                        .append(csvCell(r.getMinScore() == null ? null : r.getMinScore().toPlainString())).append(',')
                        .append(csvCell(r.getRankInBuilding() == null ? null : String.valueOf(r.getRankInBuilding()))).append(',')
                        .append(csvCell(r.getAwarded() != null && r.getAwarded() == 1 ? "已评" : "候选")).append(',')
                        .append(csvCell(r.getAwardLevel())).append(',')
                        .append(csvCell(r.getPublishedAt() == null ? null : STAMP.format(r.getPublishedAt()))).append("\r\n");
            }
            out.filename = "community-civil-rooms.csv";
        } else {
            out.errorCode = 400;
            out.errorMessage = "导出类型只支持 inspections / lates / violations / civil-rooms";
            return out;
        }
        out.csv = sb.toString();
        return out;
    }

    // =========================================================================
    // 内部实现
    // =========================================================================

    /** 组织一条检查记录（录入与导入共用），失败时 Result 里是 400/403/404 文案。 */
    private Result<?> prepareInspection(Long actorId, String role, CommunityBatch batch, Map<String, Object> body) {
        String building = trimToNull(text(pick(body, "building", "dormBuilding", "dorm_building")));
        if (building == null) {
            return Result.error("请填写楼栋 building（student_profile.dorm_building 口径，如「A 栋」）");
        }
        String roomNo = trimToNull(text(pick(body, "roomNo", "room_no", "dormRoom", "dorm_room")));
        if (roomNo == null) {
            return Result.error("请填写房间号 roomNo（如「A-305」）");
        }
        String scopeBuilding = trimToNull(batch.getScopeBuilding());
        if (scopeBuilding != null && !scopeBuilding.equals(building)) {
            return Result.error("该批次只覆盖楼栋「" + scopeBuilding + "」，不能录入「" + building + "」");
        }
        Result<?> scope = checkRoomScope(actorId, role, building, roomNo);
        if (scope != null) {
            return scope;
        }
        Map<String, Integer> maxOfKey = criteriaMaxMap(batch.getCriteriaJson());
        Map<String, Object> items = null;
        Object itemsObj = pick(body, "itemJson", "item_json", "items");
        if (itemsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) itemsObj;
            items = m;
        } else if (itemsObj instanceof String && !isBlank((String) itemsObj)) {
            try {
                items = objectMapper.readValue((String) itemsObj, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                return Result.error("itemJson 格式不正确，应为 {\"power\":18,\"fire\":19}");
            }
        }
        BigDecimal score = dec(pick(body, "score"));
        BigDecimal itemSum = null;
        if (items != null && !items.isEmpty()) {
            itemSum = BigDecimal.ZERO;
            for (Map.Entry<String, Object> entry : items.entrySet()) {
                String key = entry.getKey();
                if (!maxOfKey.containsKey(key)) {
                    return Result.error("评分项「" + key + "」不在批次评分项里（可选："
                            + String.join(" / ", maxOfKey.keySet()) + "）");
                }
                BigDecimal value = dec(entry.getValue());
                if (value == null || value.signum() < 0) {
                    return Result.error("评分项「" + key + "」的得分必须是非负数");
                }
                if (value.compareTo(new BigDecimal(maxOfKey.get(key))) > 0) {
                    return Result.error("评分项「" + key + "」超出该项满分 " + maxOfKey.get(key));
                }
                itemSum = itemSum.add(value);
            }
        }
        if (score == null) {
            if (itemSum == null) {
                return Result.error("请填写总分 score，或按批次评分项填写逐项得分 itemJson");
            }
            score = itemSum;
        }
        score = score.setScale(2, RoundingMode.HALF_UP);
        if (score.signum() < 0 || score.compareTo(new BigDecimal("100")) > 0) {
            return Result.error("总分必须在 0-100 之间");
        }
        String problems = trimToNull(text(pick(body, "problems")));
        if (problems != null && problems.length() > 500) {
            return Result.error("问题描述不能超过 500 字");
        }
        LocalDate checkDate = parseDate(pick(body, "checkDate", "check_date"));
        if (checkDate == null) {
            checkDate = LocalDate.now();
        }
        boolean needRectify = score.compareTo(GRADE_PASS) < 0 || flag(pick(body, "rectifyRequired", "rectify_required")) > 0;
        CommunityInspection inspection = new CommunityInspection();
        inspection.setBatchId(batch.getId());
        inspection.setBuilding(building);
        inspection.setRoomNo(roomNo);
        inspection.setLeaderStudentId(inspectionMapper.findRoomLeader(building, roomNo));
        inspection.setCheckDate(checkDate);
        inspection.setInspectorId(actorId);
        inspection.setItemJson(items == null || items.isEmpty() ? null : toJson(items));
        inspection.setScore(score);
        inspection.setGrade(gradeOf(score));
        inspection.setProblems(problems);
        inspection.setRectifyRequired(needRectify ? 1 : 0);
        inspection.setRectifyDeadline(needRectify ? checkDate.plusDays(7) : null);
        inspection.setRectifyStatus(needRectify ? "pending" : "none");
        inspection.setSource(trimToNull(text(pick(body, "source"))) == null ? "manual" : trimToNull(text(pick(body, "source"))));
        return Result.success(inspection);
    }

    /** 辅导员只能操作名下学生所在宿舍；管理员不受限。 */
    private Result<?> checkRoomScope(Long actorId, String role, String building, String roomNo) {
        if (isAdmin(role)) {
            return null;
        }
        if (inspectionMapper.countRoomOfCounselor(actorId, building, roomNo) > 0) {
            return null;
        }
        return Result.error(403, "只能操作名下学生所在宿舍（" + building + " " + roomNo + " 不在你的负责范围）");
    }

    /** 解析 CSV（表头驱动，支持中英别名），返回行 Map；解析不了的行原样带 error 字段。 */
    private List<Map<String, Object>> parseInspectionCsv(String csv) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] lines = csv.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        List<String> header = null;
        for (String line : lines) {
            if (isBlank(line)) {
                continue;
            }
            List<String> cells = splitCsvLine(line);
            if (header == null) {
                header = new ArrayList<>();
                for (String cell : cells) {
                    header.add(normalizeHeader(cell));
                }
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < cells.size(); i++) {
                String key = header.get(i);
                if (key == null) {
                    continue;
                }
                row.put(key, trimToNull(cells.get(i)));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 表头归一化：楼栋/building、房间号/roomNo、总分/score、问题/problems、检查日期/checkDate、逐项/itemJson。 */
    private String normalizeHeader(String cell) {
        String h = trimToNull(cell);
        if (h == null) {
            return null;
        }
        String low = h.toLowerCase();
        if ("building".equals(low) || "楼栋".equals(h) || "宿舍楼".equals(h)) {
            return "building";
        }
        if ("roomno".equals(low) || "room_no".equals(low) || "房间号".equals(h) || "宿舍".equals(h)) {
            return "roomNo";
        }
        if ("score".equals(low) || "总分".equals(h) || "得分".equals(h)) {
            return "score";
        }
        if ("problems".equals(low) || "问题".equals(h) || "问题描述".equals(h)) {
            return "problems";
        }
        if ("checkdate".equals(low) || "check_date".equals(low) || "检查日期".equals(h)) {
            return "checkDate";
        }
        if ("itemjson".equals(low) || "item_json".equals(low) || "逐项得分".equals(h)) {
            return "itemJson";
        }
        return null;
    }

    /** 极简 CSV 行切分（支持双引号包裹与 "" 转义）。 */
    private List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        cells.add(sb.toString());
        return cells;
    }

    private static Map<String, Object> errorRow(int index, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("row", index + 2);
        item.put("message", message == null ? "数据不合法" : message);
        return item;
    }

    private static CsvExport tooMany(CsvExport out) {
        out.errorCode = 400;
        out.errorMessage = "导出数据量超过 " + MAX_EXPORT + " 行，请缩小范围（学期 / 日期 / 楼栋）后重试";
        return out;
    }

    /** 评分项 JSON 校验：数组、key 唯一、max 为正整数、满分合计 100。 */
    public String validateCriteria(String criteriaJson) {
        if (isBlank(criteriaJson)) {
            return "请填写评分项 criteriaJson（学校发布口径；留空则用平台演示评分项）";
        }
        List<Map<String, Object>> items;
        try {
            items = objectMapper.readValue(criteriaJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return "评分项 JSON 不正确，应为 [{\"key\":\"power\",\"name\":\"用电安全\",\"max\":20,\"ruleRef\":\"园区管理规定第十条\"}]";
        }
        if (items == null || items.isEmpty()) {
            return "评分项至少要有 1 项";
        }
        Set<String> keys = new LinkedHashSet<>();
        int sum = 0;
        for (Map<String, Object> item : items) {
            String key = trimToNull(text(item.get("key")));
            if (key == null) {
                return "评分项缺少 key";
            }
            if (!keys.add(key)) {
                return "评分项 key 重复：" + key;
            }
            if (trimToNull(text(item.get("name"))) == null) {
                return "评分项 " + key + " 缺少 name";
            }
            Integer max = integer(item.get("max"));
            if (max == null || max <= 0) {
                return "评分项 " + key + " 的 max 必须是正整数";
            }
            sum += max;
        }
        if (sum != 100) {
            return "评分项满分合计应为 100，当前为 " + sum;
        }
        return null;
    }

    /** 评分项 key → max。 */
    public Map<String, Integer> criteriaMaxMap(String criteriaJson) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (isBlank(criteriaJson)) {
            criteriaJson = DEFAULT_CRITERIA_JSON;
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(criteriaJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> item : items) {
                String key = trimToNull(text(item.get("key")));
                Integer max = integer(item.get("max"));
                if (key != null && max != null) {
                    map.put(key, max);
                }
            }
        } catch (Exception ignored) {
            // 批次里存的是坏 JSON 时返回空表，录入接口会因「评分项不在批次评分项里」而报错
        }
        return map;
    }

    /** 评分项 JSON → List<Map>（列表展示用）。 */
    public List<Map<String, Object>> criteriaOf(String criteriaJson) {
        if (isBlank(criteriaJson)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(criteriaJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return items == null ? new ArrayList<Map<String, Object>>() : items;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 是否用的平台演示评分项。 */
    public static boolean isDemoCriteria(String criteriaJson) {
        return DEFAULT_CRITERIA_JSON.equals(criteriaJson == null ? null : criteriaJson.trim());
    }

    /** 分数 → 等级：excellent >= 90 / good 80-89 / pass 60-79 / fail < 60。 */
    public static String gradeOf(BigDecimal score) {
        if (score == null) {
            return "fail";
        }
        if (score.compareTo(GRADE_EXCELLENT) >= 0) {
            return "excellent";
        }
        if (score.compareTo(GRADE_GOOD) >= 0) {
            return "good";
        }
        if (score.compareTo(GRADE_PASS) >= 0) {
            return "pass";
        }
        return "fail";
    }

    /** 楼栋内名次：均分降序，同分并列（1 + 比自己高的房间数）。 */
    public static int rankInBuilding(List<BigDecimal> buildingScores, BigDecimal avgScore) {
        if (buildingScores == null || avgScore == null) {
            return 1;
        }
        int higher = 0;
        for (BigDecimal other : buildingScores) {
            if (other != null && other.compareTo(avgScore) > 0) {
                higher++;
            }
        }
        return higher + 1;
    }

    /** "9001,9002" / "[9001,9002]" → JSON 数组字符串 "[9001,9002]"。 */
    public static String toJsonArray(String ids) {
        if (isBlank(ids)) {
            return null;
        }
        String body = ids.trim();
        if (body.startsWith("[")) {
            return body;
        }
        StringBuilder sb = new StringBuilder("[");
        String[] parts = body.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i].trim());
        }
        return sb.append(']').toString();
    }

    // ---- 权限守卫（返回 null 表示通过） ----

    public static Result<?> guardLogin(Long actorId) {
        return actorId == null ? Result.error(401, "请先登录") : null;
    }

    public static Result<?> guardStaff(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        return STAFF_ROLES.contains(role) ? null : Result.error(403, "无权访问");
    }

    public static Result<?> guardAdmin(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        return "admin".equals(role) ? null : Result.error(403, "仅管理员可操作");
    }

    public static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    public static boolean isStaff(String role) {
        return STAFF_ROLES.contains(role);
    }

    public static boolean isStudent(String role) {
        return "student".equals(role);
    }

    /** 辅导员看名下学生；管理员看全部。 */
    public static Long scopeCounselor(Long actorId, String role) {
        return isAdmin(role) ? null : actorId;
    }

    // ---- 通用工具（三个社区服务共用，public static 便于直接调用） ----

    public static Object pick(Map<String, Object> body, String... keys) {
        if (body == null) {
            return null;
        }
        for (String key : keys) {
            if (body.containsKey(key)) {
                Object value = body.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    public static boolean has(Map<String, Object> body, String... keys) {
        if (body == null) {
            return false;
        }
        for (String key : keys) {
            if (body.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    public static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static String trim(Object value) {
        String s = text(value);
        return s == null ? null : s.trim();
    }

    public static String trimToNull(Object value) {
        String s = trim(value);
        return s == null || s.isEmpty() ? null : s;
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public static int flag(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 0 ? 0 : 1;
        }
        String s = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "是".equals(s) || "yes".equalsIgnoreCase(s)) {
            return 1;
        }
        return 0;
    }

    public static Integer integer(Object value) {
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
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int toInt(Object value, int fallback) {
        Integer v = integer(value);
        return v == null ? fallback : v;
    }

    public static Long longOf(Object value) {
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
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal dec(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal scale2(Object value) {
        BigDecimal v = dec(value);
        return v == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static LocalDate parseDate(Object value) {
        String s = trim(value);
        if (s == null) {
            return null;
        }
        if (s.length() >= 10) {
            s = s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s, DAY);
        } catch (Exception e) {
            return null;
        }
    }

    public static LocalTime parseTime(Object value) {
        String s = trim(value);
        if (s == null) {
            return null;
        }
        if (s.length() == 5) {
            s = s + ":00";
        }
        if (s.length() > 8) {
            s = s.substring(0, 8);
        }
        try {
            return LocalTime.parse(s, CLOCK);
        } catch (Exception e) {
            return null;
        }
    }

    /** 支持 "yyyy-MM-dd HH:mm:ss" / ISO 带 T / "yyyy-MM-dd"（补 00:00:00）。 */
    public static LocalDateTime parseDateTime(Object value) {
        String s = trim(value);
        if (s == null) {
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

    public static Map<String, Object> pageOf(List<?> list, int total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    /** 批量操作的单条失败明细：{id, message}。 */
    public static Map<String, Object> rowError(Long id, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("id", id);
        error.put("message", message);
        return error;
    }

    /** CSV 单元格：含 , " 换行 才加引号，内部 " 翻倍。 */
    public static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** 姓名脱敏：保留首个码点，其余打星。 */
    public static String maskName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String s = value.trim();
        if (s.codePointCount(0, s.length()) <= 1) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, 1)) + "*";
    }

    /** 学号脱敏：只留后 4 位。 */
    public static String tail4(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }

    public static String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 状态 / 枚举的中文文案（错误提示与导出表头用，统一一处）。 */
    public static String statusLabel(String status) {
        if (status == null) {
            return "-";
        }
        switch (status) {
            case "draft":
                return "草稿";
            case "checking":
                return "检查中";
            case "scored":
                return "已评分";
            case "published":
                return "已公布";
            case "finished":
                return "已结束";
            case "cancelled":
                return "已取消";
            case "pending":
                return "待处理";
            case "contacted":
                return "已联系";
            case "notified":
                return "已告知";
            case "closed":
                return "已了结";
            case "signup":
                return "报名中";
            case "ongoing":
                return "进行中";
            default:
                return status;
        }
    }

    public static String rectifyLabel(String rectifyStatus) {
        if (rectifyStatus == null) {
            return "-";
        }
        switch (rectifyStatus) {
            case "none":
                return "无需整改";
            case "pending":
                return "待整改";
            case "submitted":
                return "已提交说明";
            case "accepted":
                return "复查通过";
            case "rejected":
                return "复查未通过";
            default:
                return rectifyStatus;
        }
    }

    public static String gradeLabel(String grade) {
        if (grade == null) {
            return "-";
        }
        switch (grade) {
            case "excellent":
                return "优秀";
            case "good":
                return "良好";
            case "pass":
                return "合格";
            case "fail":
                return "不合格";
            default:
                return grade;
        }
    }

    public static String violationTypeLabel(String type) {
        if (type == null) {
            return "-";
        }
        switch (type) {
            case "late":
                return "晚归";
            case "absence":
                return "夜不归宿";
            case "appliance":
                return "违规电器";
            case "fire":
                return "消防隐患";
            case "pet":
                return "饲养宠物";
            case "rent":
                return "出租床位";
            case "climb":
                return "攀爬";
            case "noise":
                return "噪音";
            default:
                return "其他";
        }
    }

    public static String adviceLabel(String advice) {
        if (advice == null) {
            return "-";
        }
        switch (advice) {
            case "education":
                return "教育";
            case "circulated":
                return "通报";
            case "warning":
                return "警告";
            case "serious_warning":
                return "严重警告";
            case "recorded":
                return "记录在案";
            default:
                return advice;
        }
    }

    /**
     * 导出结果载体：errorCode 非空表示失败（控制器回该状态码），否则 csv + filename 直接落字节。
     * 与 P13/P14 既有导出保持同一形状。
     */
    public static class CsvExport {
        public Integer errorCode;
        public String errorMessage;
        public String csv;
        public String filename;
    }
}
