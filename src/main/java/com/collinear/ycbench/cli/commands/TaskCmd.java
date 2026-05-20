package com.collinear.ycbench.cli.commands;

import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.cli.commands.task.TaskAcceptCmd;
import com.collinear.ycbench.cli.commands.task.TaskAssignCmd;
import com.collinear.ycbench.cli.commands.task.TaskCancelCmd;
import com.collinear.ycbench.cli.commands.task.TaskDispatchCmd;
import com.collinear.ycbench.cli.commands.task.TaskInspectCmd;
import com.collinear.ycbench.cli.commands.task.TaskListCmd;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code yc-bench task ...} */
@CommandLine.Command(
        name = "task",
        description = "任务接受/分配/分发/列表/检查/取消。",
        subcommands = {
                TaskAcceptCmd.class,
                TaskAssignCmd.class,
                TaskDispatchCmd.class,
                TaskListCmd.class,
                TaskInspectCmd.class,
                TaskCancelCmd.class
        })
public final class TaskCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        return JsonOutput.err("Usage: yc-bench task <accept|assign|dispatch|list|inspect|cancel>");
    }
}
