package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.sim.SimInitCmd;
import com.collinear.ycbench.cli.commands.sim.SimResumeCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench sim ...} — 模拟生命周期（init + resume）。*/
@CommandLine.Command(
        name = "sim",
        description = "模拟初始化和时间推进。",
        subcommands = {SimInitCmd.class, SimResumeCmd.class})
public final class SimCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench sim <init|resume>");
    }
}
