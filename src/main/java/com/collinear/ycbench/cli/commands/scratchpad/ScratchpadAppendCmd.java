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

@CommandLine.Command(name = "append", description = "追加文本到 scratchpad（换行符分隔）。")
public final class ScratchpadAppendCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--content", required = true)
    String content;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            Scratchpad s = ScratchpadDao.find(db, st.companyId);
            String existing = s == null ? "" : s.content;
            String next = existing.isEmpty() ? content : existing + "\n" + content;
            ScratchpadDao.upsert(db, st.companyId, next);
            return JsonOutput.ok(Map.of("ok", true, "content", next));
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("scratchpad append failed: " + ex.getMessage());
        }
    }
}
