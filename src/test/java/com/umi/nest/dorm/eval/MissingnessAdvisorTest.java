package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.config.CompatWeights;
import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.SpecialNeed;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺失感知内核的口径测试。
 *
 * <p>这些断言钉住的是<b>定义</b>，不是某一组数字：可评估性怎么算、哪个格子该排第一、
 * 什么时候必须承认「问不出来」。
 */
class MissingnessAdvisorTest {

    private static final double EPS = 1e-9;

    private static StudentFeature full(double smoking) {
        return StudentFeature.of(new double[]{
                23.5, 8.0, smoking, 0.6, 0.5, 0.4, 0.5, 0.5, 0.5, 0.3, 0.5
        });
    }

    private static StudentFeature allNaN() {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = Double.NaN;
        }
        return StudentFeature.of(v);
    }

    private static StudentFeature withMissing(double smokingOrNaN, int... missingCells) {
        double[] v = new double[]{
                23.5, 8.0, smokingOrNaN, 0.6, 0.5, 0.4, 0.5, 0.5, 0.5, 0.3, 0.5
        };
        for (int c : missingCells) {
            v[c] = Double.NaN;
        }
        return StudentFeature.of(v);
    }

    private static Student student(long id, String grade) {
        return new Student(id, "S" + id, Gender.MALE, grade, "计算机", "工科", "", SpecialNeed.NONE);
    }

    @Test
    @DisplayName("双方都答过：Cov=1、可评估率=1、没有零证据对")
    void coverageFullData() {
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, full(0.2));
        obs.put(2L, full(0.2));
        Map<Long, Long> roomOf = new LinkedHashMap<>();
        roomOf.put(1L, 100L);
        roomOf.put(2L, 100L);

        MissingnessAdvisor.Coverage c = MissingnessAdvisor.coverage(obs, roomOf, null);
        assertEquals(1, c.pairs());
        assertEquals(0, c.pairsWithoutEvidence());
        assertEquals(1.0, c.cov(), EPS);
        assertEquals(1.0, c.evalRatio(), EPS);
        assertEquals(0, c.missingCells());
        assertTrue(c.evaluable());
    }

    @Test
    @DisplayName("谁都没答：Cov=0、可评估率=0、且不能把分数当结论")
    void coverageNoEvidence() {
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, allNaN());
        obs.put(2L, allNaN());
        Map<Long, Long> roomOf = new LinkedHashMap<>();
        roomOf.put(1L, 100L);
        roomOf.put(2L, 100L);

        MissingnessAdvisor.Coverage c = MissingnessAdvisor.coverage(obs, roomOf, null);
        assertEquals(1, c.pairs());
        assertEquals(1, c.pairsWithoutEvidence());
        assertEquals(0.0, c.cov(), EPS);
        assertEquals(0.0, c.evalRatio(), EPS);
        assertFalse(c.evaluable());
        assertFalse(c.reasons().isEmpty());
    }

    @Test
    @DisplayName("作息要两个格子都已知才算可比，只答一半不加分")
    void coverageSleepNeedsBothCells() {
        // 只有入睡时刻已知（格子 1 缺失），其余软特征全缺
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, withMissing(Double.NaN, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        obs.put(2L, withMissing(Double.NaN, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        Map<Long, Long> roomOf = new LinkedHashMap<>();
        roomOf.put(1L, 100L);
        roomOf.put(2L, 100L);

        MissingnessAdvisor.Coverage c = MissingnessAdvisor.coverage(obs, roomOf, null);
        assertEquals(0.0, c.cov(), EPS);
        assertEquals(0, c.pairs() - c.pairsWithoutEvidence());

        // 把起床时刻补上，作息这一维就成立，Cov 应等于作息权重
        obs.put(1L, full(0.2));
        obs.put(2L, full(0.2));
        // 再退一步：只让作息可比、其它维度全缺
        obs.put(1L, withMissing(0.2, 2, 3, 4, 5, 6, 7, 8, 9));
        obs.put(2L, withMissing(0.2, 2, 3, 4, 5, 6, 7, 8, 9));
        MissingnessAdvisor.Coverage sleepOnly = MissingnessAdvisor.coverage(obs, roomOf, null);
        assertEquals(0.20, sleepOnly.cov(), 1e-6);
    }

    @Test
    @DisplayName("催填排序：把「别人都答过、只有我缺」的格子排在最前面")
    void rankPrefersCellOthersAnswered() {
        List<Student> students = new ArrayList<>();
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        for (long id = 1; id <= 4; id++) {
            students.add(student(id, "2024"));
            // 1 号只缺吸烟；2~4 号全答
            obs.put(id, id == 1 ? withMissing(Double.NaN, 2) : full(0.2));
        }
        Map<Long, String> nos = new LinkedHashMap<>();
        nos.put(1L, "2024001");

        MissingnessAdvisor.Report r = MissingnessAdvisor.rank(students, nos, obs, null,
                10, 24, 4, 200_000L);
        assertFalse(r.top().isEmpty());
        MissingnessAdvisor.PriorityRow first = r.top().get(0);
        assertEquals(1L, first.studentId());
        assertEquals(2, first.cell());
        assertEquals("吸烟倾向", first.cellLabel());
        assertTrue(first.delta() > 0.05, "补齐后应预计有可观的相容度增益，实际 " + first.delta());
        assertTrue(first.knownCounterparts() > 0);
        assertEquals(1, r.missingCells());
    }

    @Test
    @DisplayName("没人答过这一题时增益只能是 0，不编造收益")
    void rankNoCounterpartMeansZero() {
        List<Student> students = new ArrayList<>();
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        for (long id = 1; id <= 3; id++) {
            students.add(student(id, "2024"));
            obs.put(id, withMissing(Double.NaN, 2));
        }
        MissingnessAdvisor.Report r = MissingnessAdvisor.rank(students, null, obs, null,
                10, 24, 4, 200_000L);
        assertFalse(r.top().isEmpty());
        for (MissingnessAdvisor.PriorityRow row : r.top()) {
            assertEquals(0.0, row.delta(), EPS);
        }
    }

    @Test
    @DisplayName("单题缺失统计与格子下标一一对应")
    void cellTallies() {
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, withMissing(Double.NaN, 2));
        obs.put(2L, full(0.2));
        obs.put(3L, full(0.7));

        List<MissingnessAdvisor.CellTally> tallies = MissingnessAdvisor.cellTallies(obs);
        assertEquals(10, tallies.size());
        assertEquals(1, tallies.get(2).missing());
        assertEquals(3, tallies.get(2).students());
        assertEquals(0, tallies.get(0).missing());
    }

    @Test
    @DisplayName("维度权重与生产模型一致（同源，不另立一套）")
    void dimWeightsMatchProduction() {
        double[] w = MissingnessAdvisor.dimWeights(CompatWeights.defaults());
        assertEquals(8, w.length);
        assertEquals(0.20, w[0], 1e-9);
        double sum = 0;
        for (double v : w) {
            sum += v;
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    @DisplayName("评估次数上限必须生效：宁可截断也不让页面卡住")
    void truncatesAtBudget() {
        List<Student> students = new ArrayList<>();
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        for (long id = 1; id <= 8; id++) {
            students.add(student(id, "2024"));
            // 1 号大面积缺答、2~8 号全答：保证真的会产生评估，才测得到预算截断
            obs.put(id, id == 1
                    ? withMissing(Double.NaN, 2, 3, 4, 5, 6, 7, 8, 9)
                    : full(0.2));
        }
        MissingnessAdvisor.Report r = MissingnessAdvisor.rank(students, null, obs, null,
                10, 24, 4, 20L);
        assertTrue(r.truncated());
        assertTrue(r.evaluations() <= 20);
    }

    @Test
    @DisplayName("没有房间就不会算出可评估性，也不假装达标")
    void coverageWithoutAssignment() {
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, full(0.2));
        MissingnessAdvisor.Coverage c = MissingnessAdvisor.coverage(obs, Map.of(), null);
        assertEquals(0, c.pairs());
        assertEquals(0.0, c.evalRatio(), EPS);
        assertFalse(c.evaluable());
        assertTrue(c.reasons().get(0).contains("还没有分配结果"));
    }
}
