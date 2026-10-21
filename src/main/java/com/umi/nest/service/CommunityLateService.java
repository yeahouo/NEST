package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.CommunityLate;
import com.umi.nest.entity.CommunityViolation;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.CommunityInspectionMapper;
import com.umi.nest.mapper.CommunityLateMapper;
import com.umi.nest.mapper.CommunityViolationMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 晚归 / 夜不归宿与社区违规台账（学工 P15）。
 *
 * <p>口径（《学工平台功能对齐说明》§10.2-3/-4）：晚归判定照 S19 第七条——正常出入 6:00-23:30，
 * 之外才算晚归，断电时间只在页面提示；没有门禁硬件，source=card 也走导入或人工登记；
 * 违规处理只到教育惩戒与建议（advice），且 rule_ref 必填，平台不生成处分决定。</p>
 *
 * <p>状态迁移一律 CAS（handling / confirmed 是这里的状态列），每个入口先做显式前置校验；
 * fromStatus 取自刚读到的现值，但**不依赖**它做放行判断。</p>
 *
 * <p>学生端只拿到本人记录，且内部处理备注（handling_note / result_note / handled_by）
 * 在服务层抹掉，不靠前端不渲染。</p>
 */
@Service("communityLateService")
public class CommunityLateService {

    private static final List<String> LATE_TYPES = Arrays.asList("late", "absent");
    private static final List<String> LATE_SOURCES = Arrays.asList("card", "check", "report");
    private static final List<String> HANDLING_FLOW = Arrays.asList("pending", "contacted", "closed");

    private static final List<String> VIOLATION_TYPES = Arrays.asList("late", "absence", "appliance", "fire",
            "pet", "rent", "climb", "noise", "other");
    private static final List<String> VIOLATION_ADVICE = Arrays.asList("education", "circulated", "warning",
            "serious_warning", "recorded");
    private static final List<String> VIOLATION_HANDLING_FLOW = Arrays.asList("pending", "notified", "closed");

    @Autowired
    private CommunityLateMapper lateMapper;

    @Autowired
    private CommunityViolationMapper violationMapper;

    @Autowired
    private CommunityInspectionMapper inspectionMapper;

    @Autowired
    private StudentProfileMapper studentProfileMapper;

    // =========================================================================
    // 晚归 / 夜不归宿：GET /lates、POST /lates、PUT /lates/{id}、
    //                POST /lates/{id}/confirm、POST /lates/{id}/handle、GET /my/lates、
    //                POST /my/lates/{id}/note
    // =========================================================================

    /** 晚归台账分页列表（管理员全量；辅导员只看名下学生）。 */
    public Result<?> listLates(Long actorId, String role, String studentId, String building, String type,
                               String handling, String confirmed, String keyword, String startDate,
                               String endDate, Integer page, Integer size) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        Long target = CommunityService.longOf(studentId);
        Integer confirmedFlag = CommunityService.isBlank(confirmed) ? null : CommunityService.flag(confirmed);
        if (confirmedFlag != null && "false".equalsIgnoreCase(confirmed)) {
            confirmedFlag = 0;
        }
        Long counselorId = CommunityService.scopeCounselor(actorId, role);
        List<CommunityLate> rows = lateMapper.list(target, CommunityService.trimToNull(building),
                CommunityService.trimToNull(type), CommunityService.trimToNull(handling), confirmedFlag,
                CommunityService.trimToNull(keyword), counselorId, CommunityService.parseDate(startDate),
                CommunityService.parseDate(endDate), (p - 1) * s, s);
        int total = lateMapper.countList(target, CommunityService.trimToNull(building),
                CommunityService.trimToNull(type), CommunityService.trimToNull(handling), confirmedFlag,
                CommunityService.trimToNull(keyword), counselorId, CommunityService.parseDate(startDate),
                CommunityService.parseDate(endDate));
        return Result.success(CommunityService.pageOf(rows, total, p, s));
    }

    /** 登记晚归 / 夜不归宿：晚归（late）时间必须落在 6:00-23:30 之外，夜不归宿（absent）不限。 */
    public Result<?> createLate(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        Long studentId = CommunityService.longOf(CommunityService.pick(body, "studentId", "student_id"));
        if (studentId == null) {
            return Result.error("请指定学生 studentId");
        }
        StudentProfile profile = studentProfileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(404, "学生档案不存在（student_profile 里没有该学生）");
        }
        Result<?> scope = checkStudentScope(actorId, role, studentId);
        if (scope != null) {
            return scope;
        }
        LocalDate occurDate = CommunityService.parseDate(CommunityService.pick(body, "occurDate", "occur_date", "date"));
        if (occurDate == null) {
            return Result.error("请填写发生日期 occurDate（yyyy-MM-dd）");
        }
        String type = CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "type"))), "late");
        if (!LATE_TYPES.contains(type)) {
            return Result.error("类型只支持 late（晚归）/ absent（夜不归宿）");
        }
        String source = CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "source"))), "report");
        if (!LATE_SOURCES.contains(source)) {
            return Result.error("来源只支持 card（门禁导入）/ check（巡查）/ report（上报）");
        }
        LocalTime outTime = CommunityService.parseTime(CommunityService.pick(body, "outTime", "out_time"));
        LocalTime inTime = CommunityService.parseTime(CommunityService.pick(body, "inTime", "in_time"));
        if (CommunityService.has(body, "outTime", "out_time") && outTime == null) {
            return Result.error("外出时间 outTime 格式不正确（HH:mm）");
        }
        if (CommunityService.has(body, "inTime", "in_time") && inTime == null) {
            return Result.error("回园区时间 inTime 格式不正确（HH:mm）");
        }
        if ("late".equals(type)) {
            if (outTime == null && inTime == null) {
                return Result.error("晚归记录至少要有一个时间：外出时间 outTime 或回园区时间 inTime");
            }
            for (LocalTime t : Arrays.asList(outTime, inTime)) {
                if (t != null && !isOutsideNormalWindow(t)) {
                    return Result.error("晚归时间必须落在 6:00-23:30 之外（正常出入时段内不算晚归，"
                            + CommunityService.CLOCK.format(t) + " 属于正常出入）");
                }
            }
        }
        String reason = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "reason")));
        if (reason != null && reason.length() > 32) {
            return Result.error("原因分类码不能超过 32 字");
        }
        if (reason == null) {
            reason = "late".equals(type) ? "late_return" : "night_out";
        }
        String reasonNote = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "reasonNote", "reason_note")));
        if (reasonNote != null && reasonNote.length() > 255) {
            return Result.error("原因说明不能超过 255 字");
        }
        if (lateMapper.findByStudentDate(studentId, occurDate, type) != null) {
            return Result.error("该学生当天已有同类型记录（同一学生同一天同一类型只登记一条）");
        }
        CommunityLate late = new CommunityLate();
        late.setStudentId(studentId);
        late.setBuilding(CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "building"))), profile.getDormBuilding()));
        late.setRoomNo(CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "roomNo", "room_no"))), profile.getDormRoom()));
        late.setOccurDate(occurDate);
        late.setType(type);
        late.setOutTime(outTime);
        late.setInTime(inTime);
        late.setSource(source);
        late.setReason(reason);
        late.setReasonNote(reasonNote);
        late.setConfirmed(0);
        late.setCounselorId(profile.getCounselorId());
        late.setHandling("pending");
        lateMapper.insert(late);
        return Result.success(lateMapper.findById(late.getId()));
    }

    /** 修改晚归台账：已了结（closed）的记录不能再改。 */
    public Result<?> updateLate(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        CommunityLate exist = lateMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "晚归记录不存在");
        }
        Result<?> scope = checkStudentScope(actorId, role, exist.getStudentId());
        if (scope != null) {
            return scope;
        }
        if ("closed".equals(exist.getHandling())) {
            return Result.error("已了结的记录不能再修改（如需变更请先重开处理状态）");
        }
        String type = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "type")));
        if (type != null && !LATE_TYPES.contains(type)) {
            return Result.error("类型只支持 late（晚归）/ absent（夜不归宿）");
        }
        String source = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "source")));
        if (source != null && !LATE_SOURCES.contains(source)) {
            return Result.error("来源只支持 card / check / report");
        }
        LocalTime outTime = CommunityService.has(body, "outTime", "out_time")
                ? CommunityService.parseTime(CommunityService.pick(body, "outTime", "out_time")) : exist.getOutTime();
        LocalTime inTime = CommunityService.has(body, "inTime", "in_time")
                ? CommunityService.parseTime(CommunityService.pick(body, "inTime", "in_time")) : exist.getInTime();
        String finalType = type == null ? exist.getType() : type;
        if ("late".equals(finalType)) {
            if (outTime == null && inTime == null) {
                return Result.error("晚归记录至少要有一个时间：外出时间 outTime 或回园区时间 inTime");
            }
            for (LocalTime t : Arrays.asList(outTime, inTime)) {
                if (t != null && !isOutsideNormalWindow(t)) {
                    return Result.error("晚归时间必须落在 6:00-23:30 之外（"
                            + CommunityService.CLOCK.format(t) + " 属于正常出入）");
                }
            }
        }
        String reason = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "reason")));
        if (reason != null && reason.length() > 32) {
            return Result.error("原因分类码不能超过 32 字");
        }
        String reasonNote = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "reasonNote", "reason_note")));
        if (reasonNote != null && reasonNote.length() > 255) {
            return Result.error("原因说明不能超过 255 字");
        }
        LocalDate occurDate = CommunityService.has(body, "occurDate", "occur_date", "date")
                ? CommunityService.parseDate(CommunityService.pick(body, "occurDate", "occur_date", "date"))
                : exist.getOccurDate();
        if (CommunityService.has(body, "occurDate", "occur_date", "date") && occurDate == null) {
            return Result.error("发生日期 occurDate 格式不正确（yyyy-MM-dd）");
        }
        CommunityLate merged = new CommunityLate();
        merged.setId(exist.getId());
        merged.setBuilding(CommunityService.has(body, "building")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "building"))) : exist.getBuilding());
        merged.setRoomNo(CommunityService.has(body, "roomNo", "room_no")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "roomNo", "room_no"))) : exist.getRoomNo());
        merged.setOccurDate(occurDate);
        merged.setType(finalType);
        merged.setOutTime(outTime);
        merged.setInTime(inTime);
        merged.setSource(source == null ? exist.getSource() : source);
        merged.setReason(reason == null ? exist.getReason() : reason);
        merged.setReasonNote(CommunityService.has(body, "reasonNote", "reason_note") ? reasonNote : exist.getReasonNote());
        int rows = lateMapper.update(merged, exist.getHandling());
        if (rows != 1) {
            return Result.error(CommunityService.CAS_FAIL);
        }
        return Result.success(lateMapper.findById(id));
    }

    /** 核实晚归记录（辅导员 / 管理员）：confirmed=0 → 1。 */
    public Result<?> confirmLate(Long actorId, String role, Long id) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityLate exist = lateMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "晚归记录不存在");
        }
        Result<?> scope = checkStudentScope(actorId, role, exist.getStudentId());
        if (scope != null) {
            return scope;
        }
        if (exist.getConfirmed() != null && exist.getConfirmed() == 1) {
            return Result.error("该记录已核实，无需重复核实");
        }
        int rows = lateMapper.confirm(id, actorId, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CommunityService.CAS_FAIL);
        }
        return Result.success("已核实", lateMapper.findById(id));
    }

    /** 处理晚归：pending → contacted → closed（只允许向前推进），可同时关联违规台账。 */
    public Result<?> handleLate(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityLate exist = lateMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "晚归记录不存在");
        }
        Result<?> scope = checkStudentScope(actorId, role, exist.getStudentId());
        if (scope != null) {
            return scope;
        }
        String current = exist.getHandling();
        if ("closed".equals(current)) {
            return Result.error("该记录已了结，不能再推进处理状态");
        }
        String handling = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "handling", "status")));
        if (handling == null) {
            return Result.error("请给出处理状态 handling（pending / contacted / closed）");
        }
        if (!HANDLING_FLOW.contains(handling)) {
            return Result.error("处理状态只支持 pending / contacted / closed");
        }
        if (HANDLING_FLOW.indexOf(handling) <= HANDLING_FLOW.indexOf(current)) {
            return Result.error("处理状态只能向前推进（当前 " + CommunityService.statusLabel(current)
                    + " → 目标 " + CommunityService.statusLabel(handling) + "）");
        }
        String handlingNote = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "handlingNote", "handling_note", "note")));
        if (handlingNote != null && handlingNote.length() > 500) {
            return Result.error("处理备注不能超过 500 字");
        }
        Long violationId = CommunityService.longOf(CommunityService.pick(body, "violationId", "violation_id"));
        if (violationId != null) {
            CommunityViolation violation = violationMapper.findById(violationId);
            if (violation == null) {
                return Result.error(404, "关联的违规台账不存在");
            }
            if (!violation.getStudentId().equals(exist.getStudentId())) {
                return Result.error("关联的违规台账不是该学生的记录");
            }
        }
        int rows = lateMapper.handle(id, current, handling, handlingNote, violationId);
        if (rows != 1) {
            return Result.error(CommunityService.CAS_FAIL);
        }
        return Result.success("已更新处理状态", lateMapper.findById(id));
    }

    /** 学生查看本人晚归记录（内部处理备注在服务层抹掉）。 */
    public Result<?> myLates(Long actorId, String role) {
        Result<?> guard = CommunityService.guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可查看自己的晚归记录");
        }
        List<CommunityLate> rows = lateMapper.list(actorId, null, null, null, null, null, null, null, null, 0, 200);
        for (CommunityLate row : rows) {
            row.setHandlingNote(null);
        }
        return Result.success(rows);
    }

    /** 学生补充本人说明（POST /my/lates/{id}/note）。 */
    public Result<?> submitMyNote(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        CommunityLate exist = lateMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "晚归记录不存在");
        }
        if (!exist.getStudentId().equals(actorId)) {
            return Result.error(403, "只能补充本人晚归记录的说明");
        }
        String note = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "studentNote", "student_note", "note")));
        if (note == null) {
            return Result.error("请填写说明内容");
        }
        if (note.length() > 500) {
            return Result.error("说明不能超过 500 字");
        }
        int rows = lateMapper.submitNote(id, actorId, note);
        if (rows != 1) {
            return Result.error("提交失败：该记录不存在或不属于你");
        }
        return Result.success("说明已提交", lateMapper.findById(id));
    }

    // =========================================================================
    // 违规台账：GET /violations、POST /violations、PUT /violations/{id}、
    //           POST /violations/{id}/handle、GET /my/violations
    // =========================================================================

    /** 违规台账分页列表（管理员全量；辅导员只看名下学生）。 */
    public Result<?> listViolations(Long actorId, String role, String studentId, String building, String type,
                                    String advice, String handling, String keyword, String startDate,
                                    String endDate, Integer page, Integer size) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        Long target = CommunityService.longOf(studentId);
        Long counselorId = CommunityService.scopeCounselor(actorId, role);
        List<CommunityViolation> rows = violationMapper.list(target, CommunityService.trimToNull(building),
                CommunityService.trimToNull(type), CommunityService.trimToNull(advice),
                CommunityService.trimToNull(handling), CommunityService.trimToNull(keyword), counselorId,
                CommunityService.parseDate(startDate), CommunityService.parseDate(endDate), (p - 1) * s, s);
        int total = violationMapper.countList(target, CommunityService.trimToNull(building),
                CommunityService.trimToNull(type), CommunityService.trimToNull(advice),
                CommunityService.trimToNull(handling), CommunityService.trimToNull(keyword), counselorId,
                CommunityService.parseDate(startDate), CommunityService.parseDate(endDate));
        return Result.success(CommunityService.pageOf(rows, total, p, s));
    }

    /** 登记违规台账：rule_ref 必填（处理只到教育惩戒与建议，不生成处分决定）。 */
    public Result<?> createViolation(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        Long studentId = CommunityService.longOf(CommunityService.pick(body, "studentId", "student_id"));
        if (studentId == null) {
            return Result.error("请指定学生 studentId");
        }
        StudentProfile profile = studentProfileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(404, "学生档案不存在（student_profile 里没有该学生）");
        }
        Result<?> scope = checkStudentScope(actorId, role, studentId);
        if (scope != null) {
            return scope;
        }
        String type = CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "type"))), "other");
        if (!VIOLATION_TYPES.contains(type)) {
            return Result.error("违规类型只支持 late / absence / appliance / fire / pet / rent / climb / noise / other");
        }
        String detail = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "detail", "description")));
        if (detail == null) {
            return Result.error("请填写违规事实描述 detail");
        }
        if (detail.length() > 500) {
            return Result.error("违规事实描述不能超过 500 字");
        }
        String ruleRef = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "ruleRef", "rule_ref")));
        if (ruleRef == null) {
            return Result.error("请填写依据条文 ruleRef（处理只到教育惩戒与建议，必须写明依据）");
        }
        if (ruleRef.length() > 128) {
            return Result.error("依据条文不能超过 128 字");
        }
        String advice = CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "advice"))), "education");
        if (!VIOLATION_ADVICE.contains(advice)) {
            return Result.error("处理建议只支持 education / circulated / warning / serious_warning / recorded");
        }
        LocalDateTime occurredAt = CommunityService.parseDateTime(CommunityService.pick(body, "occurredAt", "occurred_at"));
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now().withNano(0);
        }
        Long lateId = CommunityService.longOf(CommunityService.pick(body, "lateId", "late_id"));
        CommunityLate late = null;
        if (lateId != null) {
            late = lateMapper.findById(lateId);
            if (late == null) {
                return Result.error(404, "关联的晚归记录不存在");
            }
            if (!late.getStudentId().equals(studentId)) {
                return Result.error("关联的晚归记录不是该学生的记录");
            }
        }
        CommunityViolation violation = new CommunityViolation();
        violation.setStudentId(studentId);
        violation.setBuilding(CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "building"))), profile.getDormBuilding()));
        violation.setRoomNo(CommunityService.orDefault(CommunityService.trimToNull(
                CommunityService.text(CommunityService.pick(body, "roomNo", "room_no"))), profile.getDormRoom()));
        violation.setInspectionId(CommunityService.longOf(CommunityService.pick(body, "inspectionId", "inspection_id")));
        violation.setLateId(lateId);
        violation.setType(type);
        violation.setOccurredAt(occurredAt);
        violation.setDetail(detail);
        violation.setRuleRef(ruleRef);
        violation.setAdvice(advice);
        violation.setHandling("pending");
        violation.setCounselorId(profile.getCounselorId());
        violationMapper.insert(violation);
        if (late != null) {
            lateMapper.linkViolation(lateId, violation.getId());
        }
        return Result.success(violationMapper.findById(violation.getId()));
    }

    /** 修改违规台账：已办结（closed）的不能再改。 */
    public Result<?> updateViolation(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (body == null) {
            return Result.error("请求内容不能为空");
        }
        CommunityViolation exist = violationMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "违规台账不存在");
        }
        Result<?> scope = checkStudentScope(actorId, role, exist.getStudentId());
        if (scope != null) {
            return scope;
        }
        if ("closed".equals(exist.getHandling())) {
            return Result.error("已办结的台账不能再修改");
        }
        String type = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "type")));
        if (type != null && !VIOLATION_TYPES.contains(type)) {
            return Result.error("违规类型只支持 late / absence / appliance / fire / pet / rent / climb / noise / other");
        }
        String advice = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "advice")));
        if (advice != null && !VIOLATION_ADVICE.contains(advice)) {
            return Result.error("处理建议只支持 education / circulated / warning / serious_warning / recorded");
        }
        String detail = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "detail", "description")));
        if (detail != null && detail.length() > 500) {
            return Result.error("违规事实描述不能超过 500 字");
        }
        String ruleRef = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "ruleRef", "rule_ref")));
        if (ruleRef != null && ruleRef.length() > 128) {
            return Result.error("依据条文不能超过 128 字");
        }
        if (ruleRef == null && exist.getRuleRef() == null) {
            return Result.error("请填写依据条文 ruleRef");
        }
        if (CommunityService.has(body, "detail", "description") && detail == null) {
            return Result.error("违规事实描述不能为空");
        }
        LocalDateTime occurredAt = CommunityService.has(body, "occurredAt", "occurred_at")
                ? CommunityService.parseDateTime(CommunityService.pick(body, "occurredAt", "occurred_at"))
                : exist.getOccurredAt();
        if (CommunityService.has(body, "occurredAt", "occurred_at") && occurredAt == null) {
            return Result.error("发生时间 occurredAt 格式不正确（yyyy-MM-dd HH:mm:ss）");
        }
        CommunityViolation merged = new CommunityViolation();
        merged.setId(exist.getId());
        merged.setBuilding(CommunityService.has(body, "building")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "building"))) : exist.getBuilding());
        merged.setRoomNo(CommunityService.has(body, "roomNo", "room_no")
                ? CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "roomNo", "room_no"))) : exist.getRoomNo());
        merged.setType(type == null ? exist.getType() : type);
        merged.setOccurredAt(occurredAt);
        merged.setDetail(detail == null ? exist.getDetail() : detail);
        merged.setRuleRef(ruleRef == null ? exist.getRuleRef() : ruleRef);
        merged.setAdvice(advice == null ? exist.getAdvice() : advice);
        int rows = violationMapper.update(merged, exist.getHandling());
        if (rows != 1) {
            return Result.error(CommunityService.CAS_FAIL);
        }
        return Result.success(violationMapper.findById(id));
    }

    /** 处理违规：pending → notified → closed（只允许向前推进）。 */
    public Result<?> handleViolation(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = CommunityService.guardStaff(actorId, role);
        if (guard != null) {
            return guard;
        }
        CommunityViolation exist = violationMapper.findById(id);
        if (exist == null) {
            return Result.error(404, "违规台账不存在");
        }
        Result<?> scope = checkStudentScope(actorId, role, exist.getStudentId());
        if (scope != null) {
            return scope;
        }
        String current = exist.getHandling();
        if ("closed".equals(current)) {
            return Result.error("该台账已办结，不能再推进处理状态");
        }
        String handling = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "handling", "status")));
        if (handling == null) {
            return Result.error("请给出处理状态 handling（pending / notified / closed）");
        }
        if (!VIOLATION_HANDLING_FLOW.contains(handling)) {
            return Result.error("处理状态只支持 pending / notified / closed");
        }
        if (VIOLATION_HANDLING_FLOW.indexOf(handling) <= VIOLATION_HANDLING_FLOW.indexOf(current)) {
            return Result.error("处理状态只能向前推进（当前 " + CommunityService.statusLabel(current)
                    + " → 目标 " + CommunityService.statusLabel(handling) + "）");
        }
        String resultNote = CommunityService.trimToNull(CommunityService.text(CommunityService.pick(body, "resultNote", "result_note", "note")));
        if (resultNote != null && resultNote.length() > 500) {
            return Result.error("处理结果说明不能超过 500 字");
        }
        int rows = violationMapper.handle(id, current, handling, resultNote, actorId, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CommunityService.CAS_FAIL);
        }
        return Result.success("已更新处理状态", violationMapper.findById(id));
    }

    /** 学生查看本人违规记录：内部处理结果说明与处理人在服务层抹掉。 */
    public Result<?> myViolations(Long actorId, String role) {
        Result<?> guard = CommunityService.guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(actorId);
        if (profile == null) {
            return Result.error(403, "仅学生本人可查看自己的违规记录");
        }
        List<CommunityViolation> rows = violationMapper.list(actorId, null, null, null, null, null, null,
                null, null, 0, 200);
        for (CommunityViolation row : rows) {
            row.setResultNote(null);
            row.setHandledBy(null);
            row.setHandledByName(null);
        }
        return Result.success(rows);
    }

    /** 辅导员只能登记 / 处理名下学生的记录（管理员不受限）。 */
    private Result<?> checkStudentScope(Long actorId, String role, Long studentId) {
        if (CommunityService.isAdmin(role)) {
            return null;
        }
        if (studentId != null && lateMapper.countStudentOfCounselor(actorId, studentId) > 0) {
            return null;
        }
        return Result.error(403, "只能操作名下学生的记录（该学生不在你的负责范围）");
    }

    /** 6:00-23:30 之外才算晚归。 */
    private static boolean isOutsideNormalWindow(LocalTime t) {
        return t.isBefore(CommunityService.NORMAL_IN) || t.isAfter(CommunityService.NORMAL_OUT);
    }

    /** 供控制器使用的空列表（学生没有宿舍档案时）。 */
    public static List<CommunityLate> emptyLates() {
        return new ArrayList<>();
    }

    /** 供控制器 / 其它服务读取的违规类型与建议枚举（避免前端硬编码）。 */
    public static Map<String, Object> enums() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lateType", LATE_TYPES);
        data.put("lateSource", LATE_SOURCES);
        data.put("violationType", VIOLATION_TYPES);
        data.put("violationAdvice", VIOLATION_ADVICE);
        return data;
    }
}
