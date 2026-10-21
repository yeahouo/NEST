package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.CommunityService;
import com.umi.nest.service.PartyService;
import com.umi.nest.service.PartyTrainingService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * 学生党建（学工 P16）：/api/party/**。
 *
 * <p>口径见《学工平台功能对齐说明》§11.2（11 条）。共 31 条方法级路由：
 * 申请登记 4 + 阶段台账与推进 7 + 考察/思想汇报 5 + 党校培训 11 + 公示 6 + 材料 3 + 统计导出 2
 * （其中「我的台账」「我的思想汇报」「我的党校」3 条为学生端自助入口）。</p>
 *
 * <p>除 /export（CSV 字节流，失败时回该状态码）外，所有接口都返回 HTTP 200 + {code,message,data}；
 * 业务失败用 Result.error（默认 400，鉴权 401 / 权限 403 / 不存在 404）。
 * 登录态沿用会话键 userId / role（与 CommunityController、RankController 一致）。</p>
 *
 * <p>设计口径：</p>
 * <ul>
 *   <li>平台**不做自动资格判定**（§11.2-1）：所有「应当/不得」都是带 rule_ref 条款引用的提示文案，
 *       只提示不阻断推进；阶段推进只做「只向前 + 状态机 CAS」的机械校验。</li>
 *   <li>期限常量全部来自 §11.1 原文且**两条来源并存**（国标 S24 / 校规 S23），
 *       平台不替学校选，见 {@link PartyService#rules()}。</li>
 *   <li>学生端由**服务层**抹掉支部内部考察意见/推优讨论/他人信息（§11.2-4），不依赖前端不渲染。</li>
 * </ul>
 */
@RestController("partyController")
@RequestMapping("/api/party")
public class PartyController {

    @Autowired
    private PartyService partyService;

    @Autowired
    private PartyTrainingService partyTrainingService;

    // =========================================================================
    // 1. 入党申请登记（4 条）
    // =========================================================================

    /** 1. 登记入党申请。学生本人自报 / 辅导员代录（staff 传 userId）。前置态：无（可重复登记会被拒）。 */
    @PostMapping("/applications")
    public Result<?> registerApplication(HttpSession session,
                                         @RequestBody(required = false) Map<String, Object> body) {
        return partyService.registerApplication(userId(session), role(session), body);
    }

    /** 2. 申请台账列表。参数：userId / status / keyword / page / size。权限：学生只见自己，teacher 名下，admin 全量。 */
    @GetMapping("/applications")
    public Result<?> listApplications(HttpSession session,
                                      @RequestParam(name = "userId", required = false) Long userId,
                                      @RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "keyword", required = false) String keyword,
                                      @RequestParam(name = "page", required = false) Integer page,
                                      @RequestParam(name = "size", required = false) Integer size) {
        return partyService.listApplications(userId(session), role(session), userId, status, keyword, page, size);
    }

    /** 3. 申请详情。前置态：active / withdrawn。权限：本人 / 名下 / 全量。 */
    @GetMapping("/applications/{id}")
    public Result<?> applicationDetail(HttpSession session,
                                       @PathVariable("id") Long id) {
        return partyService.applicationDetail(userId(session), role(session), id);
    }

    /** 4. 修改申请（含谈话登记）。CAS 前置态：{active}。权限：本人 / 名下 / admin。 */
    @PutMapping("/applications/{id}")
    public Result<?> updateApplication(HttpSession session,
                                       @PathVariable("id") Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return partyService.updateApplication(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 2. 阶段台账查询与推进（7 条）
    // =========================================================================

    /** 5. 阶段台账列表。参数：userId / stage / branchName / keyword / page / size。 */
    @GetMapping("/stages")
    public Result<?> listStages(HttpSession session,
                                @RequestParam(name = "userId", required = false) Long userId,
                                @RequestParam(name = "stage", required = false) String stage,
                                @RequestParam(name = "branchName", required = false) String branchName,
                                @RequestParam(name = "keyword", required = false) String keyword,
                                @RequestParam(name = "page", required = false) Integer page,
                                @RequestParam(name = "size", required = false) Integer size) {
        return partyService.listStages(userId(session), role(session), userId, stage, branchName, keyword, page, size);
    }

    /** 6. 我的阶段台账（学生端自助；含每步日期、下一步待办与时限倒计时）。 */
    @GetMapping("/stages/mine")
    public Result<?> myStage(HttpSession session) {
        return partyService.myStage(userId(session), role(session));
    }

    /** 7. 阶段台账详情（学生端已抹掉支部内部考察意见/推优讨论）。 */
    @GetMapping("/stages/{id}")
    public Result<?> stageDetail(HttpSession session,
                                 @PathVariable("id") Long id) {
        return partyService.stageDetail(userId(session), role(session), id);
    }

    /**
     * 8. 阶段推进（只向前）。CAS 前置态：由服务层按**显式枚举前置态**给出
     * （activist←applicant / candidate←activist / probationary←candidate / full←probationary）。
     * 资格线只作提示，不阻断。权限：teacher / admin。
     */
    @PostMapping("/stages/{id}/advance")
    public Result<?> advanceStage(HttpSession session,
                                  @PathVariable("id") Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return partyService.advanceStage(userId(session), role(session), id, body);
    }

    /** 9. 阶段回退（仅 admin，必须填原因，写审计）。CAS 前置态：显式枚举当前态。 */
    @PostMapping("/stages/{id}/rollback")
    public Result<?> rollbackStage(HttpSession session,
                                   @PathVariable("id") Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return partyService.rollbackStage(userId(session), role(session), id, body);
    }

    /** 10. 绑定当前阶段的公示（S22 第 11/14/25 步）。CAS 前置态：显式枚举当前态。 */
    @PostMapping("/stages/{id}/publicity")
    public Result<?> bindStagePublicity(HttpSession session,
                                        @PathVariable("id") Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return partyService.bindStagePublicity(userId(session), role(session), id, body);
    }

    /** 11. 维护支部信息 / 内部考察意见 / 推优讨论（**学生端不下发**）。CAS 前置态：显式枚举当前态。 */
    @PutMapping("/stages/{id}/internal")
    public Result<?> updateStageInternal(HttpSession session,
                                         @PathVariable("id") Long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        return partyService.updateStageInternal(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 3. 考察与思想汇报（5 条）
    // =========================================================================

    /** 12. 提交思想汇报（学生本人）。前置态：无（频率只提示，不阻断）。 */
    @PostMapping("/observations/thought-report")
    public Result<?> submitThoughtReport(HttpSession session,
                                         @RequestBody(required = false) Map<String, Object> body) {
        return partyService.submitThoughtReport(userId(session), role(session), body);
    }

    /** 13. 登记考察记录（staff；也可代学生补登思想汇报）。type=observation|thought_report。 */
    @PostMapping("/observations")
    public Result<?> recordObservation(HttpSession session,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return partyService.recordObservation(userId(session), role(session), body);
    }

    /** 14. 我的思想汇报与考察（学生端自助；抹掉 internal_note）。 */
    @GetMapping("/observations/mine")
    public Result<?> myObservations(HttpSession session,
                                    @RequestParam(name = "type", required = false) String type,
                                    @RequestParam(name = "page", required = false) Integer page,
                                    @RequestParam(name = "size", required = false) Integer size) {
        return partyService.myObservations(userId(session), role(session), type, page, size);
    }

    /** 15. 考察/思想汇报台账列表。参数：userId / type / reviewStatus / page / size。 */
    @GetMapping("/observations")
    public Result<?> listObservations(HttpSession session,
                                      @RequestParam(name = "userId", required = false) Long userId,
                                      @RequestParam(name = "type", required = false) String type,
                                      @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
                                      @RequestParam(name = "page", required = false) Integer page,
                                      @RequestParam(name = "size", required = false) Integer size) {
        return partyService.listObservations(userId(session), role(session), userId, type, reviewStatus, page, size);
    }

    /** 16. 审核思想汇报/考察记录。CAS 前置态：{pending} → reviewed|returned。权限：teacher / admin。 */
    @PostMapping("/observations/{id}/review")
    public Result<?> reviewObservation(HttpSession session,
                                       @PathVariable("id") Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return partyService.reviewObservation(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 4. 党校培训（11 条）
    // =========================================================================

    /** 17. 新建党校班次。前置态：无（默认 status=draft）。权限：teacher / admin。 */
    @PostMapping("/trainings")
    public Result<?> createTraining(HttpSession session,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return partyTrainingService.createTraining(userId(session), role(session), body);
    }

    /** 18. 班次列表。学生端只看 signup / ongoing / finished。参数：status / term / keyword / page / size。 */
    @GetMapping("/trainings")
    public Result<?> listTrainings(HttpSession session,
                                   @RequestParam(name = "status", required = false) String status,
                                   @RequestParam(name = "term", required = false) String term,
                                   @RequestParam(name = "keyword", required = false) String keyword,
                                   @RequestParam(name = "page", required = false) Integer page,
                                   @RequestParam(name = "size", required = false) Integer size) {
        return partyTrainingService.listTrainings(userId(session), role(session), status, term, keyword, page, size);
    }

    /** 19. 我的党校报名与结业（学生端自助；附结业标准无来源的说明）。 */
    @GetMapping("/trainings/mine")
    public Result<?> myTrainings(HttpSession session,
                                 @RequestParam(name = "page", required = false) Integer page,
                                 @RequestParam(name = "size", required = false) Integer size) {
        return partyTrainingService.myTrainings(userId(session), role(session), page, size);
    }

    /** 20. 党校培训统计（班次状态 / 学员状态）。 */
    @GetMapping("/trainings/stats")
    public Result<?> trainingStats(HttpSession session) {
        return partyTrainingService.stats(userId(session), role(session));
    }

    /** 21. 班次详情（学生端草稿不可见，返回 404）。 */
    @GetMapping("/trainings/{id}")
    public Result<?> trainingDetail(HttpSession session,
                                    @PathVariable("id") Long id) {
        return partyTrainingService.trainingDetail(userId(session), role(session), id);
    }

    /** 22. 修改班次。CAS 前置态：{draft, signup, ongoing}（已结束/已取消不可改）。权限：teacher / admin。 */
    @PutMapping("/trainings/{id}")
    public Result<?> updateTraining(HttpSession session,
                                    @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return partyTrainingService.updateTraining(userId(session), role(session), id, body);
    }

    /** 23. 学员名单。参数：trainingId / userId / status / page / size。 */
    @GetMapping("/training-members")
    public Result<?> listTrainingMembers(HttpSession session,
                                         @RequestParam(name = "trainingId", required = false) Long trainingId,
                                         @RequestParam(name = "userId", required = false) Long userId,
                                         @RequestParam(name = "status", required = false) String status,
                                         @RequestParam(name = "page", required = false) Integer page,
                                         @RequestParam(name = "size", required = false) Integer size) {
        return partyTrainingService.listTrainingMembers(userId(session), role(session), trainingId, userId, status,
                page, size);
    }

    /** 24. 学生报名。CAS 前置态：班次 {signup, ongoing}；同一班次不可重复报名。 */
    @PostMapping("/trainings/{id}/signup")
    public Result<?> signupTraining(HttpSession session,
                                    @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return partyTrainingService.signupTraining(userId(session), role(session), id, body);
    }

    /** 25. 辅导员审核报名。CAS 前置态：{signed} → approved|rejected。权限：teacher / admin。 */
    @PostMapping("/training-members/{id}/review")
    public Result<?> reviewTrainingMember(HttpSession session,
                                          @PathVariable("id") Long id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return partyTrainingService.reviewTrainingMember(userId(session), role(session), id, body);
    }

    /**
     * 26. 结业登记。CAS 前置态：{approved} → completed，写结业结论/日期/证书并写审计（§11.2-5）。
     * 证书有效期默认「颁发之日起一年内有效」，传 certValidUntil 即采信人工覆盖值（§11.2-7）。
     */
    @PostMapping("/training-members/{id}/complete")
    public Result<?> completeTrainingMember(HttpSession session,
                                            @PathVariable("id") Long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return partyTrainingService.completeTrainingMember(userId(session), role(session), id, body);
    }

    /** 27. 覆盖结业证书信息。前置态：{completed}。 */
    @PutMapping("/training-members/{id}/cert")
    public Result<?> updateTrainingCert(HttpSession session,
                                        @PathVariable("id") Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return partyTrainingService.updateTrainingCert(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 5. 公示（6 条）
    // =========================================================================

    /** 28. 创建公示（node_type 只支持 S22 三个有来源节点）。前置态：无（默认 status=draft）。 */
    @PostMapping("/publicities")
    public Result<?> createPublicity(HttpSession session,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return partyService.createPublicity(userId(session), role(session), body);
    }

    /** 29. 公示列表。学生端只看 publicizing / closed（草稿不外泄）。参数：userId / nodeType / status / page / size。 */
    @GetMapping("/publicities")
    public Result<?> listPublicities(HttpSession session,
                                     @RequestParam(name = "userId", required = false) Long userId,
                                     @RequestParam(name = "nodeType", required = false) String nodeType,
                                     @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "page", required = false) Integer page,
                                     @RequestParam(name = "size", required = false) Integer size) {
        return partyService.listPublicities(userId(session), role(session), userId, nodeType, status, page, size);
    }

    /** 30. 公示详情。 */
    @GetMapping("/publicities/{id}")
    public Result<?> publicityDetail(HttpSession session,
                                     @PathVariable("id") Long id) {
        return partyService.publicityDetail(userId(session), role(session), id);
    }

    /** 31. 修改公示。CAS 前置态：{draft}（已发布/已结束不可改）。 */
    @PutMapping("/publicities/{id}")
    public Result<?> updatePublicity(HttpSession session,
                                     @PathVariable("id") Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return partyService.updatePublicity(userId(session), role(session), id, body);
    }

    /** 32. 发布公示。CAS 前置态：**只接受 {draft}**。权限：teacher / admin（管理员管公示）。 */
    @PostMapping("/publicities/{id}/publish")
    public Result<?> publishPublicity(HttpSession session,
                                      @PathVariable("id") Long id) {
        return partyService.publishPublicity(userId(session), role(session), id);
    }

    /**
     * 33. 关闭公示。CAS 前置态：**只接受 {publicizing}**；
     * **已结束（closed）再 close 返回 400**，不做幂等 200（P15 E2E 缺陷 D2 的教训）。
     */
    @PostMapping("/publicities/{id}/close")
    public Result<?> closePublicity(HttpSession session,
                                    @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return partyService.closePublicity(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 6. 材料清单（3 条）
    // =========================================================================

    /** 34. 登记材料清单条目（只登记名称与状态，不生成官方表格 PDF）。 */
    @PostMapping("/materials")
    public Result<?> addMaterial(HttpSession session,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return partyService.addMaterial(userId(session), role(session), body);
    }

    /** 35. 材料清单列表。参数：userId / stage / submitted / page / size。 */
    @GetMapping("/materials")
    public Result<?> listMaterials(HttpSession session,
                                   @RequestParam(name = "userId", required = false) Long userId,
                                   @RequestParam(name = "stage", required = false) String stage,
                                   @RequestParam(name = "submitted", required = false) Integer submitted,
                                   @RequestParam(name = "page", required = false) Integer page,
                                   @RequestParam(name = "size", required = false) Integer size) {
        return partyService.listMaterials(userId(session), role(session), userId, stage, submitted, page, size);
    }

    /** 36. 交件/退件登记（只记已交/未交 + 备注 + 附件文件名）。 */
    @PostMapping("/materials/{id}/submit")
    public Result<?> submitMaterial(HttpSession session,
                                    @PathVariable("id") Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return partyService.submitMaterial(userId(session), role(session), id, body);
    }

    // =========================================================================
    // 7. 统计与导出（2 条）
    // =========================================================================

    /** 37. 党建综合统计（阶段/支部/申请/公示/考察/党校/材料分布 + 全部期限口径）。 */
    @GetMapping("/stats")
    public Result<?> stats(HttpSession session) {
        return partyService.stats(userId(session), role(session));
    }

    /**
     * 38. 导出 CSV（学工党建台账）。
     *
     * <p>脱敏（§11.2-6）：只给**学号后四位 + 姓名 + 班级 + 阶段 + 日期 + 支部**，
     * **不含身份证号、不含联系方式**。文件带 UTF-8 BOM（Excel 中文不乱码）。</p>
     *
     * <p>失败时按 errorCode 回状态码（401 未登录 / 403 非辅导员 / 400 参数或超量）。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session,
                                         @RequestParam(name = "type", required = false) String type,
                                         @RequestParam(name = "branchName", required = false) String branchName,
                                         @RequestParam(name = "stage", required = false) String stage) {
        CommunityService.CsvExport out = partyService.exportCsv(userId(session), role(session), type, branchName, stage);
        if (out.errorCode != null) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // =========================================================================
    // 会话工具（与 CommunityController 完全同口径）
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
