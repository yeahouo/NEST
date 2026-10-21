package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.service.EvalAcademicService;
import com.umi.nest.service.EvalBonusService;
import com.umi.nest.service.RankService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
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
 * 综测排名公示与导出（学工 P13）接口。
 *
 * <p>HTTP 状态码一律 200，业务结果放在 {@code {code,message,data}} 里：401 未登录、403 越权、
 * 400 参数非法（中文文案）、404 资源不存在。会话里的 userId/role 由本类解包后传进 service，
 * 与 AidReviewController / HonorController 的写法保持一致。</p>
 *
 * <p>权限矩阵：批次维护与 compute/publicize/finish、导出 = admin（导出 teacher 也可）；
 * A 登记与导入、排名列表与明细、加分审核、异议处理、统计 = teacher|admin；
 * 加分申报与撤回、异议提交、我的综测 = 学生本人；公示必须登录。</p>
 */
@RestController
@RequestMapping("/api/rank")
public class RankController {

    private final RankService rankService;

    private final EvalAcademicService academicService;

    private final EvalBonusService bonusService;

    public RankController(RankService rankService, EvalAcademicService academicService,
                          EvalBonusService bonusService) {
        this.rankService = rankService;
        this.academicService = academicService;
        this.bonusService = bonusService;
    }

    // ---------------------------------------------------------------- 批次

    /** 批次列表（登录可读；非 admin 自动隐藏 draft）：data = 纯数组。 */
    @GetMapping("/batches")
    public Result<?> batches(HttpSession session,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword) {
        return rankService.listBatches(userId(session), role(session), status, keyword);
    }

    /** 新建批次（admin）：data = 批次对象。 */
    @PostMapping("/batches")
    public Result<?> createBatch(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return rankService.createBatch(userId(session), role(session), body);
    }

    /** 增量修改批次（admin）：只改传了的字段；data = 批次对象。 */
    @PutMapping("/batches/{id}")
    public Result<?> updateBatch(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return rankService.updateBatch(userId(session), role(session), id, body);
    }

    /** 计算综测并落快照（admin）：data = {batch, rankedStudents, missingAcademic, totalStudents, unverifiableNote}。 */
    @PostMapping("/batches/{id}/compute")
    public Result<?> compute(HttpSession session, @PathVariable Long id) {
        return rankService.compute(userId(session), role(session), id);
    }

    /** 发布公示（admin，computed→publicity）：data = 批次对象。 */
    @PostMapping("/batches/{id}/publicize")
    public Result<?> publicize(HttpSession session, @PathVariable Long id) {
        return rankService.publicize(userId(session), role(session), id);
    }

    /** 结束公示（admin，publicity→finished）：data = 批次对象。 */
    @PostMapping("/batches/{id}/finish")
    public Result<?> finish(HttpSession session, @PathVariable Long id) {
        return rankService.finish(userId(session), role(session), id);
    }

    // ---------------------------------------------------------------- 学习成绩 A

    /** 登记/修正学习成绩 A（teacher|admin）：存在即更新；data = A 台账对象。 */
    @PutMapping("/academic")
    public Result<?> saveAcademic(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return academicService.save(userId(session), role(session), body);
    }

    /** 导入学习成绩 CSV（teacher|admin）：data = {inserted, updated, failed:[{line,message}]}，逐行失败不影响其它行。 */
    @PostMapping("/academic/import")
    public Result<?> importAcademic(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return academicService.importCsv(userId(session), role(session), body);
    }

    /** 待补 A 名单（teacher|admin，按批次定位学期）：data = 纯数组 {studentId,studentName,studentNo,className,major,grade}。 */
    @GetMapping("/academic/missing")
    public Result<?> missingAcademic(HttpSession session, @RequestParam(required = false) Long batchId) {
        return academicService.missing(userId(session), role(session), batchId);
    }

    // ---------------------------------------------------------------- 加分申报

    /** 我的加分申报（学生，按学期）：data = 纯数组。 */
    @GetMapping("/bonus/my")
    public Result<?> myBonus(HttpSession session, @RequestParam(required = false) String academicTerm) {
        return bonusService.my(userId(session), role(session), academicTerm);
    }

    /** 提交加分申报（学生，落 submitted）：data = 申报对象。 */
    @PostMapping("/bonus")
    public Result<?> submitBonus(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return bonusService.submit(userId(session), role(session), body);
    }

    /** 修改本人加分申报（学生，限 draft/submitted/rejected）：data = 申报对象。 */
    @PutMapping("/bonus/{id}")
    public Result<?> updateBonus(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return bonusService.update(userId(session), role(session), id, body);
    }

    /** 撤回本人加分申报（学生，submitted→draft）：data = 申报对象。 */
    @PostMapping("/bonus/{id}/withdraw")
    public Result<?> withdrawBonus(HttpSession session, @PathVariable Long id) {
        return bonusService.withdraw(userId(session), role(session), id);
    }

    /** 加分审核列表（teacher|admin，辅导员只看名下学生）：data = {list,total,page,size}。 */
    @GetMapping("/bonus/list")
    public Result<?> listBonus(HttpSession session,
                               @RequestParam(required = false) String academicTerm,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return bonusService.list(userId(session), role(session), academicTerm, status, keyword, page, size);
    }

    /** 审核加分申报（teacher|admin，辅导员只能审名下学生；reject 必须写 ≥4 字原因）：data = 申报对象。 */
    @PostMapping("/bonus/{id}/review")
    public Result<?> reviewBonus(HttpSession session, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return bonusService.review(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 排名

    /** 排名列表（teacher|admin，看全专业）：data = {list,total,page,size}。 */
    @GetMapping("/list")
    public Result<?> list(HttpSession session,
                          @RequestParam(required = false) Long batchId,
                          @RequestParam(required = false) String major,
                          @RequestParam(required = false) String className,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size) {
        return rankService.list(userId(session), role(session), batchId, major, className, keyword, page, size);
    }

    /** 单个学生明细（teacher|admin）：data = {row, bonuses, academic}。 */
    @GetMapping("/detail/{studentId}")
    public Result<?> detail(HttpSession session, @PathVariable Long studentId,
                            @RequestParam(required = false) Long batchId) {
        return rankService.detail(userId(session), role(session), batchId, studentId);
    }

    /** 我的综测（登录即可）：data = {academicTerm,batch,row,bonuses,bonusTotal,cappedCategories,objections,evalDims,note}。 */
    @GetMapping("/my")
    public Result<?> my(HttpSession session, @RequestParam(required = false) String academicTerm) {
        return rankService.mine(userId(session), role(session), academicTerm);
    }

    /** 公示（必须登录）：data = 纯数组（只含姓名/学号后四位/专业/班级/年级/奖学金等级），非公示批次为空数组。 */
    @GetMapping("/publicity")
    public Result<?> publicity(HttpSession session, @RequestParam(required = false) Long batchId) {
        return rankService.publicity(userId(session), role(session), batchId);
    }

    /** 批次统计（teacher|admin）：data = {batch,totalStudents,rankedStudents,missingAcademic,tier1,tier2,tier3,qualified,byMajor,objections,bonusPending,unverifiableNote}。 */
    @GetMapping("/stats")
    public Result<?> stats(HttpSession session, @RequestParam(required = false) Long batchId) {
        return rankService.stats(userId(session), role(session), batchId);
    }

    // ---------------------------------------------------------------- 异议

    /** 提交公示异议（学生，只在公示期内且同批次只允许一条 pending）：data = 异议对象。 */
    @PostMapping("/objections")
    public Result<?> createObjection(HttpSession session, @RequestBody(required = false) Map<String, Object> body) {
        return rankService.createObjection(userId(session), role(session), body);
    }

    /** 我的公示异议（学生）：data = 纯数组。 */
    @GetMapping("/objections/my")
    public Result<?> myObjections(HttpSession session) {
        return rankService.myObjections(userId(session), role(session));
    }

    /** 异议列表（teacher|admin，辅导员只看名下学生）：data = {list,total,page,size}。 */
    @GetMapping("/objections")
    public Result<?> listObjections(HttpSession session,
                                    @RequestParam(required = false) Long batchId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return rankService.listObjections(userId(session), role(session), batchId, status, page, size);
    }

    /** 答复异议（该生辅导员或 admin；answer ≥4 字；CAS 只处理 pending）：data = 异议对象。 */
    @PostMapping("/objections/{id}/handle")
    public Result<?> handleObjection(HttpSession session, @PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return rankService.handleObjection(userId(session), role(session), id, body);
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 导出排名 CSV（admin|teacher）：text/csv;charset=UTF-8，带 UTF-8 BOM（Excel 直接打开不乱码）。
     * 未登录或学生调用返回 403。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session, @RequestParam(required = false) Long batchId) {
        RankService.CsvExport out = rankService.export(userId(session), role(session), batchId);
        if (out.errorCode != null) {
            return ResponseEntity.status(out.errorCode).build();
        }
        byte[] bytes = ("\uFEFF" + out.csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    // ---------------------------------------------------------------- 会话

    private static Long userId(HttpSession session) {
        Object value = session.getAttribute("userId");
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String role(HttpSession session) {
        Object value = session.getAttribute("role");
        return value == null ? null : String.valueOf(value);
    }
}
