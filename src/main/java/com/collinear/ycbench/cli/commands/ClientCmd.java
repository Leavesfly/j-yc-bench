package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.client.ClientHistoryCmd;
import com.collinear.ycbench.cli.commands.client.ClientListCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench client ...} */
@CommandLine.Command(
        name = "client",
        description = "客户列表和信任查询。",
        subcommands = {ClientListCmd.class, ClientHistoryCmd.class})
public final class ClientCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench client <list|history>");
    }
}
