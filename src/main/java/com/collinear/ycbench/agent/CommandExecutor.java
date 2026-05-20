package com.collinear.ycbench.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 将 {@code yc-bench ...} 作为子进程运行并规范化结果。
 *
 * <p>镜像 {@code agent/commands/executor.py + policy.py}：验证命令以 {@code yc-bench} 开头，
 * 使用简单的 shell 风格分词进行拆分，然后 fork 一个 JVM 运行 {@link com.collinear.ycbench.cli.Main}。
 */
public final class CommandExecutor {

    /** 结果镜像 Python 中的 {@code RunCommandResult}。 */
    public static final class Result {
        public boolean ok;
        public int exitCode;
        public String stdout = "";
        public String stderr = "";
        public String simTime;       // always null here (preserved for compatibility)
        public String command;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("exit_code", exitCode);
            m.put("stdout", stdout);
            m.put("stderr", stderr);
            m.put("sim_time", simTime);
            m.put("command", command);
            return m;
        }
    }

    private final long timeoutSeconds;
    private final String javaBin;
    private final String classpath;

    public CommandExecutor(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        // 复用当前 JVM 和 classpath，这样我们就不需要构建 fat-jar。
        this.javaBin = System.getProperty("java.home") + "/bin/java";
        this.classpath = System.getProperty("java.class.path");
    }

    public Result run(String command) {
        Result r = new Result();
        r.command = command;
        List<String> argv = parseBenchCommand(command);
        if (argv == null) {
            r.ok = false;
            r.exitCode = 2;
            r.stderr = "only top-level `yc-bench` commands are allowed";
            return r;
        }

        // 删除前导的 "yc-bench" 令牌 — 我们直接启动 Java Main。
        List<String> tail = argv.subList(1, argv.size());
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("com.collinear.ycbench.cli.Main");
        cmd.addAll(tail);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            String stdout = readAll(p.getInputStream());
            String stderr = readAll(p.getErrorStream());
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                r.ok = false;
                r.exitCode = 124;
                r.stdout = stdout;
                r.stderr = "command timed out after " + timeoutSeconds + " seconds";
                return r;
            }
            int code = p.exitValue();
            r.exitCode = code;
            r.ok = code == 0;
            r.stdout = stdout;
            r.stderr = stderr;
            return r;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            r.ok = false;
            r.exitCode = 1;
            r.stderr = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return r;
        }
    }

    /** 用于 {@code yc-bench …} 命令的非常小的 shell 风格分词器。 */
    static List<String> parseBenchCommand(String command) {
        if (command == null) return null;
        String s = command.trim();
        if (s.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    quote = c;
                } else if (Character.isWhitespace(c)) {
                    if (cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                } else if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty() || !"yc-bench".equals(out.get(0))) return null;
        return out;
    }

    private static String readAll(java.io.InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
