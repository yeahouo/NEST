package com.umi.nest.dorm.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一对室友相容度的"可解释分解"——每个维度贡献多少、总分多少。
 * 这是四维可解释里"相容度层"的数据源。
 *
 * @param total        总分（落在 [-1, 1]）
 * @param dimensions   维度名 → 该维度的加权贡献（Σdimensions = total）
 */
public record CompatBreakdown(double total, Map<String, Double> dimensions) {

    /** 把贡献取绝对值倒序，用于"最影响这对室友的三个因素"。 */
    public Map<String, Double> orderedByImpact() {
        Map<String, Double> sorted = new LinkedHashMap<>();
        dimensions.entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** 只保留对总分影响最大的 n 个维度。 */
    public Map<String, Double> topImpact(int n) {
        Map<String, Double> out = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Double> e : orderedByImpact().entrySet()) {
            if (i++ >= n) {
                break;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
