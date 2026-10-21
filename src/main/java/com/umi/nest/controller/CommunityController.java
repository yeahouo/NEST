package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.CommunityActivityService;
import com.umi.nest.service.CommunityLateService;
import com.umi.nest.service.CommunityService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 社区管理（学工 P15）：/api/community/**。
 *
 * <p>口径见《学工平台功能对齐说明》§10。共 40 条方法级路由：
 * 批次 6 + 检查 7 + 整改 2 + 晚归 6 + 学生说明 1 + 违规 5 + 文明宿舍 4 + 活动 7 + 统计与导出 2。</p>
 *
 * <p>除 /export（CSV 字节流，失败时回该状态码）外，所有接口都返回 HTTP 200 + {code,message,data}；
 * 业务失败用 Result.error（默认 400，鉴权 401 / 权限 403 / 不存在 404）。
 * 登录态沿用会话键 userId / role（与 RankController、PsycheController 一致）。</p>
 */
@RestController("communityController")
@RequestMapping("/api/community")
public class CommunityController {

    @Autowired
    private CommunityService communityService;

    @Autowired
    private CommunityLateService communityLateService;

    @Autowired
    private CommunityActivityService communityActivityService;

    // =========================================================================
    // 检查批次（6 条）
    // =========================================================================

    /** 1. 批次列表。参数：term / checkType / status / keyword。 */
    @GetMapping("/batches")
    public Result<?> listBatches(HttpSession session,
                                 @RequestParam(name = "term", required = false) String term,
                                 @RequestParam(name = "checkType", required = false) String checkType,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "keyword", required = false) String keyword) {
        return communityService.listBatches(userId(session), role(session), term, checkType, status, keyword);
    }

    /** 2. 新建批次（admin / 辅导员）。 */
    @PostMapping("/batches")
    public Result<?> createBatch(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityService.createBatch(userId(session), role(session), body);
    }

    /** 3. 修改批次：draft → checking → scored 逐级推进。 */
    @PutMapping("/batches/{id}")
    public Result<?> updateBatch(HttpSession session, @PathVariable("id") Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return communityService.updateBatch(userId(session), role(session), id, body);
    }

    /** 4. 发布批次：draft / checking / scored → published。 */
    @PostMapping("/batches/{id}/publish")
    public Result<?> publishBatch(HttpSession session, @PathVariable("id") Long id) {
        return communityService.publishBatch(userId(session), role(session), id);
    }

    /** 5. 结束批次：published / scored → finished。 */
    @PostMapping("/batches/{id}/close")
    public Result<?> closeBatch(HttpSession session, @PathVariable("id") Long id) {
        return communityService.closeBatch(userId(session), role(session), id);
    }

    /** 6. 批次统计（等级分布 / 整改进度 / 均分）。 */
    @GetMapping("/batches/{id}/stats")
    public Result<?> batchStats(HttpSession session, @PathVariable("id") Long id) {
        return communityService.batchStats(userId(session), role(session), id);
    }

    // =========================================================================
    // 宿舍检查（7 条）
    // =========================================================================

    /** 7. 检查记录分页列表；学生只能看到本宿舍、辅导员只看名下学生宿舍。 */
    @GetMapping("/inspections")
    public Result<?> listInspections(HttpSession session,
                                     @RequestParam(name = "batchId", required = false) Long batchId,
                                     @RequestParam(name = "term", required = false) String term,
                                     @RequestParam(name = "building", required = false) String building,
                                     @RequestParam(name = "roomNo", required = false) String roomNo,
                                     @RequestParam(name = "grade", required = false) String grade,
                                     @RequestParam(name = "rectifyStatus", required = false) String rectifyStatus,
                                     @RequestParam(name = "source", required = false) String source,
                                     @RequestParam(name = "keyword", required = false) String keyword,
                                     @RequestParam(name = "page", required = false) Integer page,
                                     @RequestParam(name = "size", required = false) Integer size) {
        return communityService.listInspections(userId(session), role(session), batchId, term, building, roomNo,
                grade, rectifyStatus, source, keyword, page, size);
    }

    /** 8. 录入检查记录（admin / 辅导员）；item_json 必须落在批次 criteria_json 的评分项内。 */
    @PostMapping("/inspections")
    public Result<?> createInspection(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityService.createInspection(userId(session), role(session), body);
    }

    /** 9. 检查记录详情。 */
    @GetMapping("/inspections/{id}")
    public Result<?> inspectionDetail(HttpSession session, @PathVariable("id") Long id) {
        return communityService.inspectionDetail(userId(session), role(session), id);
    }

    /** 10. 逻辑删除检查记录（仅 checking / scored 批次内可删）。 */
    @DeleteMapping("/inspections/{id}")
    public Result<?> deleteInspection(HttpSession session, @PathVariable("id") Long id) {
        return communityService.deleteInspection(userId(session), role(session), id);
    }

    /** 11. CSV 批量导入检查记录（rows 数组或 csv 文本）。 */
    @PostMapping("/inspections/import")
    public Result<?> importInspections(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityService.importInspections(userId(session), role(session), body);
    }

    /** 12. 整改复查：rectify_status=submitted 时 pass → accepted / fail → rejected。 */
    @PostMapping("/inspections/{id}/recheck")
    public Result<?> recheckInspection(HttpSession session, @PathVariable("id") Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return communityService.recheckInspection(userId(session), role(session), id, body);
    }

    /** 13. 学生查看本宿舍的检查结果。 */
    @GetMapping("/my/room")
    public Result<?> myRoom(HttpSession session) {
        return communityService.myRoom(userId(session), role(session));
    }

    // =========================================================================
    // 整改闭环（2 条）
    // =========================================================================

    /** 14. 学生提交整改说明：pending / rejected → submitted。 */
    @PostMapping("/inspections/{id}/rectify")
    public Result<?> submitRectify(HttpSession session, @PathVariable("id") Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return communityService.submitRectify(userId(session), role(session), id, body);
    }

    /** 15. 学生查看本人宿舍的整改要求与进度。 */
    @GetMapping("/my/rectifications")
    public Result<?> myRectifications(HttpSession session) {
        return communityService.myRectifications(userId(session), role(session));
    }

    // =========================================================================
    // 晚归 / 夜不归宿（6 条）
    // =========================================================================

    /** 16. 晚归台账分页列表。 */
    @GetMapping("/lates")
    public Result<?> listLates(HttpSession session,
                               @RequestParam(name = "studentId", required = false) String studentId,
                               @RequestParam(name = "building", required = false) String building,
                               @RequestParam(name = "type", required = false) String type,
                               @RequestParam(name = "handling", required = false) String handling,
                               @RequestParam(name = "confirmed", required = false) String confirmed,
                               @RequestParam(name = "keyword", required = false) String keyword,
                               @RequestParam(name = "startDate", required = false) String startDate,
                               @RequestParam(name = "endDate", required = false) String endDate,
                               @RequestParam(name = "page", required = false) Integer page,
                               @RequestParam(name = "size", required = false) Integer size) {
        return communityLateService.listLates(userId(session), role(session), studentId, building, type, handling,
                confirmed, keyword, startDate, endDate, page, size);
    }

    /** 17. 登记晚归 / 夜不归宿（admin / 辅导员）。 */
    @PostMapping("/lates")
    public Result<?> createLate(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.createLate(userId(session), role(session), body);
    }

    /** 18. 修改晚归台账（已了结不可改）。 */
    @PutMapping("/lates/{id}")
    public Result<?> updateLate(HttpSession session, @PathVariable("id") Long id,
                                @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.updateLate(userId(session), role(session), id, body);
    }

    /** 19. 核实晚归记录（confirmed 0 → 1）。 */
    @PostMapping("/lates/{id}/confirm")
    public Result<?> confirmLate(HttpSession session, @PathVariable("id") Long id) {
        return communityLateService.confirmLate(userId(session), role(session), id);
    }

    /** 20. 处理晚归：pending → contacted → closed。 */
    @PostMapping("/lates/{id}/handle")
    public Result<?> handleLate(HttpSession session, @PathVariable("id") Long id,
                                @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.handleLate(userId(session), role(session), id, body);
    }

    /** 21. 学生查看本人晚归记录（内部处理备注不下发）。 */
    @GetMapping("/my/lates")
    public Result<?> myLates(HttpSession session) {
        return communityLateService.myLates(userId(session), role(session));
    }

    // =========================================================================
    // 学生说明（1 条）
    // =========================================================================

    /** 22. 学生对本人晚归记录补充说明。 */
    @PostMapping("/my/lates/{id}/note")
    public Result<?> submitMyNote(HttpSession session, @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.submitMyNote(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 违规台账（5 条）
    // =========================================================================

    /** 23. 违规台账分页列表。 */
    @GetMapping("/violations")
    public Result<?> listViolations(HttpSession session,
                                    @RequestParam(name = "studentId", required = false) String studentId,
                                    @RequestParam(name = "building", required = false) String building,
                                    @RequestParam(name = "type", required = false) String type,
                                    @RequestParam(name = "advice", required = false) String advice,
                                    @RequestParam(name = "handling", required = false) String handling,
                                    @RequestParam(name = "keyword", required = false) String keyword,
                                    @RequestParam(name = "startDate", required = false) String startDate,
                                    @RequestParam(name = "endDate", required = false) String endDate,
                                    @RequestParam(name = "page", required = false) Integer page,
                                    @RequestParam(name = "size", required = false) Integer size) {
        return communityLateService.listViolations(userId(session), role(session), studentId, building, type, advice,
                handling, keyword, startDate, endDate, page, size);
    }

    /** 24. 登记违规台账（rule_ref 必填；不生成处分决定）。 */
    @PostMapping("/violations")
    public Result<?> createViolation(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.createViolation(userId(session), role(session), body);
    }

    /** 25. 修改违规台账（已办结不可改）。 */
    @PutMapping("/violations/{id}")
    public Result<?> updateViolation(HttpSession session, @PathVariable("id") Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.updateViolation(userId(session), role(session), id, body);
    }

    /** 26. 处理违规：pending → notified → closed。 */
    @PostMapping("/violations/{id}/handle")
    public Result<?> handleViolation(HttpSession session, @PathVariable("id") Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return communityLateService.handleViolation(userId(session), role(session), id, body);
    }

    /** 27. 学生查看本人违规记录（处理结果说明与处理人不下发）。 */
    @GetMapping("/my/violations")
    public Result<?> myViolations(HttpSession session) {
        return communityLateService.myViolations(userId(session), role(session));
    }

    // =========================================================================
    // 文明宿舍（4 条）
    // =========================================================================

    /** 28. 文明宿舍候选 / 结果列表。 */
    @GetMapping("/civil-rooms")
    public Result<?> listCivilRooms(HttpSession session,
                                    @RequestParam(name = "term", required = false) String term,
                                    @RequestParam(name = "building", required = false) String building,
                                    @RequestParam(name = "awarded", required = false) Integer awarded) {
        return communityService.listCivilRooms(userId(session), role(session), term, building, awarded);
    }

    /** 29. 按学期汇总已发布批次检查均分，生成楼栋内排名候选（名额不自动截断）。 */
    @PostMapping("/civil-rooms/compute")
    public Result<?> computeCivilRooms(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityService.computeCivilRooms(userId(session), role(session), body);
    }

    /** 30. 公示文明宿舍结果（admin 人工确认 awarded / awardLevel）。 */
    @PostMapping("/civil-rooms/{id}/publish")
    public Result<?> publishCivilRoom(HttpSession session, @PathVariable("id") Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return communityService.publishCivilRoom(userId(session), role(session), id, body);
    }

    /** 31. 文明宿舍公示（登录必需，匿名 401；只露楼栋 / 房号 / 均分 / 等级，不含姓名学号）。 */
    @GetMapping("/civil-rooms/publicity")
    public Result<?> civilPublicity(HttpSession session,
                                    @RequestParam(name = "term", required = false) String term,
                                    @RequestParam(name = "building", required = false) String building) {
        return communityService.civilPublicity(userId(session), role(session), term, building);
    }

    // =========================================================================
    // 社区活动（7 条）
    // =========================================================================

    /** 32. 活动列表（学生只看非草稿、非取消）。 */
    @GetMapping("/activities")
    public Result<?> listActivities(HttpSession session,
                                    @RequestParam(name = "term", required = false) String term,
                                    @RequestParam(name = "activityType", required = false) String activityType,
                                    @RequestParam(name = "level", required = false) String level,
                                    @RequestParam(name = "status", required = false) String status,
                                    @RequestParam(name = "keyword", required = false) String keyword) {
        return communityActivityService.listActivities(userId(session), role(session), term, activityType, level,
                status, keyword);
    }

    /** 33. 新建活动（admin / 辅导员）。 */
    @PostMapping("/activities")
    public Result<?> createActivity(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return communityActivityService.createActivity(userId(session), role(session), body);
    }

    /** 34. 修改活动 / 推进活动状态。 */
    @PutMapping("/activities/{id}")
    public Result<?> updateActivity(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return communityActivityService.updateActivity(userId(session), role(session), id, body);
    }

    /** 35. 学生报名活动（同一活动同一学生只允许一次；名额满 / 已截止 400）。 */
    @PostMapping("/activities/{id}/signup")
    public Result<?> signupActivity(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return communityActivityService.signupActivity(userId(session), role(session), id, body);
    }

    /** 36. 报名审核：signed → approved / rejected（支持单条与 items 批量）。 */
    @PostMapping("/activities/{id}/review")
    public Result<?> reviewSignups(HttpSession session, @PathVariable("id") Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return communityActivityService.reviewSignups(userId(session), role(session), id, body);
    }

    /** 37. 签到置位 attended / absent，可登记 award（admin / 辅导员）。 */
    @PostMapping("/activities/{id}/attendance")
    public Result<?> markAttendance(HttpSession session, @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return communityActivityService.markAttendance(userId(session), role(session), id, body);
    }

    /** 38. 学生查看本人报名记录。 */
    @GetMapping("/my/activities")
    public Result<?> myActivities(HttpSession session,
                                 @RequestParam(name = "activityId", required = false) String activityId) {
        return communityActivityService.myActivities(userId(session), role(session), activityId);
    }

    // =========================================================================
    // 统计与导出（2 条）
    // =========================================================================

    /** 39. 社区管理综合统计（检查 / 晚归 / 违规 / 活动）。 */
    @GetMapping("/stats")
    public Result<?> stats(HttpSession session,
                           @RequestParam(name = "term", required = false) String term,
                           @RequestParam(name = "startDate", required = false) String startDate,
                           @RequestParam(name = "endDate", required = false) String endDate) {
        return communityService.stats(userId(session), role(session), term, startDate, endDate);
    }

    /** 40. 导出 CSV（type = inspections / lates / violations / civil-rooms，UTF-8 BOM，脱敏）。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session,
                                         @RequestParam(name = "type", required = false) String type,
                                         @RequestParam(name = "term", required = false) String term,
                                         @RequestParam(name = "building", required = false) String building,
                                         @RequestParam(name = "grade", required = false) String grade,
                                         @RequestParam(name = "rectifyStatus", required = false) String rectifyStatus,
                                         @RequestParam(name = "startDate", required = false) String startDate,
                                         @RequestParam(name = "endDate", required = false) String endDate) {
        CommunityService.CsvExport out = communityService.exportCsv(userId(session), role(session), type, term,
                building, grade, rectifyStatus, startDate, endDate);
        if (out.errorCode != null) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    // =========================================================================
    // 会话工具（与 RankController / PsycheController 一致）
    // =========================================================================

    private static Long userId(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("userId");
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String role(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("role");
        return value == null ? null : String.valueOf(value);
    }
}
