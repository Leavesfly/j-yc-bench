package com.collinear.ycbench.cli.commands.scratchpad;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.model.Scratchpad;
import com.collinear.ycbench.db.model.SimState;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "read", description = "读取 scratchpad 内容。")
public final class ScratchpadReadCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            Scratchpad s = ScratchpadDao.find(db, st.companyId);
            String content = s == null ? "" : s.content;
            return JsonOutput.ok(Map.of("content", content));
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("scratchpad read failed: " + ex.getMessage());
        }
    }
}
