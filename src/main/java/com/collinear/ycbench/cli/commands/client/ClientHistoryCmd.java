package com.collinear.ycbench.cli.commands.client;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "history", description = "每个客户的成功/失败历史。")
public final class ClientHistoryCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            List<Client> clients = ClientDao.listAllOrderedByName(db);
            List<Map<String, Object>> rows = new ArrayList<>(clients.size());
            for (Client c : clients) {
                int success = TaskDao.countByCompanyAndClientAndStatus(db, st.companyId, c.id, TaskStatus.COMPLETED_SUCCESS);
                int fail = TaskDao.countByCompanyAndClientAndStatus(db, st.companyId, c.id, TaskStatus.COMPLETED_FAIL);
                ClientTrust ct = ClientDao.findTrust(db, st.companyId, c.id);
                int total = success + fail;
                double failRate = total > 0 ? Math.round((double) fail / total * 1000.0) / 10.0 : 0.0;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("client_name", c.name);
                r.put("trust_level", ct == null ? 0.0 : ct.trustLevel);
                r.put("tasks_succeeded", success);
                r.put("tasks_failed", fail);
                r.put("failure_rate_pct", failRate);
                rows.add(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", rows.size());
            out.put("client_history", rows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("client history failed: " + ex.getMessage());
        }
    }
}
