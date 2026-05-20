#!/usr/bin/env bash
#
# Differential test: same seed → Python sim init vs Java sim init.
#
# Compares the deterministic, world-seeding output of both implementations.
# Failure means the two runtimes have diverged.
#
# Usage:
#   ./scripts/diff_python_java.sh [SEED]
#
# Default SEED=42.
set -euo pipefail

SEED="${1:-42}"
COMPANY="DiffTestCo"
HORIZON_YEARS=1
START_DATE_ISO="2025-01-01"       # Java accepts ISO or MM/DD/YYYY
START_DATE_PY="01/01/2025"        # Python only accepts MM/DD/YYYY

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY_ROOT="$(cd "$ROOT/.." && pwd)"
PY_OUT="/tmp/yc-diff-py"
J_OUT="/tmp/yc-diff-java"
rm -rf "$PY_OUT" "$J_OUT"
mkdir -p "$PY_OUT" "$J_OUT"

# ---------- Java side ----------
CP_FILE=/tmp/cp.txt
[ -s "$CP_FILE" ] || (cd "$ROOT" && /tmp/apache-maven-3.9.9/bin/mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE")
CP="$ROOT/target/classes:$(cat "$CP_FILE")"

(
  cd "$J_OUT"
  java -cp "$CP" com.collinear.ycbench.cli.Main sim init \
    --seed "$SEED" --start-date "$START_DATE_ISO" \
    --horizon-years "$HORIZON_YEARS" --company-name "$COMPANY" > sim_init.json
  java -cp "$CP" com.collinear.ycbench.cli.Main company status > company_status.json
  java -cp "$CP" com.collinear.ycbench.cli.Main employee list > employee_list.json
  java -cp "$CP" com.collinear.ycbench.cli.Main market browse --limit 50 > market_browse.json
)

# ---------- Python side ----------
(
  cd "$PY_OUT"
  PYTHONPATH="$PY_ROOT/src" python3 -m yc_bench sim init \
    --seed "$SEED" --start-date "$START_DATE_PY" \
    --horizon-years "$HORIZON_YEARS" --company-name "$COMPANY" > sim_init.json
  PYTHONPATH="$PY_ROOT/src" python3 -m yc_bench company status > company_status.json
  PYTHONPATH="$PY_ROOT/src" python3 -m yc_bench employee list > employee_list.json
  PYTHONPATH="$PY_ROOT/src" python3 -m yc_bench market browse --limit 50 > market_browse.json
)

# ---------- Compare ----------
python3 "$ROOT/scripts/diff_python_java.py" "$PY_OUT" "$J_OUT"
