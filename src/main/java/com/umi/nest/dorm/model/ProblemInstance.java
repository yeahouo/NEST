package com.umi.nest.dorm.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次分配任务的完整输入。求解器只能看到这个对象里的一切，
 * <b>看不到</b>隐真值（{@code dorm.data.GroundTruth}）。
 *
 * @param students      全部待分配学生
 * @param rooms         全部可用房间
 * @param observations  学生 id → 问卷观测特征（可能含 NaN，已由调用方决定是否补齐）
 * @param preferences   学生 id → 志愿
 * @param configPathR   （不参与序列化）约束配置由 {@code SolveRequest} 承载
 */
public record ProblemInstance(
        List<Student> students,
        List<Room> rooms,
        Map<Long, StudentFeature> observations,
        Map<Long, Preference> preferences
) {
    public ProblemInstance {
        students = List.copyOf(students);
        rooms = List.copyOf(rooms);
        observations = Map.copyOf(observations);
        preferences = Map.copyOf(preferences);
    }

    public int studentCount() {
        return students.size();
    }

    public int totalCapacity() {
        int c = 0;
        for (Room r : rooms) {
            c += r.capacity();
        }
        return c;
    }

    public StudentFeature featureOf(long studentId) {
        return observations.get(studentId);
    }

    public Preference preferenceOf(long studentId) {
        return preferences.getOrDefault(studentId, Preference.EMPTY);
    }

    public Map<Long, Student> byId() {
        Map<Long, Student> m = new LinkedHashMap<>(students.size() * 2);
        for (Student s : students) {
            m.put(s.id(), s);
        }
        return m;
    }

    public Map<Long, Room> roomById() {
        Map<Long, Room> m = new LinkedHashMap<>(rooms.size() * 2);
        for (Room r : rooms) {
            m.put(r.id(), r);
        }
        return m;
    }

    /** 便捷构造：从学生列表直接建索引。 */
    public static ProblemInstance of(List<Student> students,
                                     List<Room> rooms,
                                     Map<Long, StudentFeature> obs,
                                     Map<Long, Preference> pref) {
        return new ProblemInstance(new ArrayList<>(students), new ArrayList<>(rooms), obs, pref);
    }
}
