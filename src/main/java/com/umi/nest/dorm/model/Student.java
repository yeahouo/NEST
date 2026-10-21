package com.umi.nest.dorm.model;

/**
 * 学生（只含"硬属性"，即分块与硬约束需要的字段）。
 * <p>软特征（作息、抽烟、卫生、性格等）放在 {@link StudentFeature}，
 * 志愿放在 {@link Preference}，三者按 studentId 关联。
 */
public record Student(
        long id,
        String name,
        Gender gender,
        String grade,
        String major,
        String majorCluster,
        String buildingPref,
        SpecialNeed specialNeed
) {
    public Student {
        if (grade == null) {
            grade = "";
        }
        if (majorCluster == null || majorCluster.isBlank()) {
            majorCluster = major == null ? "UNKNOWN" : major;
        }
        if (specialNeed == null) {
            specialNeed = SpecialNeed.NONE;
        }
    }

    /** 老生＝年级更小（数字更小），新生＝数字更大。用于"年级不混住"策略排序。 */
    public boolean isSpecial() {
        return specialNeed.isSpecial();
    }
}
