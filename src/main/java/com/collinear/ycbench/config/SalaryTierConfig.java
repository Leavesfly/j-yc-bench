package com.collinear.ycbench.config;

/**
 * 一个薪资层级（初级/中级/高级）。镜像 {@code config/schema.py} 中的
 * {@code SalaryTierConfig}。
 *
 * <p>字段名称与 YAML 键完全匹配，以便 Jackson 自动绑定。
 */
public final class SalaryTierConfig {
    public String name;
    public double share;       // 此层级中员工的比例（所有层级之和必须为 1.0）
    public long minCents;      // 最低月薪（美分）（yaml: min_cents）
    public long maxCents;      // 最高月薪（美分）（yaml: max_cents）
    public double rateMin;     // 最低技能速率（单位/小时）         （yaml: rate_min）
    public double rateMax;     // 最高技能速率（单位/小时）         （yaml: rate_max）
}
