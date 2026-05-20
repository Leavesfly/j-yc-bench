package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.company.CompanyStatusCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench company ...} */
@CommandLine.Command(
        name = "company",
        description = "公司状态、声望、财务。",
        subcommands = {CompanyStatusCmd.class})
public final class CompanyCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench company <status>");
    }
}
