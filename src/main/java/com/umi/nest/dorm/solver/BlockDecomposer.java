package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Student;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 约束结构分块分解（创新点 ②）——整套算法能上万的根基。
 *
 * <p>为什么要分块：把"万级学生全局匹配"这个问题直接丢给任何启发式，
 * 邻域都会大到搜不动；而宿舍问题天然存在一组<b>互不耦合的硬约束切面</b>——
 * 性别、专业大类、年级。切完之后块与块之间没有任何可行解的相互牵制，
 * 于是问题规模从 O(全体) 降为 O(最大块)，而且块天然可并行。
 *
 * <p>分块键（全部来自配置，不写死）：
 * <pre>
 *   {性别}|{N 普通 / S 特殊需求}[|{专业大类}[|{年级}]]
 * </pre>
 * 特殊需求学生单独成块：他们的房间是专用房，与普通学生的匹配空间不相交。
 * {@code enforceGradeHomogeneous=false}（允许多年级混住）时年级不进分块键。
 *
 * <p>房间分配：每个"池"（性别 × 普通/特殊）内，把房间按容量大小贪心地塞给各块——
 * 优先选"容量不超过剩余需求的最大房间"，这样生成数据时凑出来的余数房间
 * 会回到它本该属于的那一块，几乎总能一次分配成功。万一失败（房间池容量不足），
 * 退化为"整池合并成一块"，宁可牺牲专业集中度也不能让硬约束违规。
 */
public final class BlockDecomposer {

    /**
     * @param blocks   分解结果
     * @param warnings 退化/异常情况的说明（正常应为空）
     */
    public record Result(List<Block> blocks, List<String> warnings, int unallocatedRooms) {
    }

    private BlockDecomposer() {
    }

    public static Result decompose(ProblemInstance problem, ConstraintConfig cfg) {
        // 1) 学生 → 块键（保持输入顺序，保证结果可复现）
        Map<String, List<Student>> blocks = new LinkedHashMap<>();
        for (Student s : problem.students()) {
            blocks.computeIfAbsent(blockKey(s, cfg), k -> new ArrayList<>()).add(s);
        }

        // 2) 房间 → 池
        Map<String, List<Room>> pools = new LinkedHashMap<>();
        for (Room r : problem.rooms()) {
            pools.computeIfAbsent(poolKey(r), k -> new ArrayList<>()).add(r);
        }

        List<String> warnings = new ArrayList<>();
        List<Block> result = new ArrayList<>();
        int unallocated = 0;

        for (Map.Entry<String, List<Room>> poolEntry : pools.entrySet()) {
            String pool = poolEntry.getKey();
            List<Room> poolRooms = poolEntry.getValue();

            // 该池内的块
            Map<String, List<Student>> poolBlocks = new LinkedHashMap<>();
            for (Map.Entry<String, List<Student>> e : blocks.entrySet()) {
                if (poolOf(e.getKey()).equals(pool)) {
                    poolBlocks.put(e.getKey(), e.getValue());
                }
            }
            if (poolBlocks.isEmpty()) {
                unallocated += poolRooms.size();
                continue; // 该池没有学生，房间先记为空闲
            }

            // 块按人数降序，先满足大块
            List<Map.Entry<String, List<Student>>> ordered = new ArrayList<>(poolBlocks.entrySet());
            ordered.sort(Comparator.comparingInt((Map.Entry<String, List<Student>> e) -> e.getValue().size()).reversed());

            TreeMap<Integer, ArrayDeque<Room>> avail = buildAvail(poolRooms);
            Map<String, List<Room>> assigned = new LinkedHashMap<>();
            boolean ok = true;
            for (Map.Entry<String, List<Student>> e : ordered) {
                List<Room> chosen = takeRooms(avail, e.getValue().size());
                int cap = chosen.stream().mapToInt(Room::capacity).sum();
                if (cap < e.getValue().size()) {
                    ok = false;
                    break;
                }
                assigned.put(e.getKey(), chosen);
            }

            if (!ok) {
                // 退化：整池合并成一块
                warnings.add("池 " + pool + " 房间无法按块精确剖分，退化为整池单块求解（专业/年级集中度可能下降）");
                avail = buildAvail(poolRooms);
                List<Student> all = new ArrayList<>();
                for (Map.Entry<String, List<Student>> e : ordered) {
                    all.addAll(e.getValue());
                }
                List<Room> allRooms = new ArrayList<>();
                Room rr;
                while ((rr = pollAny(avail)) != null) {
                    allRooms.add(rr);
                }
                if (allRooms.stream().mapToInt(Room::capacity).sum() < all.size()) {
                    warnings.add("池 " + pool + " 总容量不足：学生 " + all.size() + "，床位 "
                            + allRooms.stream().mapToInt(Room::capacity).sum());
                }
                result.add(new Block(pool + "|merged", all, allRooms));
                continue;
            }

            // 剩余房间挂到最大的那块（多出床位不影响可行性）
            List<Room> leftover = new ArrayList<>();
            Room r;
            while ((r = pollAny(avail)) != null) {
                leftover.add(r);
            }
            if (!leftover.isEmpty()) {
                String biggest = ordered.get(0).getKey();
                List<Room> cur = new ArrayList<>(assigned.get(biggest));
                cur.addAll(leftover);
                assigned.put(biggest, cur);
            }
            for (Map.Entry<String, List<Student>> e : ordered) {
                List<Room> rooms = assigned.get(e.getKey());
                if (rooms == null) {
                    rooms = List.of();
                }
                result.add(new Block(e.getKey(), e.getValue(), rooms));
            }
        }

        // 3) 有学生但没有对应房间池的块——必须显式报出来，否则会静默产生未分配
        for (Map.Entry<String, List<Student>> e : blocks.entrySet()) {
            String pool = poolOf(e.getKey());
            if (!pools.containsKey(pool)) {
                warnings.add("块 " + e.getKey() + " 没有任何房间池 " + pool + "，该块 " + e.getValue().size() + " 人无法分配");
                result.add(new Block(e.getKey(), e.getValue(), List.of()));
            }
        }

        result.removeIf(b -> b.studentCount() == 0);
        return new Result(result, warnings, unallocated);
    }

    /** 学生 → 块键。 */
    public static String blockKey(Student s, ConstraintConfig cfg) {
        StringBuilder sb = new StringBuilder(24);
        sb.append(s.gender().code()).append('|');
        if (s.specialNeed().isSpecial()) {
            // 特殊需求学生单独成块：专用房与普通房不相交
            sb.append('S');
            return sb.toString();
        }
        sb.append('N');
        if (cfg.isBlockByMajorCluster()) {
            sb.append('|').append(s.majorCluster());
        }
        if (cfg.isEnforceGradeHomogeneous()) {
            sb.append('|').append(s.grade());
        }
        return sb.toString();
    }

    /** 房间 → 池键（性别 × 普通/特殊）。块键的前两段与之一致，例如 {@code F|N}。 */
    public static String poolKey(Room r) {
        return r.gender().code() + "|" + (r.forSpecialNeeds() ? "S" : "N");
    }

    /** 从块键取池键。 */
    public static String poolOf(String blockKey) {
        int i = blockKey.indexOf('|');
        if (i < 0) {
            return blockKey;
        }
        int j = blockKey.indexOf('|', i + 1);
        return j < 0 ? blockKey : blockKey.substring(0, j);
    }

    // ---------------- 内部 ----------------

    private static TreeMap<Integer, ArrayDeque<Room>> buildAvail(List<Room> rooms) {
        TreeMap<Integer, ArrayDeque<Room>> avail = new TreeMap<>();
        for (Room r : rooms) {
            avail.computeIfAbsent(r.capacity(), k -> new ArrayDeque<>()).add(r);
        }
        return avail;
    }

    /**
     * 取若干房间，使容量之和 ≥ need。
     * 优先"容量不超过剩余需求的最大房间"（把余数房间留给它自己的块），
     * 若所有房间都比剩余需求大，则取容量最小的那间。
     */
    private static List<Room> takeRooms(TreeMap<Integer, ArrayDeque<Room>> avail, int need) {
        List<Room> chosen = new ArrayList<>();
        while (need > 0 && !avail.isEmpty()) {
            Integer key = avail.floorKey(need);
            if (key == null) {
                key = avail.firstKey();
            }
            ArrayDeque<Room> q = avail.get(key);
            Room r = q.poll();
            if (q.isEmpty()) {
                avail.remove(key);
            }
            if (r == null) {
                continue;
            }
            chosen.add(r);
            need -= r.capacity();
        }
        return chosen;
    }

    private static Room pollAny(TreeMap<Integer, ArrayDeque<Room>> avail) {
        if (avail.isEmpty()) {
            return null;
        }
        Integer key = avail.lastKey();
        ArrayDeque<Room> q = avail.get(key);
        Room r = q.poll();
        if (q.isEmpty()) {
            avail.remove(key);
        }
        return r;
    }
}
