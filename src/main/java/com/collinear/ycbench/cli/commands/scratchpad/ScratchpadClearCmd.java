package com.collinear.ycbench.cli.commands.scratchpad;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.model.SimState;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "clear", description = "清空 scratchpad。")
public final class ScratchpadClearCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            ScratchpadDao.upsert(db, st.companyId, "");
            return JsonOutput.ok(Map.of("ok", true));
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("scratchpad clear failed: " + ex.getMessage());
        }
    }
}
