package com.collinear.ycbench.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 从内置预设名称或 YAML 文件路径加载 {@link ExperimentConfig}。
 *
 * <p>镜像 {@code config/loader.py}：
 * <ul>
 *   <li>如果参数以 {@code .yaml}/{@code .yml} 结尾或包含路径分隔符，
 *       则视为文件路径。</li>
 *   <li>否则视为预设名称，并在 {@code resources/presets/&lt;name&gt;.yaml} 处查找。</li>
 *   <li>YAML 根部的 {@code extends: <preset>} 继承自内置预设并
 *       在此之上合并此文件（深度合并）。</li>
 *   <li>环境变量 {@code YC_BENCH_MODEL}、{@code YC_BENCH_TEMPERATURE}、
 *       {@code YC_BENCH_TOP_P}、{@code YC_BENCH_HISTORY_KEEP_ROUNDS}、
 *       {@code YC_BENCH_AUTO_ADVANCE_TURNS} 具有最终优先级。</li>
 * </ul>
 */
public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConfigLoader() {
    }

    public static ExperimentConfig load(String pathOrName) {
        Map<String, Object> raw = readRaw(pathOrName);

        // 处理 `extends:` 继承
        Object extendsValue = raw.remove("extends");
        if (extendsValue instanceof String) {
            Map<String, Object> base = readPreset((String) extendsValue);
            raw = deepMerge(base, raw);
        }

        ExperimentConfig cfg = YAML.convertValue(raw, ExperimentConfig.class);
        if (cfg.world != null) {
            cfg.world.deriveTrustParams();
            cfg.world.validate();
        }
        applyEnvOverrides(cfg);
        return cfg;
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readRaw(String pathOrName) {
        boolean looksLikeFile = pathOrName.endsWith(".yaml")
                || pathOrName.endsWith(".yml")
                || pathOrName.contains("/")
                || pathOrName.contains("\\");
        if (looksLikeFile) {
            Path p = Path.of(pathOrName);
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("Config file not found: " + p.toAbsolutePath());
            }
            try {
                return YAML.readValue(p.toFile(), Map.class);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to parse " + p + ": " + ex.getMessage(), ex);
            }
        }
        return readPreset(pathOrName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readPreset(String name) {
        String resource = "/presets/" + name + ".yaml";
        try (InputStream in = ConfigLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "Unknown preset '" + name + "'. "
                                + "Expected resource at " + resource
                                + ". Pass a file path ending in .yaml for a custom config.");
            }
            return YAML.readValue(in, Map.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read preset '" + name + "': " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new HashMap<>(base);
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object existing = result.get(e.getKey());
            if (existing instanceof Map && e.getValue() instanceof Map) {
                result.put(e.getKey(),
                        deepMerge((Map<String, Object>) existing, (Map<String, Object>) e.getValue()));
            } else {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    private static void applyEnvOverrides(ExperimentConfig cfg) {
        String v;
        if ((v = System.getenv("YC_BENCH_MODEL")) != null) {
            cfg.agent.model = v;
        }
        if ((v = System.getenv("YC_BENCH_TEMPERATURE")) != null) {
            cfg.agent.temperature = Double.parseDouble(v);
        }
        if ((v = System.getenv("YC_BENCH_TOP_P")) != null) {
            cfg.agent.topP = Double.parseDouble(v);
        }
        if ((v = System.getenv("YC_BENCH_HISTORY_KEEP_ROUNDS")) != null) {
            cfg.agent.historyKeepRounds = Integer.parseInt(v);
        }
        if ((v = System.getenv("YC_BENCH_AUTO_ADVANCE_TURNS")) != null) {
            cfg.loop.autoAdvanceAfterTurns = Integer.parseInt(v);
        }
    }

    /**
     * 便捷方法：加载活动实验（通过 {@code YC_BENCH_EXPERIMENT} 环境变量，
     * 默认为 {@code "default"}）并仅返回其 {@link WorldConfig}。
     */
    public static WorldConfig getWorldConfig() {
        String name = System.getenv().getOrDefault("YC_BENCH_EXPERIMENT", "default");
        return load(name).world;
    }
}
