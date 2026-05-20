package com.collinear.ycbench.services;

import com.collinear.ycbench.config.PyRandom;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.model.Domain;

import java.util.ArrayList;
import java.util.List;

/** 生成客户。镜像 {@code services/generate_clients.py}。 */
public final class GenerateClients {

    private static final List<String> NAME_POOL = List.of(
            "Nexus AI", "Vertex Labs", "Quantum Dynamics", "Atlas Computing",
            "Helix Systems", "Orion Data", "Cipher Corp", "Prism Analytics",
            "Nova Research", "Zenith Technologies", "Apex Robotics", "Stratos Cloud",
            "Vanguard ML", "Equinox Labs", "Cortex Intelligence"
    );

    private GenerateClients() {
    }

    public static final class Generated {
        public String name;
        public double rewardMultiplier;
        public String tier = "Standard";
        public List<String> specialtyDomains = List.of();
        public double loyalty;
    }

    public static List<Generated> generate(long runSeed, int count, WorldConfig cfg) {
        if (count <= 0) {
            return List.of();
        }
        if (count > NAME_POOL.size()) {
            throw new IllegalArgumentException(
                    "count (" + count + ") exceeds available client names (" + NAME_POOL.size() + ")");
        }
        RngStreams streams = new RngStreams(runSeed);
        PyRandom rng = streams.stream("clients");
        List<String> names = rng.sample(NAME_POOL, count);

        int nRats = Math.max(1, (int) Math.round(count * cfg.loyaltyRatFraction));

        List<Generated> clients = new ArrayList<>(count);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            double mult = round2(rng.triangular(
                    cfg.clientRewardMultLow, cfg.clientRewardMultHigh, cfg.clientRewardMultMode));
            String tier = tierFromMultiplier(mult, cfg);

            int nSpec = rng.random() < cfg.clientSingleSpecialtyProb ? 1 : 2;
            List<Domain> picked = rng.sample(Domain.all(), nSpec);
            List<String> specs = new ArrayList<>(picked.size());
            for (Domain d : picked) specs.add(d.value);

            double loyalty;
            if (i < nRats) {
                // RAT：忠诚度在 [-1.0, -0.3]；将奖励乘数提升到前 30%
                loyalty = round3(rng.uniform(-1.0, -0.3));
                mult = Math.max(mult, cfg.clientRewardMultHigh * 0.75);
            } else {
                loyalty = round3(rng.triangular(-0.3, 1.0, cfg.loyaltyMode));
            }

            Generated c = new Generated();
            c.name = name;
            c.rewardMultiplier = mult;
            c.tier = tier;
            c.specialtyDomains = specs;
            c.loyalty = loyalty;
            clients.add(c);
        }
        // 打乱顺序，使 RAT 不总是排在前面
        rng.shuffle(clients);
        return clients;
    }

    private static String tierFromMultiplier(double mult, WorldConfig cfg) {
        if (mult < cfg.clientTierPremiumThreshold) return "Standard";
        if (mult < cfg.clientTierEnterpriseThreshold) return "Premium";
        return "Enterprise";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
