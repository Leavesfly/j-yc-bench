package com.collinear.ycbench.cli.commands.client;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.SimState;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list", description = "列出客户及其当前信任级别。")
public final class ClientListCmd implements Callable<Integer> {
    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            List<Client> clients = ClientDao.listAllOrderedByName(db);
            List<Map<String, Object>> rows = new ArrayList<>(clients.size());
            for (Client c : clients) {
                ClientTrust ct = ClientDao.findTrust(db, st.companyId, c.id);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("client_id", c.id.toString());
                r.put("name", c.name);
                r.put("trust_level", ct == null ? 0.0 : ct.trustLevel);
                r.put("tier", c.tier);
                r.put("specialties", c.specialtyDomains == null ? List.of() : c.specialtyDomains);
                rows.add(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", rows.size());
            out.put("clients", rows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("client list failed: " + ex.getMessage());
        }
    }
}
