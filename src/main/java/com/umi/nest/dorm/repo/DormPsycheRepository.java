package com.umi.nest.dorm.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.psyche.BigFive;
import com.umi.nest.dorm.psyche.Evidence;
import com.umi.nest.dorm.psyche.ExtractionResult;
import com.umi.nest.dorm.psyche.ExtractorEvaluator;
import com.umi.nest.dorm.psyche.PsycheDimension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 心理特征提取模块的持久层（创新点 4）。
 *
 * <p>单独成类而不塞进 {@link DormRepository}：分配域与提取域的生命周期不同 ——
 * 分配数据每跑一次「一键分配」就可能被清空重建，而学生填的问卷和聊天记录是
 * 不可再生的原始素材，绝不能因为重新分配而丢。放在同一个类里很容易被一次
 * 手滑的 {@code clearDormData()} 带走。
 *
 * <p>三张表的职责边界：
 * <ul>
 *   <li>{@code dorm_psyche_text} —— 原始文本，只增不改（证据链的根）</li>
 *   <li>{@code dorm_psyche_answer} —— 量表作答，一题一行，可覆盖</li>
 *   <li>{@code dorm_psyche_profile} —— 提取结果快照，每个学生一行，可重算</li>
 * </ul>
 * 写入 {@code dorm_student_feature} 是**独立的一步**（{@link #applyProfile}），
 * 需要人明确点「采用」。理由是提取出的值会直接影响谁和谁住，不该悄悄生效。
 */
@Repository
public class DormPsycheRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DormPsycheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ==================================================================
    // 原始文本
    // ==================================================================

    /** 某个学生全部素材，按时间正序。{@code consent=0} 的行不会被任何提取逻辑读到。 */
    public List<Map<String, Object>> textsOf(long studentId) {
        return jdbc.queryForList(
                "SELECT id, kind, content, char_count, consent, source, created_at "
                        + "FROM dorm_psyche_text WHERE student_id=? AND consent=1 ORDER BY id",
                studentId);
    }

    /** 含未授权素材的完整列表，供管理端核对"学生交了什么"。 */
    public List<Map<String, Object>> allTextsOf(long studentId) {
        return jdbc.queryForList(
                "SELECT id, kind, content, char_count, consent, source, created_at "
                        + "FROM dorm_psyche_text WHERE student_id=? ORDER BY id",
                studentId);
    }

    public long saveText(long studentId, String kind, String content, boolean consent, String source) {
        String k = kind == null || kind.isBlank() ? "ESSAY" : kind.trim().toUpperCase();
        String src = source == null || source.isBlank() ? "MANUAL" : source.trim().toUpperCase();
        jdbc.update("INSERT INTO dorm_psyche_text(student_id, kind, content, char_count, consent, source) "
                        + "VALUES(?,?,?,?,?,?)",
                studentId, k, content, content == null ? 0 : content.length(), consent ? 1 : 0, src);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public int deleteText(long id) {
        return jdbc.update("DELETE FROM dorm_psyche_text WHERE id=?", id);
    }

    public int countTexts() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dorm_psyche_text", Integer.class);
        return n == null ? 0 : n;
    }

    public int countTextsWithConsent() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dorm_psyche_text WHERE consent=1", Integer.class);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 量表作答
    // ==================================================================

    /** itemCode → 作答值。作息维是小时，其余是 0..1（"维度原生值"）。 */
    public Map<String, Double> answersOf(long studentId) {
        Map<String, Double> out = new LinkedHashMap<>();
        // 用列名取值而不是下标：这一行里 item_code 是字符串、answer_num 是数字，
        // 写成 rs.getDouble(1) 会去解析"extro"然后抛 For input string。
        jdbc.query("SELECT item_code, answer_num FROM dorm_psyche_answer WHERE student_id=?",
                (rs, i) -> {
                    double v = rs.getDouble("answer_num");
                    if (!rs.wasNull()) {
                        out.put(rs.getString("item_code"), v);
                    }
                    return null;
                }, studentId);
        return out;
    }

    /** 学生答过的自由文本题，以 {@code itemCode → answerText} 返回。 */
    public Map<String, String> answerTextsOf(long studentId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT item_code, answer_text FROM dorm_psyche_answer "
                        + "WHERE student_id=? AND answer_text IS NOT NULL",
                (rs, i) -> {
                    String t = rs.getString(2);
                    if (t != null && !t.isBlank()) {
                        out.put(rs.getString("item_code"), t);
                    }
                    return null;
                }, studentId);
        return out;
    }

    public void saveAnswer(long studentId, String itemCode, int itemNo, String dimension,
                           Double answerNum, String answerText) {
        jdbc.update("INSERT INTO dorm_psyche_answer(student_id, item_code, item_no, dimension, "
                        + "answer_num, answer_text) VALUES(?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE item_no=VALUES(item_no), dimension=VALUES(dimension), "
                        + "answer_num=VALUES(answer_num), answer_text=VALUES(answer_text)",
                studentId, itemCode, itemNo, dimension,
                answerNum == null || answerNum.isNaN() ? null : answerNum, answerText);
    }

    public List<Map<String, Object>> listAnswers(long studentId) {
        return jdbc.queryForList("SELECT item_code, item_no, dimension, answer_num, answer_text, updated_at "
                + "FROM dorm_psyche_answer WHERE student_id=? ORDER BY item_no", studentId);
    }

    public int countAnswers() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dorm_psyche_answer", Integer.class);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 提取结果快照
    // ==================================================================

    public void saveProfile(long studentId, ExtractionResult r) {
        jdbc.update("INSERT INTO dorm_psyche_profile(student_id, method, llm_used, big_five_json, "
                        + "features_json, confidence_json, evidence_json, narrative, text_chars, "
                        + "answered_items, coverage, prompt_tokens, completion_tokens, elapsed_ms, "
                        + "spread_json, samples) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE method=VALUES(method), llm_used=VALUES(llm_used), "
                        + "big_five_json=VALUES(big_five_json), features_json=VALUES(features_json), "
                        + "confidence_json=VALUES(confidence_json), evidence_json=VALUES(evidence_json), "
                        + "narrative=VALUES(narrative), text_chars=VALUES(text_chars), "
                        + "answered_items=VALUES(answered_items), coverage=VALUES(coverage), "
                        + "prompt_tokens=VALUES(prompt_tokens), completion_tokens=VALUES(completion_tokens), "
                        + "elapsed_ms=VALUES(elapsed_ms), spread_json=VALUES(spread_json), "
                        + "samples=VALUES(samples)",
                studentId,
                r.method(),
                r.llmUsed() ? 1 : 0,
                json(bigFiveMap(r.bigFive())),
                json(featuresMap(r)),
                json(byDimension(r::confidenceOf)),
                json(evidenceList(r.evidence())),
                r.narrative(),
                r.textChars(),
                r.answeredItems(),
                r.coverage(),
                r.promptTokens(),
                r.completionTokens(),
                r.elapsedMs(),
                r.spread().isEmpty() ? null : json(r.spread()),
                samplesOf(r.method()));
    }

    /**
     * 从提取器标识里取采样次数：{@code LlmExtractor} 把方法名写成 {@code LLM×2}。
     *
     * <p>故意不新加一列去存这个数、而是从 method 里解析，是因为
     * "采了几次"和"这是什么提取器"本来就是同一件事的两面：
     * 分成两个字段存，将来迟早出现 method=LLM×2 而 samples=1 的矛盾行。
     */
    static int samplesOf(String method) {
        if (method == null) {
            return 1;
        }
        int i = method.indexOf('×');
        if (i < 0) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(method.substring(i + 1).trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ==================================================================
    // 提取器评测留档
    // ==================================================================

    /**
     * 把一次评测的每个提取器结果各写一行。
     *
     * <p>留档的意义不是"存起来好看"，而是回答「换一批学生结论还成立吗」：
     * 同一张表里按 extractor 分组看多次运行，才能判断某个提取器
     * 是真的更准，还是恰好在这一批人身上更准。
     */
    public void saveEval(List<ExtractorEvaluator.Report> reports, String headline, String detail) {
        for (ExtractorEvaluator.Report r : reports) {
            List<Map<String, Object>> dims = new ArrayList<>();
            for (ExtractorEvaluator.DimStat d : r.dims()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dimension", d.dimension());
                m.put("hourScale", d.hourScale());
                m.put("n", d.n());
                m.put("mae", d.mae());
                m.put("bias", d.bias());
                m.put("pearson", d.pearson());
                m.put("spearman", d.spearman());
                m.put("truthStd", d.truthStd());
                m.put("estStd", d.estStd());
                m.put("collapse", d.collapse());
                m.put("coverage", d.coverage());
                dims.add(m);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dims", dims);
            payload.put("headline", headline);
            payload.put("detail", detail);
            jdbc.update("INSERT INTO dorm_psyche_eval(extractor, variant, cases, mae_hour, mae01, "
                            + "pearson_mean, spearman_mean, collapse, coverage, elapsed_ms, detail_json) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    r.extractor(), r.variant(), r.cases(), r.maeHour(), r.mae01(),
                    r.pearsonMean(), r.spearmanMean(), r.collapse01(), r.coverage(),
                    r.elapsedMs(), json(payload));
        }
    }

    public List<Map<String, Object>> listEvals(int limit) {
        return jdbc.queryForList(
                "SELECT id, extractor, variant, cases, mae_hour, mae01, pearson_mean, spearman_mean, "
                        + "collapse, coverage, elapsed_ms, created_at FROM dorm_psyche_eval "
                        + "ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200)));
    }

    public Map<String, Object> profileOf(long studentId) {
        List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT * FROM dorm_psyche_profile WHERE student_id=?", studentId);
        if (l.isEmpty()) {
            return null;
        }
        Map<String, Object> row = l.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("method", row.get("method"));
        out.put("llmUsed", truthy(row.get("llm_used")));
        out.put("features", readJsonMap(row.get("features_json")));
        out.put("confidence", readJsonMap(row.get("confidence_json")));
        out.put("spread", readJsonMap(row.get("spread_json")));
        out.put("samples", row.get("samples"));
        out.put("bigFive", readJsonMap(row.get("big_five_json")));
        out.put("evidence", readJsonList(row.get("evidence_json")));
        out.put("narrative", row.get("narrative"));
        out.put("textChars", row.get("text_chars"));
        out.put("answeredItems", row.get("answered_items"));
        out.put("coverage", row.get("coverage"));
        out.put("promptTokens", row.get("prompt_tokens"));
        out.put("completionTokens", row.get("completion_tokens"));
        out.put("elapsedMs", row.get("elapsed_ms"));
        out.put("applied", truthy(row.get("applied")));
        out.put("appliedSource", row.get("applied_source"));
        out.put("createdAt", row.get("created_at"));
        out.put("appliedAt", row.get("applied_at"));
        return out;
    }

    public List<Map<String, Object>> listProfiles(Long studentId, int limit) {
        String sql = "SELECT p.student_id, s.student_no, s.name, p.method, p.llm_used, p.coverage, "
                + "p.text_chars, p.answered_items, p.applied, p.applied_source, p.elapsed_ms, p.created_at "
                + "FROM dorm_psyche_profile p JOIN dorm_student s ON s.id = p.student_id "
                + (studentId == null ? "" : "WHERE p.student_id=" + studentId + " ")
                + "ORDER BY p.created_at DESC LIMIT " + Math.max(1, Math.min(limit, 500));
        return jdbc.queryForList(sql);
    }

    public int countProfiles() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dorm_psyche_profile", Integer.class);
        return n == null ? 0 : n;
    }

    public int countApplied() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dorm_psyche_profile WHERE applied=1", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 把提取到的特征写回 {@code dorm_student_feature}，这些值随后会被求解器读到并影响分配。
     *
     * <p>语义是**逐维覆盖**而不是整行替换：只写入本次提供了值的维度，
     * 其余维度保留原值。学生补充一条新素材不该抹掉他上次填的问卷。
     * 全部为 NaN 的维度保持 NULL —— 缺失就是缺失，绝不写 0 假装知道。
     *
     * @return 实际写入/更新的维度数
     */
    public int applyProfile(long studentId, Map<PsycheDimension, Double> values, String source) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        double[] cur = loadFeatureRow(studentId);
        int written = 0;
        for (Map.Entry<PsycheDimension, Double> e : values.entrySet()) {
            Double v = e.getValue();
            if (v == null || v.isNaN()) {
                continue;
            }
            cur[e.getKey().index()] = v;
            written++;
        }
        if (written == 0) {
            return 0;
        }
        upsertFeatureRow(studentId, cur, source);
        jdbc.update("UPDATE dorm_psyche_profile SET applied=1, applied_source=?, applied_at=NOW() "
                + "WHERE student_id=?", source, studentId);
        return written;
    }

    /** 读现有特征行；没有则全 NaN。只取算法要的 11 维，忽略 source/updated_at。 */
    private double[] loadFeatureRow(long studentId) {
        double[] f = new double[StudentFeature.DIM];
        for (int i = 0; i < f.length; i++) {
            f[i] = Double.NaN;
        }
        List<double[]> l = jdbc.query("SELECT sleep_start, sleep_end, smoking, cleanliness, extroversion, "
                        + "study_in_room, noise_tolerance, social_activity, share_items, gaming, ambition "
                        + "FROM dorm_student_feature WHERE student_id=?",
                (rs, i) -> {
                    double[] row = new double[StudentFeature.DIM];
                    for (int k = 0; k < StudentFeature.DIM; k++) {
                        double v = rs.getDouble(k + 1);
                        row[k] = rs.wasNull() ? Double.NaN : v;
                    }
                    return row;
                }, studentId);
        if (!l.isEmpty()) {
            return l.get(0);
        }
        return f;
    }

    private void upsertFeatureRow(long studentId, double[] f, String source) {
        jdbc.update("INSERT INTO dorm_student_feature(student_id, sleep_start, sleep_end, smoking, "
                        + "cleanliness, extroversion, study_in_room, noise_tolerance, social_activity, "
                        + "share_items, gaming, ambition, source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE sleep_start=VALUES(sleep_start), sleep_end=VALUES(sleep_end), "
                        + "smoking=VALUES(smoking), cleanliness=VALUES(cleanliness), "
                        + "extroversion=VALUES(extroversion), study_in_room=VALUES(study_in_room), "
                        + "noise_tolerance=VALUES(noise_tolerance), social_activity=VALUES(social_activity), "
                        + "share_items=VALUES(share_items), gaming=VALUES(gaming), ambition=VALUES(ambition), "
                        + "source=VALUES(source), updated_at=CURRENT_TIMESTAMP",
                studentId, box(f[0]), box(f[1]), box(f[2]), box(f[3]), box(f[4]), box(f[5]),
                box(f[6]), box(f[7]), box(f[8]), box(f[9]), box(f[10]),
                source == null || source.isBlank() ? "AGENT_LLM" : source);
    }

    // ==================================================================
    // 学生 / 账号 关联（学生端门户登录用）
    // ==================================================================

    /** 全部宿舍学生 id，按学号排序。批量提取用。 */
    public List<Long> allStudentIds(int limit) {
        return jdbc.query("SELECT id FROM dorm_student ORDER BY student_no LIMIT ?",
                (rs, i) -> rs.getLong(1), Math.max(1, limit));
    }

    public String studentNoOf(long studentId) {
        List<String> l = jdbc.query("SELECT student_no FROM dorm_student WHERE id=?",
                (rs, i) -> rs.getString(1), studentId);
        return l.isEmpty() ? "" : nz(l.get(0));
    }

    /**
     * 当前 {@code dorm_student_feature} 里生效的值（标签 → 值），供"采用前后对比"用。
     * 缺失维度返回 null，不是 0。
     */
    public Map<String, Object> currentFeatures(long studentId) {
        double[] f = loadFeatureRow(studentId);
        Map<String, Object> m = new LinkedHashMap<>();
        boolean any = false;
        for (PsycheDimension d : PsycheDimension.values()) {
            double v = f[d.index()];
            m.put(d.label(), Double.isNaN(v) ? null : v);
            any |= !Double.isNaN(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", any);
        out.put("values", m);
        out.put("note", any ? "这些是求解器当前读到的值。" : "尚无任何特征，分配时会走均值插补。");
        return out;
    }

    /**
     * 按用户名或学号在系统用户表里找账号 id。
     * <p>{@code users} 表既有 {@code username} 也有 {@code student_id}，学生注册时
     * 两者都可能填，所以两个都查；软删除的行不算。
     */
    public Long userIdOfNo(String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            return null;
        }
        String key = studentNo.trim();
        List<Long> l = jdbc.query(
                "SELECT id FROM users WHERE (username=? OR student_id=?) "
                        + "AND (deleted IS NULL OR deleted=0) LIMIT 1",
                (rs, i) -> rs.getLong(1), key, key);
        return l.isEmpty() ? null : l.get(0);
    }

    public Student studentById(long studentId) {
        List<Student> l = jdbc.query("SELECT id, student_no, name, gender, grade, major, major_cluster, "
                        + "building_pref, special_need FROM dorm_student WHERE id=?",
                (rs, i) -> mapStudent(rs), studentId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 按学号查，学生端首次登录时用来把账号和名单绑起来。 */
    public Student studentByNo(String studentNo) {
        List<Student> l = jdbc.query("SELECT id, student_no, name, gender, grade, major, major_cluster, "
                        + "building_pref, special_need FROM dorm_student WHERE student_no=?",
                (rs, i) -> mapStudent(rs), studentNo);
        return l.isEmpty() ? null : l.get(0);
    }

    public Long studentIdOfUser(long userId) {
        List<Long> l = jdbc.query("SELECT id FROM dorm_student WHERE user_id=?",
                (rs, i) -> rs.getLong(1), userId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 把宿舍名单里的学生与系统账号绑定。 */
    public void linkUser(long studentId, long userId) {
        jdbc.update("UPDATE dorm_student SET user_id=? WHERE id=?", userId, studentId);
    }

    public void unlinkUser(long studentId) {
        jdbc.update("UPDATE dorm_student SET user_id=NULL WHERE id=?", studentId);
    }

    /** 管理端列表：学生 + 是否已有素材/结果/已采用，一屏看清"谁还没做心理测评"。 */
    public List<Map<String, Object>> studentPsycheOverview(String keyword, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.student_no, s.name, s.gender, s.grade, s.major_cluster, s.user_id, "
                        + "(SELECT COUNT(*) FROM dorm_psyche_text t WHERE t.student_id=s.id AND t.consent=1) AS text_count, "
                        + "(SELECT COUNT(*) FROM dorm_psyche_answer a WHERE a.student_id=s.id) AS answer_count, "
                        + "p.method, p.coverage, p.applied, p.applied_source, p.created_at "
                        + "FROM dorm_student s LEFT JOIN dorm_psyche_profile p ON p.student_id = s.id ");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append("WHERE s.student_no LIKE ? OR s.name LIKE ? ");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append("ORDER BY s.id LIMIT ").append(Math.max(1, Math.min(limit, 1000)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static Student mapStudent(java.sql.ResultSet rs) throws java.sql.SQLException {
        com.umi.nest.dorm.model.Gender g =
                "F".equalsIgnoreCase(rs.getString("gender"))
                        ? com.umi.nest.dorm.model.Gender.FEMALE
                        : com.umi.nest.dorm.model.Gender.MALE;
        com.umi.nest.dorm.model.SpecialNeed sn = com.umi.nest.dorm.model.SpecialNeed.NONE;
        try {
            sn = com.umi.nest.dorm.model.SpecialNeed.valueOf(
                    nz(rs.getString("special_need")).toUpperCase());
        } catch (Exception ignored) {
            // 库里出现未知取值时按"无特殊需求"处理，不能因此让整条查询失败
        }
        return new Student(rs.getLong("id"), rs.getString("name"), g, rs.getString("grade"),
                rs.getString("major"), rs.getString("major_cluster"),
                rs.getString("building_pref"), sn);
    }

    private static Map<String, Double> byDimension(java.util.function.ToDoubleFunction<PsycheDimension> f) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            double v = f.applyAsDouble(d);
            m.put(d.label(), Double.isNaN(v) ? null : v);
        }
        return m;
    }

    private static Map<String, Double> featuresMap(ExtractionResult r) {
        return byDimension(r::valueOf);
    }

    private static Map<String, Double> bigFiveMap(BigFive b) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (b == null) {
            return m;
        }
        m.put("开放性", b.openness());
        m.put("尽责性", b.conscientiousness());
        m.put("外向性", b.extraversion());
        m.put("宜人性", b.agreeableness());
        m.put("神经质", b.neuroticism());
        return m;
    }

    private static List<Map<String, Object>> evidenceList(List<Evidence> ev) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ev == null) {
            return out;
        }
        for (Evidence e : ev) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dimension", e.dimension());
            m.put("quote", e.quote());
            m.put("reasoning", e.reasoning());
            m.put("weight", e.weight());
            m.put("source", e.source());
            out.add(m);
        }
        return out;
    }

    public String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(Object o) {
        if (o == null) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(String.valueOf(o), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonList(Object o) {
        if (o == null) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(String.valueOf(o), List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return "1".equals(String.valueOf(o)) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** NaN → NULL；其余原样。缺失绝不当 0 存。 */
    private static Double box(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
