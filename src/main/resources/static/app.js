/**
 * j-yc-bench Web Dashboard — Frontend Application
 */
(function() {
    'use strict';

    const API_BASE = '';
    let ws = null;
    let currentTaskFilter = 'all';
    let refreshInterval = null;

    // ============ Initialization ============
    document.addEventListener('DOMContentLoaded', () => {
        initTabs();
        initTaskFilters();
        initRunControls();
        initActivityClear();
        connectWebSocket();
        loadDashboard();
        loadRunStatus();
        refreshInterval = setInterval(loadDashboard, 5000);
    });

    // ============ Run Controls ============
    let currentMode = 'llm'; // 'llm' or 'bot'

    function initRunControls() {
        const btnStart = document.getElementById('btn-start');
        const btnStop = document.getElementById('btn-stop');
        const btnRestart = document.getElementById('btn-restart');
        const modal = document.getElementById('run-modal');
        const modalClose = document.getElementById('modal-close');
        const modalCancel = document.getElementById('modal-cancel');
        const modalConfirm = document.getElementById('modal-confirm');

        let pendingAction = null;

        btnStart.addEventListener('click', () => { pendingAction = 'start'; showModal(); });
        btnRestart.addEventListener('click', () => { pendingAction = 'restart'; showModal(); });
        btnStop.addEventListener('click', () => stopRun());
        modalClose.addEventListener('click', () => hideModal());
        modalCancel.addEventListener('click', () => hideModal());
        modalConfirm.addEventListener('click', () => {
            hideModal();
            if (pendingAction === 'start') startRun();
            else if (pendingAction === 'restart') restartRun();
        });
        modal.addEventListener('click', (e) => { if (e.target === modal) hideModal(); });

        // 模式切换
        document.querySelectorAll('.mode-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                currentMode = btn.getAttribute('data-mode');
                document.getElementById('group-model').style.display = currentMode === 'llm' ? 'block' : 'none';
                document.getElementById('group-bot').style.display = currentMode === 'bot' ? 'block' : 'none';
            });
        });
    }

    function showModal() {
        document.getElementById('run-modal').style.display = 'flex';
    }
    function hideModal() {
        document.getElementById('run-modal').style.display = 'none';
    }

    async function startRun() {
        const body = getRunParams();
        try {
            const resp = await fetch(`${API_BASE}/api/run/start`, {
                method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
            });
            const data = await resp.json();
            if (resp.ok) { clearActivityFeed(); updateRunUI(data.status); }
            else alert(data.error || 'Start failed');
        } catch(e) { alert('Request failed: ' + e.message); }
    }

    async function stopRun() {
        if (!confirm('确定要中止当前仿真吗？')) return;
        try {
            const resp = await fetch(`${API_BASE}/api/run/stop`, { method: 'POST' });
            const data = await resp.json();
            if (resp.ok) updateRunUI(data.status);
            else alert(data.error || 'Stop failed');
        } catch(e) { alert('Request failed: ' + e.message); }
    }

    async function restartRun() {
        if (!confirm('确定要清除旧数据并重新运行仿真吗？')) return;
        const body = getRunParams();
        try {
            const resp = await fetch(`${API_BASE}/api/run/restart`, {
                method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
            });
            const data = await resp.json();
            if (resp.ok) { clearActivityFeed(); updateRunUI(data.status); }
            else alert(data.error || 'Restart failed');
        } catch(e) { alert('Request failed: ' + e.message); }
    }

    function getRunParams() {
        const params = {
            mode: currentMode,
            seed: parseInt(document.getElementById('input-seed').value) || 1,
            config: document.getElementById('input-config').value.trim() || 'default'
        };
        if (currentMode === 'bot') {
            params.bot = document.getElementById('input-bot').value;
        } else {
            params.model = document.getElementById('input-model').value.trim() || 'ollama/qwen3.5:4b';
        }
        return params;
    }

    async function loadRunStatus() {
        try {
            const resp = await fetch(`${API_BASE}/api/run/status`);
            if (!resp.ok) return;
            const data = await resp.json();
            updateRunUI(data.status);
            // 填充默认值到 modal
            if (data.model) document.getElementById('input-model').value = data.model;
            if (data.seed) document.getElementById('input-seed').value = data.seed;
            if (data.config) document.getElementById('input-config').value = data.config;

            // 刷新后恢复活动面板状态
            const runStatus = (data.status || '').toUpperCase();
            if (runStatus === 'RUNNING' || runStatus === 'COMPLETED' || runStatus === 'ERROR') {
                showActivityPanel();
                const mode = data.currentMode || '';
                const bot = data.currentBot || '';
                const model = data.currentModel || '';
                const elapsed = data.startTimeMs > 0 ? Math.round((Date.now() - data.startTimeMs) / 1000) : 0;
                const feed = document.getElementById('activity-feed');
                if (feed && feed.children.length === 0) {
                    // 注入一条恢复提示事件
                    const el = document.createElement('div');
                    el.className = 'event-item status-change';
                    if (runStatus === 'RUNNING') {
                        const desc = mode === 'bot' ? `Bot "${bot}"` : `LLM "${model}"`;
                        el.innerHTML = `<div class="event-header">
                            <span class="event-title">🟢 ${desc} 运行中</span>
                            <span class="event-time">已运行 ${formatElapsed(elapsed)}</span>
                        </div>`;
                    } else if (runStatus === 'COMPLETED') {
                        const desc = mode === 'bot' ? `Bot "${bot}"` : `LLM "${model}"`;
                        el.innerHTML = `<div class="event-header">
                            <span class="event-title">🏁 ${desc} 已完成</span>
                            <span class="event-time">退出码: ${data.lastExitCode}</span>
                        </div>`;
                        hideActivityRunning();
                        const indicator = document.getElementById('activity-indicator');
                        indicator.textContent = '✅';
                    } else if (runStatus === 'ERROR') {
                        el.innerHTML = `<div class="event-header">
                            <span class="event-title">❌ 运行出错</span>
                            <span class="event-time">${esc(data.lastError || '')}</span>
                        </div>`;
                        hideActivityRunning();
                        const indicator = document.getElementById('activity-indicator');
                        indicator.textContent = '❌';
                    }
                    feed.prepend(el);
                }
                // 运行中时保持运行指示
                if (runStatus !== 'RUNNING') {
                    hideActivityRunning();
                }
            }
        } catch(e) { /* ignore */ }
    }

    function formatElapsed(seconds) {
        if (seconds < 60) return seconds + '秒';
        if (seconds < 3600) return Math.floor(seconds / 60) + '分' + (seconds % 60) + '秒';
        return Math.floor(seconds / 3600) + '时' + Math.floor((seconds % 3600) / 60) + '分';
    }

    function updateRunUI(runStatus) {
        const badge = document.getElementById('run-status-badge');
        const btnStart = document.getElementById('btn-start');
        const btnStop = document.getElementById('btn-stop');
        const btnRestart = document.getElementById('btn-restart');
        const statusText = badge.querySelector('.status-text');

        // Reset classes
        badge.className = 'run-status-badge';
        const statusLower = (runStatus || 'IDLE').toLowerCase();
        badge.classList.add(statusLower);

        const labelMap = { idle:'空闲', running:'运行中', stopping:'停止中', completed:'已完成', error:'出错' };
        statusText.textContent = labelMap[statusLower] || runStatus;

        const isRunning = statusLower === 'running' || statusLower === 'stopping';
        btnStart.disabled = isRunning;
        btnStop.disabled = !isRunning;
        btnRestart.disabled = statusLower === 'stopping';
    }

    // ============ WebSocket ============
    function connectWebSocket() {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${proto}//${location.host}/ws`);
        ws.onopen = () => updateWsStatus(true);
        ws.onclose = () => { updateWsStatus(false); setTimeout(connectWebSocket, 3000); };
        ws.onerror = () => updateWsStatus(false);
        ws.onmessage = (evt) => {
            try {
                const data = JSON.parse(evt.data);
                if (data.type === 'dashboard_update') {
                    renderDashboard(data.payload);
                } else if (data.type === 'run_status') {
                    updateRunUI(data.status);
                    appendActivityEvent(data);
                    if (data.status === 'COMPLETED' || data.status === 'ERROR') {
                        setTimeout(loadDashboard, 1000);
                    }
                } else if (data.type === 'turn_start') {
                    showActivityPanel();
                    appendTurnStartEvent(data);
                } else if (data.type === 'turn_end') {
                    appendTurnEndEvent(data);
                }
            } catch(e) { /* ignore */ }
        };
    }

    function updateWsStatus(connected) {
        const el = document.querySelector('#ws-status .dot');
        el.className = connected ? 'dot dot-connected' : 'dot dot-disconnected';
    }

    // ============ Data Loading ============
    async function loadDashboard() {
        try {
            const resp = await fetch(`${API_BASE}/api/simulation/dashboard`);
            if (!resp.ok) { renderNoSimulation(); return; }
            const data = await resp.json();
            renderDashboard(data);
            document.getElementById('last-refresh').textContent = '最后刷新: ' + new Date().toLocaleTimeString();
        } catch(e) { console.warn('Dashboard load failed', e); }
    }

    async function loadEmployees() {
        try {
            const resp = await fetch(`${API_BASE}/api/employees`);
            if (!resp.ok) return;
            renderEmployees(await resp.json());
        } catch(e) { /* ignore */ }
    }

    async function loadTasks() {
        try {
            const resp = await fetch(`${API_BASE}/api/tasks`);
            if (!resp.ok) return;
            renderTasks(await resp.json());
        } catch(e) { /* ignore */ }
    }

    async function loadClients() {
        try {
            const resp = await fetch(`${API_BASE}/api/clients`);
            if (!resp.ok) return;
            renderClients(await resp.json());
        } catch(e) { /* ignore */ }
    }

    async function loadFinance() {
        try {
            const resp = await fetch(`${API_BASE}/api/finance/ledger`);
            if (!resp.ok) return;
            renderFinance(await resp.json());
        } catch(e) { /* ignore */ }
    }

    async function loadMarket() {
        try {
            const resp = await fetch(`${API_BASE}/api/tasks/market`);
            if (!resp.ok) return;
            renderMarket(await resp.json());
        } catch(e) { /* ignore */ }
    }

    async function loadMetrics() {
        try {
            const resp = await fetch(`${API_BASE}/api/finance/metrics`);
            if (!resp.ok) return;
            renderChart(await resp.json());
        } catch(e) { /* ignore */ }
    }

    // ============ Rendering ============
    function renderNoSimulation() {
        document.getElementById('kpi-funds').textContent = 'N/A';
        document.getElementById('sim-time').textContent = '无仿真数据';
    }

    function renderDashboard(d) {
        // Sim time
        const simDate = d.simTime ? d.simTime.substring(0, 10) : '--';
        const endDate = d.horizonEnd ? d.horizonEnd.substring(0, 10) : '--';
        document.getElementById('sim-time').textContent = simDate + ' → ' + endDate;

        // KPIs
        document.getElementById('kpi-funds').textContent = d.fundsFormatted || formatMoney(d.fundsCents);
        document.getElementById('kpi-runway').textContent = d.runwayMonths > 0
            ? `跑道: ${d.runwayMonths} 个月` : '跑道: ∞';
        document.getElementById('kpi-employees').textContent = d.employeeCount;
        document.getElementById('kpi-payroll').textContent = '月工资: ' + (d.monthlyPayrollFormatted || formatMoney(d.monthlyPayrollCents));
        const totalDone = (d.completedSuccess || 0) + (d.completedFail || 0);
        const totalAll = totalDone + (d.activeTasks || 0) + (d.plannedTasks || 0);
        document.getElementById('kpi-tasks').textContent = totalAll > 0 ? totalDone + '/' + totalAll : (d.activeTasks + d.plannedTasks);
        document.getElementById('kpi-tasks-detail').textContent = d.activeTasks > 0 ? `进行中${d.activeTasks}` : (totalDone > 0 ? `完成${totalDone}项` : `活跃/计划`);

        // Prestige
        const prestige = d.prestige || {};
        const domains = ['research', 'inference', 'training', 'data'];
        let totalPrestige = 0;
        domains.forEach(domain => {
            const val = prestige[domain] || 0;
            totalPrestige += val;
            const bar = document.getElementById('bar-' + domain);
            const valEl = document.getElementById('val-' + domain);
            if (bar) bar.style.width = Math.min(val / 10 * 100, 100) + '%';
            if (valEl) valEl.textContent = val.toFixed(1);
        });
        document.getElementById('kpi-prestige').textContent = (totalPrestige / 4).toFixed(1);

        // Success rate
        const total = (d.completedSuccess || 0) + (d.completedFail || 0);
        const rate = total > 0 ? Math.round((d.completedSuccess / total) * 100) : 0;
        document.getElementById('kpi-success-rate').textContent = total > 0 ? rate + '%' : '--';
        document.getElementById('kpi-ok-fail').textContent = `✓${d.completedSuccess || 0} / ✗${d.completedFail || 0}`;

        // Bankrupt alert
        if (d.bankrupt) {
            let alert = document.querySelector('.bankrupt-alert');
            if (!alert) {
                alert = document.createElement('div');
                alert.className = 'bankrupt-alert';
                alert.innerHTML = '<h3>💀 公司已破产</h3><p>资金为负，仿真终止</p>';
                document.querySelector('.dashboard').prepend(alert);
            }
        }

        // Load chart data
        loadMetrics();
    }

    function renderEmployees(employees) {
        const tbody = document.getElementById('employees-tbody');
        tbody.innerHTML = employees.map(emp => {
            const skills = emp.skills || {};
            return `<tr>
                <td><strong>${esc(emp.name)}</strong></td>
                <td><span class="badge">${esc(emp.tier)}</span></td>
                <td>${emp.salaryFormatted}</td>
                <td>${emp.workHoursPerDay}h</td>
                <td>${skillCell(skills.research)}</td>
                <td>${skillCell(skills.inference)}</td>
                <td>${skillCell(skills.training)}</td>
                <td>${skillCell(skills.data)}</td>
                <td>${emp.assignedTasks}</td>
            </tr>`;
        }).join('');
    }

    function renderTasks(tasks) {
        const filtered = currentTaskFilter === 'all' ? tasks : tasks.filter(t => t.status === currentTaskFilter);
        const tbody = document.getElementById('tasks-tbody');
        tbody.innerHTML = filtered.map(t => `<tr>
            <td><strong>${esc(t.title)}</strong></td>
            <td><span class="badge badge-${t.status}">${statusLabel(t.status)}</span></td>
            <td>${esc(t.clientName || '--')}</td>
            <td style="color:var(--green)">${t.rewardFormatted}</td>
            <td>${t.requiredPrestige}</td>
            <td>${progressBar(t.progressMilestonePct)}</td>
            <td>${t.deadline ? t.deadline.substring(0, 10) : '--'}</td>
        </tr>`).join('');
    }

    function renderClients(clients) {
        const grid = document.getElementById('clients-grid');
        grid.innerHTML = clients.map(c => `
            <div class="client-card ${c.isRat ? 'rat' : ''}">
                <div class="client-name">${esc(c.name)} ${c.isRat ? '⚠️' : ''}</div>
                <div class="client-meta">
                    <span>等级: ${esc(c.tier)}</span>
                    <span>奖励倍数: ${c.rewardMultiplier}x</span>
                    <span>完成: ✓${c.completedSuccess} ✗${c.completedFail}</span>
                </div>
                <div class="client-meta">
                    <span>专长: ${(c.specialtyDomains || []).join(', ') || '无'}</span>
                </div>
                <div class="client-trust">
                    <span style="font-size:11px;color:var(--text-dim)">信任度: ${c.trustLevel.toFixed(1)}</span>
                    <div class="trust-bar"><div class="trust-fill" style="width:${Math.min(c.trustLevel * 20, 100)}%"></div></div>
                </div>
            </div>
        `).join('');
    }

    function renderFinance(entries) {
        const tbody = document.getElementById('finance-tbody');
        const recent = entries.slice(-50).reverse();
        tbody.innerHTML = recent.map(e => `<tr>
            <td>${e.occurredAt ? e.occurredAt.substring(0, 10) : '--'}</td>
            <td><span class="badge">${esc(e.category)}</span></td>
            <td style="color:${e.amountCents >= 0 ? 'var(--green)' : 'var(--red)'}">${e.amountFormatted}</td>
            <td>${esc(e.refType || '--')}</td>
        </tr>`).join('');
    }

    function renderMarket(tasks) {
        const grid = document.getElementById('market-grid');
        grid.innerHTML = tasks.map(t => `
            <div class="market-card">
                <div class="task-title">${esc(t.title)}</div>
                <div class="task-meta">
                    <span>客户: ${esc(t.clientName || '未知')}</span>
                    <span>声望要求: ${t.requiredPrestige}</span>
                    <span>Slot: #${t.marketSlot || '--'}</span>
                </div>
                <div class="task-reward">${t.rewardFormatted}</div>
            </div>
        `).join('');
    }

    function renderChart(metrics) {
        const canvas = document.getElementById('funds-chart');
        if (!canvas || !metrics || metrics.length === 0) return;
        const ctx = canvas.getContext('2d');
        const w = canvas.width = canvas.parentElement.clientWidth;
        const h = canvas.height = 250;

        ctx.clearRect(0, 0, w, h);

        const values = metrics.map(m => m.endingFundsCents / 100);
        const labels = metrics.map(m => m.month);
        const maxVal = Math.max(...values, 200000);
        const minVal = Math.min(...values, 0);
        const range = maxVal - minVal || 1;

        const padding = { top: 20, right: 20, bottom: 40, left: 70 };
        const chartW = w - padding.left - padding.right;
        const chartH = h - padding.top - padding.bottom;

        // Grid lines
        ctx.strokeStyle = '#2d3748';
        ctx.lineWidth = 1;
        for (let i = 0; i <= 4; i++) {
            const y = padding.top + (chartH * i / 4);
            ctx.beginPath(); ctx.moveTo(padding.left, y); ctx.lineTo(w - padding.right, y); ctx.stroke();
            const val = maxVal - (range * i / 4);
            ctx.fillStyle = '#8899a6';
            ctx.font = '10px sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText('$' + Math.round(val).toLocaleString(), padding.left - 8, y + 4);
        }

        // Line
        if (values.length > 1) {
            ctx.beginPath();
            ctx.strokeStyle = '#3b82f6';
            ctx.lineWidth = 2;
            values.forEach((val, i) => {
                const x = padding.left + (chartW * i / (values.length - 1));
                const y = padding.top + chartH - ((val - minVal) / range * chartH);
                if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            });
            ctx.stroke();

            // Fill area
            ctx.lineTo(padding.left + chartW, padding.top + chartH);
            ctx.lineTo(padding.left, padding.top + chartH);
            ctx.closePath();
            const grad = ctx.createLinearGradient(0, padding.top, 0, h);
            grad.addColorStop(0, 'rgba(59,130,246,0.2)');
            grad.addColorStop(1, 'rgba(59,130,246,0)');
            ctx.fillStyle = grad;
            ctx.fill();
        }

        // X labels
        ctx.fillStyle = '#8899a6';
        ctx.font = '10px sans-serif';
        ctx.textAlign = 'center';
        const step = Math.max(1, Math.floor(labels.length / 6));
        labels.forEach((label, i) => {
            if (i % step === 0) {
                const x = padding.left + (chartW * i / (labels.length - 1));
                ctx.fillText(label.substring(0, 7), x, h - 10);
            }
        });
    }

    // ============ Tabs ============
    function initTabs() {
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
                document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
                btn.classList.add('active');
                const tabId = btn.getAttribute('data-tab');
                document.getElementById('panel-' + tabId).classList.add('active');
                // Load data for tab
                switch(tabId) {
                    case 'employees': loadEmployees(); break;
                    case 'tasks': loadTasks(); break;
                    case 'clients': loadClients(); break;
                    case 'finance': loadFinance(); break;
                    case 'market': loadMarket(); break;
                }
            });
        });
        // Initial load
        loadEmployees();
    }

    function initTaskFilters() {
        document.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                currentTaskFilter = btn.getAttribute('data-status');
                loadTasks();
            });
        });
    }

    // ============ Activity Feed ============
    function clearActivityFeed() {
        const feed = document.getElementById('activity-feed');
        if (feed) feed.innerHTML = '';
        // 重置 meta 信息
        const turnEl = document.getElementById('activity-turn');
        const timeEl = document.getElementById('activity-sim-time');
        const fundsEl = document.getElementById('activity-funds');
        if (turnEl) turnEl.textContent = '回合: --';
        if (timeEl) timeEl.textContent = '仿真时间: --';
        if (fundsEl) fundsEl.textContent = '资金: --';
    }

    function showActivityPanel() {
        document.getElementById('activity-empty').style.display = 'none';
        document.getElementById('activity-feed').style.display = 'flex';
        document.getElementById('activity-meta').style.display = 'flex';
        const indicator = document.getElementById('activity-indicator');
        indicator.textContent = '🔴';
        indicator.classList.add('running');
    }

    function hideActivityRunning() {
        const indicator = document.getElementById('activity-indicator');
        indicator.classList.remove('running');
    }

    function appendTurnStartEvent(data) {
        const feed = document.getElementById('activity-feed');
        const el = document.createElement('div');
        el.className = 'event-item turn-start';
        el.innerHTML = `<div class="event-header">
            <span class="event-title">🚀 回合 ${data.turn} 开始</span>
            <span class="event-time">${fmtTime(data.timestamp)}</span>
        </div>`;
        feed.prepend(el);
        trimFeed(feed);
        updateActivityMeta({turn: data.turn});
    }

    function appendTurnEndEvent(data) {
        const feed = document.getElementById('activity-feed');
        const snap = data.snapshot || {};
        const cmds = data.commands || [];

        // 如果是终态（Bot完成或LLM完成），渲染运行摘要卡片
        if (data.terminal) {
            const isBankrupt = data.terminalReason === 'bankruptcy' || data.terminalReason === 'bankrupt';
            const el = document.createElement('div');
            el.className = 'event-item run-summary' + (isBankrupt ? ' bankrupt' : '');
            el.innerHTML = `<div class="event-header">
                <span class="event-title">${isBankrupt ? '💀 仿真结束 — 破产' : '🎉 仿真完成'}</span>
                <span class="event-time">${fmtTime(data.timestamp)}</span>
            </div>
            <div class="summary-grid">
                <div class="summary-stat">
                    <div class="stat-value">${data.turn}</div>
                    <div class="stat-label">总回合</div>
                </div>
                <div class="summary-stat">
                    <div class="stat-value" style="color:${isBankrupt ? 'var(--red)' : 'var(--green)'}">${formatMoney(snap.fundsCents)}</div>
                    <div class="stat-label">最终资金</div>
                </div>
                <div class="summary-stat">
                    <div class="stat-value">${snap.simTime ? snap.simTime.substring(0,10) : '--'}</div>
                    <div class="stat-label">结束时间</div>
                </div>
            </div>
            ${cmds.length > 0 ? '<div class="event-body" style="margin-top:8px;font-size:11px;color:var(--text-dim)">' + esc(cmds[0].result || '') + '</div>' : ''}`;
            feed.prepend(el);
            hideActivityRunning();
            const indicator = document.getElementById('activity-indicator');
            indicator.textContent = isBankrupt ? '💀' : '✅';
        } else {
            // 正常回合结束：渲染命令列表
            cmds.forEach(cmd => {
                const cmdEl = document.createElement('div');
                cmdEl.className = 'event-item cmd';
                cmdEl.innerHTML = `<div class="event-body">
                    <span class="cmd-text">$ ${esc(cmd.command)}</span>
                    ${cmd.result ? '<br><span class="result-text">' + esc(cmd.result) + '</span>' : ''}
                </div>`;
                feed.prepend(cmdEl);
            });

            const el = document.createElement('div');
            el.className = 'event-item turn-end';
            el.innerHTML = `<div class="event-header">
                <span class="event-title">✅ 回合 ${data.turn} 完成 (${cmds.length} 条命令)</span>
                <span class="event-time">${fmtTime(data.timestamp)}</span>
            </div>
            <div class="event-snapshot">
                <span class="snap-item">📅 ${snap.simTime ? snap.simTime.substring(0,10) : '--'}</span>
                <span class="snap-item">💰 ${formatMoney(snap.fundsCents)}</span>
                <span class="snap-item">📋 活跃${snap.activeTasks || 0} / 计划${snap.plannedTasks || 0}</span>
                <span class="snap-item">👥 ${snap.employeeCount || 0}人</span>
            </div>`;
            feed.prepend(el);
        }
        trimFeed(feed);
        if (data.terminal) {
            updateActivityMeta({totalTurns: data.turn, simTime: snap.simTime, fundsCents: snap.fundsCents});
        } else {
            updateActivityMeta({turn: data.turn, simTime: snap.simTime, fundsCents: snap.fundsCents});
        }
    }

    function appendActivityEvent(data) {
        if (!data.status) return;
        showActivityPanel();
        const feed = document.getElementById('activity-feed');
        const el = document.createElement('div');
        el.className = 'event-item status-change';
        const icon = {RUNNING:'🟢', COMPLETED:'🏁', ERROR:'❌', STOPPING:'⏸', IDLE:'⚪'}[data.status] || '📌';
        el.innerHTML = `<div class="event-header">
            <span class="event-title">${icon} ${data.message || data.status}</span>
            <span class="event-time">${fmtTime(new Date().toISOString())}</span>
        </div>`;
        feed.prepend(el);
        trimFeed(feed);

        // 更新指示器状态
        if (data.status === 'COMPLETED' || data.status === 'ERROR' || data.status === 'IDLE') {
            hideActivityRunning();
            const indicator = document.getElementById('activity-indicator');
            indicator.textContent = data.status === 'COMPLETED' ? '✅' : data.status === 'ERROR' ? '❌' : '⚪';
        }
    }

    function updateActivityMeta(info) {
        if (info.totalTurns != null) {
            document.getElementById('activity-turn').textContent = '总回合: ' + info.totalTurns;
        } else if (info.turn != null) {
            document.getElementById('activity-turn').textContent = '回合: ' + info.turn;
        }
        if (info.simTime) document.getElementById('activity-sim-time').textContent = '仿真时间: ' + info.simTime.substring(0, 10);
        if (info.fundsCents != null) document.getElementById('activity-funds').textContent = '资金: ' + formatMoney(info.fundsCents);
    }

    function trimFeed(feed) {
        while (feed.children.length > 200) feed.removeChild(feed.lastChild);
    }

    function fmtTime(ts) {
        if (!ts) return '';
        try { return new Date(ts).toLocaleTimeString(); } catch(e) { return ts; }
    }

    function initActivityClear() {
        const btn = document.getElementById('btn-clear-log');
        if (btn) btn.addEventListener('click', () => { document.getElementById('activity-feed').innerHTML = ''; });
    }

    // ============ Helpers ============
    function formatMoney(cents) {
        if (cents == null) return '--';
        const neg = cents < 0;
        const abs = Math.abs(cents);
        return (neg ? '-$' : '$') + Math.floor(abs / 100).toLocaleString();
    }

    function esc(s) { return s ? String(s).replace(/</g,'&lt;').replace(/>/g,'&gt;') : ''; }

    function statusLabel(s) {
        const map = { active:'进行中', planned:'计划中', completed_success:'✓完成', completed_fail:'✗失败', cancelled:'已取消', market:'市场' };
        return map[s] || s;
    }

    function skillCell(val) {
        if (val == null || val === undefined) return '<span style="color:var(--text-dim)">—</span>';
        const pct = Math.min(val / 3 * 100, 100);
        return `<span class="skill-bar-mini"><span class="fill" style="width:${pct}%"></span></span>${val.toFixed(2)}`;
    }

    function progressBar(pct) {
        pct = pct || 0;
        return `<div style="display:flex;align-items:center;gap:6px">
            <div style="flex:1;height:6px;background:var(--bg);border-radius:3px;overflow:hidden;min-width:60px">
                <div style="height:100%;width:${pct}%;background:var(--accent);border-radius:3px"></div>
            </div>
            <span style="font-size:11px">${pct}%</span>
        </div>`;
    }
})();
