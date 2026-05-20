package com.collinear.ycbench.config;

/**
 * 世界生成器使用的随机数量规格。镜像 {@code config/schema.py} 中的
 * {@code WorldDists} Pydantic 模型。
 */
public final class WorldDists {
    public DistSpec requiredPrestige;       // yaml: required_prestige（所需声望）
    public DistSpec rewardFundsCents;       // yaml: reward_funds_cents（奖励资金美分）
    public DistSpec domainCount;            // yaml: domain_count（领域数量）
    public DistSpec requiredQty;            // yaml: required_qty（所需数量）
    public DistSpec rewardPrestigeDelta;    // yaml: reward_prestige_delta（奖励声望增量）
    public DistSpec requiredTrust;          // yaml: required_trust（所需信任）
    public DistSpec skillBoost;             // yaml: skill_boost（技能提升）
}
