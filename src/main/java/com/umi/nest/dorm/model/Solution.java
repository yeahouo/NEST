package com.umi.nest.dorm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个分配解：学生 → 房间。房间 → 成员的反向索引同步维护，便于 O(1) 取室友。
 * <p>注意：{@code studentToRoom} 里没有条目的学生即为"未分配"。
 */
public final class Solution {

    private final Map<Long, Long> studentToRoom = new LinkedHashMap<>();
    private final Map<Long, List<Long>> roomToStudents = new LinkedHashMap<>();

    public static Solution empty() {
        return new Solution();
    }

    public static Solution of(Map<Long, Long> assignment) {
        Solution s = new Solution();
        for (Map.Entry<Long, Long> e : assignment.entrySet()) {
            if (e.getValue() != null && e.getValue() >= 0) {
                s.assign(e.getKey(), e.getValue());
            }
        }
        return s;
    }

    public void assign(long studentId, long roomId) {
        Long old = studentToRoom.put(studentId, roomId);
        if (old != null && old == roomId) {
            return;
        }
        if (old != null) {
            List<Long> prev = roomToStudents.get(old);
            if (prev != null) {
                prev.remove(studentId);
            }
        }
        roomToStudents.computeIfAbsent(roomId, k -> new ArrayList<>(4)).add(studentId);
    }

    public void unassign(long studentId) {
        Long old = studentToRoom.remove(studentId);
        if (old != null) {
            List<Long> prev = roomToStudents.get(old);
            if (prev != null) {
                prev.remove(studentId);
            }
        }
    }

    /** @return 房间 id，未分配返回 -1 */
    public long roomOf(long studentId) {
        return studentToRoom.getOrDefault(studentId, -1L);
    }

    public boolean isAssigned(long studentId) {
        return studentToRoom.containsKey(studentId);
    }

    public List<Long> occupants(long roomId) {
        return roomToStudents.getOrDefault(roomId, List.of());
    }

    public int occupancy(long roomId) {
        return roomToStudents.getOrDefault(roomId, List.of()).size();
    }

    public Map<Long, Long> asMap() {
        return Collections.unmodifiableMap(studentToRoom);
    }

    public Map<Long, List<Long>> byRoom() {
        return Collections.unmodifiableMap(roomToStudents);
    }

    public int assignedCount() {
        return studentToRoom.size();
    }

    /** 深拷贝，供局部搜索做"试走-回滚"。 */
    public Solution copy() {
        Solution s = new Solution();
        for (Map.Entry<Long, List<Long>> e : roomToStudents.entrySet()) {
            List<Long> copy = new ArrayList<>(e.getValue());
            s.roomToStudents.put(e.getKey(), copy);
            for (Long stu : copy) {
                s.studentToRoom.put(stu, e.getKey());
            }
        }
        return s;
    }

    @Override
    public String toString() {
        return "Solution{assigned=" + studentToRoom.size() + ", rooms=" + roomToStudents.size() + "}";
    }
}
