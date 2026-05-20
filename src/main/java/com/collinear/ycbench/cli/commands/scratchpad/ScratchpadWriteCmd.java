package com.collinear.ycbench.cli.commands.scratchpad;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.model.SimState;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "write", description = "覆盖 scratchpad 内容。")
public final class ScratchpadWriteCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--content", required = true)
    String content;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            ScratchpadDao.upsert(db, st.companyId, content);
            return JsonOutput.ok(Map.of("ok", true, "content", content));
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("scratchpad write failed: " + ex.getMessage());
        }
    }
}
