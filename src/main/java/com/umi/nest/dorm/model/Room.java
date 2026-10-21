package com.umi.nest.dorm.model;

/**
 * 房间（床位资源）。
 *
 * @param id              房间编号（主键，仅用于内部索引）
 * @param roomNo          房号，人读用，例如 "305"；可为空，为空时回退到楼栋+编号
 * @param buildingId      楼栋编号，分块键之一
 * @param gender          该房间允许的性别，硬约束
 * @param capacity        容量（典型为 4，也有 2/6）
 * @param forSpecialNeeds 是否为特殊需求房间（仅特殊需求学生可入住）
 * @param gradeRestriction 年级限制，null 表示不限；非空时只允许该年级学生入住
 */
public record Room(
        long id,
        String roomNo,
        String buildingId,
        Gender gender,
        int capacity,
        boolean forSpecialNeeds,
        String gradeRestriction
) {
    public Room {
        if (capacity <= 0) {
            throw new IllegalArgumentException("房间容量必须为正: " + id);
        }
    }

    public static Room of(long id, String buildingId, Gender gender, int capacity) {
        return new Room(id, null, buildingId, gender, capacity, false, null);
    }

    public static Room of(long id, String roomNo, String buildingId, Gender gender, int capacity) {
        return new Room(id, roomNo, buildingId, gender, capacity, false, null);
    }

    /**
     * 人读标签。优先用房号——学生看到的必须是"1 号楼 305"，
     * 而不是数据库主键 {@code M1-12}：申诉页面写着"M1-12"没人能走对宿舍。
     */
    public String label() {
        String b = buildingId == null ? "?" : buildingId;
        if (roomNo != null && !roomNo.isBlank()) {
            return b + "-" + roomNo;
        }
        return b + "-" + id;
    }
}
