package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 志愿簇预分组（"先让互相点名的人住一起"）。
 *
 * <p>为什么必须有这一步：志愿名单里藏着<b>算法可以直接读到的强信号</b>——
 * 互相点名的一群人本身就是一个房间。如果先去按相容度贪心，
 * 这个信号会被"特征相似度"淹没（尤其是观测带噪声时，
 * 靠特征根本还原不出真值上的最近邻），于是大量互相想住的人被拆开，
 * 表现为阻塞对居高不下、志愿满足率接近 0。
 *
 * <p>做法：在块内建"互相点名图"，按度数降序找<b>团</b>（clique，
 * 两两都互相点名），把团整块塞进一间房；团的规模受房间容量限制。
 * 这一步只消费志愿信息，不碰相容度，因此和其他阶段正交。
 */
public final class PreferenceSeeder {

    private PreferenceSeeder() {
    }

    /**
     * @return 被预分组占用的房间数
     */
    public static int seed(Block block, Solution sol, RoomScorer scorer) {
        List<Long> ids = block.studentIds();
        if (ids.size() < 2 || block.rooms().isEmpty()) {
            return 0;
        }
        Set<Long> inBlock = new HashSet<>(ids);

        // 1) 互相点名邻接表
        Map<Long, Set<Long>> mutual = new HashMap<>();
        int edges = 0;
        for (Long a : ids) {
            for (Long b : scorer.preferenceOf(a).preferredRoommates()) {
                if (a.equals(b) || !inBlock.contains(b)) {
                    continue;
                }
                if (scorer.preferenceOf(b).preferredRoommates().contains(a)) {
                    mutual.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                    edges++;
                }
            }
        }
        if (edges == 0) {
            return 0;
        }

        // 2) 候选房间：按容量升序（优先用刚好装得下的房间，避免浪费床位）
        List<Room> rooms = new ArrayList<>(block.rooms());
        rooms.sort(Comparator.comparingInt(Room::capacity));
        Set<Long> roomUsed = new HashSet<>();

        // 3) 学生按互助度数降序
        List<Long> order = new ArrayList<>(ids);
        order.sort(Comparator.comparingInt((Long x) -> -mutual.getOrDefault(x, Set.of()).size()));

        int seededRooms = 0;
        for (Long s : order) {
            if (sol.isAssigned(s)) {
                continue;
            }
            Set<Long> clique = new HashSet<>();
            clique.add(s);
            // 只找"两两互相点名"的团，且规模不超过最大房间容量
            int maxCap = rooms.stream().mapToInt(Room::capacity).max().orElse(1);
            List<Long> cands = new ArrayList<>(mutual.getOrDefault(s, Set.of()));
            cands.sort(Comparator.comparingInt((Long x) -> -mutual.getOrDefault(x, Set.of()).size()));
            for (Long c : cands) {
                if (clique.size() >= maxCap) {
                    break;
                }
                if (sol.isAssigned(c) || clique.contains(c)) {
                    continue;
                }
                boolean connectedToAll = true;
                for (Long m : clique) {
                    if (!mutual.getOrDefault(m, Set.of()).contains(c)) {
                        connectedToAll = false;
                        break;
                    }
                }
                if (connectedToAll) {
                    clique.add(c);
                }
            }
            if (clique.size() < 2) {
                continue;
            }
            Room target = pickRoom(rooms, roomUsed, clique.size(), clique, scorer);
            if (target == null) {
                continue;
            }
            for (Long m : clique) {
                sol.assign(m, target.id());
            }
            roomUsed.add(target.id());
            seededRooms++;
        }
        return seededRooms;
    }

    /** 找一间未使用、装得下这个团、且不违反硬约束的房间（容量最小的优先）。 */
    private static Room pickRoom(List<Room> rooms, Set<Long> used, int size, Set<Long> clique, RoomScorer scorer) {
        Room fallback = null;
        for (Room r : rooms) {
            if (used.contains(r.id()) || r.capacity() < size) {
                continue;
            }
            List<Long> members = new ArrayList<>(clique);
            if (!scorer.hardOk(r, members)) {
                continue;
            }
            if (r.capacity() == size) {
                return r;
            }
            if (fallback == null) {
                fallback = r;
            }
        }
        return fallback;
    }
}
