package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.CompatWeights;
import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创新点实验的护栏测试。
 *
 * <p>最要紧的一条是 {@link #dimScoreMatchesProductionModel}：{@code InformationValueExperiment}
 * 为了让 VOI 在几十万次评估里跑得动，自己实现了 8 个维度函数（{@link RuleBasedCompatibility}
 * 里那些是包私有的，跨包不可复用）。这里用随机特征（含大量 NaN）逐对核对两边的分数完全一致，
 * 一旦有人改了生产模型的公式而忘了改实验副本，这条测试就会红。
 */
class InformationValueExperimentTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------------ 公式一致性

    @Test
    @DisplayName("实验用的 8 维公式与生产模型逐对一致（含缺失）")
    void dimScoreMatchesProductionModel() {
        Random rnd = new Random(20261016L);
        CompatWeights w = CompatWeights.defaults().normalized();
        double[] weights = {w.sleep(), w.smoking(), w.cleanliness(), w.extroversion(),
                w.studyNoise(), w.gamingNoise(), w.social(), w.sharing()};
        RuleBasedCompatibility compat = new RuleBasedCompatibility(Map.of());

        int checked = 0;
        for (int t = 0; t < 400; t++) {
            double miss = switch (t % 4) {
                case 0 -> 0.0;
                case 1 -> 0.30;
                case 2 -> 0.60;
                default -> 0.90;
            };
            StudentFeature a = randomFeature(rnd, miss);
            StudentFeature b = randomFeature(rnd, miss);
            double mine = 0;
            for (int d = 0; d < weights.length; d++) {
                mine += weights[d] * InformationValueExperiment.dimScore(d, a, b);
            }
            long idA = 10_000L + t;
            long idB = 20_000L + t;
            double theirs = new RuleBasedCompatibility(Map.of(idA, a, idB, b)).score(idA, idB);
            assertEquals(theirs, mine, EPS, "第 " + t + " 对特征（缺失率 " + miss + "）两边分数不一致");
            checked++;
        }
        assertTrue(checked == 400);
        assertEquals(0.0, compat.score(1L, 2L), EPS, "空表应返回 0");
    }

    @Test
    @DisplayName("任一维只有一侧已知时该维贡献 0")
    void unknownSideContributesNothing() {
        StudentFeature known = feature(0.2, 0.2, 0.9, 0.5, 0.5, 0.5, 0.3, 0.3, 0.5, 0.5);
        StudentFeature onlySmokingMissing = feature(0.2, 0.2, Double.NaN, 0.5, 0.5, 0.5, 0.3, 0.3, 0.5, 0.5);
        assertEquals(0.0, InformationValueExperiment.dimScore(1, known, onlySmokingMissing), EPS);
        // 其余 7 维仍应正常参与：整对分数不等于 0
        double sum = 0;
        for (int d = 0; d < 8; d++) {
            sum += InformationValueExperiment.dimWeight(d) * InformationValueExperiment.dimScore(d, known, onlySmokingMissing);
        }
        assertTrue(sum > 0.0);
    }

    // ------------------------------------------------------------------ 证据覆盖率

    @Test
    @DisplayName("双方全已知 ⇒ 覆盖率 1；全缺失 ⇒ 0 且不属于可评估对")
    void knownWeightBounds() {
        StudentFeature full = feature(0.2, 0.2, 0.9, 0.5, 0.5, 0.5, 0.3, 0.3, 0.5, 0.5);
        StudentFeature empty = allMissing();
        assertEquals(1.0, InformationValueExperiment.knownWeight(full, full), 1e-12);
        assertEquals(0.0, InformationValueExperiment.knownWeight(empty, full), 1e-12);

        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, full);
        obs.put(2L, full);
        Solution sol = Solution.empty();
        sol.assign(1L, 101L);
        sol.assign(2L, 101L);
        double[] cov = InformationValueExperiment.covAndEval(obs, sol);
        assertEquals(1.0, cov[0], 1e-12);
        assertEquals(1.0, cov[1], 1e-12);

        Map<Long, StudentFeature> emptyObs = new LinkedHashMap<>();
        emptyObs.put(1L, empty);
        emptyObs.put(2L, empty);
        double[] cov2 = InformationValueExperiment.covAndEval(emptyObs, sol);
        assertEquals(0.0, cov2[0], 1e-12);
        assertEquals(0.0, cov2[1], 1e-12, "一维都不可比就不该算可评估对");
    }

    @Test
    @DisplayName("只答了吸烟的两个学生：覆盖率等于吸烟权重、仍算可评估")
    void singleDimensionCoverage() {
        StudentFeature a = allMissingWith(2, 0.9);
        StudentFeature b = allMissingWith(2, 0.1);
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, a);
        obs.put(2L, b);
        Solution sol = Solution.empty();
        sol.assign(1L, 101L);
        sol.assign(2L, 101L);
        double[] cov = InformationValueExperiment.covAndEval(obs, sol);
        CompatWeights w = CompatWeights.defaults().normalized();
        assertEquals(w.smoking(), cov[0], 1e-12);
        assertEquals(1.0, cov[1], 1e-12);
    }

    // ------------------------------------------------------------------ 缺失格与 VOI

    @Test
    @DisplayName("缺失格清单只数参与打分的格：ambition 缺失不算")
    void missingCellsIgnoreAmbition() {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = 0.5;
        }
        v[10] = Double.NaN;   // ambition：模型不用
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(7L, StudentFeature.of(v));
        assertTrue(InformationValueExperiment.missingCells(obs).isEmpty(), "ambition 缺失不应进催填清单");

        v[0] = Double.NaN;
        v[9] = Double.NaN;
        obs.put(7L, StudentFeature.of(v));
        List<InformationValueExperiment.Cell> cells = InformationValueExperiment.missingCells(obs);
        assertEquals(2, cells.size());
        assertEquals(0, cells.get(0).cell());
        assertEquals(9, cells.get(1).cell());
        assertEquals(7L, cells.get(0).studentId());
    }

    @Test
    @DisplayName("VOI 排序：对方都答过的那格排在「补了也没用」的那格前面")
    void voiPrefersCellWithKnownCounterparts() {
        // 4 名男生。p1 缺「吸烟」与「入睡」；其余 3 人吸烟已知，但他们的入睡也缺。
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, withMissing(feature(0.3, 0.3, 0.9, 0.5, 0.5, 0.5, 0.4, 0.4, 0.5, 0.5), 2, 0));
        obs.put(2L, withMissing(feature(0.3, 0.3, 0.1, 0.6, 0.5, 0.5, 0.4, 0.4, 0.5, 0.5), 0, 1));
        obs.put(3L, withMissing(feature(0.3, 0.3, 0.2, 0.4, 0.5, 0.5, 0.4, 0.4, 0.5, 0.5), 0, 1));
        obs.put(4L, withMissing(feature(0.3, 0.3, 0.3, 0.7, 0.5, 0.5, 0.4, 0.4, 0.5, 0.5), 0, 1));

        List<Student> students = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            students.add(new Student(id, "s" + id, Gender.MALE, "2024", "计算机", "工科", null, null));
        }
        List<Room> rooms = List.of(Room.of(101L, "A", Gender.MALE, 4));
        ProblemInstance p = new ProblemInstance(students, rooms, obs, Map.of());

        List<InformationValueExperiment.Cell> missing = InformationValueExperiment.missingCells(obs);
        assertEquals(8, missing.size(), "1 号 2 格（吸烟+入睡）+ 2/3/4 号各 2 格（入睡+起床）");

        List<InformationValueExperiment.Cell> ranked = InformationValueExperiment.voiRanking(p, missing, 1);
        assertEquals(1, ranked.size());
        assertEquals(1L, ranked.get(0).studentId());
        assertEquals(2, ranked.get(0).cell(), "p1 的吸烟格应排第一：其余三人这一维都已知");
        // 入睡格补一个人没有用（睡觉得两人四格全已知），期望增益为 0，因此拿不到预算
        assertTrue(InformationValueExperiment.voiRanking(p, missing, missing.size()).stream()
                        .anyMatch(c -> c.cell() == 0 || c.cell() == 1),
                "预算充足时零收益的格子仍然应出现在名单末尾");
        Set<String> uniq = new HashSet<>();
        for (var c : ranked) {
            assertTrue(uniq.add(c.label()), "同一个格不该被选两次");
        }
    }

    @Test
    @DisplayName("补齐一臂：填真值后该格从缺失变成已知")
    void withCellMakesCellKnown() {
        StudentFeature f = allMissingWith(2, Double.NaN);
        assertTrue(Double.isNaN(InformationValueExperiment.cellValue(f, 2)));
        StudentFeature g = InformationValueExperiment.withCell(f, 2, 0.75);
        assertEquals(0.75, InformationValueExperiment.cellValue(g, 2), EPS);
    }

    @Test
    @DisplayName("生成器在缺失率 0 时不产生任何缺失格")
    void generateWithoutMissingHasNoGaps() {
        var ds = InformationValueExperiment.generate(40, 0.0, 3L);
        assertNotNull(ds);
        assertTrue(InformationValueExperiment.missingCells(ds.problem().observations()).isEmpty());
    }

    // ------------------------------------------------------------------ 工具

    private static StudentFeature randomFeature(Random rnd, double missing) {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextDouble();
        }
        for (int cell : InformationValueExperiment.MODEL_CELLS) {
            if (rnd.nextDouble() < missing) {
                v[cell] = Double.NaN;
            }
        }
        return StudentFeature.of(v);
    }

    /** 按「入睡 0.3 / 起床 0.3 / 吸烟 / 整洁 / 外向 / 自习 / 噪声容忍 / 社交 / 共享 / 游戏」构造。 */
    private static StudentFeature feature(double s0, double s1, double smoking, double clean, double extro,
                                         double study, double tol, double social, double share, double gaming) {
        return new StudentFeature(s0, s1, smoking, clean, extro, study, tol, social, share, gaming, 0.5);
    }

    private static StudentFeature allMissing() {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = Double.NaN;
        }
        return StudentFeature.of(v);
    }

    private static StudentFeature allMissingWith(int cell, double value) {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = Double.NaN;
        }
        if (!Double.isNaN(value)) {
            v[cell] = value;
        }
        return StudentFeature.of(v);
    }

    private static StudentFeature withMissing(StudentFeature f, int cellA, int cellB) {
        double[] v = f.toArray();
        v[cellA] = Double.NaN;
        v[cellB] = Double.NaN;
        return StudentFeature.of(v);
    }
}
