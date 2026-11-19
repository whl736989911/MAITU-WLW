#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s --db-url URL [--tenant-count N] [--device-count N]\n' "$0" >&2
}

db_url=''
tenant_count=1
device_count=15000
while (($#)); do
  case "$1" in
    --db-url)
      (($# >= 2)) || { usage; exit 2; }
      db_url=$2
      shift 2
      ;;
    --tenant-count)
      (($# >= 2)) || { usage; exit 2; }
      tenant_count=$2
      shift 2
      ;;
    --device-count)
      (($# >= 2)) || { usage; exit 2; }
      device_count=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

[[ -n "$db_url" ]] || { usage; exit 2; }
for value in "$tenant_count" "$device_count"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { printf 'Counts must be positive integers.\n' >&2; exit 2; }
done
command -v psql >/dev/null || { printf 'psql is required.\n' >&2; exit 127; }
command -v python3 >/dev/null || { printf 'python3 is required to format EXPLAIN output as JSON.\n' >&2; exit 127; }

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
database_name=$(psql "$db_url" -X -A -t -q -v ON_ERROR_STOP=1 -c 'SELECT current_database()')
case "$database_name" in
  *perf*) ;;
  *) printf 'Refusing writes: database name must contain "perf" (got %s).\n' "$database_name" >&2; exit 1 ;;
esac

psql "$db_url" -X -q -v ON_ERROR_STOP=1 \
  -v tenant_count="$tenant_count" -v device_count="$device_count" \
  -f "$script_dir/seed.sql" >/dev/null

raw_plans=$(mktemp)
trap 'rm -f "$raw_plans"' EXIT
for _ in {1..10}; do
  psql "$db_url" -X -A -t -q -v ON_ERROR_STOP=1 -f "$script_dir/query.sql" >>"$raw_plans"
done

python3 - "$database_name" "$tenant_count" "$device_count" "$raw_plans" <<'PY'
import json
import sys
from pathlib import Path

labels = ["pagination", "status_filter", "keyword_search", "aggregate"]
text = Path(sys.argv[4]).read_text(encoding="utf-8")
decoder = json.JSONDecoder()
plans = []
position = 0
while position < len(text):
    while position < len(text) and text[position].isspace():
        position += 1
    if position == len(text):
        break
    value, position = decoder.raw_decode(text, position)
    plans.append(value[0]["Plan"])
if len(plans) != 10 * len(labels):
    raise SystemExit(f"Expected {10 * len(labels)} EXPLAIN plans; received {len(plans)}")

def visit(node):
    yield node
    for child in node.get("Plans", []):
        yield from visit(child)

results = []
for index, label in enumerate(labels):
    samples = []
    indexes = set()
    hit_blocks = []
    for iteration in range(10):
        plan = plans[iteration * len(labels) + index]
        nodes = list(visit(plan))
        samples.append(float(plan["Actual Total Time"]))
        hit_blocks.append(sum(int(node.get("Shared Hit Blocks", 0)) for node in nodes))
        indexes.update(node["Index Name"] for node in nodes if "Index Name" in node)
    ordered = sorted(samples)
    p95 = ordered[max(0, int(0.95 * len(ordered) + 0.999999) - 1)]
    results.append({
        "query": label,
        "samples": len(samples),
        "p95_ms": round(p95, 3),
        "shared_hit_blocks_avg": round(sum(hit_blocks) / len(hit_blocks), 3),
        "index_hit": bool(indexes),
        "indexes": sorted(indexes),
    })
print(json.dumps({
    "database": sys.argv[1],
    "tenant_count": int(sys.argv[2]),
    "device_count": int(sys.argv[3]),
    "results": results,
}, separators=(",", ":")))
PY
