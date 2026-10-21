package com.umi.nest.dorm.service;

import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.repo.DormRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺失感知服务的接口形状测试：这一层只做取数 → 调算法 → 拼形状，
 * 所以这里钉死"批次回退、limit 钳制、无批次时不报错"这几件容易回归的事。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MissingnessServiceTest {

    private static final double NAN = Double.NaN;

    @Mock
    private DormRepository repo;

    private MissingnessService service;

    private List<Student> students;
    private Map<Long, String> studentNoOf;
    private Map<Long, StudentFeature> obs;

    @BeforeEach
    void setUp() {
        service = new MissingnessService(repo);
        students = List.of(
                stu(1L, "张三"), stu(2L, "李四"), stu(3L, "王五"), stu(4L, "赵六"));
        studentNoOf = new LinkedHashMap<>();
        studentNoOf.put(1L, "S001");
        studentNoOf.put(2L, "S002");
        studentNoOf.put(3L, "S003");
        studentNoOf.put(4L, "S004");
        obs = new LinkedHashMap<>();
        // 1 号只缺"吸烟倾向"（cell=2），2~4 号答齐：这样既产生可评估关系，也有可建议的格子
        obs.put(1L, feature(NAN, 2));
        obs.put(2L, feature(0.1));
        obs.put(3L, feature(0.6));
        obs.put(4L, feature(0.9));
    }

    // ---------------------------------------------------------------- summary

    @Test
    @DisplayName("summary：不传批次时回退到最近一次分配，并带出批次名")
    void summaryFallsBackToLatestBatch() {
        when(repo.loadProblem()).thenReturn(loaded());
        when(repo.latestBatchId()).thenReturn(7L);
        when(repo.roomOfBatch(7L)).thenReturn(Map.of(1L, 100L, 2L, 100L, 3L, 101L, 4L, 101L));
        when(repo.getBatch(7L)).thenReturn(new LinkedHashMap<>(Map.of("name", "API smoke 1000")));

        Map<String, Object> out = service.summary(null);

        assertEquals(7L, out.get("batchId"));
        assertEquals("API smoke 1000", out.get("batchName"));
        assertEquals(4, out.get("students"));
        assertEquals(40, out.get("totalCells"));
        assertEquals(1, out.get("missingCells"));
        assertEquals(2, out.get("pairs"));
        assertNotNull(out.get("cov"));
        assertNotNull(out.get("evalRatio"));
        assertNotNull(out.get("byCell"));
        assertEquals(10, ((List<?>) out.get("byCell")).size());
        assertEquals("missingness-aware-v1", out.get("model"));
        Map<?, ?> thresholds = (Map<?, ?>) out.get("thresholds");
        assertEquals(0.15, thresholds.get("covMin"));
        assertEquals(0.50, thresholds.get("evalMin"));
        verify(repo).roomOfBatch(7L);
    }

    @Test
    @DisplayName("summary：显式批次号优先，不再去问最近批次")
    void summaryUsesExplicitBatch() {
        when(repo.loadProblem()).thenReturn(loaded());
        when(repo.roomOfBatch(3L)).thenReturn(Map.of(1L, 100L, 2L, 100L, 3L, 101L, 4L, 101L));
        when(repo.getBatch(3L)).thenReturn(new LinkedHashMap<>(Map.of("name", "第三批")));

        Map<String, Object> out = service.summary(3L);

        assertEquals(3L, out.get("batchId"));
        assertEquals("第三批", out.get("batchName"));
        verify(repo).roomOfBatch(3L);
        verify(repo, never()).latestBatchId();
    }

    @Test
    @DisplayName("summary：一次都没分配过时不能报错，只说明'还没有分配结果'")
    void summaryWithoutAnyBatch() {
        when(repo.loadProblem()).thenReturn(loaded());
        when(repo.latestBatchId()).thenReturn(null);

        Map<String, Object> out = service.summary(null);

        assertNull(out.get("batchId"));
        assertNull(out.get("batchName"));
        assertEquals(0, out.get("pairs"));
        assertEquals(false, out.get("evaluable"));
        List<?> reasons = (List<?>) out.get("reasons");
        assertFalse(reasons.isEmpty());
        assertTrue(String.valueOf(reasons.get(0)).contains("还没有分配结果"),
                "reasons 应说明还没分配过，实际=" + reasons);
        verify(repo, never()).roomOfBatch(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("summary：批次行里没有 name 字段时 batchName 为 null，而不是 'null' 字符串")
    void summaryToleratesBatchRowWithoutName() {
        when(repo.loadProblem()).thenReturn(loaded());
        when(repo.latestBatchId()).thenReturn(9L);
        when(repo.roomOfBatch(9L)).thenReturn(Map.of(1L, 100L, 2L, 100L));
        when(repo.getBatch(9L)).thenReturn(new LinkedHashMap<>(Map.of("id", 9)));

        Map<String, Object> out = service.summary(null);

        assertNull(out.get("batchName"));
    }

    // --------------------------------------------------------------- priority

    @Test
    @DisplayName("priority：limit 为 null / 0 / 999 时分别取默认 20、下限 1、上限 100")
    void priorityClampsLimit() {
        when(repo.loadProblem()).thenReturn(loaded());

        assertEquals(20, service.priority(null).get("limit"));
        assertEquals(1, service.priority(0).get("limit"));
        assertEquals(1, service.priority(-5).get("limit"));
        assertEquals(100, service.priority(999).get("limit"));
        assertEquals(7, service.priority(7).get("limit"));
    }

    @Test
    @DisplayName("priority：会给出'先问谁问哪题'的行，且吸烟是唯一被建议的格子")
    void priorityReturnsActionableRows() {
        when(repo.loadProblem()).thenReturn(loaded());

        Map<String, Object> out = service.priority(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");

        assertFalse(rows.isEmpty(), "1 号缺吸烟、另三人有已知对照，应该给出建议");
        Map<String, Object> top = rows.get(0);
        assertEquals(1, top.get("rank"));
        assertEquals(1L, top.get("studentId"));
        assertEquals("S001", top.get("studentNo"));
        assertEquals("张三", top.get("name"));
        assertEquals(2, top.get("cell"));
        assertEquals("吸烟倾向", top.get("cellLabel"));
        assertTrue(((Number) top.get("delta")).doubleValue() > 0.0,
                "有已知对照时期望增益应大于 0，实际=" + top.get("delta"));
        assertTrue(((Number) top.get("knownCounterparts")).intValue() >= 3);
        assertEquals(1, out.get("missingCells"));
        assertEquals("missingness-aware-v1", out.get("model"));
        assertEquals(MissingnessService.MAX_EVALUATIONS, out.get("budget"));
    }

    @Test
    @DisplayName("priority：全员都答齐时返回空表，而不是编造收益")
    void priorityEmptyWhenNothingMissing() {
        obs.clear();
        obs.put(1L, feature(0.2));
        obs.put(2L, feature(0.3));
        obs.put(3L, feature(0.4));
        obs.put(4L, feature(0.5));
        when(repo.loadProblem()).thenReturn(loaded());

        Map<String, Object> out = service.priority(20);

        assertEquals(0, out.get("missingCells"));
        assertTrue(((List<?>) out.get("rows")).isEmpty());
        assertEquals(0, out.get("scored"));
    }

    // ---------------------------------------------------------------- fixture

    private DormRepository.LoadedData loaded() {
        ProblemInstance problem = new ProblemInstance(students,
                List.of(Room.of(100L, "A", "B1", Gender.MALE, 2),
                        Room.of(101L, "B", "B1", Gender.MALE, 2)),
                Map.copyOf(obs), Map.of());
        return new DormRepository.LoadedData(problem, studentNoOf);
    }

    private static Student stu(long id, String name) {
        return new Student(id, name, Gender.MALE, "2024", "软件工程", "计算机类", null, null);
    }

    private static StudentFeature feature(double smoking) {
        return feature(smoking, -1);
    }

    /**
     * @param missingCell 置为 NaN 的格子下标；-1 表示不缺
     */
    private static StudentFeature feature(double smoking, int missingCell) {
        double[] v = {23.5, 7.5, smoking, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        if (missingCell >= 0) {
            v[missingCell] = NAN;
        }
        return StudentFeature.of(v);
    }
}
