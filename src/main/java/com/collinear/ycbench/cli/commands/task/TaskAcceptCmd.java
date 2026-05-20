package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;
import com.collinear.ycbench.services.GenerateTasks;
import picocli.CommandLine;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** {@code yc-bench task accept --task-id ...} */
@CommandLine.Command(name = "accept", description = "接受市场任务；将 market → planned 转换。")
public final class TaskAcceptCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--task-id", required = true, description = "任务 UUID 或标题（例如 Task-42）。")
    String taskId;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            WorldConfig cfg = ConfigLoader.getWorldConfig();

            Task task = TaskResolver.resolveTask(db, taskId);
            if (task == null) return JsonOutput.err("Task '" + taskId + "' not found.");
            if (task.status != TaskStatus.MARKET) {
                return JsonOutput.err("Task " + taskId + " is not in market status (current: " + task.status.value + ").");
            }

            UUID companyId = st.companyId;
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, task.id);

            Map<Domain, Double> prestigeMap = new EnumMap<>(Domain.class);
            for (CompanyPrestige p : CompanyDao.listPrestige(db, companyId)) {
                prestigeMap.put(p.domain, p.prestigeLevel);
            }
            for (TaskRequirement r : reqs) {
                double pl = prestigeMap.getOrDefault(r.domain, 1.0);
                if (task.requiredPrestige > pl) {
                    return JsonOutput.err(String.format(
                            "Company prestige in %s (%.1f) does not meet task requirement (%d).",
                            r.domain.value, pl, task.requiredPrestige));
                }
            }

            double trustLevel = 0.0;
            Client clientRow = null;
            if (task.clientId != null) {
                clientRow = ClientDao.findById(db, task.clientId);
                ClientTrust ct = ClientDao.findTrust(db, companyId, task.clientId);
                if (ct != null) trustLevel = ct.trustLevel;
                if (task.requiredTrust > 0 && trustLevel < task.requiredTrust) {
                    String name = clientRow != null ? clientRow.name : "unknown";
                    return JsonOutput.err(String.format(
                            "Client trust with %s (%.1f) does not meet task requirement (%d).",
                            name, trustLevel, task.requiredTrust));
                }
            }

            boolean isRat = clientRow != null && clientRow.loyalty < -0.3;

            // 信任工作量减少（仅非 RAT）。
            if (!isRat && task.clientId != null) {
                double reduction = cfg.trustWorkReductionMax * (trustLevel / cfg.trustMax);
                for (TaskRequirement r : reqs) {
                    int reduced = (int) (r.requiredQty * (1 - reduction));
                    r.requiredQty = Math.max(200, reduced);
                    TaskDao.updateRequirement(db, r.taskId, r.domain, r.requiredQty, r.completedQty);
                }
            }

            // 刷新需求以防上述写入有变化。
            reqs = TaskDao.listRequirements(db, task.id);

            double maxQty = 0.0;
            for (TaskRequirement r : reqs) maxQty = Math.max(maxQty, r.requiredQty);
            OffsetDateTime acceptedAt = st.simTime;
            OffsetDateTime deadline = TaskResolver.computeDeadline(acceptedAt, maxQty, cfg);

            // 在争议扣回之前快照广告奖励。
            task.advertisedRewardCents = task.rewardFundsCents;

            // RAT 范围蔓延。
            if (isRat) {
                double intensity = Math.abs(clientRow.loyalty);
                double inflation = Math.max(3.0, cfg.scopeCreepMax * intensity);
                for (TaskRequirement r : reqs) {
                    int inflated = (int) (r.requiredQty * (1 + inflation));
                    r.requiredQty = Math.min(25000, Math.max(200, inflated));
                    TaskDao.updateRequirement(db, r.taskId, r.domain, r.requiredQty, r.completedQty);
                }
            }

            // 转换任务状态。
            task.status = TaskStatus.PLANNED;
            task.companyId = companyId;
            task.acceptedAt = acceptedAt;
            task.deadline = deadline;
            TaskDao.update(db, task);

            // 替换任务 — 基于 (slot, generation) 键。
            int slot = task.marketSlot != null ? task.marketSlot : 0;
            int generation = TaskDao.countAcceptedForSlot(db, slot);

            int replacedClientIndex = 0;
            List<Client> clientsByName = ClientDao.listAllOrderedByName(db);
            if (task.clientId != null) {
                for (int i = 0; i < clientsByName.size(); i++) {
                    if (clientsByName.get(i).id.equals(task.clientId)) {
                        replacedClientIndex = i;
                        break;
                    }
                }
            }
            List<String> replacementSpec = clientRow != null ? clientRow.specialtyDomains : null;

            GenerateTasks.Generated replacement = GenerateTasks.generateReplacement(
                    st.runSeed, slot * 1000 + generation,
                    task.requiredPrestige, replacedClientIndex, cfg, replacementSpec);

            Client replacementClient = clientsByName.isEmpty()
                    ? null
                    : clientsByName.get(Math.floorMod(replacement.clientIndex, clientsByName.size()));

            Task repRow = new Task();
            repRow.id = UUID.randomUUID();
            repRow.companyId = null;
            repRow.clientId = replacementClient != null ? replacementClient.id : null;
            repRow.status = TaskStatus.MARKET;
            repRow.title = replacement.title;
            repRow.requiredPrestige = replacement.requiredPrestige;
            repRow.rewardFundsCents = replacement.rewardFundsCents;
            repRow.rewardPrestigeDelta = replacement.rewardPrestigeDelta;
            repRow.skillBoostPct = replacement.skillBoostPct;
            repRow.acceptedAt = null;
            repRow.deadline = null;
            repRow.completedAt = null;
            repRow.success = null;
            repRow.progressMilestonePct = 0;
            repRow.requiredTrust = replacement.requiredTrust;
            repRow.marketSlot = slot;
            TaskDao.insert(db, repRow);

            for (Map.Entry<Domain, Double> e : replacement.requirements.entrySet()) {
                TaskRequirement tr = new TaskRequirement();
                tr.taskId = repRow.id;
                tr.domain = e.getKey();
                tr.requiredQty = e.getValue();
                tr.completedQty = 0.0;
                TaskDao.insertRequirement(db, tr);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("task_id", task.title);
            out.put("status", task.status.value);
            out.put("accepted_at", acceptedAt.toString());
            out.put("deadline", deadline.toString());
            out.put("replacement_task_id", repRow.title);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("task accept failed: " + ex.getMessage());
        }
    }

    /** 抑制旧 javac 中 ArrayList 的 unused-import 警告。*/
    @SuppressWarnings("unused")
    private static final List<?> _unused = new ArrayList<>();
}
