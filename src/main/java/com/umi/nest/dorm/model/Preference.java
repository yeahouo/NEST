package com.umi.nest.dorm.model;

import java.util.List;

/**
 * 学生志愿（软约束，进入目标函数的志愿满足项）。
 *
 * @param studentId           学生编号
 * @param preferredRoommates  希望同住的同学 id（有序，靠前权重更高）
 * @param avoidRoommates      明确不希望同住的同学 id
 * @param preferredBuildings  楼栋志愿
 */
public record Preference(
        long studentId,
        List<Long> preferredRoommates,
        List<Long> avoidRoommates,
        List<String> preferredBuildings
) {
    public static final Preference EMPTY = new Preference(0L, List.of(), List.of(), List.of());

    public Preference {
        preferredRoommates = preferredRoommates == null ? List.of() : List.copyOf(preferredRoommates);
        avoidRoommates = avoidRoommates == null ? List.of() : List.copyOf(avoidRoommates);
        preferredBuildings = preferredBuildings == null ? List.of() : List.copyOf(preferredBuildings);
    }

    public boolean isEmpty() {
        return preferredRoommates.isEmpty() && avoidRoommates.isEmpty() && preferredBuildings.isEmpty();
    }
}
