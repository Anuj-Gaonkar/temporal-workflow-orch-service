#!/usr/bin/env python3
"""Rows, sizes and read/write operations of Temporal's Postgres, before and after a load test.

Temporal keeps its own Postgres (container cern-v2-orch-temporal-postgres) with two databases:
`temporal` (workflow state, history, timers) and `temporal_visibility` (what the Temporal UI lists).
This tool only READS. Python 3 standard library only; it talks to Postgres through `docker exec psql`.

  python3 load-test/temporal_db_stats.py snapshot before          # rows, sizes and operation counters
  ... run the load test ...
  python3 load-test/temporal_db_stats.py snapshot after
  python3 load-test/temporal_db_stats.py diff before after [--workflows N] [--seconds S]
  python3 load-test/temporal_db_stats.py watch --stop-when-idle --final-snapshot after
                                                                   # samples growth every 5s into a CSV

What `diff` reports (all as after minus before):
  * rows and bytes per table (exact count(*))
  * writes:  rows inserted / updated / deleted (pg_stat_database and pg_stat_user_tables)
  * reads:   rows returned by scans (tup_returned) and rows fetched by index/bitmap scans (tup_fetched);
             per table, index-fetched rows and sequentially scanned rows
  * blocks read from disk vs found in cache (cache hit %)
  * transactions committed / rolled back, temp files, deadlocks
  * WAL generated (bytes, records, full-page images): the most honest "how much was written" number
  * everything per workflow (--workflows, default: growth of the `executions` table) and per second (--seconds)

The counters are cumulative since the Postgres container (re)started, so only differences mean anything.
The exact count(*) queries of a snapshot are themselves reads. To keep them out of the numbers, a snapshot reads
the counters BEFORE and AFTER counting and `diff` uses the two clean edges (after-counting of the first
snapshot, before-counting of the second). Postgres flushes statistics to shared memory within about a second,
so a snapshot waits 2 s before reading.
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))

COUNT_QUERY = """
SELECT table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint,
       pg_total_relation_size(format('%I.%I', table_schema, table_name)::regclass)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY 1
"""

DB_FIELDS = ["xact_commit", "xact_rollback", "blks_read", "blks_hit", "tup_returned", "tup_fetched",
             "tup_inserted", "tup_updated", "tup_deleted", "temp_files", "temp_bytes", "deadlocks"]
TBL_FIELDS = ["n_tup_ins", "n_tup_upd", "n_tup_hot_upd", "n_tup_del", "seq_scan", "seq_tup_read", "idx_scan",
              "idx_tup_fetch", "n_dead_tup", "n_live_tup", "heap_blks_read", "heap_blks_hit", "idx_blks_read", "idx_blks_hit"]
WAL_FIELDS = ["lsn_bytes", "wal_records", "wal_fpi", "wal_bytes"]

COUNTER_QUERY = f"""
SELECT 'db', {", ".join(f"coalesce({f},0)::bigint" for f in DB_FIELDS)}
FROM pg_stat_database WHERE datname = current_database();
SELECT 'wal', pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint, coalesce(wal_records,0)::bigint, coalesce(wal_fpi,0)::bigint, coalesce(wal_bytes,0)::bigint
FROM pg_stat_wal;
SELECT 'tbl', s.relname, {", ".join(f"coalesce({c},0)::bigint" for c in
        ["s.n_tup_ins", "s.n_tup_upd", "s.n_tup_hot_upd", "s.n_tup_del", "s.seq_scan", "s.seq_tup_read", "s.idx_scan",
         "s.idx_tup_fetch", "s.n_dead_tup", "s.n_live_tup", "io.heap_blks_read", "io.heap_blks_hit", "io.idx_blks_read", "io.idx_blks_hit"])}
FROM pg_stat_user_tables s JOIN pg_statio_user_tables io USING (relid);
"""

RUNNING_QUERY = "SELECT count(*) FROM current_executions WHERE status = 1 AND workflow_id NOT LIKE 'temporal-sys%'"


def psql(args, db, sql):
    cmd = ["docker", "exec", "-i", args.container, "psql", "-U", args.user, "-d", db, "-At", "-F", "|", "-v", "ON_ERROR_STOP=1"]
    r = subprocess.run(cmd, input=sql, text=True, capture_output=True)
    if r.returncode != 0:
        sys.exit(f"psql failed on database {db}:\n{r.stderr or r.stdout}")
    return r.stdout


def read_counters(args):
    """{'db': {db: {field: n}}, 'tables': {db: {table: {field: n}}}, 'wal': {field: n}}"""
    out = {"db": {}, "tables": {}, "wal": {}}
    for i, db in enumerate(args.dbs.split(",")):
        tables = {}
        for line in psql(args, db, COUNTER_QUERY).splitlines():
            parts = line.split("|")
            if parts[0] == "db":
                out["db"][db] = dict(zip(DB_FIELDS, map(int, parts[1:])))
            elif parts[0] == "wal" and i == 0:  # cluster-wide, once
                out["wal"] = dict(zip(WAL_FIELDS, map(int, parts[1:])))
            elif parts[0] == "tbl":
                tables[parts[1]] = dict(zip(TBL_FIELDS, map(int, parts[2:])))
        out["tables"][db] = tables
    return out


def take(args):
    """Rows and sizes {db: {table: [rows, bytes]}} plus the counters read before and after counting."""
    time.sleep(args.settle)
    before = read_counters(args)
    dbs = {}
    for db in args.dbs.split(","):
        tables = {}
        for line in psql(args, db, COUNT_QUERY).splitlines():
            name, rows, size = line.split("|")
            tables[name] = [int(rows), int(size)]
        dbs[db] = tables
    time.sleep(args.settle)
    after = read_counters(args)
    return dbs, {"before": before, "after": after}


def path_for(args, name):
    return name if name.endswith(".json") or os.sep in name else os.path.join(args.dir, name + ".json")


def load(args, name):
    with open(path_for(args, name), encoding="utf-8") as fh:
        return json.load(fh)


def save_snapshot(args, label):
    dbs, counters = take(args)
    snap = {"label": label, "taken_at": datetime.now(IST).isoformat(timespec="seconds"), "databases": dbs, "counters": counters}
    os.makedirs(args.dir, exist_ok=True)
    path = path_for(args, label)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(snap, fh, indent=1)
    return snap, path


def fmt_bytes(n):
    sign = "-" if n < 0 else ""
    n = abs(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{sign}{n:,.0f} {unit}" if unit == "B" else f"{sign}{n:,.1f} {unit}"
        n /= 1024


def cmd_snapshot(args):
    snap, path = save_snapshot(args, args.label)
    print(f"saved {path} ({snap['taken_at']} IST)")
    for db, tables in snap["databases"].items():
        total_rows = sum(r for r, _ in tables.values())
        total_bytes = sum(b for _, b in tables.values())
        print(f"\n{db}: {total_rows:,} rows, {fmt_bytes(total_bytes)}")
        for name, (rows, size) in sorted(tables.items(), key=lambda kv: -kv[1][0]):
            if rows:
                print(f"  {name:<28}{rows:>12,}{fmt_bytes(size):>14}")


# ----------------------------------------------------------------------------- diff

def diff_rows(before, after):
    """[(db, table, rows_before, rows_after, delta_rows, delta_bytes)] for every table in either snapshot."""
    rows = []
    for db in sorted(set(before["databases"]) | set(after["databases"])):
        b, a = before["databases"].get(db, {}), after["databases"].get(db, {})
        for table in sorted(set(b) | set(a)):
            rb, bb = b.get(table, [0, 0])
            ra, ba = a.get(table, [0, 0])
            rows.append((db, table, rb, ra, ra - rb, ba - bb))
    return rows


def sub(a, b):
    return {k: a.get(k, 0) - b.get(k, 0) for k in set(a) | set(b)}


def counter_delta(before_snap, after_snap):
    """Counter difference over the interval, excluding the snapshots' own counting queries."""
    if "counters" not in before_snap or "counters" not in after_snap:
        return None
    start, end = before_snap["counters"]["after"], after_snap["counters"]["before"]
    out = {"db": {}, "tables": {}, "wal": sub(end["wal"], start["wal"])}
    for db in end["db"]:
        out["db"][db] = sub(end["db"][db], start["db"].get(db, {}))
        out["tables"][db] = {t: sub(v, start["tables"].get(db, {}).get(t, {})) for t, v in end["tables"][db].items()}
    return out


def pct(part, total):
    return f"{100 * part / total:.1f}%" if total else "n/a"


def per(n, workflows):
    return f"{n / workflows:,.2f}" if workflows else "n/a"


def cmd_diff(args):
    before, after = load(args, args.before), load(args, args.after)
    rows = diff_rows(before, after)
    print(f"{before['label']} ({before['taken_at']})  ->  {after['label']} ({after['taken_at']})\n")
    print("ROWS AND SIZE")
    print(f"{'database':<22}{'table':<28}{'before':>11}{'after':>11}{'+rows':>11}{'+size':>13}")
    for db, table, rb, ra, dr, dbytes in sorted(rows, key=lambda r: -r[4]):
        if dr or args.all:
            print(f"{db:<22}{table:<28}{rb:>11,}{ra:>11,}{dr:>+11,}{fmt_bytes(dbytes):>13}")
    for db in sorted({r[0] for r in rows}):
        print(f"{db:<22}{'TOTAL':<28}{sum(r[2] for r in rows if r[0] == db):>11,}{sum(r[3] for r in rows if r[0] == db):>11,}"
              f"{sum(r[4] for r in rows if r[0] == db):>+11,}{fmt_bytes(sum(r[5] for r in rows if r[0] == db)):>13}")

    delta = counter_delta(before, after)
    if delta is None:
        print("\n(no operation counters in these snapshots: they were taken before counters were added)")
        return

    workflows = args.workflows
    if workflows is None:
        workflows = next((r[4] for r in rows if r[0] == "temporal" and r[1] == "executions"), 0)
        print(f"\nper-workflow figures use N = {workflows:,} (growth of temporal.executions; override with --workflows)")
    secs = args.seconds

    if any(v < 0 for d in delta["db"].values() for v in d.values()):
        print("WARNING: a counter went down, so Postgres statistics were reset or the container restarted between the snapshots.")

    print("\nOPERATIONS PER DATABASE")
    print(f"{'database':<22}{'commits':>10}{'rollbacks':>10}{'rows inserted':>15}{'updated':>10}{'deleted':>10}{'rows returned':>15}{'rows fetched':>14}{'disk blk reads':>16}{'cache hit':>11}")
    for db, d in delta["db"].items():
        print(f"{db:<22}{d['xact_commit']:>10,}{d['xact_rollback']:>10,}{d['tup_inserted']:>15,}{d['tup_updated']:>10,}{d['tup_deleted']:>10,}"
              f"{d['tup_returned']:>15,}{d['tup_fetched']:>14,}{d['blks_read']:>16,}{pct(d['blks_hit'], d['blks_hit'] + d['blks_read']):>11}")

    w = delta["wal"]
    print(f"\nWAL GENERATED (whole Postgres server): {fmt_bytes(w['lsn_bytes'])} written, {w['wal_records']:,} records, "
          f"{w['wal_fpi']:,} full-page images, {fmt_bytes(w['wal_bytes'])} counted by pg_stat_wal")

    for db, tables in delta["tables"].items():
        active = {t: v for t, v in tables.items() if v["n_tup_ins"] or v["n_tup_upd"] or v["n_tup_del"] or v["idx_scan"] or v["seq_scan"]}
        print(f"\nOPERATIONS PER TABLE ({db}), by writes")
        print(f"{'table':<28}{'inserts':>10}{'updates':>10}{'hot%':>7}{'deletes':>10}{'idx scans':>11}{'rows via idx':>14}{'seq scans':>11}{'rows via seq':>14}{'disk blks':>11}{'cache hit':>11}")
        for t, v in sorted(active.items(), key=lambda kv: -(kv[1]["n_tup_ins"] + kv[1]["n_tup_upd"] + kv[1]["n_tup_del"])):
            hit, read = v["heap_blks_hit"] + v["idx_blks_hit"], v["heap_blks_read"] + v["idx_blks_read"]
            print(f"{t:<28}{v['n_tup_ins']:>10,}{v['n_tup_upd']:>10,}{pct(v['n_tup_hot_upd'], v['n_tup_upd']):>7}{v['n_tup_del']:>10,}"
                  f"{v['idx_scan']:>11,}{v['idx_tup_fetch']:>14,}{v['seq_scan']:>11,}{v['seq_tup_read']:>14,}{read:>11,}{pct(hit, hit + read):>11}")

    print(f"\nPER WORKFLOW (N = {workflows:,})")
    temporal = delta["db"].get("temporal", {})
    visibility = delta["db"].get("temporal_visibility", {})
    writes = lambda d: d.get("tup_inserted", 0) + d.get("tup_updated", 0) + d.get("tup_deleted", 0)
    print(f"  writes (insert+update+delete) : {per(writes(temporal), workflows)} in temporal, {per(writes(visibility), workflows)} in visibility")
    print(f"  transactions committed        : {per(temporal.get('xact_commit', 0), workflows)} in temporal, {per(visibility.get('xact_commit', 0), workflows)} in visibility")
    print(f"  rows returned / fetched       : {per(temporal.get('tup_returned', 0), workflows)} / {per(temporal.get('tup_fetched', 0), workflows)} in temporal")
    print(f"  WAL generated                 : {fmt_bytes(w['lsn_bytes'] / workflows) if workflows else 'n/a'}")
    if secs:
        print(f"\nPER SECOND (over {secs:,.0f} s)")
        print(f"  writes    : {writes(temporal) / secs:,.0f}/s temporal, {writes(visibility) / secs:,.0f}/s visibility")
        print(f"  commits   : {temporal.get('xact_commit', 0) / secs:,.0f}/s temporal")
        print(f"  WAL       : {fmt_bytes(w['lsn_bytes'] / secs)}/s")


# ----------------------------------------------------------------------------- watch

def sample(args, writer, now):
    counters = read_counters(args)
    for db in args.dbs.split(","):
        d = counters["db"].get(db, {})
        for f in DB_FIELDS:
            writer.writerow([now, db, "", f, d.get(f, 0)])
        sizes = psql(args, db, "SELECT relname, pg_total_relation_size(relid) FROM pg_stat_user_tables").splitlines()
        size_of = {line.split("|")[0]: int(line.split("|")[1]) for line in sizes}
        for t, v in counters["tables"][db].items():
            for f in ("n_live_tup", "n_tup_ins", "n_tup_upd", "n_tup_del"):
                writer.writerow([now, db, t, f, v[f]])
            writer.writerow([now, db, t, "bytes", size_of.get(t, 0)])
    for f, v in counters["wal"].items():
        writer.writerow([now, "", "__wal__", f, v])


def running_workflows(args):
    return int(psql(args, "temporal", RUNNING_QUERY).strip() or 0)


def cmd_watch(args):
    """Cheap sampler: uses Postgres's own counters and row estimates, never count(*), so it can run all day."""
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    new = not os.path.exists(args.out)
    seen_running, idle_samples = False, 0
    deadline = time.time() + args.max_minutes * 60
    with open(args.out, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["time", "database", "table", "metric", "value"])
        print(f"sampling every {args.interval:g}s into {args.out}" + (" (stops when the workflows are done)" if args.stop_when_idle else " (Ctrl+C to stop)"))
        try:
            while time.time() < deadline:
                now = datetime.now(IST).isoformat(timespec="seconds")
                sample(args, w, now)
                fh.flush()
                if args.stop_when_idle:
                    n = running_workflows(args)
                    seen_running = seen_running or n > 0
                    idle_samples = idle_samples + 1 if (seen_running and n == 0) else 0
                    if idle_samples >= 2:
                        print(f"{now}: no running workflows for 2 samples, stopping")
                        break
                time.sleep(args.interval)
            else:
                print("stopped: --max-minutes reached")
        except KeyboardInterrupt:
            print("stopped")
    if args.final_snapshot:
        time.sleep(args.settle + 10)  # let the last cleanup and statistics flush finish
        snap, path = save_snapshot(args, args.final_snapshot)
        print(f"saved final snapshot {path} ({snap['taken_at']} IST)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--container", default="cern-v2-orch-temporal-postgres")
    ap.add_argument("--user", default="temporal")
    ap.add_argument("--dbs", default="temporal,temporal_visibility")
    ap.add_argument("--dir", default="load-test/snapshots")
    ap.add_argument("--settle", type=float, default=2, help="seconds to wait before reading counters (default 2)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("snapshot", help="save row counts, sizes and operation counters")
    s.add_argument("label")
    d = sub.add_parser("diff", help="compare two snapshots")
    d.add_argument("before")
    d.add_argument("after")
    d.add_argument("--all", action="store_true", help="include tables that did not change")
    d.add_argument("--workflows", type=int, help="number of workflows in the run, for per-workflow figures")
    d.add_argument("--seconds", type=float, help="duration to use for per-second figures")
    w = sub.add_parser("watch", help="sample counters over time into a CSV")
    w.add_argument("--out", default="load-test/snapshots/watch.csv")
    w.add_argument("--interval", type=float, default=5)
    w.add_argument("--stop-when-idle", action="store_true", help="stop after workflows were running and none are left")
    w.add_argument("--final-snapshot", metavar="LABEL", help="take this snapshot when the watch ends")
    w.add_argument("--max-minutes", type=float, default=240, help="safety limit (default 240)")
    args = ap.parse_args()
    {"snapshot": cmd_snapshot, "diff": cmd_diff, "watch": cmd_watch}[args.cmd](args)


if __name__ == "__main__":
    main()
