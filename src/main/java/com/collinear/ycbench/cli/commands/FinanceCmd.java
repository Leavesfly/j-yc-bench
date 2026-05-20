package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.finance.FinanceLedgerCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench finance ...} */
@CommandLine.Command(
        name = "finance",
        description = "账本查询和月度财务摘要。",
        subcommands = {FinanceLedgerCmd.class})
public final class FinanceCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench finance <ledger>");
    }
}
