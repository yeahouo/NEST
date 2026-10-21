package com.umi.nest.dorm.service;

import com.umi.nest.dorm.compat.CompatibilityFunction;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.eval.EvaluationMetrics;
import com.umi.nest.dorm.eval.Evaluator;
import com.umi.nest.dorm.explain.AppealResult;
import com.umi.nest.dorm.explain.AppealService;
import com.umi.nest.dorm.explain.Explanation;
import com.umi.nest.dorm.explain.ExplanationService;
import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.SpecialNeed;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.repo.DormRepository;
import com.umi.nest.dorm.solver.NestSolver;
import com.umi.nest.dorm.solver.SolveResult;
import com.umi.nest.dorm.web.DormRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿舍域应用服务：把"数据进 → 求解 → 落库 → 解释 → 申诉"这条链子串起来。
 *
 * <p>两条刻意的边界：
 * <ul>
 *   <li><b>求解器只读 {@code dorm_student_feature}（观测值）</b>，永远不碰
 *       {@code dorm_ground_truth}（隐真值）。真值只在评估时由 evaluator 读，
 *       所以"算法看不到答案"不是靠自觉，而是靠这条代码路径不存在。</li>
 *   <li><b>解释在分配时一次性算好落库</b>，查询接口只读不重算。否则上千人同时刷新，
 *       每次都重算块内对比方案，服务会被自己的解释功能打爆。</li>
 * </ul>
 */
@Service
public class DormService {

    /** 超过这个人数就不预生成逐人解释了（块内对比方案是 O(块²)，万人要跑很久）。 */
    public static final int EXPLAIN_LIMIT = 4000;

    private final DormRepository repo;

    public DormService(DormRepository repo) {
        this.repo = repo;
    }

    // ==================================================================
    // 数据
    // ==================================================================

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>(repo.dataSummary());
        out.put("hasGroundTruth", repo.hasGroundTruth());
        out.put("explainLimit", EXPLAIN_LIMIT);
        return out;
    }

    @Transactional
    public Map<String, Object> clear() {
        repo.clearDormData();
        return Map.of("cleared", true, "summary", repo.dataSummary());
    }

    /**
     * 生成带隐真值的合成数据并入库。
     *
     * <p>注意这里写了<b>两套数</b>：{@code dorm_student_feature} 放观测值
     * （真值 + 噪声 + 随机缺失），{@code dorm_ground_truth} 放真值 z 与两个隐变量。
     * 学生 id 用"生成器 id → 数据库自增 id"的映射对齐，志愿与真值一起重映射，
     * 避免两套 id 悄悄错位（这类错位不会报错，只会让所有指标变成噪音）。
     */
    @Transactional
    public Map<String, Object> synthesize(DormRequests.Synth req) {
        int n = req.students() == null ? 1000 : Math.max(1, req.students());
        long seed = req.seed() == null ? 20260927L : req.seed();
        GenParams base = GenParams.defaults(n, seed);
        GenParams p = new GenParams(
                n,
                req.roomCapacity() == null ? base.roomCapacity() : req.roomCapacity(),
                req.noise() == null ? base.noiseLevel() : req.noise(),
                req.missing() == null ? base.missingRate() : req.missing(),
                req.tension() == null ? base.preferenceTension() : req.tension(),
                base.preferenceCount(),
                base.specialNeedRate(),
                seed,
                req.gradeMix() == null ? base.gradeMix() : req.gradeMix(),
                req.buildingCount() == null ? base.buildingCount() : req.buildingCount());

        SyntheticDataset ds = SyntheticDataGenerator.generate(p);
        ProblemInstance problem = ds.problem();

        repo.clearDormData();

        // ---- 学生 ----
        List<DormRepository.StudentRow> rows = new ArrayList<>();
        for (Student s : problem.students()) {
            StudentFeature obs = problem.observations().get(s.id());
            rows.add(new DormRepository.StudentRow(
                    studentNo(s.id()), s.name(), s.gender().code(), s.grade(), s.major(),
                    s.majorCluster(), s.buildingPref(), s.specialNeed().name(),
                    obs == null ? null : obs.toArray()));
        }
        Map<String, Long> noToId = repo.importStudents(rows);

        // ---- id 重映射 ----
        Map<Long, Long> idMap = new HashMap<>();
        for (Student s : problem.students()) {
            Long mapped = noToId.get(studentNo(s.id()));
            if (mapped != null) {
                idMap.put(s.id(), mapped);
            }
        }

        // ---- 房间 ----
        repo.importRooms(problem.rooms());

        // ---- 志愿（键与目标都要重映射） ----
        Map<Long, Preference> prefs = new HashMap<>();
        for (Map.Entry<Long, Preference> e : problem.preferences().entrySet()) {
            Long sid = idMap.get(e.getKey());
            if (sid == null) {
                continue;
            }
            Preference old = e.getValue();
            prefs.put(sid, new Preference(sid,
                    remap(old.preferredRoommates(), idMap),
                    remap(old.avoidRoommates(), idMap),
                    old.preferredBuildings()));
        }
        repo.importPreferences(prefs);

        // ---- 隐真值（同样重映射） ----
        Map<Long, StudentFeature> trueFeats = new HashMap<>();
        Map<Long, Double> latent = new HashMap<>();
        Map<Long, Double> smokeTol = new HashMap<>();
        GroundTruth gt = ds.truth();
        for (Long genId : gt.students()) {
            Long dbId = idMap.get(genId);
            if (dbId == null) {
                continue;
            }
            trueFeats.put(dbId, gt.trueFeatureOf(genId));
            latent.put(dbId, gt.latentOf(genId));
            smokeTol.put(dbId, gt.smokeToleranceOf(genId));
        }
        String tag = "synth-n" + n + "-s" + seed + (p.missingRate() > 0 ? "-m" + p.missingRate() : "")
                + "-t" + p.preferenceTension();
        repo.saveGroundTruth(trueFeats, latent, smokeTol, tag);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasetTag", tag);
        out.put("params", p.label());
        out.put("summary", repo.dataSummary());
        out.put("hiddenTruthStored", trueFeats.size());
        out.put("note", "真值已单独存入 dorm_ground_truth；求解器读不到这张表，"
                + "所以指标是在算法看不见的答案上算的。");
        return out;
    }

    /**
     * 只体检、不导入。学校拿到真实名单后先跑这个，看会报什么。
     *
     * <p>单独开一个入口的理由：体检和导入走的是同一份判定逻辑，先跑体检可以把
     * 「改数据」和「改库」两件事分开。直接导入的话，一次失误就写进去了。
     */
    public Map<String, Object> preflight(DormRequests.ImportData req) {
        ImportPreflight.Report rep = ImportPreflight.check(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", rep.ok());
        out.put("errors", rep.errors());
        out.put("warnings", rep.warnings());
        out.put("students", rep.students());
        out.put("rooms", rep.rooms());
        out.put("beds", rep.beds());
        out.put("withFeature", rep.withFeature());
        out.put("byGender", rep.byGender());
        out.put("bedsByGender", rep.bedsByGender());
        List<Map<String, Object>> list = new ArrayList<>();
        for (ImportPreflight.Issue i : rep.issues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", i.severity());
            m.put("where", i.where());
            m.put("message", i.message());
            list.add(m);
        }
        out.put("issues", list);
        out.put("markdown", rep.markdown());
        return out;
    }

    /** 真实数据导入：只写观测值，不写真值（因为没有）。 */
    @Transactional
    public Map<String, Object> importData(DormRequests.ImportData req) {
        if (req == null || req.students() == null || req.students().isEmpty()) {
            throw new IllegalArgumentException("students 不能为空");
        }
        // 先体检再动手。脏数据的自然表现方式是外键报错、或者静默写到一个不相干的人身上，
        // 两种都极难反查到「第几行写错了」。体检不通过就整体拒绝并逐条说明，不"导进去再说"。
        ImportPreflight.Report pre = ImportPreflight.check(req);
        if (!pre.ok()) {
            StringBuilder sb = new StringBuilder("导入体检未通过，已拒绝（共 "
                    + pre.errors() + " 项错误，另有 " + pre.warnings() + " 项提醒）：");
            for (ImportPreflight.Issue i : pre.issues()) {
                if (ImportPreflight.ERROR.equals(i.severity())) {
                    sb.append("\n- ").append(i.where()).append('：').append(i.message());
                }
            }
            sb.append("\n可以先用 POST /api/dorm/data/preflight 单独体检，它不写任何数据。");
            throw new IllegalArgumentException(sb.toString());
        }
        if (Boolean.TRUE.equals(req.replaceAll())) {
            repo.clearDormData();
        }
        List<DormRepository.StudentRow> rows = new ArrayList<>();
        for (DormRequests.StudentDto d : req.students()) {
            rows.add(new DormRepository.StudentRow(d.studentNo(), d.name(), normGender(d.gender()),
                    d.grade(), d.major(), d.majorCluster(), d.buildingPref(),
                    normSpecial(d.specialNeed()), pad(d.feature())));
        }
        Map<String, Long> noToId = repo.importStudents(rows);

        // 志愿：请求里用学号引用同学，这里换算成数据库 id
        Set<Long> knownIds = new HashSet<>(noToId.values());
        Map<Long, Preference> prefs = new HashMap<>();
        for (DormRequests.StudentDto d : req.students()) {
            Long sid = noToId.get(d.studentNo() == null ? null : d.studentNo().trim());
            if (sid == null) {
                continue;
            }
            // 楼栋意愿：求解器（RoomScorer:142-144）只读 Preference.preferredBuildings，
            // 不读 Student.buildingPref。早先这里写死 List.of()，等于导进来的楼栋意愿
            // 对求解毫无作用——学生填了、报告里算了、分配时被忽略。
            Preference p = new Preference(sid,
                    resolveMates(d.wantRoommateWith(), noToId, knownIds),
                    resolveMates(d.avoidRoommateWith(), noToId, knownIds),
                    buildingList(d.buildingPref()));
            // 只在请求里真的带了志愿时才写。空志愿不等于「这个人没有志愿」，只等于
            // 「这次导入没说他有什么志愿」——当成清空会把学生在门户里自己填的擦掉。
            // 要清空请用 replaceAll=true 走全量重置。
            if (!p.isEmpty()) {
                prefs.put(sid, p);
            }
        }
        repo.importPreferences(prefs);

        int roomCount = 0;
        if (req.rooms() != null) {
            List<Room> rooms = new ArrayList<>();
            long seq = 1;
            for (DormRequests.RoomDto r : req.rooms()) {
                rooms.add(new Room(seq++, r.roomNo(), r.buildingId(), Gender.parse(normGender(r.gender())),
                        r.capacity() == null ? 4 : r.capacity(),
                        Boolean.TRUE.equals(r.forSpecialNeeds()),
                        r.gradeRestriction() == null || r.gradeRestriction().isBlank() ? null : r.gradeRestriction()));
            }
            repo.importRooms(rooms);
            roomCount = rooms.size();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", rows.size());
        out.put("rooms", roomCount);
        out.put("summary", repo.dataSummary());
        out.put("preflight", pre.markdown());
        out.put("preflightWarnings", pre.warnings());
        out.put("note", "真实数据没有隐真值，分配的相容度指标会是「模型预测值」，"
                + "只能用于自检和展示，不能拿去和合成数据实验的真值指标并排比较。");
        return out;
    }

    // ==================================================================
    // 一键分配
    // ==================================================================

    @Transactional
    public Map<String, Object> assign(DormRequests.Assign req) {
        ConstraintConfig cfg = req != null && req.config() != null ? req.config() : ConstraintConfig.defaults();
        cfg.validate();

        DormRepository.LoadedData data = repo.loadProblem();
        ProblemInstance problem = data.problem();
        if (problem.studentCount() == 0) {
            throw new IllegalStateException("还没有学生数据。先调用 /api/dorm/data/synthesize 或 /api/dorm/data/import。");
        }
        if (problem.totalCapacity() < problem.studentCount()) {
            throw new IllegalStateException(String.format(
                    "床位不够：学生 %d 人，床位 %d 个。硬约束下不可能零违规——请先补房间。",
                    problem.studentCount(), problem.totalCapacity()));
        }

        SolveResult res = new NestSolver().solve(problem, cfg);

        CompatibilityFunction compat = new RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights());
        GroundTruth gt = repo.loadGroundTruth();
        EvaluationMetrics metrics = gt != null
                ? Evaluator.evaluate(gt, problem, res.solution(), res.solver(), res.runtimeMs())
                : Evaluator.evaluateModelSide(problem, res, compat);

        String name = req != null && req.name() != null && !req.name().isBlank()
                ? req.name()
                : ("分配 " + java.time.LocalDateTime.now().withNano(0));
        long batchId = repo.createBatch(name, res, problem, repo.json(cfg));
        repo.saveAssignments(batchId, problem, res.solution());
        repo.finishBatch(batchId, metrics, res);

        int explained = 0;
        boolean wantExplain = req == null || req.withExplanations() == null || req.withExplanations();
        if (wantExplain && problem.studentCount() <= EXPLAIN_LIMIT) {
            ExplanationService es = new ExplanationService(problem, cfg);
            Map<Long, Explanation> exps = es.explainAll(res.solution());
            repo.saveExplanations(batchId, exps);
            explained = exps.size();
        }

        repo.saveAudit(batchId, "satisfaction.byMajorCluster",
                Evaluator.satisfactionByGroup(problem, res.solution(), compat, Student::majorCluster));
        repo.saveAudit(batchId, "satisfaction.byGrade",
                Evaluator.satisfactionByGroup(problem, res.solution(), compat, Student::grade));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("name", name);
        out.put("solution", res.summary());
        out.put("metrics", metrics);
        out.put("diagnostics", res.diagnostics());
        out.put("warnings", res.warnings());
        out.put("explanationsStored", explained);
        if (explained == 0 && wantExplain) {
            out.put("explainSkippedReason", "学生数超过 " + EXPLAIN_LIMIT
                    + "，逐人解释（含块内对比方案）成本太高，已跳过。可对单个学生实时查询。");
        }
        out.put("metricBasisWarning", metrics.truthBased() ? null
                : "当前数据没有隐真值，相容度指标为模型预测值，不可作为实验结论。");
        return out;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public List<Map<String, Object>> batches(int limit) {
        return repo.listBatches(limit);
    }

    public Map<String, Object> batch(long batchId) {
        Map<String, Object> b = repo.getBatch(batchId);
        if (b == null) {
            throw new IllegalArgumentException("批次不存在: " + batchId);
        }
        Map<String, Object> out = new LinkedHashMap<>(b);
        out.put("config", repo.configOf(batchId));
        out.put("rooms", repo.roomsOf(batchId));
        return out;
    }

    public List<Map<String, Object>> assignments(long batchId, Long roomId) {
        return repo.assignmentsOf(batchId, roomId);
    }

    /** 解释：优先读预生成的；没有就现场算一份（只算这一个人，代价可控）。 */
    public Map<String, Object> explain(long batchId, long studentId) {
        String json = repo.explanationJson(batchId, studentId);
        if (json != null) {
            try {
                return Map.of("source", "PRECOMPUTED", "explanation", repo.mapper().readValue(json, Map.class));
            } catch (Exception ignored) {
                // 落到实时计算
            }
        }
        DormRepository.LoadedData data = repo.loadProblem();
        ConstraintConfig cfg = repo.configOf(batchId);
        Solution sol = solutionOf(batchId, data.problem());
        Map<Long, Explanation> one = new ExplanationService(data.problem(), cfg)
                .explainAll(sol, List.of(studentId));
        Explanation e = one.get(studentId);
        if (e == null) {
            throw new IllegalArgumentException("找不到该学生或该批次里没有此人: " + studentId);
        }
        return Map.of("source", "ON_DEMAND", "explanation", e);
    }

    /** 房间视角：住谁 + 房内两两相容度矩阵，给前端画"为什么这间房分高/分低"。 */
    public Map<String, Object> roomView(long batchId, long roomId) {
        DormRepository.LoadedData data = repo.loadProblem();
        ConstraintConfig cfg = repo.configOf(batchId);
        ProblemInstance problem = data.problem();
        Solution sol = solutionOf(batchId, problem);
        Room room = problem.roomById().get(roomId);
        if (room == null) {
            throw new IllegalArgumentException("房间不存在: " + roomId);
        }
        CompatibilityFunction compat = new RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights());
        List<Long> members = sol.occupants(roomId);
        List<Map<String, Object>> memberViews = new ArrayList<>();
        for (Long id : members) {
            Student s = problem.byId().get(id);
            if (s == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", id);
            m.put("studentNo", data.studentNoOf().get(id));
            m.put("name", s.name());
            m.put("major", s.major());
            m.put("grade", s.grade());
            memberViews.add(m);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                long a = members.get(i);
                long b = members.get(j);
                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("a", a);
                pr.put("b", b);
                pr.put("score", compat.score(a, b));
                pr.put("breakdown", compat.explain(a, b).orderedByImpact());
                pairs.add(pr);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", roomId);
        out.put("roomLabel", room.label());
        out.put("buildingId", room.buildingId());
        out.put("capacity", room.capacity());
        out.put("members", memberViews);
        out.put("pairs", pairs);
        out.put("note", "这里的相容度是算法用的模型值（学生看得到的那套），"
                + "不是实验里的隐真值。");
        return out;
    }

    public List<Map<String, Object>> audit(long batchId) {
        return repo.mapper() == null ? List.of() : repo.auditOf(batchId);
    }

    // ==================================================================
    // 申诉
    // ==================================================================

    /** 申诉试算：实时算（学生点一次等几秒是能接受的），算完落库留痕。 */
    @Transactional
    public Map<String, Object> appeal(long batchId, DormRequests.Appeal req) {
        if (req == null || req.studentId() == null) {
            throw new IllegalArgumentException("studentId 必填");
        }
        DormRepository.LoadedData data = repo.loadProblem();
        ConstraintConfig cfg = repo.configOf(batchId);
        ProblemInstance problem = data.problem();
        if (!problem.byId().containsKey(req.studentId())) {
            throw new IllegalArgumentException("学生不存在: " + req.studentId());
        }
        Solution sol = solutionOf(batchId, problem);
        AppealService svc = new AppealService(problem, cfg);
        AppealResult r = req.costBudget() == null
                ? svc.tryRoommateRequest(sol, req.studentId(), nz(req.wantRoomWith()))
                : svc.tryRoommateRequest(sol, req.studentId(), nz(req.wantRoomWith()),
                req.costBudget(), System.nanoTime());

        long appealId = repo.insertAppeal(batchId, req.studentId(), "ROOMMATE",
                joinIds(req.wantRoomWith()), r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appealId", appealId);
        out.put("result", r);
        out.put("note", AppealResult.NOT_FOUND_IN_SCOPE.equals(r.status())
                ? "「在我搜过的范围内没找到」不等于「无解」——范围已在 scope 里写明。"
                : null);
        return out;
    }

    public List<Map<String, Object>> appeals(long batchId) {
        return repo.listAppeals(batchId);
    }

    /**
     * 采纳申诉：把建议的两人对调真正写回分配结果。
     *
     * <p>这里只改 {@code dorm_assignment} 的两行，<b>不重跑求解</b>——
     * 重跑会让其他学生的房间变动，而他们没有申诉。改完把
     * {@code is_appealed} 置 1，审计时能数出"有多少分配是被人工干预过的"。
     */
    @Transactional
    public Map<String, Object> applyAppeal(long appealId, String handledBy) {
        Map<String, Object> row = repo.getAppeal(appealId);
        if (row == null) {
            throw new IllegalArgumentException("申诉不存在: " + appealId);
        }
        // 跨块试算的结论放在 cross_json 里，与最初那级的 payload_json 并存（两次试算的范围与代价
        // 完全不同，覆盖掉就说不清「当初为什么答的是没找到」）。有跨块结论且它给出了方案时优先用它：
        // 管理员点「采纳」的意思是「采纳我现在看到的那份方案」，而列表上显示的就是跨块那一级。
        AppealResult r = null;
        String source = "最初试算（房间级/块级）";
        if (row.get("cross_json") != null && "DONE".equals(String.valueOf(row.get("cross_state")))) {
            try {
                AppealResult cross = repo.mapper()
                        .readValue(String.valueOf(row.get("cross_json")), AppealResult.class);
                if (cross.hasSuggestion()) {
                    r = cross;
                    source = "跨块试算";
                }
            } catch (Exception ignored) {
                // 跨块结果解析不了就退回最初那级——不该因为一条脏记录让整次采纳失败
            }
        }
        if (r == null) {
            String status = String.valueOf(row.get("status"));
            if (!AppealResult.SATISFIED.equals(status)) {
                throw new IllegalStateException("这条申诉最初那级的结论是 " + status
                        + "，没有可执行的方案；如果它做过跨块试算，请先确认跨块状态是 DONE 且给出了方案。");
            }
            try {
                r = repo.mapper().readValue(String.valueOf(row.get("payload_json")), AppealResult.class);
            } catch (Exception e) {
                throw new IllegalStateException("申诉记录里的建议无法解析: " + e.getMessage());
            }
        }
        if (!r.hasSuggestion()) {
            throw new IllegalStateException("这条申诉没有可执行的建议");
        }
        long batchId = ((Number) row.get("batch_id")).longValue();
        DormRepository.LoadedData data = repo.loadProblem();
        Map<Long, Room> rooms = data.problem().roomById();

        for (AppealResult.Swap s : r.suggestions()) {
            Room to = rooms.get(s.toRoom());
            repo.moveStudent(batchId, s.studentId(), s.toRoom(), to == null ? "" : to.label(),
                    to == null ? "" : to.buildingId());
            if (s.otherId() >= 0) {
                Room back = rooms.get(s.fromRoom());
                repo.moveStudent(batchId, s.otherId(), s.fromRoom(),
                        back == null ? "" : back.label(), back == null ? "" : back.buildingId());
            }
        }
        repo.updateAppealStatus(appealId, "ACCEPTED", handledBy);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appealId", appealId);
        out.put("status", "ACCEPTED");
        out.put("moves", r.suggestions().size());
        out.put("note", "只改动相关学生的房间，未重跑全局分配；"
                + "批次指标是采纳前的快照，如需新指标请重新分配。");
        return out;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 把数据库里的分配结果还原成 {@link Solution}，供解释/申诉/房间视角使用。 */
    public Solution solutionOf(long batchId, ProblemInstance problem) {
        Map<Long, Long> m = new HashMap<>();
        for (Map<String, Object> row : repo.assignmentsOf(batchId, null)) {
            Object roomId = row.get("room_id");
            if (roomId == null) {
                continue;
            }
            long sid = ((Number) row.get("student_id")).longValue();
            m.put(sid, ((Number) roomId).longValue());
        }
        return Solution.of(m);
    }

    private static String studentNo(long genId) {
        return "S" + String.format("%06d", genId);
    }

    private static List<Long> remap(List<Long> ids, Map<Long, Long> idMap) {
        List<Long> out = new ArrayList<>();
        for (Long id : ids) {
            Long m = idMap.get(id);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    private static List<Long> nz(List<Long> l) {
        return l == null ? List.of() : l;
    }

    /**
     * 把请求里的特征数组补成 {@link StudentFeature#DIM} 长度的 {@code double[]}。
     *
     * <p>请求里用 {@code Double[]}（元素可为 null）是为了能表达「这一维未知」——真实问卷的缺失
     * 是零散的，只有装箱数组表示得了。这里统一落成 {@code Double.NaN}，因为下游
     * （{@code GroundTruth} / 求解器的均值插补 / {@code DormRepository} 存库）一律用 NaN 当缺失标记，
     * 存库时写 SQL NULL、读回来还原成 NaN。
     */
    private static double[] pad(Double[] f) {
        if (f == null) {
            return null;
        }
        double[] out = new double[StudentFeature.DIM];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = 0; i < Math.min(f.length, StudentFeature.DIM); i++) {
            if (f[i] != null) {
                out[i] = f[i];
            }
        }
        return out;
    }

    /**
     * 楼栋意愿：请求里给的是一个字符串，允许 {@code "A1"} 或 {@code "A1,A2"} 这类写法。
     *
     * <p>为什么要转成列表：求解器和解释器都从 {@code Preference.preferredBuildings} 读楼栋
     * 意愿（{@code RoomScorer:142-144}、{@code ExplanationService:278-282}），而不是从
     * {@code Student.buildingPref} 读。所以导入时必须在这里落一份，否则学生填的楼栋意愿
     * 只会在档案里躺着。
     */
    private static List<String> buildingList(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : raw.split("[,，;；、\\s]+")) {
            String v = t.trim();
            if (!v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 把志愿名单换算成数据库 id。
     *
     * <p>入参是字符串而不是 Long，因为真实学号常带字母（{@code S20230101}），用数字表达不出来。
     * 解析顺序：先当学号找，找不到再当数字 id 试一次（兼容早期调用方直接传 id），
     * 都找不到就**丢掉**。丢掉而不是原样保留是关键——把一个找不到的学号当 id 用，
     * 会静默指向另一个真实存在的人，而志愿又正是权重最高的输入。
     */
    private static List<Long> resolveMates(List<String> raw, Map<String, Long> noToId, Set<Long> knownIds) {
        List<Long> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String t : raw) {
            if (t == null) {
                continue;
            }
            String s = t.trim();
            if (s.isEmpty()) {
                continue;
            }
            Long id = noToId.get(s);
            if (id == null) {
                try {
                    long n = Long.parseLong(s);
                    id = knownIds.contains(n) ? n : null;
                } catch (NumberFormatException notANumber) {
                    id = null;
                }
            }
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static String normGender(String g) {
        if (g == null || g.isBlank()) {
            throw new IllegalArgumentException("gender 必填（M/F 或 MALE/FEMALE）");
        }
        String u = g.trim().toUpperCase();
        // 「男/女」也要认：学校的表里几乎都是这两个字，让它们在这里抛异常等于把
        // 一个纯粹的格式问题变成一个要读日志才能定位的 500。
        if (u.startsWith("M") || u.contains("男")) {
            return "M";
        }
        if (u.startsWith("F") || u.contains("女")) {
            return "F";
        }
        throw new IllegalArgumentException("无法识别的 gender: " + g + "（支持 M/F、MALE/FEMALE、男/女）");
    }

    private static String normSpecial(String s) {
        if (s == null || s.isBlank()) {
            return SpecialNeed.NONE.name();
        }
        try {
            return SpecialNeed.valueOf(s.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无法识别的 specialNeed: " + s
                    + "（可选 NONE/WHEELCHAIR/LOW_FLOOR/QUIET）");
        }
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}
