package com.collinear.ycbench.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 轻量级 {@code .env} 加载器。镜像 {@code python-dotenv} 的
 * {@code load_dotenv(find_dotenv(usecwd=True), override=False)} 行为，由
 * {@code runner/main.py} 使用。
 *
 * <p>搜索顺序：当前工作目录及其祖先目录，选择遇到的第一个
 * {@code .env} 文件。每个 {@code KEY=VALUE} 行被设置为系统属性
 * （因此 {@link Database} 和 {@link com.collinear.ycbench.agent.runtime.HttpLlmRuntime}
 * 通过 {@code System.getenv} 回退到 {@code System.getProperty} 来获取）。
 *
 * <p>{@code override=false}：已存在的环境变量/系统属性优先。
 */
public final class DotEnv {

    private static final Logger LOG = Logger.getLogger(DotEnv.class.getName());
    private static boolean loaded = false;

    private DotEnv() {
    }

    /** 幂等——只有第一次调用才会执行实际工作。*/
    public static synchronized void loadFromCwdOrAncestors() {
        if (loaded) return;
        loaded = true;
        Path found = findDotEnv(Path.of("").toAbsolutePath());
        if (found == null) return;
        try {
            apply(Files.readString(found, StandardCharsets.UTF_8));
            LOG.fine("Loaded .env from " + found);
        } catch (IOException ex) {
            LOG.warning("Failed to read .env at " + found + ": " + ex.getMessage());
        }
    }

    static Path findDotEnv(Path start) {
        Path p = start;
        while (p != null) {
            Path candidate = p.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;
            p = p.getParent();
        }
        return null;
    }

    /** 解析 .env 风格的文档并将未设置的键设置为系统属性。*/
    static void apply(String content) {
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            value = stripQuotes(value);

            // override=false：已有的环境变量/系统属性优先。
            if (System.getenv(key) != null) continue;
            if (System.getProperty(key) != null) continue;
            System.setProperty(key, value);
        }
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }
}
