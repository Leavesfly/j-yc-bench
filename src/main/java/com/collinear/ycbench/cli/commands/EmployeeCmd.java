package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.employee.EmployeeListCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench employee ...} */
@CommandLine.Command(
        name = "employee",
        description = "列出、检查和观察员工。",
        subcommands = {EmployeeListCmd.class})
public final class EmployeeCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench employee <list>");
    }
}
