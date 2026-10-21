package com.umi.nest.dorm.model;

/**
 * 性别。硬约束：房间的性别限制必须与入住学生的性别一致。
 */
public enum Gender {
    MALE,
    FEMALE;

    public static Gender parse(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toUpperCase();
        if (t.startsWith("M") || t.equals("男") || t.equals("1")) {
            return MALE;
        }
        if (t.startsWith("F") || t.equals("女") || t.equals("0")) {
            return FEMALE;
        }
        throw new IllegalArgumentException("无法解析性别: " + s);
    }

    /** 便于分块键拼接。 */
    public String code() {
        return this == MALE ? "M" : "F";
    }
}
