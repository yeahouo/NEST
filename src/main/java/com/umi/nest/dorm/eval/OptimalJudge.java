package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.solver.SolveResult;

/**
 * 最优解裁判：小规模上求出的<b>真正最优解</b>，用来回答"你的启发式离最优有多远"。
 *
 * <p>为什么它必须是一个可插拔接口而不是直接 {@code new CpSatJudge()}：
 * CP-SAT 走的是 OR-Tools 的本地原生库，会带来约 110MB 的跨平台二进制
 * （六个平台的 jar 全在依赖里）。而生产部署只需要贪心求解器——
 * 把 110MB 塞进给学校的那个 jar 里毫无道理。
 * 于是用反射在运行时试探：编译期用 {@code -Pcpsat} 挂上源码目录，
 * 默认构建则 {@link #load()} 返回 {@code null}，实验报告照常出，只是少一栏"最优解"。
 *
 * <p>这条路径是否可用，在报告里必须<b>显式写成文字</b>（见 {@code ExperimentRunner} 的 notes），
 * 不能让"没有最优解对比"悄悄变成"我们就是最优"。
 */
public interface OptimalJudge {

    /** 裁判的名字，进实验报告的方法列。 */
    String name();

    /** 超过这个规模就拒绝求解（CP-SAT 在 200 人以上建模变量爆炸，且本来就只做小规模裁判）。 */
    int maxStudents();

    /**
     * 求最优解（或时限内最好解）。不可用时返回 {@code null}，绝不抛异常——
     * 裁判缺席不该把整轮实验打断。
     */
    SolveResult solve(ProblemInstance problem, ConstraintConfig cfg);

    /**
     * 上一次 {@link #solve} 返回 {@code null} 的原因；没有失败时返回 {@code null}。
     *
     * <p>为什么需要这个看似多余的访问器：如果裁判"没有结论"，而实验报告只写一句
     * "裁判不可用"，那么<b>超规模拒绝</b>、<b>原生库加载失败</b>、
     * <b>整数规划本身无解</b>这三件完全不同的事会显示成同一行字。
     * 前者是设计使然，后者是环境故障，最后一个是真正的发现（我们的硬约束组合不可行）。
     * 分不清就等于没报告。
     */
    default String lastFailure() {
        return null;
    }

    /**
     * 试探性加载 CP-SAT 裁判。
     *
     * <p>返回 {@code null} 是正常情况（默认构建），不是错误。
     * 捕获 {@link Throwable} 而不是 {@link Exception}：原生库加载失败会抛
     * {@code UnsatisfiedLinkError}，它是 Error 不是 Exception，漏掉会让整轮实验崩掉。
     */
    static OptimalJudge load() {
        try {
            Class<?> cls = Class.forName("com.umi.nest.dorm.eval.cpsat.CpSatJudge");
            return (OptimalJudge) cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 加载失败的原因，写进报告 notes 用。 */
    static String loadFailureReason() {
        try {
            Class.forName("com.umi.nest.dorm.eval.cpsat.CpSatJudge");
            return "已编译但无法实例化";
        } catch (ClassNotFoundException e) {
            return "未编译（需要用 -Pcpsat profile 构建）";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
