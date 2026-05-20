package com.collinear.ycbench.services;

import com.collinear.ycbench.config.PyRandom;
import com.collinear.ycbench.config.SalaryTierConfig;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.model.Domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 生成员工。镜像 {@code services/generate_employees.py}。
 *
 * <p>为了在不同运行种子之间保持一致性，上游代码在种子公司员工时使用固定的世界种子 (=1)
 * 用于 {@link com.collinear.ycbench.services.SeedWorld}。
 */
public final class GenerateEmployees {

    /** 10 人初创公司的固定层级组成（模块化索引处理 N）。 */
    private static final List<String> TIER_SEQUENCE = List.of(
            "junior", "junior", "junior", "junior", "junior",
            "mid", "mid", "mid",
            "senior", "senior"
    );

    private GenerateEmployees() {
    }

    public static final class Generated {
        public final String name;
        public final double workHoursPerDay;
        public final long salaryCents;
        public final String tier;
        public final Map<Domain, Double> ratesByDomain;

        Generated(String name, double workHoursPerDay, long salaryCents,
                  String tier, Map<Domain, Double> ratesByDomain) {
            this.name = name;
            this.workHoursPerDay = workHoursPerDay;
            this.salaryCents = salaryCents;
            this.tier = tier;
            this.ratesByDomain = ratesByDomain;
        }
    }

    public static List<Generated> generate(long runSeed, int count, WorldConfig cfg) {
        if (count <= 0) {
            return List.of();
        }
        RngStreams streams = new RngStreams(runSeed);

        // 层级分配
        PyRandom tierRng = streams.stream("tier_assignment");
        int seqLen = TIER_SEQUENCE.size();
        List<String> tiers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tiers.add(TIER_SEQUENCE.get(i % seqLen));
        }
        tierRng.shuffle(tiers);

        List<Generated> out = new ArrayList<>(count);
        for (int idx = 1; idx <= count; idx++) {
            PyRandom rng = streams.stream("employee_" + idx);
            String tierName = tiers.get(idx - 1);
            SalaryTierConfig tier = tierByName(cfg, tierName);

            Map<Domain, Double> rates = new EnumMap<>(Domain.class);
            for (Domain d : Domain.all()) {
                double r = rng.uniform(tier.rateMin, tier.rateMax);
                rates.put(d, round4(r));
            }
            long salary = RngHelpers.sampleRightSkewTriangularInt(rng, tier.minCents, tier.maxCents);
            out.add(new Generated(
                    "Emp_" + idx,
                    cfg.workHoursPerDay,
                    salary,
                    tierName,
                    rates));
        }
        return out;
    }

    private static SalaryTierConfig tierByName(WorldConfig cfg, String name) {
        if ("junior".equals(name)) return cfg.salaryJunior;
        if ("mid".equals(name)) return cfg.salaryMid;
        if ("senior".equals(name)) return cfg.salarySenior;
        throw new IllegalArgumentException("Unknown tier: " + name);
    }

    private static double round4(double v) {
        return Math.round(v * 1.0e4) / 1.0e4;
    }
}
