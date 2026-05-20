package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.market.MarketBrowseCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench market ...} */
@CommandLine.Command(
        name = "market",
        description = "浏览市场任务。",
        subcommands = {MarketBrowseCmd.class})
public final class MarketCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench market <browse>");
    }
}
