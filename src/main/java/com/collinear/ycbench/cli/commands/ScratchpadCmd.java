package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.scratchpad.ScratchpadAppendCmd;
import com.collinear.ycbench.cli.commands.scratchpad.ScratchpadClearCmd;
import com.collinear.ycbench.cli.commands.scratchpad.ScratchpadReadCmd;
import com.collinear.ycbench.cli.commands.scratchpad.ScratchpadWriteCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench scratchpad ...} */
@CommandLine.Command(
        name = "scratchpad",
        description = "读取/写入 agent 的笔记 scratchpad。",
        subcommands = {
                ScratchpadReadCmd.class,
                ScratchpadWriteCmd.class,
                ScratchpadAppendCmd.class,
                ScratchpadClearCmd.class
        })
public final class ScratchpadCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench scratchpad <read|write|append|clear>");
    }
}
