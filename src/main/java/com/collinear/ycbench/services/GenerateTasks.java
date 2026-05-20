package com.collinear.ycbench.services;

import com.collinear.ycbench.config.DistSpec;
import com.collinear.ycbench.config.PyRandom;
import com.collinear.ycbench.config.Sampling;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.model.Domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 生成市场任务。镜像 {@code services/generate_tasks.py}。 */
public final class GenerateTasks {

    /** 前 10 个任务强制设为声望 1，以确保引导路径。 */
    private static final int[] STRATIFIED_PRESTIGE = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private GenerateTasks() {
    }

    public static final class Generated {
        public String title;
        public int requiredPrestige;
        public long rewardFundsCents;
        public double rewardPrestigeDelta;
        public double skillBoostPct;
        public int progressMilestonePct;
        public Map<Domain, Double> requirements;
        public int clientIndex;
        public int requiredTrust;
    }

    public static List<Generated> generate(long runSeed, int count, WorldConfig cfg,
                                           List<List<String>> clientSpecialties,
                                           List<Double> clientRewardMults) {
        if (count <= 0) {
            return List.of();
        }
        RngStreams streams = new RngStreams(runSeed);
        int numClients = cfg.numClients > 0 ? cfg.numClients : 1;
        List<Generated> out = new ArrayList<>(count);

        for (int idx = 1; idx <= count; idx++) {
            PyRandom rng = streams.stream("task_" + idx);
            int prestige = sampleRequiredPrestige(rng, cfg, idx - 1);
            int clientIndex = (idx - 1) % numClients;
            List<String> spec = (clientSpecialties != null && !clientSpecialties.isEmpty())
                    ? clientSpecialties.get(clientIndex % clientSpecialties.size()) : null;
            Map<Domain, Double> requirements = sampleRequirements(rng, cfg, prestige, spec);

            Generated t = makeTask(rng, cfg, prestige, idx, requirements, clientIndex);
            if (clientRewardMults != null && clientIndex < clientRewardMults.size()) {
                double m = clientRewardMults.get(clientIndex);
                t.rewardFundsCents = (long) (t.rewardFundsCents * m);
            }
            out.add(t);
        }
        return out;
    }

    /** 市场补充逻辑使用的替换任务生成器。 */
    public static Generated generateReplacement(long runSeed, int replenishCounter,
                                                int replacedPrestige, int replacedClientIndex,
                                                WorldConfig cfg, List<String> specialtyDomains) {
        RngStreams streams = new RngStreams(runSeed);
        PyRandom rng = streams.stream("replenish_" + replenishCounter);
        Map<Domain, Double> requirements = sampleRequirements(rng, cfg, replacedPrestige, specialtyDomains);
        return makeTask(rng, cfg, replacedPrestige, replenishCounter, requirements, replacedClientIndex);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static int sampleRequiredPrestige(PyRandom rng, WorldConfig cfg, int index) {
        if (index < STRATIFIED_PRESTIGE.length) {
            return STRATIFIED_PRESTIGE[index];
        }
        return (int) Sampling.sampleFromSpec(rng, cfg.dist.requiredPrestige);
    }

    private static long sampleRewardFundsCents(PyRandom rng, WorldConfig cfg, int prestige) {
        long base = (long) Sampling.sampleFromSpec(rng, cfg.dist.rewardFundsCents);
        return (long) (base * (1.0 + cfg.rewardPrestigeScale * (prestige - 1)));
    }

    private static Map<Domain, Double> sampleRequirements(PyRandom rng, WorldConfig cfg,
                                                          int prestige, List<String> specialtyDomains) {
        int k = (int) Sampling.sampleFromSpec(rng, cfg.dist.domainCount);
        List<Domain> picked = sampleDomainsWithBias(rng, k, specialtyDomains, cfg.taskSpecialtyDomainBias);
        double scale = 1.0 + cfg.prestigeQtyScale * (prestige - 1);
        Map<Domain, Double> out = new EnumMap<>(Domain.class);
        for (Domain d : picked) {
            double qty = (long) Sampling.sampleFromSpec(rng, cfg.dist.requiredQty) * scale;
            out.put(d, (double) (long) qty);
        }
        return out;
    }

    private static List<Domain> sampleDomainsWithBias(PyRandom rng, int k,
                                                      List<String> specialtyDomains, double specialtyBias) {
        if (specialtyDomains == null || specialtyDomains.isEmpty() || k <= 0) {
            return rng.sample(Domain.all(), Math.max(0, Math.min(k, Domain.all().size())));
        }
        List<Domain> available = new ArrayList<>(Domain.all());
        List<Domain> specialties = new ArrayList<>();
        for (Domain d : Domain.all()) if (specialtyDomains.contains(d.value)) specialties.add(d);

        List<Domain> picked = new ArrayList<>(k);
        Domain first;
        if (!specialties.isEmpty() && rng.random() < specialtyBias) {
            first = rng.choice(specialties);
        } else {
            first = rng.choice(available);
        }
        picked.add(first);
        available.remove(first);

        if (k > 1 && !available.isEmpty()) {
            int take = Math.min(k - 1, available.size());
            picked.addAll(rng.sample(available, take));
        }
        return picked;
    }

    private static int requiredTrustFromReward(PyRandom rng, WorldConfig cfg, long rewardCents) {
        double rewardFloor = 300_000;
        double rewardCeiling = 4_000_000;
        DistSpec d = cfg.dist.rewardFundsCents;
        if (d instanceof DistSpec.Triangular) {
            rewardFloor = ((DistSpec.Triangular) d).low;
            rewardCeiling = ((DistSpec.Triangular) d).high;
        }
        if (rewardCents <= rewardFloor) return 0;
        double rewardFrac = Math.min(1.0, (rewardCents - rewardFloor) / (rewardCeiling - rewardFloor));
        double trustProb = Math.max(0.0, (rewardFrac - cfg.trustRewardThreshold) / Math.max(1e-9, cfg.trustRewardRamp));
        if (rng.random() >= trustProb) return 0;
        int v = (int) (1 + rewardFrac * cfg.trustLevelRewardScale);
        return Math.max(1, Math.min(v, cfg.trustLevelMaxRequired));
    }

    private static Generated makeTask(PyRandom rng, WorldConfig cfg, int prestige, int serial,
                                      Map<Domain, Double> requirements, int clientIndex) {
        long reward = sampleRewardFundsCents(rng, cfg, prestige);
        int requiredTrust = requiredTrustFromReward(rng, cfg, reward);
        if (requiredTrust > 0) {
            reward = (long) (reward * (1.0 + cfg.trustGatedRewardBoost * requiredTrust));
        }
        Generated g = new Generated();
        g.title = "Task-" + serial;
        g.requiredPrestige = prestige;
        g.rewardFundsCents = reward;
        g.rewardPrestigeDelta = Sampling.sampleFromSpec(rng, cfg.dist.rewardPrestigeDelta);
        g.skillBoostPct = Sampling.sampleFromSpec(rng, cfg.dist.skillBoost);
        g.progressMilestonePct = 0;
        g.requirements = requirements;
        g.clientIndex = clientIndex;
        g.requiredTrust = requiredTrust;
        return g;
    }
}
