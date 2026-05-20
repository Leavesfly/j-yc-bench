-- j-yc-bench schema. Mirrors the SQLAlchemy models in src/yc_bench/db/models/*.py.
-- SQLite uses dynamic typing; we still declare types to keep intent clear.

CREATE TABLE IF NOT EXISTS companies (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    funds_cents   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS company_prestige (
    company_id      TEXT NOT NULL,
    domain          TEXT NOT NULL,
    prestige_level  REAL NOT NULL DEFAULT 1.0,
    PRIMARY KEY (company_id, domain),
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    CHECK (prestige_level >= 1 AND prestige_level <= 10)
);

CREATE TABLE IF NOT EXISTS employees (
    id                  TEXT PRIMARY KEY,
    company_id          TEXT NOT NULL,
    name                TEXT NOT NULL,
    tier                TEXT NOT NULL DEFAULT 'junior',
    work_hours_per_day  REAL NOT NULL DEFAULT 9.0,
    salary_cents        INTEGER NOT NULL,
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    CHECK (work_hours_per_day > 0),
    CHECK (salary_cents >= 0)
);

CREATE TABLE IF NOT EXISTS employee_skill_rates (
    employee_id           TEXT NOT NULL,
    domain                TEXT NOT NULL,
    rate_domain_per_hour  REAL NOT NULL DEFAULT 1.0,
    PRIMARY KEY (employee_id, domain),
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    CHECK (rate_domain_per_hour >= 0)
);

CREATE TABLE IF NOT EXISTS clients (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    reward_multiplier   REAL NOT NULL DEFAULT 1.0,
    tier                TEXT NOT NULL DEFAULT 'Standard',
    specialty_domains   TEXT NOT NULL DEFAULT '[]',   -- JSON array
    loyalty             REAL NOT NULL DEFAULT 0.0
);

CREATE TABLE IF NOT EXISTS client_trust (
    company_id   TEXT NOT NULL,
    client_id    TEXT NOT NULL,
    trust_level  REAL NOT NULL DEFAULT 0.0,
    PRIMARY KEY (company_id, client_id),
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    FOREIGN KEY (client_id)  REFERENCES clients(id)   ON DELETE CASCADE,
    CHECK (trust_level >= 0 AND trust_level <= 5)
);

CREATE TABLE IF NOT EXISTS tasks (
    id                       TEXT PRIMARY KEY,
    company_id               TEXT,
    client_id                TEXT,
    status                   TEXT NOT NULL DEFAULT 'market',
    title                    TEXT NOT NULL,
    required_prestige        INTEGER NOT NULL,
    reward_funds_cents       INTEGER NOT NULL,
    reward_prestige_delta    REAL NOT NULL,
    skill_boost_pct          REAL NOT NULL,
    accepted_at              TEXT,
    deadline                 TEXT,
    completed_at             TEXT,
    success                  INTEGER,            -- nullable boolean
    progress_milestone_pct   INTEGER NOT NULL DEFAULT 0,
    required_trust           INTEGER NOT NULL DEFAULT 0,
    advertised_reward_cents  INTEGER,
    market_slot              INTEGER,
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    FOREIGN KEY (client_id)  REFERENCES clients(id)   ON DELETE SET NULL,
    CHECK (required_prestige >= 1 AND required_prestige <= 10),
    CHECK (skill_boost_pct >= 0),
    CHECK (reward_funds_cents >= 0),
    CHECK (reward_prestige_delta >= 0 AND reward_prestige_delta <= 5),
    CHECK (required_trust >= 0 AND required_trust <= 5)
);

CREATE TABLE IF NOT EXISTS task_requirements (
    task_id        TEXT NOT NULL,
    domain         TEXT NOT NULL,
    required_qty   REAL NOT NULL,
    completed_qty  REAL NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id, domain),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    CHECK (required_qty >= 200 AND required_qty <= 25000),
    CHECK (completed_qty >= 0),
    CHECK (completed_qty <= required_qty)
);

CREATE TABLE IF NOT EXISTS task_assignments (
    task_id      TEXT NOT NULL,
    employee_id  TEXT NOT NULL,
    assigned_at  TEXT NOT NULL,
    PRIMARY KEY (task_id, employee_id),
    FOREIGN KEY (task_id)     REFERENCES tasks(id)     ON DELETE CASCADE,
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sim_events (
    id            TEXT PRIMARY KEY,
    company_id    TEXT NOT NULL,
    event_type    TEXT NOT NULL,
    scheduled_at  TEXT NOT NULL,
    payload       TEXT NOT NULL,                  -- JSON
    dedupe_key    TEXT,
    consumed      INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_sim_events_company_consumed
    ON sim_events(company_id, consumed, scheduled_at);
CREATE INDEX IF NOT EXISTS ix_sim_events_dedupe ON sim_events(dedupe_key);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id            TEXT PRIMARY KEY,
    company_id    TEXT NOT NULL,
    occurred_at   TEXT NOT NULL,
    category      TEXT NOT NULL,
    amount_cents  INTEGER NOT NULL,
    ref_type      TEXT,
    ref_id        TEXT,
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_ledger_company_time
    ON ledger_entries(company_id, occurred_at);

CREATE TABLE IF NOT EXISTS sim_state (
    company_id         TEXT PRIMARY KEY,
    sim_time           TEXT NOT NULL,
    run_seed           INTEGER NOT NULL,
    horizon_end        TEXT NOT NULL,
    replenish_counter  INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS scratchpads (
    company_id  TEXT PRIMARY KEY,
    content     TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sessions (
    id           TEXT PRIMARY KEY,
    company_id   TEXT NOT NULL,
    started_at   TEXT NOT NULL,
    ended_at     TEXT,
    wake_reason  TEXT NOT NULL,
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS monthly_metrics (
    company_id          TEXT NOT NULL,
    month_start         TEXT NOT NULL,
    revenue_cents       INTEGER NOT NULL,
    cost_cents          INTEGER NOT NULL,
    return_cents        INTEGER NOT NULL,
    ending_funds_cents  INTEGER NOT NULL,
    PRIMARY KEY (company_id, month_start),
    FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
);
