package com.umi.nest.dorm.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.eval.EvaluationMetrics;
import com.umi.nest.dorm.explain.Explanation;
import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.SpecialNeed;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.solver.SolveResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿舍域数据访问层。
 *
 * <p>这里刻意用 {@link JdbcTemplate} 而不是 MyBatis XML：教学域那批 mapper 是既有的、
 * 有人维护的；宿舍域是一次性大批量导入 + 复杂查询交织，SQL 直接写在这里比散到 XML 里
 * 更好核对"到底查了什么"。两套并存不冲突。
 *
 * <p>重要约定：<b>可空的三列（特征 double）用 SQL NULL 表示缺失</b>，
 * 读出来还原成 {@link Double#NaN}。绝不能用 0 顶替缺失——"没回答"和"打 0 分"是两回事。
 */
@Repository
public class DormRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DormRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ==================================================================
    // 概览 / 清空
    // ==================================================================

    public Map<String, Object> dataSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", count("dorm_student"));
        out.put("rooms", count("dorm_room"));
        out.put("capacityTotal", jdbc.queryForObject(
                "SELECT COALESCE(SUM(capacity),0) FROM dorm_room", Integer.class));
        out.put("preferences", count("dorm_preference"));
        out.put("features", count("dorm_student_feature"));
        out.put("batches", count("dorm_batch"));
        out.put("byGender", rows("SELECT gender AS k, COUNT(*) AS v FROM dorm_student GROUP BY gender"));
        out.put("byCluster", rows("SELECT major_cluster AS k, COUNT(*) AS v FROM dorm_student GROUP BY major_cluster"));
        out.put("byGrade", rows("SELECT grade AS k, COUNT(*) AS v FROM dorm_student GROUP BY grade"));
        out.put("roomsByPool", rows("SELECT CONCAT(gender, IF(for_special_needs=1,'|S','|N')) AS k, "
                + "COUNT(*) AS v, SUM(capacity) AS beds FROM dorm_room GROUP BY k"));
        out.put("specialNeeds", rows("SELECT special_need AS k, COUNT(*) AS v FROM dorm_student "
                + "WHERE special_need <> 'NONE' GROUP BY special_need"));
        return out;
    }

    public void clearDormData() {
        // 顺序：先子后父，避免外键报错（batch 的子表都是 ON DELETE CASCADE，这里显式删更直观）
        for (String t : List.of("dorm_explanation", "dorm_assignment", "dorm_appeal",
                "dorm_audit", "dorm_ground_truth", "dorm_batch",
                "dorm_preference", "dorm_student_feature", "dorm_student", "dorm_room")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ==================================================================
    // 导入
    // ==================================================================

    /** 一行导入数据：学生基本信息 + 可空特征。 */
    public record StudentRow(String studentNo, String name, String gender, String grade, String major,
                             String majorCluster, String buildingPref, String specialNeed,
                             double[] feature) {
    }

    /**
     * 导入学生（按学号 upsert）。特征数组允许为 null（= 全部缺失）。
     *
     * @return 学生学号 → 数据库 id
     */
    public Map<String, Long> importStudents(List<StudentRow> rows) {
        Map<String, Long> idOf = new HashMap<>();
        for (StudentRow r : rows) {
            Long id = jdbc.query("SELECT id FROM dorm_student WHERE student_no = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, r.studentNo());
            if (id == null) {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO dorm_student(student_no,name,gender,grade,major,major_cluster,building_pref,special_need)"
                                    + " VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, r.studentNo());
                    ps.setString(2, nz(r.name()));
                    ps.setString(3, r.gender());
                    ps.setString(4, nz(r.grade()));
                    ps.setString(5, nz(r.major()));
                    ps.setString(6, nz(r.majorCluster()));
                    ps.setString(7, nz(r.buildingPref()));
                    ps.setString(8, nz(r.specialNeed()).isEmpty() ? "NONE" : r.specialNeed());
                    return ps;
                }, kh);
                id = kh.getKey().longValue();
            } else {
                jdbc.update("UPDATE dorm_student SET name=?,gender=?,grade=?,major=?,major_cluster=?,"
                                + "building_pref=?,special_need=? WHERE id=?",
                        nz(r.name()), r.gender(), nz(r.grade()), nz(r.major()), nz(r.majorCluster()),
                        nz(r.buildingPref()), nz(r.specialNeed()).isEmpty() ? "NONE" : r.specialNeed(), id);
            }
            idOf.put(r.studentNo(), id);
            if (r.feature() != null) {
                upsertFeature(id, r.feature());
            }
        }
        return idOf;
    }

    public void upsertFeature(long studentId, double[] f) {
        if (f == null || f.length < StudentFeature.DIM) {
            return;
        }
        jdbc.update("INSERT INTO dorm_student_feature(student_id,sleep_start,sleep_end,smoking,cleanliness,"
                        + "extroversion,study_in_room,noise_tolerance,social_activity,share_items,gaming,ambition)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE sleep_start=VALUES(sleep_start), sleep_end=VALUES(sleep_end),"
                        + " smoking=VALUES(smoking), cleanliness=VALUES(cleanliness), extroversion=VALUES(extroversion),"
                        + " study_in_room=VALUES(study_in_room), noise_tolerance=VALUES(noise_tolerance),"
                        + " social_activity=VALUES(social_activity), share_items=VALUES(share_items),"
                        + " gaming=VALUES(gaming), ambition=VALUES(ambition)",
                // 顺序必须与列顺序严格一致：student_id 在第一位，漏掉就会把"入睡时刻"写进主键，
                // 报出来的是外键报错，真正的原因（参数错位）要顺着占位符一个个数才看得见。
                studentId, box(f[0]), box(f[1]), box(f[2]), box(f[3]), box(f[4]), box(f[5]), box(f[6]),
                box(f[7]), box(f[8]), box(f[9]), box(f[10]));
    }

    /** 导入房间（按楼栋+房号 upsert）。 */
    public Map<String, Long> importRooms(List<Room> rooms) {
        Map<String, Long> idOf = new HashMap<>();
        for (Room r : rooms) {
            String key = r.buildingId() + "/" + (r.roomNo() == null ? String.valueOf(r.id()) : r.roomNo());
            Long id = jdbc.query("SELECT id FROM dorm_room WHERE building_id=? AND room_no=?",
                    rs -> rs.next() ? rs.getLong(1) : null, r.buildingId(), key.substring(key.indexOf('/') + 1));
            if (id == null) {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO dorm_room(room_no,building_id,gender,capacity,for_special_needs,grade_restriction)"
                                    + " VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, key.substring(key.indexOf('/') + 1));
                    ps.setString(2, r.buildingId());
                    ps.setString(3, r.gender().code());
                    ps.setInt(4, r.capacity());
                    ps.setBoolean(5, r.forSpecialNeeds());
                    ps.setString(6, r.gradeRestriction() == null ? "" : r.gradeRestriction());
                    return ps;
                }, kh);
                id = kh.getKey().longValue();
            }
            idOf.put(key, id);
        }
        return idOf;
    }

    /**
     * 写入志愿。**按「学生 + 种类」替换**，既不是全表替换，也不是按学生整体替换。
     *
     * <p>早先这里是先 {@code DELETE FROM dorm_preference} 再插：导入一批学生就把全库的志愿
     * 清空，包括其他学生在门户里自己填的那些。改成「按学生删」之后还剩一个口子——学校的名单
     * 里通常只有楼栋意愿、没有室友意愿，按学生整体替换会把这名同学自己填的室友志愿一起删掉。
     *
     * <p>所以粒度落到「种类」：{@code ROOMMATE} / {@code AVOID} / {@code BUILDING} 三者独立，
     * 传了哪一种才替换哪一种，没传的保持原样。要整体清空走 {@code replaceAll=true} 的全量重置，
     * 不靠导入来清。
     */
    public void importPreferences(Map<Long, Preference> prefs) {
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<Long, Preference> e : prefs.entrySet()) {
            long sid = e.getKey();
            Preference p = e.getValue();
            if (!p.preferredRoommates().isEmpty()) {
                jdbc.update("DELETE FROM dorm_preference WHERE student_id=? AND kind='ROOMMATE'", sid);
                for (Long t : p.preferredRoommates()) {
                    batch.add(new Object[]{sid, "ROOMMATE", t, null});
                }
            }
            if (!p.avoidRoommates().isEmpty()) {
                jdbc.update("DELETE FROM dorm_preference WHERE student_id=? AND kind='AVOID'", sid);
                for (Long t : p.avoidRoommates()) {
                    batch.add(new Object[]{sid, "AVOID", t, null});
                }
            }
            if (!p.preferredBuildings().isEmpty()) {
                jdbc.update("DELETE FROM dorm_preference WHERE student_id=? AND kind='BUILDING'", sid);
                for (String b : p.preferredBuildings()) {
                    batch.add(new Object[]{sid, "BUILDING", null, b});
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO dorm_preference(student_id,kind,target_student,building_id) VALUES(?,?,?,?)",
                    batch);
        }
    }

    // ==================================================================
    // 学生志愿（学生自助填报）
    //
    // 与上面 importPreferences 的分工要写清楚：那个是学校批量导入，只覆盖「请求里真的带
    // 了志愿」的学生（没带志愿的行一律不动，见 DormService.importData 的注释）；这个是学生
    // 自己在门户上改，只动他自己那几行。所以学校每学期重新导入名单**不会**再抹掉学生自填
    // 的志愿——唯一会全清的入口是 replaceAll=true 的全量重置。
    // ==================================================================

    /** 读一个学生的志愿。 */
    public Preference preferenceOf(long studentId) {
        List<Long> want = new ArrayList<>();
        List<Long> avoid = new ArrayList<>();
        List<String> buildings = new ArrayList<>();
        jdbc.query("SELECT kind,target_student,building_id FROM dorm_preference"
                + " WHERE student_id=? ORDER BY id", rs -> {
            String kind = nz(rs.getString("kind"));
            if ("ROOMMATE".equals(kind)) {
                long t = rs.getLong("target_student");
                if (!rs.wasNull()) {
                    want.add(t);
                }
            } else if ("AVOID".equals(kind)) {
                long t = rs.getLong("target_student");
                if (!rs.wasNull()) {
                    avoid.add(t);
                }
            } else if ("BUILDING".equals(kind)) {
                String b = rs.getString("building_id");
                if (b != null && !b.isBlank()) {
                    buildings.add(b);
                }
            }
        }, studentId);
        return new Preference(studentId, want, avoid, buildings);
    }

    /**
     * 覆盖式保存一个学生的志愿。
     *
     * <p>加事务是因为这是「先删后插」两步：中途失败会把这个学生的志愿清空成"什么都没填"，
     * 而"什么都没填"和"填了但没存上"在结果上无法区分——后者会让学生以为自己的志愿生效了。
     */
    @Transactional
    public void savePreferenceOf(long studentId, List<Long> wantRoommateIds,
                                 List<Long> avoidRoommateIds, List<String> buildings) {
        jdbc.update("DELETE FROM dorm_preference WHERE student_id=?", studentId);
        List<Object[]> batch = new ArrayList<>();
        if (wantRoommateIds != null) {
            for (Long t : wantRoommateIds) {
                batch.add(new Object[]{studentId, "ROOMMATE", t, null});
            }
        }
        if (avoidRoommateIds != null) {
            for (Long t : avoidRoommateIds) {
                batch.add(new Object[]{studentId, "AVOID", t, null});
            }
        }
        if (buildings != null) {
            for (String b : buildings) {
                batch.add(new Object[]{studentId, "BUILDING", null, b});
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO dorm_preference(student_id,kind,target_student,building_id) VALUES(?,?,?,?)",
                    batch);
        }
    }

    /** 现有楼栋清单（去重），给学生端做楼栋志愿的选项。 */
    public List<String> listBuildings() {
        List<String> out = new ArrayList<>();
        jdbc.query("SELECT DISTINCT building_id FROM dorm_room"
                + " WHERE building_id IS NOT NULL AND building_id<>'' ORDER BY building_id",
                rs -> {
                    out.add(rs.getString(1));
                });
        return out;
    }

    /** 现有房间的最大容量，用来给学生解释「为什么最多只能填这么多个想同住的人」。 */
    public int maxRoomCapacity() {
        Integer v = jdbc.query("SELECT MAX(capacity) AS m FROM dorm_room", rs -> {
            if (!rs.next()) {
                return null;
            }
            int x = rs.getInt("m");
            return rs.wasNull() ? null : x;
        });
        return v == null ? 4 : v;
    }

    /**
     * 按学号或姓名找同学。
     *
     * <p>只选出学生端的公开字段。调用方负责限制关键字长度与返回条数——
     * 空关键字直接查会返回整张名单，等于把宿舍名册做成一个可翻页的接口。
     */
    public List<Map<String, Object>> searchStudents(String keyword, int limit) {
        String like = "%" + keyword + "%";
        return jdbc.queryForList("SELECT id,student_no,name,gender,grade,major,major_cluster,special_need"
                + " FROM dorm_student WHERE student_no LIKE ? OR name LIKE ?"
                + " ORDER BY student_no LIMIT ?", like, like, limit);
    }

    /** 按 id 批量取学生的公开字段（把志愿里的 id 还原成人能看懂的名字）。 */
    public List<Map<String, Object>> studentsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForList("SELECT id,student_no,name,gender,grade,major,major_cluster,special_need"
                + " FROM dorm_student WHERE id IN (" + in + ") ORDER BY id", ids.toArray());
    }

    /**
     * 谁把我填进了「想同住」。
     *
     * <p><b>调用方必须只在「我已经把对方填进想同住」的语境里使用它</b>，
     * 用来把"单向志愿"标成"双向志愿"。绝不能拿它渲染一份「谁想跟我住」的完整名单：
     * 那等于把全校人际关系做成一张可查询的表，而且是不对称的
     * （看不到谁讨厌我，却看得到谁想跟我住），学生会立刻学会拿它做人情排名。
     */
    public List<Long> studentsWhoWantMe(long studentId) {
        List<Long> out = new ArrayList<>();
        jdbc.query("SELECT student_id FROM dorm_preference WHERE kind='ROOMMATE' AND target_student=?",
                rs -> {
                    out.add(rs.getLong(1));
                }, studentId);
        return out;
    }

    // ==================================================================
    // 读出 → 模型
    // ==================================================================

    public record LoadedData(ProblemInstance problem, Map<Long, String> studentNoOf) {
    }

    public LoadedData loadProblem() {
        Map<Long, String> studentNoOf = new LinkedHashMap<>();
        List<Student> students = new ArrayList<>();
        jdbc.query("SELECT id,student_no,name,gender,grade,major,major_cluster,building_pref,special_need"
                + " FROM dorm_student ORDER BY id", rs -> {
            long id = rs.getLong("id");
            studentNoOf.put(id, rs.getString("student_no"));
            students.add(new Student(id,
                    rs.getString("name"),
                    Gender.parse(rs.getString("gender")),
                    rs.getString("grade"),
                    rs.getString("major"),
                    rs.getString("major_cluster"),
                    rs.getString("building_pref"),
                    SpecialNeed.valueOf(nz(rs.getString("special_need")).isEmpty()
                            ? "NONE" : rs.getString("special_need"))));
        });

        Map<Long, StudentFeature> feats = new HashMap<>();
        jdbc.query("SELECT student_id,sleep_start,sleep_end,smoking,cleanliness,extroversion,study_in_room,"
                + "noise_tolerance,social_activity,share_items,gaming,ambition FROM dorm_student_feature", rs -> {
            double[] v = new double[StudentFeature.DIM];
            v[0] = unbox(rs.getObject("sleep_start"));
            v[1] = unbox(rs.getObject("sleep_end"));
            v[2] = unbox(rs.getObject("smoking"));
            v[3] = unbox(rs.getObject("cleanliness"));
            v[4] = unbox(rs.getObject("extroversion"));
            v[5] = unbox(rs.getObject("study_in_room"));
            v[6] = unbox(rs.getObject("noise_tolerance"));
            v[7] = unbox(rs.getObject("social_activity"));
            v[8] = unbox(rs.getObject("share_items"));
            v[9] = unbox(rs.getObject("gaming"));
            v[10] = unbox(rs.getObject("ambition"));
            feats.put(rs.getLong("student_id"), StudentFeature.of(v));
        });

        Map<Long, List<Long>> want = new HashMap<>();
        Map<Long, List<Long>> avoid = new HashMap<>();
        Map<Long, List<String>> buildings = new HashMap<>();
        jdbc.query("SELECT student_id,kind,target_student,building_id FROM dorm_preference", rs -> {
            long sid = rs.getLong("student_id");
            String kind = rs.getString("kind");
            if ("ROOMMATE".equals(kind)) {
                want.computeIfAbsent(sid, k -> new ArrayList<>()).add(rs.getLong("target_student"));
            } else if ("AVOID".equals(kind)) {
                avoid.computeIfAbsent(sid, k -> new ArrayList<>()).add(rs.getLong("target_student"));
            } else if ("BUILDING".equals(kind)) {
                buildings.computeIfAbsent(sid, k -> new ArrayList<>()).add(rs.getString("building_id"));
            }
        });
        Map<Long, Preference> prefs = new HashMap<>();
        for (Student s : students) {
            prefs.put(s.id(), new Preference(s.id(),
                    want.getOrDefault(s.id(), List.of()),
                    avoid.getOrDefault(s.id(), List.of()),
                    buildings.getOrDefault(s.id(), List.of())));
        }

        List<Room> rooms = new ArrayList<>();
        jdbc.query("SELECT id,room_no,building_id,gender,capacity,for_special_needs,grade_restriction"
                + " FROM dorm_room ORDER BY id", rs -> {
            rooms.add(new Room(rs.getLong("id"), rs.getString("room_no"), rs.getString("building_id"),
                    Gender.parse(rs.getString("gender")), rs.getInt("capacity"),
                    rs.getBoolean("for_special_needs"), nz(rs.getString("grade_restriction")).isEmpty()
                    ? null : rs.getString("grade_restriction")));
        });

        ProblemInstance problem = new ProblemInstance(students, rooms, feats, prefs);
        return new LoadedData(problem, studentNoOf);
    }

    // ==================================================================
    // 批次
    // ==================================================================

    public long createBatch(String name, SolveResult result, ProblemInstance problem, String configJson) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dorm_batch(name,solver,status,config_json,student_count,room_count,capacity_total,"
                            + "hard_violations,unassigned_count,block_count,max_block_size,diagnostics_json)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, nz(name));
            ps.setString(2, result.solver());
            ps.setString(3, "DONE");
            ps.setString(4, truncate(configJson, 16_000_000));
            ps.setInt(5, problem.studentCount());
            ps.setInt(6, problem.rooms().size());
            ps.setInt(7, problem.totalCapacity());
            ps.setInt(8, result.hardViolationCount());
            ps.setInt(9, result.unassignedCount());
            ps.setInt(10, result.blockingPairCount());
            ps.setInt(11, result.maxBlockSize());
            ps.setString(12, json(result.diagnostics()));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void finishBatch(long batchId, EvaluationMetrics m, SolveResult result) {
        jdbc.update("UPDATE dorm_batch SET status='DONE', assigned_count=?, unassigned_count=?, hard_violations=?,"
                        + " mean_compat=?, worst_compat=?, room_variance=?, blocking_pairs=?, preference_rate=?,"
                        + " fairness_major=?, fairness_grade=?, runtime_ms=?, finished_at=NOW(), solver=?"
                        + " WHERE id=?",
                m.students() - m.unassigned(), m.unassigned(), m.hardViolations(),
                m.meanCompat(), m.worstCompat(), m.meanRoomVariance(), m.blockingPairs(), m.preferenceSatisfaction(),
                m.fairnessSpreadMajor(), m.fairnessSpreadGrade(), m.runtimeMs(), result.solver(), batchId);
    }

    public void saveAssignments(long batchId, ProblemInstance problem, Solution sol) {
        List<Object[]> batch = new ArrayList<>();
        Map<Long, Room> rooms = problem.roomById();
        Map<Long, Student> students = problem.byId();
        for (Student s : problem.students()) {
            long roomId = sol.roomOf(s.id());
            Room r = roomId == -1 ? null : rooms.get(roomId);
            batch.add(new Object[]{batchId, s.id(), roomId == -1 ? null : roomId,
                    r == null ? "" : r.label(), r == null ? "" : r.buildingId()});
        }
        if (students.isEmpty() || batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dorm_assignment(batch_id,student_id,room_id,room_label,building_id)"
                + " VALUES(?,?,?,?,?)", batch);
    }

    /** 解释批量落库：分配完成后一次性写好，查询时只读。 */
    public void saveExplanations(long batchId, Map<Long, Explanation> explanations) {
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<Long, Explanation> e : explanations.entrySet()) {
            batch.add(new Object[]{batchId, e.getKey(), e.getValue().roomId() == -1 ? null : e.getValue().roomId(),
                    json(e.getValue())});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dorm_explanation(batch_id,student_id,room_id,payload_json) VALUES(?,?,?,?)",
                batch);
    }

    public void saveAudit(long batchId, String metric, Map<String, double[]> groups) {
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<String, double[]> e : groups.entrySet()) {
            batch.add(new Object[]{batchId, metric, e.getKey(), (int) e.getValue()[0], e.getValue()[1]});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dorm_audit(batch_id,metric,group_key,group_size,value) VALUES(?,?,?,?,?)", batch);
    }

    /**
     * 写入隐真值。注意这里的语义：真值特征 z_i 存进 {@code dorm_ground_truth}，
     * 而"发问卷观测到的 o_i"存在 {@code dorm_student_feature}。求解器只读后者。
     */
    public void saveGroundTruth(Map<Long, StudentFeature> trueFeatures, Map<Long, Double> latent,
                                Map<Long, Double> smokeTol, String datasetTag) {
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<Long, StudentFeature> e : trueFeatures.entrySet()) {
            double[] v = e.getValue().toArray();
            batch.add(new Object[]{e.getKey(),
                    box(v[0]), box(v[1]), box(v[2]), box(v[3]), box(v[4]), box(v[5]),
                    box(v[6]), box(v[7]), box(v[8]), box(v[9]), box(v[10]),
                    latent.get(e.getKey()), smokeTol.get(e.getKey()), nz(datasetTag)});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO dorm_ground_truth(student_id,true_sleep_start,true_sleep_end,true_smoking,"
                + "true_cleanliness,true_extroversion,true_study,true_noise_tol,true_social,true_sharing,"
                + "true_gaming,true_ambition,latent_affinity,smoke_tolerance,dataset_tag)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE true_sleep_start=VALUES(true_sleep_start), true_sleep_end=VALUES(true_sleep_end),"
                + " true_smoking=VALUES(true_smoking), true_cleanliness=VALUES(true_cleanliness),"
                + " true_extroversion=VALUES(true_extroversion), true_study=VALUES(true_study),"
                + " true_noise_tol=VALUES(true_noise_tol), true_social=VALUES(true_social),"
                + " true_sharing=VALUES(true_sharing), true_gaming=VALUES(true_gaming),"
                + " true_ambition=VALUES(true_ambition), latent_affinity=VALUES(latent_affinity),"
                + " smoke_tolerance=VALUES(smoke_tolerance), dataset_tag=VALUES(dataset_tag)", batch);
    }

    public boolean hasGroundTruth() {
        return count("dorm_ground_truth") > 0;
    }

    /** 读出隐真值；没有（= 真实学生数据）时返回 null。 */
    public GroundTruth loadGroundTruth() {
        Map<Long, StudentFeature> feats = new HashMap<>();
        Map<Long, Double> latent = new HashMap<>();
        Map<Long, Double> smoke = new HashMap<>();
        jdbc.query("SELECT student_id,true_sleep_start,true_sleep_end,true_smoking,true_cleanliness,"
                + "true_extroversion,true_study,true_noise_tol,true_social,true_sharing,true_gaming,"
                + "true_ambition,latent_affinity,smoke_tolerance FROM dorm_ground_truth", rs -> {
            long id = rs.getLong("student_id");
            double[] v = new double[StudentFeature.DIM];
            v[0] = unbox(rs.getObject("true_sleep_start"));
            v[1] = unbox(rs.getObject("true_sleep_end"));
            v[2] = unbox(rs.getObject("true_smoking"));
            v[3] = unbox(rs.getObject("true_cleanliness"));
            v[4] = unbox(rs.getObject("true_extroversion"));
            v[5] = unbox(rs.getObject("true_study"));
            v[6] = unbox(rs.getObject("true_noise_tol"));
            v[7] = unbox(rs.getObject("true_social"));
            v[8] = unbox(rs.getObject("true_sharing"));
            v[9] = unbox(rs.getObject("true_gaming"));
            v[10] = unbox(rs.getObject("true_ambition"));
            feats.put(id, StudentFeature.of(v));
            latent.put(id, unbox(rs.getObject("latent_affinity")));
            smoke.put(id, unbox(rs.getObject("smoke_tolerance")));
        });
        return feats.isEmpty() ? null : new GroundTruth(feats, latent, smoke);
    }

    public List<Map<String, Object>> listBatches(int limit) {
        return jdbc.queryForList("SELECT id,name,solver,status,student_count,room_count,assigned_count,"
                + "unassigned_count,hard_violations,mean_compat,worst_compat,blocking_pairs,preference_rate,"
                + "fairness_major,fairness_grade,runtime_ms,block_count,max_block_size,created_at,finished_at"
                + " FROM dorm_batch ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200)));
    }

    public Map<String, Object> getBatch(long batchId) {
        List<Map<String, Object>> l = jdbc.queryForList("SELECT * FROM dorm_batch WHERE id=?", batchId);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Map<String, Object>> assignmentsOf(long batchId, Long roomId) {
        String sql = "SELECT a.student_id, a.room_id, a.room_label, a.building_id, a.is_appealed,"
                + " s.student_no, s.name, s.gender, s.grade, s.major, s.major_cluster, s.special_need,"
                + " s.building_pref"
                + " FROM dorm_assignment a LEFT JOIN dorm_student s ON s.id = a.student_id"
                + " WHERE a.batch_id=?" + (roomId == null ? "" : " AND a.room_id=?")
                + " ORDER BY a.room_label, s.student_no";
        return roomId == null
                ? jdbc.queryForList(sql, batchId)
                : jdbc.queryForList(sql, batchId, roomId);
    }

    /**
     * 单个学生在某批次里的那一行。学生端门户每次进页面都要查，不能像管理端那样
     * 把整批 1 万行捞出来再在内存里过滤。
     */
    public Map<String, Object> assignmentOf(long batchId, long studentId) {
        List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT a.student_id, a.room_id, a.room_label, a.building_id, a.is_appealed,"
                        + " s.student_no, s.name, s.gender, s.grade, s.major, s.major_cluster, s.special_need"
                        + " FROM dorm_assignment a LEFT JOIN dorm_student s ON s.id = a.student_id"
                        + " WHERE a.batch_id=? AND a.student_id=?", batchId, studentId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 最近一次跑完的分配批次 id；没有就返回 null。 */
    public Long latestBatchId() {        List<Long> l = jdbc.query(
                "SELECT id FROM dorm_batch WHERE status IN ('FINISHED','DONE','COMPLETED') "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1));
        if (l.isEmpty()) {
            l = jdbc.query("SELECT id FROM dorm_batch ORDER BY id DESC LIMIT 1",
                    (rs, i) -> rs.getLong(1));
        }
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * 某批次里「学生 → 房间」的映射，供可评估性统计按房间两两配对用。
     *
     * <p>只取两列，不做 JOIN：调用方（缺失感知服务）只需要「谁和谁同房」，
     * 姓名学号它自己有另外的来源。没分到房间的学生不在返回里。
     */
    public Map<Long, Long> roomOfBatch(long batchId) {
        Map<Long, Long> m = new LinkedHashMap<>();
        jdbc.query("SELECT student_id, room_id FROM dorm_assignment WHERE batch_id=?",
                rs -> {
                    long roomId = rs.getLong("room_id");
                    if (!rs.wasNull()) {
                        m.put(rs.getLong("student_id"), roomId);
                    }
                }, batchId);
        return m;
    }

    /** 按房间聚合的视角：管理员看"每间房住了谁"用这个。 */
    public List<Map<String, Object>> roomsOf(long batchId) {
        return jdbc.queryForList("SELECT a.room_id, a.room_label, a.building_id, COUNT(*) AS occupants,"
                + " GROUP_CONCAT(s.name ORDER BY s.student_no SEPARATOR '、') AS members"
                + " FROM dorm_assignment a LEFT JOIN dorm_student s ON s.id=a.student_id"
                + " WHERE a.batch_id=? AND a.room_id IS NOT NULL"
                + " GROUP BY a.room_id, a.room_label, a.building_id ORDER BY a.room_label", batchId);
    }

    public String explanationJson(long batchId, long studentId) {
        List<String> l = jdbc.query("SELECT payload_json FROM dorm_explanation WHERE batch_id=? AND student_id=?",
                (rs, i) -> rs.getString(1), batchId, studentId);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Map<String, Object>> listExplanations(long batchId, int limit) {
        return jdbc.queryForList("SELECT student_id, room_id FROM dorm_explanation WHERE batch_id=? LIMIT "
                + Math.max(1, Math.min(limit, 500)), batchId);
    }

    /** 公平性审计明细：分组名 → 组人数 / 组内平均满意度。 */
    public List<Map<String, Object>> auditOf(long batchId) {
        return jdbc.queryForList("SELECT metric, group_key, group_size, value FROM dorm_audit"
                + " WHERE batch_id=? ORDER BY metric, value DESC", batchId);
    }

    // ==================================================================
    // 申诉
    // ==================================================================

    public long insertAppeal(long batchId, long studentId, String kind, String targetIds,
                             com.umi.nest.dorm.explain.AppealResult r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dorm_appeal(batch_id,student_id,kind,target_ids,status,scope,message,"
                            + "cost_delta,cost_budget,payload_json,elapsed_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, studentId);
            ps.setString(3, kind);
            ps.setString(4, nz(targetIds));
            ps.setString(5, r.status());
            ps.setString(6, nz(r.scope()));
            ps.setString(7, r.message());
            ps.setDouble(8, r.totalCostDelta());
            ps.setDouble(9, r.costBudget());
            ps.setString(10, json(r));
            ps.setLong(11, r.elapsedMs());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<Map<String, Object>> listAppeals(long batchId) {
        return jdbc.queryForList("SELECT a.*, s.name AS student_name, s.student_no FROM dorm_appeal a"
                + " LEFT JOIN dorm_student s ON s.id=a.student_id"
                + " WHERE a.batch_id=? ORDER BY a.id DESC", batchId);
    }

    public Map<String, Object> getAppeal(long appealId) {
        List<Map<String, Object>> l = jdbc.queryForList("SELECT * FROM dorm_appeal WHERE id=?", appealId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 某个学生自己的申诉历史，学生端门户用。刻意不含别人提过什么。 */
    public List<Map<String, Object>> studentAppeals(long studentId) {
        return jdbc.queryForList("SELECT id, batch_id, kind, target_ids, status, scope, message,"
                + " cost_delta, cost_budget, elapsed_ms, created_at, handled_at, handled_by"
                + " FROM dorm_appeal WHERE student_id=? ORDER BY id DESC LIMIT 50", studentId);
    }

    public void updateAppealStatus(long appealId, String status, String handledBy) {
        jdbc.update("UPDATE dorm_appeal SET status=?, handled_at=NOW(), handled_by=? WHERE id=?",
                status, nz(handledBy), appealId);
    }

    /**
     * 记录跨块试算的状态与结果。
     *
     * <p>{@code payload_json} 存的是最初那一级（房间级/块级）的结论，这里<b>另开一列</b>存跨块结论，
     * 而不是覆盖它：两次试算的搜索范围和改动代价完全不同，覆盖掉就再也说不清
     * 「当初为什么答的是没找到」。审计时这两列要能并排看。
     *
     * @param r 跨块试算结果；{@code null} 表示只更新状态（排队中/执行中/失败）
     */
    public void updateAppealCross(long appealId, String state, String note,
                                  com.umi.nest.dorm.explain.AppealResult r, long elapsedMs) {
        jdbc.update("UPDATE dorm_appeal SET cross_state=?, cross_note=?, cross_json=?, cross_elapsed_ms=?"
                        + " WHERE id=?",
                nz(state), note, r == null ? null : json(r), elapsedMs, appealId);
    }

    public void markAppealed(long batchId, long studentId) {
        jdbc.update("UPDATE dorm_assignment SET is_appealed=1 WHERE batch_id=? AND student_id=?", batchId, studentId);
    }

    public void moveStudent(long batchId, long studentId, Long roomId, String roomLabel, String buildingId) {
        jdbc.update("UPDATE dorm_assignment SET room_id=?, room_label=?, building_id=?, is_appealed=1"
                + " WHERE batch_id=? AND student_id=?", roomId, nz(roomLabel), nz(buildingId), batchId, studentId);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.queryForList(sql);
    }

    public String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 读了但没用的占位，保留以便将来做"配置回放"。 */
    public ConstraintConfig configOf(long batchId) {
        List<String> l = jdbc.query("SELECT config_json FROM dorm_batch WHERE id=?",
                (rs, i) -> rs.getString(1), batchId);
        if (l.isEmpty() || l.get(0) == null || l.get(0).isBlank()) {
            return ConstraintConfig.defaults();
        }
        try {
            return mapper.readValue(l.get(0), ConstraintConfig.class);
        } catch (Exception e) {
            return ConstraintConfig.defaults();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** NaN → NULL；其余原样。缺失绝不当 0 存。 */
    private static Double box(double v) {
        return Double.isNaN(v) ? null : v;
    }

    private static double unbox(Object o) {
        if (o == null) {
            return Double.NaN;
        }
        return ((Number) o).doubleValue();
    }
}
