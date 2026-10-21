package com.umi.nest.dorm.cli;

import com.umi.nest.dorm.eval.ExperimentRunner;
import com.umi.nest.dorm.eval.OptimalJudge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验矩阵命令行入口：申请书 §10.4 那张表的生成器。
 *
 * <pre>
 * 常用：
 *   # 冒烟（含 CP-SAT 最优间隙，需要 -Pcpsat 构建）
 *   java -cp "target/classes;$(cat target/cp-cpsat.txt)" com.umi.nest.dorm.cli.ExperimentCli \
 *        --students 120 --seeds 1,2,3 --methods b0,b1,b2,nest,judge --judgeStudents 200 \
 *        --out target/exp-smoke.md
 *
 *   # 主实验（规模×噪声×缺失×紧张 × 10 种子），默认不含 CP-SAT
 *   java ... ExperimentCli --preset headline --out target/exp-headline.md
 *
 *   # 消融
 *   java ... ExperimentCli --students 1000 --seeds 1,2,3 --ablate --out target/exp-ablation.md
 * </pre>
 *
 * <p>进度打到 stdout（一行一个格子），报告末尾写入 {@code --out}。
 * 之所以把报告写成文件而不是只打屏：这张表要进结题报告，
 * 而控制台重定向在中文 Windows 上极易把中文变成乱码（踩过）。
 */
public final class ExperimentCli {

    private ExperimentCli() {
    }

    public static void main(String[] args) {
        Map<String, String> a = parse(args);
        if (a.containsKey("help")) {
            System.out.println(USAGE);
            return;
        }

        ExperimentRunner.Spec spec = buildSpec(a);
        System.out.printf("实验矩阵：%d 组配置 × %d 个种子 × %d 个方法 = %d 次求解%n",
                spec.configs().size(), spec.seeds().size(), spec.methods().size(), spec.totalRuns());
        System.out.println("方法：" + spec.methods().stream().map(ExperimentRunner.Method::name).toList());
        if (spec.includeJudge()) {
            // 裁判是反射无参构造出来的（见 OptimalJudge.load），所以配置只能走系统属性。
            // 必须在 load() 之前设置：构造器里就要读这些值。
            System.setProperty("nest.cpsat.maxStudents", String.valueOf(spec.judgeMaxStudents()));
            if (a.containsKey("judgeSeconds")) {
                System.setProperty("nest.cpsat.seconds", a.get("judgeSeconds"));
            }
            OptimalJudge judge = OptimalJudge.load();
            System.out.println(judge == null
                    ? "最优解裁判不可用：" + OptimalJudge.loadFailureReason()
                    : "最优解裁判就绪：" + judge.name() + "（≤ " + judge.maxStudents() + " 人）");
        }
        System.out.println("数据集的隐真值 g 恒不可见，所有指标都在 g 上计算。开始……");

        long t0 = System.currentTimeMillis();
        ExperimentRunner.Report report = ExperimentRunner.run(spec, (done, total, label) ->
                System.out.printf("  [%d/%d] %s%n", done, total, label));

        String md = report.markdown();
        String out = a.getOrDefault("out", "target/experiment.md");
        try {
            Path p = Path.of(out);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, md, StandardCharsets.UTF_8);
            System.out.println("\n报告已写入：" + p.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("写报告失败：" + e.getMessage());
        }
        System.out.println(md);
        System.out.printf("总耗时 %.1f 秒%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static final String USAGE = """
            实验矩阵命令行

              --preset quick|headline   预设（默认 quick）
              --students 200,1000       学生数，逗号分隔（做笛卡尔积）
              --noise 0.20              观测噪声水平，逗号分隔
              --missing 0.0             特征缺失率，逗号分隔
              --tension 0.5             志愿紧张度，逗号分隔
              --seeds 1,2,3             随机种子，逗号分隔
              --methods b0,b1,b2,nest,judge
              --ablate                  追加 NEST 的四组消融
              --judge                   追加 CP-SAT 最优裁判（需 -Pcpsat 构建）
              --judgeStudents 200       裁判只在学生数不超过该值的实例上求解
              --judgeSeconds 60         裁判的总时间预算（秒）；单块上限为它的 1/4
              --out target/experiment.md
            """;

    private static ExperimentRunner.Spec buildSpec(Map<String, String> a) {
        String preset = a.get("preset");
        // 只有 preset 与 out 两个键时才认 preset：否则一个 --out 就把 preset 挤掉，
        // 用户会以为自己在跑主实验、实际上跑的是默认的小矩阵。
        if (preset != null && a.keySet().stream().allMatch(k -> k.equals("preset") || k.equals("out"))) {
            return switch (preset) {
                case "headline" -> ExperimentRunner.Spec.headline();
                default -> ExperimentRunner.Spec.quick();
            };
        }

        List<Integer> sizes = ints(a.getOrDefault("students", "400"));
        List<Double> noises = doubles(a.getOrDefault("noise", "0.20"));
        List<Double> missings = doubles(a.getOrDefault("missing", "0.0"));
        List<Double> tensions = doubles(a.getOrDefault("tension", "0.5"));
        List<Long> seeds = longs(a.getOrDefault("seeds", "1,2,3"));

        List<ExperimentRunner.Config> configs = new ArrayList<>();
        for (int n : sizes) {
            for (double noise : noises) {
                for (double missing : missings) {
                    for (double tension : tensions) {
                        configs.add(new ExperimentRunner.Config(n, noise, missing, tension));
                    }
                }
            }
        }

        List<ExperimentRunner.Method> methods = new ArrayList<>();
        for (String m : a.getOrDefault("methods", "b0,b1,b2,nest").split(",")) {
            switch (m.trim().toLowerCase()) {
                case "b0" -> methods.add(ExperimentRunner.Method.b0());
                case "b1" -> methods.add(ExperimentRunner.Method.b1());
                case "b2" -> methods.add(ExperimentRunner.Method.b2());
                case "nest" -> methods.add(ExperimentRunner.Method.nest());
                case "judge" -> { /* 裁判在下面单独加，保证排在最后 */ }
                case "" -> { }
                default -> System.out.println("忽略未知方法：" + m);
            }
        }
        if (a.containsKey("ablate")) {
            methods.addAll(ExperimentRunner.Method.ablations());
        }
        boolean judge = a.containsKey("judge") || a.getOrDefault("methods", "").contains("judge");
        if (judge) {
            // 裁判必须排在 NEST 之后：间隙要用同一格子里已产出的 NEST 目标值来算
            methods.add(new ExperimentRunner.Method(CpSatHolder.NAME, ExperimentRunner.Kind.JUDGE, c -> { }));
        }
        if (methods.isEmpty()) {
            methods.add(ExperimentRunner.Method.nest());
        }

        return new ExperimentRunner.Spec(configs, seeds, methods, judge,
                intOf(a.get("judgeStudents"), 200));
    }

    /**
     * 裁判的名字常量。
     * <p>这里刻意不 import {@code CpSatJudge}——那个类属于 {@code src/cpsat/java}，
     * 默认构建根本不编译它。字符串常量抄在这里，代价是"改名字要改两处"，
     * 换来的是主源码与 OR-Tools 之间零编译期依赖。
     */
    private static final class CpSatHolder {
        static final String NAME = "B3-CP-SAT最优(裁判)";
    }

    // ------------------------------------------------------------------ 参数解析

    private static Map<String, String> parse(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (!s.startsWith("--")) {
                continue;
            }
            String key = s.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                a.put(key, args[++i]);
            } else {
                a.put(key, "true");
            }
        }
        return a;
    }

    private static List<Integer> ints(String s) {
        List<Integer> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(Integer.parseInt(p.trim()));
            }
        }
        return out;
    }

    private static List<Long> longs(String s) {
        List<Long> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(Long.parseLong(p.trim()));
            }
        }
        return out;
    }

    private static List<Double> doubles(String s) {
        List<Double> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(Double.parseDouble(p.trim()));
            }
        }
        return out;
    }

    private static int intOf(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
