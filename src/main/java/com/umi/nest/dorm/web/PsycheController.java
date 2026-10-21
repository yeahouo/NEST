package com.umi.nest.dorm.web;

import com.umi.nest.common.Result;
import com.umi.nest.dorm.service.PsycheEvalService;
import com.umi.nest.dorm.service.PsycheService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 心理特征提取模块的 REST 接口（创新点 4）。
 *
 * <p>返回结构沿用教学域既有的 {@link Result}，前端可以用同一套解包逻辑。
 * 所有异常交给 {@code GlobalExceptionHandler}，这里不做 try/catch ——
 * 业务异常要靠错误信息说话，藏起来只会让排查变难。
 */
// 显式 bean 名：与学工域的 com.umi.nest.controller.PsycheController 简单类名相同，默认 bean 名会撞、
// 组件扫描期直接 ConflictingBeanDefinitionException（2026-10-16 踩到，闸门见 SpringBeanNameCollisionTest）。
@RestController("dormPsycheController")
@RequestMapping("/api/dorm/psyche")
public class PsycheController {

    private final PsycheService service;
    private final PsycheEvalService evalService;

    public PsycheController(PsycheService service, PsycheEvalService evalService) {
        this.service = service;
        this.evalService = evalService;
    }

    /**
     * 跑一次提取器量化评测：真值反写文字 → 提取 → 逐维误差。
     *
     * <p>这是「你怎么知道大模型提取得准」这个问题在系统里的答案。
     * 默认只跑规则提取器（约 1 秒）；带上 {@code useLlm=true} 才会调大模型，
     * 那时每个人要跑两次采样，耗时按人头线性增长。
     */
    @PostMapping("/eval")
    public Result<Map<String, Object>> eval(@RequestBody(required = false) PsycheRequests.Eval req) {
        PsycheRequests.Eval r = req == null
                ? new PsycheRequests.Eval(null, null, null, null, null, null, null) : req;
        return Result.success(evalService.run(
                r.students() == null ? 60 : r.students(),
                r.seed() == null ? 20260927L : r.seed(),
                r.missingRate() == null ? 0.20 : r.missingRate(),
                r.privacyRate() == null ? 0.25 : r.privacyRate(),
                r.llmStudents() == null ? PsycheEvalService.DEFAULT_LLM_STUDENTS : r.llmStudents(),
                r.useLlm() == null || r.useLlm(),
                r.ablation() == null || r.ablation()));
    }

    /** 历次评测记录。换种子重跑后，靠这里判断结论是稳定还是偶然。 */
    @GetMapping("/eval/history")
    public Result<?> evalHistory(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(evalService.history(limit));
    }

    /** 模块状态：大模型那一路开没开、阈值多少、已收多少素材。 */
    @GetMapping("/describe")
    public Result<Map<String, Object>> describe() {
        return Result.success(service.describe());
    }

    /** 量表题库全文。 */
    @GetMapping("/questionnaire")
    public Result<Map<String, Object>> questionnaire() {
        return Result.success(service.questionnaire());
    }

    /** 学生名单 + 每人已有素材/结果/是否已采用。管理端主列表。 */
    @GetMapping("/students")
    public Result<?> students(@RequestParam(required = false) String keyword,
                              @RequestParam(defaultValue = "200") int limit) {
        return Result.success(service.overview(keyword, limit));
    }

    /** 单个学生的全部素材、作答、提取快照与当前生效特征。 */
    @GetMapping("/students/{studentId}")
    public Result<Map<String, Object>> detail(@PathVariable long studentId) {
        return Result.success(service.detail(studentId));
    }

    /** 提交一段原始素材（自述 / 聊天记录 / 辅导员观察）。 */
    @PostMapping("/text")
    public Result<Map<String, Object>> addText(@RequestBody PsycheRequests.Text req) {
        long id = service.addText(req.studentId(), req.kind(), req.content(), req.consent());
        return Result.success("素材已保存（id=" + id + "）。注意：保存素材本身不会改变任何特征，"
                + "需要再运行一次提取才会产生新的取值。", Map.of("textId", id));
    }

    @DeleteMapping("/text/{id}")
    public Result<Map<String, Object>> deleteText(@PathVariable long id) {
        int n = service.deleteText(id);
        return Result.success(n > 0 ? "素材已删除" : "没有找到这条素材", Map.of("deleted", n));
    }

    /** 提交量表作答。值是数字（维度原生值）或字符串（自由文本题）。 */
    @PostMapping("/answers")
    public Result<Map<String, Object>> answers(@RequestBody PsycheRequests.Answers req) {
        return Result.success("作答已保存", service.submitAnswers(req.studentId(), req.answers()));
    }

    /**
     * 运行提取。不传 studentId 则批量跑（默认上限 200 人）。
     * {@code useLlm=false} 只走本地规则，做实验和不联网时用。
     */
    @PostMapping("/extract")
    public Result<Map<String, Object>> extract(@RequestBody(required = false) PsycheRequests.Extract req) {
        PsycheRequests.Extract r = req == null
                ? new PsycheRequests.Extract(null, null, null, null) : req;
        return Result.success("提取完成", service.extract(r.studentId(), r.useLlm(), r.limit()));
    }

    @GetMapping("/profile/{studentId}")
    public Result<Map<String, Object>> profile(@PathVariable long studentId) {
        return Result.success(service.profile(studentId));
    }

    /** 采用：把过了置信度阈值的维度写回特征表，此后才会影响分配。 */
    @PostMapping("/apply")
    public Result<Map<String, Object>> apply(@RequestBody PsycheRequests.Apply req) {
        return Result.success("已采用", service.apply(req.studentId(), req.minConfidence()));
    }

    /** 大模型连通性自检：真的发一句话过去，看能不能收到回复。 */
    @GetMapping("/probe")
    public Result<Map<String, Object>> probe() {
        return Result.success(service.probe());
    }

    /** 绑定学生端账号（名单先到、账号后开时用）。 */
    @PostMapping("/link")
    public Result<Map<String, Object>> link(@RequestBody PsycheRequests.Link req) {
        return Result.success(service.link(req.studentId(), req.userId(), req.studentNo()));
    }
}
