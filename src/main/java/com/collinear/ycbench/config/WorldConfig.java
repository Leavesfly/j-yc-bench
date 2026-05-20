package com.collinear.ycbench.config;

import java.util.List;

/**
 * 所有世界生成参数。逐字段镜像 {@code config/schema.py} 中的 {@code WorldConfig}，
 * 包括 {@link #deriveTrustParams()} 在 YAML 反序列化后重新计算的派生信任/忠诚度参数。
 *
 * <p>Java 中的字段名称使用 camelCase；YAML 加载器通过 Jackson 的
 * {@code PropertyNamingStrategies.SNAKE_CASE} 将 {@code snake_case} 映射为 camelCase。
 */
public final class WorldConfig {
    // --- 劳动力 ---
    public int numEmployees;
    public long initialFundsCents;
    public double initialPrestigeLevel;
    public double workHoursPerDay;

    // --- 市场 ---
    public int numMarketTasks;
    public int marketBrowseDefaultLimit;

    // --- 任务完成时的薪资提升 ---
    public double salaryBumpPct;
    public long salaryMaxCents;
    public double skillRateMax;

    // --- 声望机制 ---
    public double prestigeMax;
    public double prestigeMin;
    public double penaltyFailMultiplier;
    public double penaltyFailFundsPct = 0.0;
    public double penaltyCancelMultiplier;
    public double rewardPrestigeScale;
    public double prestigeDecayPerDay;

    // --- 客户信任（直观控制项）---
    public int numClients;
    public double trustMax;
    public double trustBuildRate;
    public double trustFragility;
    public double trustFocusPressure;
    public double trustRewardCeiling;
    public double trustWorkReductionMax;
    public double trustGatingFraction;

    // --- 客户忠诚度（对抗性客户）---
    public double loyaltyRatFraction = 0.15;
    public double loyaltySeverity = 0.5;
    public double loyaltyRevealTrust = 2.0;

    // --- 派生信任参数（由 deriveTrustParams 设置）---
    public double trustMin = 0.0;
    public double trustGainBase = 0.0;
    public double trustGainDiminishingPower = 1.5;
    public double trustFailPenalty = 0.0;
    public double trustCancelPenalty = 0.0;
    public double trustDecayPerDay = 0.0;
    public double trustCrossClientDecay = 0.0;
    public double trustBaseMultiplier = 0.50;
    public double trustRewardScale = 0.0;
    public double trustRewardThreshold = 0.0;
    public double trustRewardRamp = 0.0;
    public double trustLevelRewardScale = 3.0;
    public int trustLevelMaxRequired = 4;
    public double trustGatedRewardBoost = 0.15;
    public double clientRewardMultLow = 0.7;
    public double clientRewardMultHigh = 2.5;
    public double clientRewardMultMode = 1.0;
    public double clientSingleSpecialtyProb = 0.6;
    public double clientTierPremiumThreshold = 1.0;
    public double clientTierEnterpriseThreshold = 1.7;
    public double taskSpecialtyDomainBias = 0.7;

    // --- 派生忠诚度参数（由 deriveTrustParams 设置）---
    public double loyaltyMode = 0.61;
    public double scopeCreepMax = 0.35;
    public double disputeClawbackMax = 0.40;
    public double disputeProbMax = 0.25;

    // --- 任务缩放 ---
    public double prestigeQtyScale;
    public double deadlineQtyPerDay;
    public int deadlineMinBizDays;

    // --- 进度里程碑 ---
    public List<Double> taskProgressMilestones;

    // --- 工作时间 ---
    public int workdayStartHour;
    public int workdayEndHour;

    // --- 分布 ---
    public WorldDists dist;

    // --- 薪资层级 ---
    public SalaryTierConfig salaryJunior;
    public SalaryTierConfig salaryMid;
    public SalaryTierConfig salarySenior;

    /**
     * 从直观控制项重新推导信任/忠诚度参数。必须在反序列化后调用一次
     * （加载器会自动执行此操作）。
     */
    public void deriveTrustParams() {
        // trust_build_rate → gain_base
        trustGainBase = trustMax * 1.6 / trustBuildRate;
        // trust_fragility → fail/cancel/decay
        trustFailPenalty = trustFragility * 0.6;
        trustCancelPenalty = trustFragility * 1.0;
        trustDecayPerDay = trustFragility * 0.03;
        // trust_focus_pressure → cross-client decay
        trustCrossClientDecay = trustFocusPressure * 0.06;
        // trust_reward_ceiling → reward_scale  （Premium ref mult = 1.3, squared = 1.69）
        double refMultSq = 1.69;
        trustRewardScale = (trustRewardCeiling - trustBaseMultiplier) / (refMultSq * trustMax);
        // gating
        trustRewardThreshold = Math.max(0.0, 1.0 - 2.0 * trustGatingFraction);
        trustRewardRamp = Math.min(1.0, 2.0 * trustGatingFraction);
        // loyalty
        loyaltyMode = 1.0 - 2.6 * loyaltyRatFraction;
        scopeCreepMax = loyaltySeverity * 1.00;
        disputeClawbackMax = loyaltySeverity * 1.20;
        disputeProbMax = loyaltySeverity * 1.00;
    }

    /** 验证薪资层级份额总和。配置错误时抛出异常。*/
    public void validate() {
        double total = salaryJunior.share + salaryMid.share + salarySenior.share;
        if (Math.abs(total - 1.0) > 1e-6) {
            throw new IllegalStateException(
                    String.format("salary tier shares must sum to 1.0, got %.6f", total));
        }
    }
}
