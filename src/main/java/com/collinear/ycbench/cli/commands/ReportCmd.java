package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.report.ReportMonthlyCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench report ...} */
@CommandLine.Command(
        name = "report",
        description = "视野结束时的报告。",
        subcommands = {ReportMonthlyCmd.class})
public final class ReportCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench report <monthly>");
    }
}
