package com.umi.nest.dorm.data;

import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.SpecialNeed;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 带隐真值的合成数据生成器（设计文档 §10）。
 * <p>四步：
 * <ol>
 *   <li>生成真实特征 {@code z_i}（带专业/年级的结构性差异，让"分块"真的有信息量）</li>
 *   <li>生成隐真值相容度 {@code g}（含交互项与未观测隐变量，见 {@link GroundTruth}）</li>
 *   <li>"发问卷"得到观测 {@code o_i = f(z_i) + 噪声}，并按 missingRate 抹掉部分分量</li>
 *   <li>生成房间与志愿，保证实例一定可行（每个分块都有恰好够用的容量）</li>
 * </ol>
 * <p><b>关键不变量</b>：求解器只拿到 {@code ProblemInstance}，真值只交给评估器。
 * <p>房间容量的构造方式：对每个"分块桶"（性别 × 专业大类 × 年级 × 特殊需求）
 * 生成 {@code ceil(n/cap)} 个房间，最后一个房间容量取余数。
 * 这样总容量恰好覆盖且不浪费，保证了硬约束下必然存在可行解——
 * 现实中就是"学校按学生结构配房"，而难点在相容度优化与阻塞对，不在凑容量。
 */
public final class SyntheticDataGenerator {

    /** 专业大类 → 其下的专业。4 个大类 × 3 个专业。 */
    private static final String[][] CLUSTER_MAJORS = {
            {"信息类", "计算机科学与技术", "软件工程", "电子信息工程"},
            {"工科类", "土木工程", "机械工程", "自动化"},
            {"经管类", "会计学", "金融学", "工商管理"},
            {"人文类", "汉语言文学", "英语", "法学"},
    };

    private static final String[] GRADES = {"2023", "2024", "2025"};

    private SyntheticDataGenerator() {
    }

    public static SyntheticDataset generate(GenParams p) {
        // java.util.Random：确定性可复现，且自带 nextGaussian（要造正态噪声）
        Random rnd = new Random(p.seed());
        int n = p.studentCount();

        // ---------- 1. 学生硬属性 + 真实特征 ----------
        List<Student> students = new ArrayList<>(n);
        Map<Long, StudentFeature> trueFeatures = new LinkedHashMap<>(n * 2);
        Map<Long, Double> latent = new LinkedHashMap<>(n * 2);
        Map<Long, Double> smokeTol = new LinkedHashMap<>(n * 2);
        Map<Long, StudentFeature> observations = new LinkedHashMap<>(n * 2);
        Map<Long, String> blockBucket = new LinkedHashMap<>(n * 2);

        for (int i = 0; i < n; i++) {
            long id = i + 1L;
            Gender gender = rnd.nextDouble() < 0.5 ? Gender.MALE : Gender.FEMALE;
            int cluster = rnd.nextInt(CLUSTER_MAJORS.length);
            String clusterName = CLUSTER_MAJORS[cluster][0];
            String major = CLUSTER_MAJORS[cluster][1 + rnd.nextInt(3)];
            String grade = GRADES[weightedGrade(rnd)];
            SpecialNeed need = rnd.nextDouble() < p.specialNeedRate()
                    ? (rnd.nextBoolean() ? SpecialNeed.WHEELCHAIR : SpecialNeed.LOW_FLOOR)
                    : SpecialNeed.NONE;

            Student stu = new Student(id, String.format("学生%05d", i + 1), gender, grade, major, clusterName, null, need);
            students.add(stu);

            // 真实特征：先抽一个"作息类型"，再让其他维度与专业大类相关
            double chrono = rnd.nextDouble();
            double clusterShift = cluster * 0.06 - 0.09; // 信息类更晚睡/更爱打游戏，人文类更外向
            double sleepStart = clamp(22.5 + 3.0 * chrono + gauss(rnd, 0.40, 0.40), 20.5, 26.0);
            double sleepEnd = clamp(6.8 + 2.6 * chrono + gauss(rnd, 0.50, -0.20), 5.0, 12.0);

            double smoker = rnd.nextDouble() < 0.18 ? 0.55 + 0.45 * rnd.nextDouble() : rnd.nextDouble() * 0.12;
            double cleanliness = clamp01(gauss(rnd, 0.55, 0.20));
            double extro = clamp01(gauss(rnd, 0.50 + clusterShift * 0.4, 0.22));
            double social = clamp01(0.55 * extro + 0.45 * gauss(rnd, 0.50, 0.28));
            double gaming = clamp01(gauss(rnd, 0.45 - clusterShift, 0.25));
            double study = clamp01(gauss(rnd, 0.50 + clusterShift * 0.3, 0.25));
            double noiseTol = clamp01(gauss(rnd, 0.50, 0.22));
            double share = clamp01(gauss(rnd, 0.60, 0.20));
            double ambition = clamp01(0.5 * study + 0.5 * gauss(rnd, 0.50, 0.25));

            StudentFeature truth = new StudentFeature(sleepStart, sleepEnd, smoker, cleanliness, extro,
                    study, noiseTol, social, share, gaming, ambition);
            trueFeatures.put(id, truth);
            latent.put(id, rnd.nextDouble());
            smokeTol.put(id, rnd.nextDouble());

            // ---------- 3. 问卷观测：噪声 + 缺失 ----------
            observations.put(id, observe(truth, p.noiseLevel(), p.missingRate(), rnd));

            // 分块桶：用于生成"恰好够用"的房间。特殊需求学生不按专业/年级分块。
            String bucket = need.isSpecial()
                    ? gender.code() + "|S"
                    : gender.code() + "|N|" + clusterName + "|" + grade;
            blockBucket.put(id, bucket);
        }

        // ---------- 4. 房间：按桶生成，容量恰好覆盖 ----------
        Map<String, List<Room>> roomsByBucket = new LinkedHashMap<>();
        Map<String, String> buildingOfBucket = new LinkedHashMap<>();
        long roomSeq = 1;
        for (Map.Entry<Long, String> e : blockBucket.entrySet()) {
            roomsByBucket.computeIfAbsent(e.getValue(), k -> new ArrayList<>());
        }
        List<Room> rooms = new ArrayList<>();
        for (Map.Entry<String, List<Room>> e : roomsByBucket.entrySet()) {
            String bucket = e.getKey();
            long size = blockBucket.values().stream().filter(bucket::equals).count();
            boolean special = bucket.endsWith("|S");
            Gender g = bucket.startsWith("M") ? Gender.MALE : Gender.FEMALE;
            int cap = p.roomCapacity();
            int demand = (int) ((size + cap - 1) / cap);
            for (int k = 0; k < demand; k++) {
                int c = (k == demand - 1) ? (int) (size - (long) cap * (demand - 1)) : cap;
                rooms.add(new Room(roomSeq++, null, null, g, c, special, null));
            }
        }
        // 给房间分楼栋：同性别内尽量均匀分到 buildingCount 栋楼
        assignBuildings(rooms, p.buildingCount(), rnd);

        // ---------- 5. 志愿（楼栋志愿 + 室友志愿） ----------
        Map<Long, Preference> prefs = generatePreferences(students, trueFeatures, rooms, p, rnd);

        ProblemInstance problem = new ProblemInstance(students, rooms, observations, prefs);
        GroundTruth truth = new GroundTruth(trueFeatures, latent, smokeTol);
        return new SyntheticDataset(problem, truth, p);
    }

    // ------------------------------------------------------------------

    private static int weightedGrade(Random rnd) {
        double u = rnd.nextDouble();
        if (u < 0.32) {
            return 0;
        }
        return u < 0.66 ? 1 : 2;
    }

    /** 观测 = 真值 + 高斯噪声；分量尺度不同（作息是小时制，其余是 0..1）。 */
    static StudentFeature observe(StudentFeature truth, double noise, double missingRate, Random rnd) {
        double[] v = truth.toArray();
        double[] out = new double[StudentFeature.DIM];
        for (int i = 0; i < StudentFeature.DIM; i++) {
            if (missingRate > 0 && rnd.nextDouble() < missingRate) {
                out[i] = Double.NaN;
                continue;
            }
            double scale = (i == 0 || i == 1) ? 0.9 : 0.22;
            double val = v[i] + gauss(rnd, 0.0, noise * scale);
            if (i == 0) {
                val = clamp(val, 20.0, 26.5);
            } else if (i == 1) {
                val = clamp(val, 4.5, 13.0);
            } else {
                val = clamp01(val);
            }
            out[i] = val;
        }
        return StudentFeature.of(out);
    }

    /** 房间分楼栋：同性别内轮流分配，保证每栋楼房间数尽量均匀。 */
    private static void assignBuildings(List<Room> rooms, int buildingCount, Random rnd) {
        int bc = Math.max(1, buildingCount);
        Map<Gender, Integer> counter = new HashMap<>();
        counter.put(Gender.MALE, 0);
        counter.put(Gender.FEMALE, 0);
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            int idx = counter.merge(r.gender(), 1, Integer::sum) - 1;
            String prefix = r.forSpecialNeeds() ? "S" : "";
            String building = prefix + (r.gender() == Gender.MALE ? "男生" : "女生") + (idx % bc + 1) + "号楼";
            String roomNo = String.format("%d%02d", (idx / bc) + 1, (idx % bc) + 1);
            rooms.set(i, new Room(r.id(), roomNo, building, r.gender(), r.capacity(), r.forSpecialNeeds(), r.gradeRestriction()));
        }
    }

    /**
     * 志愿生成。{@code preferenceTension} 控制"强簇"比例：
     * 紧张度高时大量学生互相点名，于是阻塞对天然就多；
     * 宽松时志愿接近随机，阻塞对稀少。这是实验矩阵里最关键的难度旋钮。
     */
    private static Map<Long, Preference> generatePreferences(List<Student> students,
                                                             Map<Long, StudentFeature> truth,
                                                             List<Room> rooms,
                                                             GenParams p,
                                                             Random rnd) {
        // 按性别分桶，志愿只可能落在同性别内
        Map<Gender, List<Student>> byGender = new LinkedHashMap<>();
        for (Student s : students) {
            byGender.computeIfAbsent(s.gender(), k -> new ArrayList<>()).add(s);
        }
        Map<Gender, List<String>> buildingsByGender = new LinkedHashMap<>();
        for (Room r : rooms) {
            buildingsByGender.computeIfAbsent(r.gender(), k -> new ArrayList<>());
            List<String> bs = buildingsByGender.get(r.gender());
            if (!bs.contains(r.buildingId())) {
                bs.add(r.buildingId());
            }
        }

        Map<Long, List<Long>> preferred = new HashMap<>();
        Map<Long, List<Long>> avoided = new HashMap<>();
        Map<Long, List<String>> buildingPref = new HashMap<>();
        for (Student s : students) {
            preferred.put(s.id(), new ArrayList<>());
            avoided.put(s.id(), new ArrayList<>());
        }

        int k = Math.max(0, p.preferenceCount());
        for (List<Student> pool : byGender.values()) {
            boolean[] taken = new boolean[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                if (taken[i] || k == 0) {
                    continue;
                }
                Student seed = pool.get(i);
                if (rnd.nextDouble() < p.preferenceTension()) {
                    // 强簇：找特征最接近的 k 个同性同学，互相点名 → 直接制造互相想住一起的对
                    List<Integer> idx = nearest(pool, truth, i, k);
                    taken[i] = true;
                    for (int j : idx) {
                        taken[j] = true;
                        preferred.get(seed.id()).add(pool.get(j).id());
                        preferred.get(pool.get(j).id()).add(seed.id());
                        for (int m : idx) {
                            if (m != j) {
                                preferred.get(pool.get(j).id()).add(pool.get(m).id());
                            }
                        }
                    }
                } else {
                    // 宽松：随机点名（大概率不是互相的）
                    List<Long> picks = new ArrayList<>();
                    for (int t = 0; t < k * 2 && picks.size() < k; t++) {
                        int j = rnd.nextInt(pool.size());
                        if (j != i && !picks.contains(pool.get(j).id())) {
                            picks.add(pool.get(j).id());
                        }
                    }
                    preferred.get(seed.id()).addAll(picks);
                }
            }
        }

        // 少量"明确不想同住"：挑一个特征差别最大的同性同学
        for (List<Student> pool : byGender.values()) {
            for (Student s : pool) {
                if (pool.size() > 8 && rnd.nextDouble() < 0.06) {
                    int j = rnd.nextInt(pool.size());
                    Student o = pool.get(j);
                    if (o.id() != s.id()) {
                        avoided.get(s.id()).add(o.id());
                    }
                }
            }
        }

        // 楼栋志愿：40% 的学生填一个
        for (Student s : students) {
            List<String> bs = buildingsByGender.getOrDefault(s.gender(), List.of());
            if (!bs.isEmpty() && rnd.nextDouble() < 0.40) {
                buildingPref.put(s.id(), List.of(bs.get(rnd.nextInt(bs.size()))));
            }
        }

        Map<Long, Preference> out = new LinkedHashMap<>();
        for (Student s : students) {
            out.put(s.id(), new Preference(s.id(),
                    preferred.get(s.id()),
                    avoided.get(s.id()),
                    buildingPref.getOrDefault(s.id(), List.of())));
        }
        return out;
    }

    /** 在真值特征空间找 i 最近的 k 个（同性别池内）。 */
    private static List<Integer> nearest(List<Student> pool, Map<Long, StudentFeature> truth, int i, int k) {
        double[] vi = truth.get(pool.get(i).id()).toArray();
        List<int[]> order = new ArrayList<>(pool.size());
        for (int j = 0; j < pool.size(); j++) {
            if (j == i) {
                continue;
            }
            double d = dist(vi, truth.get(pool.get(j).id()).toArray());
            order.add(new int[]{j, (int) Math.round(d * 1000)});
        }
        order.sort((a, b) -> Integer.compare(a[1], b[1]));
        List<Integer> out = new ArrayList<>(k);
        for (int t = 0; t < Math.min(k, order.size()); t++) {
            out.add(order.get(t)[0]);
        }
        return out;
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            double scale = (i == 0 || i == 1) ? 0.25 : 1.0;
            s += d * d * scale;
        }
        return Math.sqrt(s);
    }

    // ---------------- 小工具 ----------------

    private static double gauss(Random rnd, double mean, double sd) {
        return mean + sd * rnd.nextGaussian();
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return (lo + hi) / 2;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
