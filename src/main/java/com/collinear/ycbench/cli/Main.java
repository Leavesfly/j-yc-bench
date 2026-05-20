package com.collinear.ycbench.cli;

import com.collinear.ycbench.cli.commands.ClientCmd;
import com.collinear.ycbench.cli.commands.CompanyCmd;
import com.collinear.ycbench.cli.commands.EmployeeCmd;
import com.collinear.ycbench.cli.commands.FinanceCmd;
import com.collinear.ycbench.cli.commands.MarketCmd;
import com.collinear.ycbench.cli.commands.ReportCmd;
import com.collinear.ycbench.cli.commands.RunCmd;
import com.collinear.ycbench.cli.commands.ScratchpadCmd;
import com.collinear.ycbench.cli.commands.SimCmd;
import com.collinear.ycbench.cli.commands.TaskCmd;
import picocli.CommandLine;

/**
 * j-yc-bench CLI 的顶层入口点。
 *
 * <p>精确镜像 Python `yc-bench` Typer 应用：每个子命令在 stdout 上打印一个 JSON
 * 文档，失败时以非零退出码和 {"error": "..."} 载荷退出。
 */
@CommandLine.Command(
        name = "yc-bench",
        mixinStandardHelpOptions = true,
        version = "j-yc-bench 0.1.0",
        description = "Long-horizon deterministic benchmark for LLM agents (Java port).",
        subcommands = {
                SimCmd.class,
                CompanyCmd.class,
                EmployeeCmd.class,
                MarketCmd.class,
                TaskCmd.class,
                FinanceCmd.class,
                ReportCmd.class,
                ScratchpadCmd.class,
                ClientCmd.class,
                RunCmd.class
        }
)
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.err);
    }

    public static void main(String[] args) {
        // 镜像 Python 的 load_dotenv(find_dotenv(usecwd=True), override=False)。
        DotEnv.loadFromCwdOrAncestors();
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
