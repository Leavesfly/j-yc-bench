package com.collinear.ycbench.web;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;

/**
 * Web UI 命令 — 启动交互式 Web 界面查看仿真状态。
 *
 * 用法: java -jar j-yc-bench.jar web --db db/default_1_ollama_qwen3.5_4b.db
 */
@Command(name = "web", description = "Launch interactive Web UI for simulation visualization")
public class WebCmd implements Runnable {

    @Option(names = {"-p", "--port"}, description = "Server port (default: 8080)", defaultValue = "8080")
    private int port;

    @Option(names = {"--db"}, description = "SQLite database file path (default: db/yc_bench.db)",
            defaultValue = "db/yc_bench.db")
    private String dbPath;

    @Option(names = {"--model"}, description = "LLM model for simulation (default: ollama/qwen3.5:4b)",
            defaultValue = "ollama/qwen3.5:4b")
    private String model;

    @Option(names = {"--seed"}, description = "Random seed (default: 1)", defaultValue = "1")
    private long seed;

    @Option(names = {"--config"}, description = "Experiment config name (default: default)",
            defaultValue = "default")
    private String config;

    @Override
    public void run() {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        // 验证数据库文件是否存在
        File dbFile = new File(dbPath);
        if (!dbFile.exists()) {
            System.err.println("❌ Database not found: " + dbPath);
            System.err.println("   Run a simulation first: java -jar j-yc-bench.jar run --seed 1");
            System.err.println("   Or specify db path: java -jar j-yc-bench.jar web --db <path>");
            System.exit(1);
        }

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║       j-yc-bench Web Interactive Dashboard          ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  Database: " + padRight(dbPath, 41) + "║");
        System.out.println("║  Port:     " + padRight(String.valueOf(port), 41) + "║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // 设置仿真控制器的默认参数
        SimulationController.setDefaults(model, seed, config);

        WebServer.start(port, jdbcUrl);

        System.out.println();
        System.out.println("🌐 Open http://localhost:" + port + " in your browser");
        System.out.println("   Press Ctrl+C to stop the server");

        // 阻塞主线程
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
