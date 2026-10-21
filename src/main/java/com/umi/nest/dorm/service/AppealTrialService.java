package com.umi.nest.dorm.service;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.explain.AppealResult;
import com.umi.nest.dorm.explain.CrossBlockAppeal;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.repo.DormRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨块试算的后台执行器。
 *
 * <p><b>为什么这一级必须异步</b>：房间级是毫秒、块级是秒，而跨块级要先把
 * 「同专业大类集中」这类政策关掉再重新分块——合并后的块是「同性别的整个年级」，
 * 几千人、上千间房。把它放在 HTTP 请求线程里，一个学生点一下就能把 Tomcat 的工作线程占住几十秒；
 * 而且 {@code NestSolver} 内部自己还会开并行池，几个请求撞在一起就是线程数的乘积在抢 CPU。
 *
 * <p><b>为什么是单线程池</b>：这些任务全是 CPU 密集的整块重解，并发执行不会更快，
 * 只会让每一个都变慢、让响应时间变得不可预测。排队反而是对的：试算是管理端的偶发操作，
 * 不是高并发业务。排队状态会如实显示给管理员（{@code QUEUED} / {@code RUNNING}）。
 *
 * <p><b>为什么结果要落库</b>：任务状态放在内存里，重启就没了；而「这条申诉做过跨块试算、
 * 结论是什么、放宽了哪条政策」是<b>要留痕的处置记录</b>，必须落库。内存里的 Job 只是给
 * 前端轮询用的进度快照，落库的那份才是权威。
 */
@Service
public class AppealTrialService {

    /** 内存里最多保留多少条任务快照；超出就丢最老的（落库那份不受影响）。 */
    private static final int MAX_JOBS = 200;

    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";
    private static final String DONE = "DONE";
    private static final String FAILED = "FAILED";

    /**
     * 一条试算任务的进度快照。
     *
     * @param result 只在 {@code DONE} 时有值
     */
    public record Job(long id, long batchId, long appealId, long studentId, List<Long> wantRoomWith,
                      String state, String note, long submittedAt, long finishedAt,
                      Map<String, Object> result) {
    }

    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Long, Long> activeByAppeal = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService pool;
    private final DormRepository repo;
    private final DormService dormService;

    public AppealTrialService(DormRepository repo, DormService dormService) {
        this.repo = repo;
        this.dormService = dormService;
        this.pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "appeal-cross-trial");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 提交一次跨块试算。
     *
     * <p>同一条申诉已经有任务在排队或执行时，直接返回那个任务的快照，<b>不重复提交</b>——
     * 否则管理员多点两下就会排出一串一模一样的重解任务，每一个都要跑几十秒。
     */
    public Map<String, Object> submit(long batchId, long appealId) {
        Map<String, Object> row = repo.getAppeal(appealId);
        if (row == null) {
            throw new IllegalArgumentException("申诉不存在: " + appealId);
        }
        long rowBatch = ((Number) row.get("batch_id")).longValue();
        if (rowBatch != batchId) {
            throw new IllegalArgumentException("申诉 " + appealId + " 不属于批次 " + batchId);
        }

        Long activeId = activeByAppeal.get(appealId);
        if (activeId != null) {
            Job existing = jobs.get(activeId);
            if (existing != null && (QUEUED.equals(existing.state()) || RUNNING.equals(existing.state()))) {
                Map<String, Object> out = view(existing);
                out.put("reused", true);
                out.put("note", "这条申诉已经有一次跨块试算在跑了，直接复用那次任务，没有重复提交。");
                return out;
            }
        }

        long id = sequence.incrementAndGet();
        long studentId = ((Number) row.get("student_id")).longValue();
        List<Long> wants = parseIds(row.get("target_ids"));
        long now = System.currentTimeMillis();
        Job queued = new Job(id, batchId, appealId, studentId, wants, QUEUED,
                "已排队，等待后台线程空闲。", now, 0L, null);
        jobs.put(id, queued);
        activeByAppeal.put(appealId, id);
        repo.updateAppealCross(appealId, QUEUED,
                "已提交跨块试算，等待后台执行。这一级会合并块并放宽政策约束，可能要几秒到几十秒。", null, 0L);
        pool.submit(() -> run(queued));
        evict();
        return view(queued);
    }

    /** 查询任务进度。任务不在内存里（重启过、或已被淘汰）时返回一个明确的说明，而不是 404。 */
    public Map<String, Object> status(long jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobId", jobId);
            out.put("state", "UNKNOWN");
            out.put("note", "内存里找不到这个任务（服务可能重启过，或任务快照已被淘汰）。"
                    + "跨块试算的结论已经落库，请直接查看这条申诉的 cross_json / cross_note。");
            return out;
        }
        return view(job);
    }

    /** 某条申诉当前有没有在跑的任务；给管理端的申诉列表用，省得前端逐条轮询。 */
    public Map<String, Object> crossStateOf(long appealId) {
        Long activeId = activeByAppeal.get(appealId);
        if (activeId == null) {
            return null;
        }
        Job job = jobs.get(activeId);
        return job == null ? null : view(job);
    }

    // ---------------- 内部 ----------------

    private void run(Job job) {
        long t0 = System.currentTimeMillis();
        mark(job, RUNNING, "正在合并块并重解，这一级可能要几秒到几十秒。");
        repo.updateAppealCross(job.appealId(), RUNNING,
                "正在合并块并重解，这一级可能要几秒到几十秒。", null, 0L);
        try {
            DormRepository.LoadedData data = repo.loadProblem();
            ConstraintConfig cfg = repo.configOf(job.batchId());
            ProblemInstance problem = data.problem();
            Solution current = dormService.solutionOf(job.batchId(), problem);
            CrossBlockAppeal.Trial trial = new CrossBlockAppeal(problem, cfg)
                    .trySatisfy(current, job.studentId(), job.wantRoomWith());
            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> view = toMap(trial, ms);
            repo.updateAppealCross(job.appealId(), DONE, trial.result().message(), trial.result(), ms);
            finish(job, DONE, trial.result().message(), view);
        } catch (Throwable e) {
            long ms = System.currentTimeMillis() - t0;
            String note = "跨块试算执行失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("error", note);
            repo.updateAppealCross(job.appealId(), FAILED, note, null, ms);
            finish(job, FAILED, note, view);
        } finally {
            activeByAppeal.remove(job.appealId());
        }
    }

    private Map<String, Object> toMap(CrossBlockAppeal.Trial trial, long elapsedMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        AppealResult r = trial.result();
        m.put("status", r.status());
        m.put("message", r.message());
        m.put("scope", r.scope());
        m.put("moves", r.suggestions());
        m.put("moveCount", r.suggestions() == null ? 0 : r.suggestions().size());
        List<Map<String, Object>> rels = new ArrayList<>();
        for (CrossBlockAppeal.Relaxation rel : trial.relaxations()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("flag", rel.flag());
            one.put("label", rel.label());
            one.put("why", rel.why());
            rels.add(one);
        }
        m.put("relaxations", rels);
        m.put("mergedStudents", trial.mergedStudents());
        m.put("mergedRooms", trial.mergedRooms());
        m.put("objectiveBefore", trial.objectiveBefore());
        m.put("objectiveAfter", trial.objectiveAfter());
        m.put("objectiveDelta", trial.objectiveDelta());
        m.put("stillBlocked", trial.stillBlocked());
        m.put("elapsedMs", elapsedMs);
        return m;
    }

    private synchronized void mark(Job job, String state, String note) {
        jobs.put(job.id(), new Job(job.id(), job.batchId(), job.appealId(), job.studentId(),
                job.wantRoomWith(), state, note, job.submittedAt(), 0L, null));
    }

    private synchronized void finish(Job job, String state, String note, Map<String, Object> result) {
        jobs.put(job.id(), new Job(job.id(), job.batchId(), job.appealId(), job.studentId(),
                job.wantRoomWith(), state, note, job.submittedAt(), System.currentTimeMillis(), result));
    }

    private Map<String, Object> view(Job job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", job.id());
        m.put("batchId", job.batchId());
        m.put("appealId", job.appealId());
        m.put("studentId", job.studentId());
        m.put("state", job.state());
        m.put("note", job.note());
        m.put("submittedAt", job.submittedAt());
        m.put("finishedAt", job.finishedAt() == 0L ? null : job.finishedAt());
        m.put("elapsedMs", job.finishedAt() == 0L ? 0L : job.finishedAt() - job.submittedAt());
        m.put("result", job.result());
        return m;
    }

    /** 只保留最近 {@code MAX_JOBS} 条快照，优先淘汰已经结束的。 */
    private void evict() {
        if (jobs.size() <= MAX_JOBS) {
            return;
        }
        List<Job> ordered = new ArrayList<>(jobs.values());
        ordered.sort(Comparator.comparingLong(Job::submittedAt));
        int drop = jobs.size() - MAX_JOBS;
        for (Job j : ordered) {
            if (drop <= 0) {
                break;
            }
            if (QUEUED.equals(j.state()) || RUNNING.equals(j.state())) {
                continue; // 正在跑的绝不淘汰，否则前端永远查不到结果
            }
            jobs.remove(j.id());
            drop--;
        }
    }

    private static List<Long> parseIds(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : String.valueOf(raw).split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 库里的脏数据不该让整次试算失败，跳过这一条即可
            }
        }
        return out;
    }
}
