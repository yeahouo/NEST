package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Student;

import java.util.List;

/**
 * 一个"块"（block decomposition 的产物）：可以独立求解的最小单元。
 * <p>块由 {@code 性别 × 专业大类 × 年级} 的键定义，并独占一组房间。
 * 块之间没有任何耦合——这正是"万级数据跑得动"的原因：
 * 求解复杂度从 O(全局) 降到 O(最大块)，而块可以并行。
 *
 * @param key      块键，例如 {@code M|N|信息类|2024}
 * @param students 块内学生
 * @param rooms    分配给该块的全部房间（容量之和 ≥ 学生数）
 */
public record Block(String key, List<Student> students, List<Room> rooms) {

    public Block {
        students = List.copyOf(students);
        rooms = List.copyOf(rooms);
    }

    public int studentCount() {
        return students.size();
    }

    public int capacity() {
        int c = 0;
        for (Room r : rooms) {
            c += r.capacity();
        }
        return c;
    }

    public List<Long> studentIds() {
        return students.stream().map(Student::id).toList();
    }

    @Override
    public String toString() {
        return "Block{" + key + ", students=" + students.size() + ", rooms=" + rooms.size()
                + ", capacity=" + capacity() + "}";
    }
}
