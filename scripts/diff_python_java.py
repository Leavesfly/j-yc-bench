#!/usr/bin/env python3
"""Compare Python vs Java CLI output snapshots.

Both directories must contain {sim_init, company_status, employee_list, market_browse}.json.
Exits non-zero on divergence in business-critical invariants.
"""
from __future__ import annotations
import json
import sys
from pathlib import Path
from typing import Any


def load(path: Path) -> Any:
    return json.loads(path.read_text())


def _normalize_iso(v: Any) -> Any:
    """Normalize ISO-8601 strings so '+00:00' and 'Z' compare equal, etc."""
    if not isinstance(v, str):
        return v
    s = v.replace("+00:00", "Z")
    # Java truncates trailing zero seconds → '2026-01-01T09:00Z'. Python keeps '09:00:00'.
    if "T" in s and s.endswith("Z"):
        head, _, _ = s.partition("Z")
        # If only HH:MM, pad to HH:MM:SS
        date_part, _, time_part = head.partition("T")
        parts = time_part.split(":")
        if len(parts) == 2:
            head = f"{date_part}T{parts[0]}:{parts[1]}:00"
        s = head + "Z"
    return s


def compare_scalar(name: str, py_val: Any, j_val: Any, *, tol: float = 0.0) -> bool:
    py_n = _normalize_iso(py_val)
    j_n = _normalize_iso(j_val)
    if isinstance(py_n, float) or isinstance(j_n, float):
        ok = abs(float(py_n) - float(j_n)) <= tol
    else:
        ok = py_n == j_n
    flag = "OK " if ok else "FAIL"
    print(f"  [{flag}] {name}: python={py_val!r} java={j_val!r}")
    return ok


def main(py_dir: str, j_dir: str) -> int:
    py = Path(py_dir)
    jv = Path(j_dir)

    py_init = load(py / "sim_init.json")
    j_init = load(jv / "sim_init.json")
    py_cs = load(py / "company_status.json")
    j_cs = load(jv / "company_status.json")
    py_el = load(py / "employee_list.json")
    j_el = load(jv / "employee_list.json")
    py_mk = load(py / "market_browse.json")
    j_mk = load(jv / "market_browse.json")

    all_ok = True

    print("=== sim init ===")
    all_ok &= compare_scalar("company_name", py_init["company_name"], j_init["company_name"])
    all_ok &= compare_scalar("seed", py_init["seed"], j_init["seed"])
    all_ok &= compare_scalar("horizon_end", py_init["horizon_end"], j_init["horizon_end"])

    print("=== company status ===")
    all_ok &= compare_scalar("funds_cents", py_cs["funds_cents"], j_cs["funds_cents"])
    all_ok &= compare_scalar("monthly_payroll_cents", py_cs["monthly_payroll_cents"], j_cs["monthly_payroll_cents"])
    all_ok &= compare_scalar("employees", py_cs["employees"], j_cs["employees"])
    all_ok &= compare_scalar("tasks.planned", py_cs["tasks"]["planned"], j_cs["tasks"]["planned"])
    all_ok &= compare_scalar("tasks.active", py_cs["tasks"]["active"], j_cs["tasks"]["active"])

    print("=== employee list ===")
    py_emps = sorted(py_el["employees"], key=lambda e: e["name"])
    j_emps = sorted(j_el["employees"], key=lambda e: e["name"])
    all_ok &= compare_scalar("employee count", len(py_emps), len(j_emps))
    py_salary = sum(e["salary_cents"] for e in py_emps)
    j_salary = sum(e["salary_cents"] for e in j_emps)
    all_ok &= compare_scalar("total salary", py_salary, j_salary)
    py_names = sorted(e["name"] for e in py_emps)
    j_names = sorted(e["name"] for e in j_emps)
    all_ok &= compare_scalar("employee names sorted", py_names, j_names)

    print("=== market browse ===")
    py_tasks = py_mk.get("tasks", [])
    j_tasks = j_mk.get("tasks", [])
    all_ok &= compare_scalar("market task count", len(py_tasks), len(j_tasks))
    py_rewards = sorted(t["reward_funds_cents"] for t in py_tasks)
    j_rewards = sorted(t["reward_funds_cents"] for t in j_tasks)
    all_ok &= compare_scalar("market reward sum", sum(py_rewards), sum(j_rewards))
    py_clients = sorted(t["client_name"] for t in py_tasks)
    j_clients = sorted(t["client_name"] for t in j_tasks)
    all_ok &= compare_scalar("market client_name multiset", py_clients, j_clients)
    py_prestige = sorted(t["required_prestige"] for t in py_tasks)
    j_prestige = sorted(t["required_prestige"] for t in j_tasks)
    all_ok &= compare_scalar("market required_prestige multiset", py_prestige, j_prestige)

    print()
    if all_ok:
        print("✓ All checks passed.")
        return 0
    print("✗ Divergence detected — Java port is NOT 1:1 with Python for the failing fields.")
    return 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: diff_python_java.py <python_out_dir> <java_out_dir>", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
