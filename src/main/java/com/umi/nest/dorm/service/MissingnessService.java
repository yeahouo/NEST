package com.umi.nest.dorm.service;

import com.umi.nest.dorm.eval.MissingnessAdvisor;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.repo.DormRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺失感知决策支持的产品侧服务：把「问卷缺了多少、这份分配能不能信、先催谁补」变成
 * 管理端可以直接看的两个接口。
 *
 * <p><b>只读</b>：本服务不写任何表，不改变分配结果。它的输出是决策建议——
 * 要么是「这份分配的相容度有多少证据撑着」，要么是「花同样的催填力气，先问这几个人这几题」。
 *
 * <p><b>不读隐真值</b>：{@code dorm_ground_truth} 在这里完全不出现。所有估计只用
 * {@code dorm_student_feature} 里的问卷观测值，和求解器看到的是同一份数据。
 *
 * <p>为什么不把计算写在这里：数学在 {@link MissingnessAdvisor}（纯函数、可单测），
 * 这一层只负责「取数 → 调算法 → 拼成前端要的形状」。
 */
@Service("dormMissingnessService")
public class MissingnessService {

    /** 每名学生最多看多少个候选室友。 */
    static final int MAX_CANDIDATES = 24;
    /** 每个格子取多少个分布样本。 */
    static final int MAX_SAMPLES = 4;
    /** 一次请求最多多少次相容度评估，超出就截断（页面不能因为催填建议卡住）。 */
    static final long MAX_EVALUATIONS = 250_000L;
    /** 默认返回多少条催填建议。 */
    static final int DEFAULT_LIMIT = 20;
    /** 单次最多返回多少条。 */
    static final int MAX_LIMIT = 100;

    private final DormRepository repo;

    public MissingnessService(DormRepository repo) {
        this.repo = repo;
    }

    /**
     * 可评估性报告：当前（或指定）批次里，同房对的相容度有多少证据。
     *
     * @param batchId 为 null 时取最近一次分配
     */
    public Map<String, Object> summary(Long batchId) {
        DormRepository.LoadedData loaded = repo.loadProblem();
        ProblemInstance problem = loaded.problem();
        Long effective = batchId != null ? batchId : repo.latestBatchId();
        Map<Long, Long> roomOf = effective == null ? Map.of() : repo.roomOfBatch(effective);

        // 权重传 null 即 CompatWeights.defaults().normalized()：全系统只有一个相容模型，
        // 不在这里为「另一个权重口径」留分叉（要改口径就改 CompatWeights，改一处生效一处）。
        MissingnessAdvisor.Coverage cov = MissingnessAdvisor.coverage(
                problem.observations(), roomOf, null);
        List<Map<String, Object>> tallies = new ArrayList<>();
        for (MissingnessAdvisor.CellTally t : MissingnessAdvisor.cellTallies(problem.observations())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cell", t.cell());
            row.put("label", t.label());
            row.put("missing", t.missing());
            row.put("students", t.students());
            row.put("missingRate", t.students() == 0 ? 0.0 : (double) t.missing() / t.students());
            tallies.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", effective);
        out.put("batchName", batchName(effective));
        out.put("students", cov.students());
        out.put("missingCells", cov.missingCells());
        out.put("totalCells", cov.totalCells());
        out.put("knownRate", round4(cov.knownRate()));
        out.put("pairs", cov.pairs());
        out.put("pairsWithoutEvidence", cov.pairsWithoutEvidence());
        out.put("cov", round4(cov.cov()));
        out.put("evalRatio", round4(cov.evalRatio()));
        out.put("evaluable", cov.pairs() == 0 ? false : cov.evaluable());
        out.put("reasons", cov.reasons());
        out.put("thresholds", Map.of("covMin", MissingnessAdvisor.COV_MIN,
                "evalMin", MissingnessAdvisor.EVAL_MIN));
        out.put("byCell", tallies);
        out.put("model", "missingness-aware-v1");
        out.put("note", "未知=中性：缺失维不参与打分、也不做均值补齐；分数只在证据覆盖率足够时才是结论。");
        return out;
    }

    /**
     * 催填优先级：给定「只补若干个格子」的预算，先问谁、问哪题。
     *
     * @param limit 返回条数（默认 20，上限 100）
     */
    public Map<String, Object> priority(Integer limit) {
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        DormRepository.LoadedData loaded = repo.loadProblem();
        ProblemInstance problem = loaded.problem();
        List<Student> students = problem.students();
        Map<Long, StudentFeature> obs = problem.observations();

        MissingnessAdvisor.Report report = MissingnessAdvisor.rank(students, loaded.studentNoOf(), obs,
                null, cap, MAX_CANDIDATES, MAX_SAMPLES, MAX_EVALUATIONS);
        List<Map<String, Object>> rows = new ArrayList<>(report.top().size());
        for (MissingnessAdvisor.PriorityRow r : report.top()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", r.rank());
            row.put("studentId", r.studentId());
            row.put("studentNo", r.studentNo());
            row.put("name", r.name());
            row.put("cell", r.cell());
            row.put("cellLabel", r.cellLabel());
            row.put("delta", round6(r.delta()));
            row.put("knownCounterparts", r.knownCounterparts());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", report.students());
        out.put("missingCells", report.missingCells());
        out.put("rows", rows);
        out.put("limit", cap);
        out.put("scored", report.candidatesScored());
        out.put("truncated", report.truncated());
        out.put("evaluations", report.evaluations());
        out.put("budget", MAX_EVALUATIONS);
        out.put("model", "missingness-aware-v1");
        out.put("note", "增益是期望值（按本校该题分布估计补齐后的收益），用于排序「值不值得去问」，"
                + "不是「问了就一定能涨这么多」。");
        return out;
    }

    private String batchName(Long batchId) {
        if (batchId == null) {
            return null;
        }
        Map<String, Object> b = repo.getBatch(batchId);
        if (b == null) {
            return null;
        }
        Object name = b.get("name");
        if (name == null) {
            name = b.get("NAME");
        }
        return name == null ? null : String.valueOf(name);
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
