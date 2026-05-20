package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.runner.RunArgs;
import com.collinear.ycbench.runner.RunCmdMain;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench run …} — agent 基准测试驱动程序。*/
@CommandLine.Command(
        name = "run",
        description = "端到端驱动完整的 agent 基准测试运行。")
public final class RunCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--model",
            description = "LLM 模型标识符（例如 ollama/qwen3.5:4b、openai/gpt-4o）。默认使用配置文件中的值。")
    String model;

    @CommandLine.Option(names = "--seed", defaultValue = "1", description = "运行种子（确定性）。默认为 1。")
    long seed;

    @CommandLine.Option(names = "--horizon-years",
            description = "覆盖配置中的 sim.horizon_years。")
    Integer horizonYears;

    @CommandLine.Option(names = "--company-name", defaultValue = "BenchCo")
    String companyName;

    @CommandLine.Option(names = "--start-date", defaultValue = "2025-01-01",
            description = "模拟开始日期（YYYY-MM-DD）。")
    String startDate;

    @CommandLine.Option(names = "--config", defaultValue = "default",
            description = "预设名称或 .yaml 配置文件的路径。")
    String configName;

    @CommandLine.Option(names = "--no-live", defaultValue = "false",
            description = "禁用实时仪表板（Java 端口目前没有 Rich 仪表板）。")
    boolean noLive;

    @CommandLine.Option(names = "--max-episodes", defaultValue = "1",
            description = "最大剧集数（破产后重启并携带 scratchpad）。")
    int maxEpisodes;

    @Override
    public Integer call() {
        if (horizonYears != null && horizonYears <= 0) {
            System.err.println("--horizon-years must be > 0");
            return 2;
        }
        if (maxEpisodes < 1) {
            System.err.println("--max-episodes must be >= 1");
            return 2;
        }
        RunArgs args = new RunArgs();
        args.model = model;
        args.seed = seed;
        args.horizonYears = horizonYears;
        args.companyName = companyName;
        args.startDate = startDate;
        args.configName = configName;
        args.noLive = noLive;
        args.maxEpisodes = maxEpisodes;
        return RunCmdMain.run(args);
    }
}
