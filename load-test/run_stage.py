#!/usr/bin/env python3
"""Runs one load-test stage (or several) end to end and writes load-test/results/<label>/<N>-run<k>/summary.json.

A stage = N fake customers (plus the 6 default ones) -> start-all -> wait until every customer got its first (Day 1)
contact -> terminate all workflows. It measures two phases:
  Phase A  start-all: how fast workflows are started (rate, per-start latency)
  Phase B  Day 1 burst: all timers fire about one day-length after their start; how long the contacts take to drain
Nothing is sent to WhatsApp: the contacts go to load-test/stub/whatsapp_stub.py.

Per stage it: resets Temporal to empty (docker compose down -v / up), loads the customers, starts the app jar with
the right settings, takes DB snapshots, calls the API, polls the stub, terminates the workflows, stops the app, and
parses the app's log. Python 3 standard library only. See load-test/LOADTEST.md.

Needs running first (see LOADTEST.md): shared Postgres, the whatsapp stub, the built jar, and (optional, for the
dashboard) the monitoring stack and load-test/resource_exporter.py.

  python load-test/run_stage.py --label baseline --stages 1000,5000,10000,25000,50000
  python load-test/run_stage.py --label smoke --stages 200 --day-length 2m          # quick check of the whole pipeline
"""
import argparse
import base64
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KV = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)')
APP = "http://localhost:8196"
STUB = "http://localhost:8182"
EXPORTER = "http://localhost:9323"
GRAFANA = "http://localhost:3001"
PROMETHEUS = "http://localhost:9091"
COMPOSE = ["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.loadtest.yml"]


def log(msg):
    print(f"{datetime.now(IST):%H:%M:%S}  {msg}", flush=True)


def http(method, url, body=None, timeout=60, auth=None):
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    if auth:
        req.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        text = resp.read().decode()
        return json.loads(text) if text else None


def reachable(url):
    try:
        http("GET", url, timeout=5)
        return True
    except Exception:
        return False


def port_open(port):
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def wait_until(what, fn, timeout, interval=2):
    end = time.time() + timeout
    while time.time() < end:
        if fn():
            return
        time.sleep(interval)
    sys.exit(f"timed out waiting for {what}")


def run(cmd, cwd=ROOT, check=True, **kw):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, **kw)
    if check and r.returncode != 0:
        sys.exit(f"command failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def psql(sql, container="cern-v2-postgres", user="cern", db="cern_v2"):
    r = run(["docker", "exec", "-i", container, "psql", "-U", user, "-d", db, "-At", "-v", "ON_ERROR_STOP=1"], input=sql)
    return r.stdout.strip()


def parse_duration(text):
    m = re.fullmatch(r"(\d+)([smhd])", text.strip())
    if not m:
        sys.exit(f"bad duration '{text}' (use 90s, 30m, 1h)")
    return int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]


def annotate(text, tags):
    """A vertical marker on the Grafana dashboard; best effort."""
    try:
        http("POST", f"{GRAFANA}/api/annotations", {"dashboardUID": "loadtest", "time": int(time.time() * 1000), "text": text, "tags": tags},
             timeout=5, auth="admin:admin")
    except Exception:
        pass


# Series stored per stage under summary["monitor"] as {min, avg, max} over the stage window (Prometheus, 5 s steps).
MONITOR = {
    "app_threads_live": 'sum(jvm_threads_live_threads{job="orch-app"})',
    "app_threads_daemon": 'sum(jvm_threads_daemon_threads{job="orch-app"})',
    "hikari_active": 'max(hikaricp_connections_active{job="orch-app"})',
    "hikari_pending": 'max(hikaricp_connections_pending{job="orch-app"})',
    "hikari_max": 'max(hikaricp_connections_max{job="orch-app"})',
    "temporal_pg_conn": 'max(pg_stat_database_numbackends{job="temporal-postgres",datname="temporal"})',
    "temporal_vis_pg_conn": 'max(pg_stat_database_numbackends{job="temporal-postgres",datname="temporal_visibility"})',
    "app_pg_conn": 'max(pg_stat_database_numbackends{job="app-postgres",datname="cern_v2"})',
    "temporal_db_size_bytes": 'sum(pg_database_size_bytes{job="temporal-postgres",datname=~"temporal|temporal_visibility"})',
    "goroutines_history": 'sum(num_goroutines{service_name="history"})',
    "goroutines_matching": 'sum(num_goroutines{service_name="matching"})',
    "goroutines_frontend": 'sum(num_goroutines{service_name="frontend"})',
    "goroutines_worker": 'sum(num_goroutines{service_name="worker"})',
    "sdk_activity_slots_free": 'min(temporal_worker_task_slots_available{worker_type="ActivityWorker"})',
    "sdk_workflow_slots_free": 'min(temporal_worker_task_slots_available{worker_type="WorkflowWorker"})',
    "sdk_pollers": 'sum(temporal_num_pollers)',
}


def prom_window(start, end):
    """min/avg/max of every MONITOR series between two epoch times. Missing series (e.g. Prometheus down) are left out."""
    out = {}
    for name, query in MONITOR.items():
        url = (f"{PROMETHEUS}/api/v1/query_range?query={urllib.parse.quote(query)}&start={start:.0f}&end={end:.0f}&step=5")
        try:
            result = http("GET", url, timeout=20)["data"]["result"]
        except Exception:
            continue
        values = [float(v) for r in result for _t, v in r["values"] if v not in ("NaN", "+Inf", "-Inf")]
        if values:
            out[name] = {"min": min(values), "avg": round(sum(values) / len(values), 2), "max": max(values)}
    return out


# ----------------------------------------------------------------------------- stage steps

def preflight(args):
    if not reachable(f"{STUB}/stats"):
        sys.exit("the WhatsApp stub is not running on :8182. Start it: python load-test/stub/whatsapp_stub.py")
    if port_open(8196):
        sys.exit("something already listens on :8196 (a running app?). Stop it: this script starts its own app.")
    if not os.path.isfile(args.jar):
        sys.exit(f"jar not found: {args.jar}\nBuild it first: ./mvnw -DskipTests package")
    if "cern-v2-postgres" not in run(["docker", "ps", "--format", "{{.Names}}"]).stdout.split():
        sys.exit("container cern-v2-postgres is not running: (cd .. && docker compose up -d)")
    if not reachable(f"{EXPORTER}/stats"):
        log("note: resource exporter is not running (python load-test/resource_exporter.py): CPU/memory will be missing from the results")


def reset_temporal():
    log("resetting Temporal to empty (docker compose down -v, up -d)")
    run(COMPOSE + ["down", "-v"])
    run(COMPOSE + ["up", "-d"])
    wait_until("Temporal gRPC port 7235", lambda: port_open(7235), 180)
    wait_until("namespace 'default'", lambda: psql("SELECT count(*) FROM namespaces WHERE name='default'",
                                                     container="cern-v2-orch-temporal-postgres", user="temporal", db="temporal") == "1", 180)
    time.sleep(5)


def load_customers(count):
    log(f"loading {count} customers (L prefix), default customers kept")
    py = [sys.executable, os.path.join(HERE, "load_customers.py")]
    run(py + ["--clean"])
    run(py + ["--count", str(count)])
    return int(psql("SELECT count(*) FROM orch.customers"))


def start_app(args, stage_dir):
    os.makedirs(stage_dir, exist_ok=True)
    cmd = ["java", f"-Xms{args.heap}", f"-Xmx{args.heap}", "-jar", os.path.abspath(args.jar),
           f"--orch.timing.day-length={args.day_length}",
           f"--orch.definitions-path={os.path.join(ROOT, 'workflow-definitions')}",
           "--logging.logback.rollingpolicy.max-file-size=1GB",
           "--orch.whatsapp.base-url=" + STUB]
    log(f"starting the app (heap {args.heap}, day-length {args.day_length}), logs in {stage_dir}\\logs")
    proc = subprocess.Popen(cmd, cwd=stage_dir, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait_until("the app on :8196", lambda: reachable(f"{APP}/workflow-definitions") and proc.poll() is None, 240, 3)
    except SystemExit:
        proc.kill()
        raise
    if proc.poll() is not None:
        sys.exit("the app exited during startup; run it by hand to see why")
    time.sleep(5)
    return proc


def snapshot(stage_dir, label):
    snap_dir = os.path.join(stage_dir, "snapshots")
    run([sys.executable, os.path.join(HERE, "temporal_db_stats.py"), "--dir", snap_dir, "snapshot", label])
    with open(os.path.join(snap_dir, label + ".json"), encoding="utf-8") as fh:
        return json.load(fh)


def snap_bytes(snap):
    return sum(size for tables in snap["databases"].values() for _rows, size in tables.values())


def snap_rows(snap, table):
    return sum(t[table][0] for t in snap["databases"].values() if table in t)


def wal(snap, edge):
    return snap["counters"][edge]["wal"]["lsn_bytes"]


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, max(0, int(round(p / 100 * len(v) + 0.5)) - 1))]


def dist(values):
    return {"n": len(values), "p50": pct(values, 50), "p95": pct(values, 95), "p99": pct(values, 99), "max": max(values) if values else None}


def parse_logs(log_dir, day_length_ms):
    """Reads the app's event= lines. Returns the numbers the summary needs."""
    import glob
    import gzip
    out = {"start_ms": [], "sched_to_start_ms": [], "exec_ms": [], "timer_late_ms": [], "start_lag_ms": [],
           "act_start_ts": [], "act_failed": 0, "wa_failed": 0, "wf_failed": 0, "start_all": {}, "terminate_all": {}}
    for f in sorted(glob.glob(os.path.join(log_dir, "temporal-workflow-orch-service.log*"))):
        opener = gzip.open if f.endswith(".gz") else open
        with opener(f, "rt", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                i = line.find(" event=")
                if i < 0:
                    continue
                kv = {k: v.strip('"') for k, v in KV.findall(line[i + 1:])}
                ev = kv.get("event")
                if ev == "API_START_DONE" and "startMs" in kv:
                    out["start_ms"].append(int(kv["startMs"]))
                elif ev == "API_START_ALL_DONE":
                    out["start_all"] = kv
                elif ev == "WF_STARTED" and "startLagMs" in kv:
                    out["start_lag_ms"].append(int(kv["startLagMs"]))
                elif ev == "ACT_START" and kv.get("stepId") == "contact-day1":
                    out["sched_to_start_ms"].append(int(kv["scheduleToStartMs"]))
                    out["act_start_ts"].append(datetime.fromisoformat(line.split(" ", 1)[0]).timestamp())
                elif ev == "ACT_DONE" and kv.get("stepId") == "contact-day1":
                    out["exec_ms"].append(int(kv["execMs"]))
                elif ev == "WF_STEP_EXIT" and kv.get("stepId") == "wait-day-1":
                    out["timer_late_ms"].append(int(kv["stepMs"]) - day_length_ms)
                elif ev == "ACT_FAILED":
                    out["act_failed"] += 1
                elif ev == "ACT_WHATSAPP_FAILED":
                    out["wa_failed"] += 1
                elif ev == "WF_FAILED":
                    out["wf_failed"] += 1
                elif ev == "API_TERMINATE_ALL_DONE":
                    out["terminate_all"] = kv
    return out


# ----------------------------------------------------------------------------- one stage

def run_stage(args, customers, k):
    stage_dir = os.path.join(args.results, args.label, f"{customers}-run{k}")
    if os.path.exists(os.path.join(stage_dir, "summary.json")) and not args.force:
        log(f"stage {customers} run {k} already has a summary.json, skipping (use --force to redo)")
        return
    log(f"=== stage {customers} customers, run {k}, label '{args.label}' ===")
    os.makedirs(stage_dir, exist_ok=True)
    day_s = parse_duration(args.day_length)
    summary = {"label": args.label, "customers_loaded": customers, "run": k, "definition": args.definition,
               "day_length": args.day_length, "heap": args.heap, "started_at": datetime.now(IST).isoformat(timespec="seconds"),
               "jar": os.path.basename(args.jar), "stub_delay_ms": None}
    proc = None
    try:
        if not args.keep_temporal:
            reset_temporal()
        total = load_customers(customers)
        summary["customers_total"] = total
        http("POST", f"{STUB}/reset")
        summary["stub_delay_ms"] = http("GET", f"{STUB}/stats").get("delay_ms")
        proc = start_app(args, stage_dir)

        snap0 = snapshot(stage_dir, "0-before")
        if reachable(f"{EXPORTER}/stats"):
            http("POST", f"{EXPORTER}/reset")

        # ---- Phase A: start-all
        log(f"PHASE A: start-all for {total} customers")
        annotate(f"{customers}: start-all begins", ["load-test", "phase-a"])
        t0 = time.time()
        result = http("POST", f"{APP}/workflows/start-all", {"definition": args.definition}, timeout=6 * 3600)
        start_wall = time.time() - t0
        started = result["started"]
        log(f"start-all done in {start_wall:.1f}s: started={started} alreadyRunning={result['alreadyRunning']} failed={result['failed']} "
            f"({started / start_wall:.1f} workflows/s)")
        annotate(f"{customers}: start-all done ({start_wall:.0f}s)", ["load-test", "phase-a"])
        snap1 = snapshot(stage_dir, "1-after-start")
        summary["start_all"] = {"wall_s": round(start_wall, 2), "total": result["total"], "started": started,
                                "already_running": result["alreadyRunning"], "failed": result["failed"],
                                "rate_per_s": round(started / start_wall, 2) if start_wall else None}

        # ---- Phase B: wait for the Day 1 burst to drain
        expected = total
        day1_at = t0 + day_s                    # the first workflow's timer
        deadline = t0 + start_wall + day_s + args.burst_timeout_min * 60
        log(f"PHASE B: waiting for {expected} first contacts; Day 1 begins about {datetime.fromtimestamp(day1_at, IST):%H:%M:%S}")
        annotate(f"{customers}: waiting for Day 1", ["load-test", "phase-b"])
        series, last_count, last_change, announced = [], -1, time.time(), False
        while True:
            try:
                s = http("GET", f"{STUB}/stats", timeout=10)
            except Exception:
                time.sleep(5)
                continue
            now = time.time()
            series.append((round(now - t0, 1), s["total"], s["inflight"]))
            if s["total"] > 0 and not announced:
                announced = True
                log("Day 1 burst started (first contact reached the stub)")
                annotate(f"{customers}: Day 1 burst starts", ["load-test", "phase-b"])
            if s["total"] != last_count:
                last_count, last_change = s["total"], now
            if s["total"] >= expected:
                break
            if now > deadline or (now > day1_at + start_wall + 300 and now - last_change > 180):
                summary["status"] = "INCOMPLETE"
                log(f"giving up: {s['total']} of {expected} contacts received")
                break
            time.sleep(5)
        drained_at = time.time()
        summary.setdefault("status", "COMPLETE")
        annotate(f"{customers}: Day 1 burst done", ["load-test", "phase-b"])
        time.sleep(3)
        snap2 = snapshot(stage_dir, "2-after-burst")
        with open(os.path.join(stage_dir, "stub-series.csv"), "w", encoding="utf-8") as fh:
            fh.write("elapsed_s,received,inflight\n" + "".join(f"{a},{b},{c}\n" for a, b, c in series))
        stub = http("GET", f"{STUB}/stats")

        # ---- terminate
        term = None
        if not args.no_terminate:
            log("terminating all workflows (first contact is done)")
            t1 = time.time()
            term = http("POST", f"{APP}/workflows/terminate-all", {"definition": args.definition, "reason": "load test: first contact done"},
                        timeout=6 * 3600)
            term_wall = time.time() - t1
            log(f"terminated {term['terminated']} of {term['total']} in {term_wall:.1f}s (failed {term['failed']})")
            annotate(f"{customers}: terminate-all done ({term_wall:.0f}s)", ["load-test", "terminate"])
            summary["terminate"] = {"wall_s": round(term_wall, 2), "total": term["total"], "terminated": term["terminated"], "failed": term["failed"]}
        snap3 = snapshot(stage_dir, "3-after-terminate")

        summary["monitor"] = prom_window(t0 - 10, time.time())
        if not summary["monitor"]:
            log("note: Prometheus returned no series (is the monitoring stack up?), threads and connections will be missing from the results")

        resources = http("GET", f"{EXPORTER}/stats") if reachable(f"{EXPORTER}/stats") else {}
        summary["resources"] = {k2: {"max": round(v["max"], 1), "avg": round(v["avg"], 1)} for k2, v in resources.items()}

        # ---- numbers
        per_wf = lambda n: round(n / started) if started else None
        summary["db"] = {
            "bytes_before": snap_bytes(snap0), "bytes_after_start": snap_bytes(snap1), "bytes_after_burst": snap_bytes(snap2),
            "bytes_after_terminate": snap_bytes(snap3),
            "bytes_per_workflow_start": per_wf(snap_bytes(snap1) - snap_bytes(snap0)),
            "bytes_per_workflow_total": per_wf(snap_bytes(snap3) - snap_bytes(snap0)),
            "executions_rows": snap_rows(snap3, "executions"),
            "wal_bytes_start_phase": wal(snap1, "before") - wal(snap0, "after"),
            "wal_bytes_burst_phase": wal(snap2, "before") - wal(snap1, "after"),
            "wal_bytes_terminate_phase": wal(snap3, "before") - wal(snap2, "after"),
        }
        summary["db"]["wal_bytes_per_workflow_start"] = per_wf(summary["db"]["wal_bytes_start_phase"])
        summary["stub"] = stub
        summary["burst"] = {"expected": expected, "received": stub["total"], "wait_after_start_all_s": round(drained_at - (t0 + start_wall), 1),
                            "stub_peak_per_s": stub["peak_per_second"], "stub_max_inflight": stub["max_inflight"],
                            "stub_first_to_last_s": round(stub["last_ts"] - stub["first_ts"], 1) if stub["first_ts"] else None}
    finally:
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(30)
            except subprocess.TimeoutExpired:
                proc.kill()
    # the log is complete only after the app has stopped
    parsed = parse_logs(os.path.join(stage_dir, "logs"), day_s * 1000)
    ts = sorted(parsed["act_start_ts"])
    per_sec = {}
    for t in ts:
        per_sec[int(t)] = per_sec.get(int(t), 0) + 1
    summary.setdefault("start_all", {}).update({"start_ms": dist(parsed["start_ms"]), "lookup_ms": int(parsed["start_all"].get("lookupMs", 0)),
                                                "total_ms_logged": int(parsed["start_all"].get("totalMs", 0))})
    summary.setdefault("burst", {}).update({
        "sched_to_start_ms": dist(parsed["sched_to_start_ms"]), "exec_ms": dist(parsed["exec_ms"]),
        "timer_lateness_ms": dist(parsed["timer_late_ms"]), "first_workflow_task_lag_ms": dist(parsed["start_lag_ms"]),
        "contacts_logged": len(ts), "app_peak_per_s": max(per_sec.values(), default=0),
        "app_first_to_last_s": round(ts[-1] - ts[0], 1) if ts else None,
        "activity_failures": parsed["act_failed"], "whatsapp_failures": parsed["wa_failed"], "workflow_failures": parsed["wf_failed"]})
    with open(os.path.join(stage_dir, "summary.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=1)
    print_summary(summary)


def ms(v):
    return "n/a" if v is None else f"{v:,}ms"


def print_summary(s):
    a, b = s["start_all"], s["burst"]
    log(f"--- stage {s['customers_loaded']} (+defaults = {s['customers_total']}), status {s.get('status')}")
    log(f"  start-all: {a['started']} started in {a['wall_s']}s = {a['rate_per_s']}/s; per-start p50/p95/p99 "
        f"{ms(a['start_ms']['p50'])}/{ms(a['start_ms']['p95'])}/{ms(a['start_ms']['p99'])}")
    log(f"  day-1 burst: {b['received']}/{b['expected']} contacts, scheduleToStart p50/p95/p99/max "
        f"{ms(b['sched_to_start_ms']['p50'])}/{ms(b['sched_to_start_ms']['p95'])}/{ms(b['sched_to_start_ms']['p99'])}/{ms(b['sched_to_start_ms']['max'])}; "
        f"timer lateness p95 {ms(b['timer_lateness_ms']['p95'])}; peak {b['app_peak_per_s']}/s")
    m = s.get("monitor", {})
    if m:
        peak = lambda k: "n/a" if k not in m else f"{m[k]['max']:,.0f}"
        log(f"  peaks: app threads {peak('app_threads_live')}, hikari active/pending {peak('hikari_active')}/{peak('hikari_pending')}, "
            f"pg conns temporal/app {peak('temporal_pg_conn')}/{peak('app_pg_conn')}, temporal history goroutines {peak('goroutines_history')}, "
            f"temporal DB {peak('temporal_db_size_bytes')} bytes")
    log(f"  failures: activity {b['activity_failures']}, whatsapp {b['whatsapp_failures']}, workflow {b['workflow_failures']}")
    if "terminate" in s:
        log(f"  terminate-all: {s['terminate']['terminated']}/{s['terminate']['total']} in {s['terminate']['wall_s']}s")
    log(f"  summary: {os.path.join(s['label'], str(s['customers_loaded']) + '-run' + str(s['run']))}\\summary.json")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--label", required=True, help="name of this series, e.g. baseline or parallel-start; results go to results/<label>/")
    ap.add_argument("--stages", default="1000,5000,10000,25000,50000", help="comma separated customer counts (L-prefixed, default customers are added)")
    ap.add_argument("--repeat", type=int, default=1, help="runs per stage (default 1)")
    ap.add_argument("--day-length", default="1h", help="one campaign day, e.g. 1h (default), 30m, 2m")
    ap.add_argument("--definition", default="OUTREACH_20_DAY")
    ap.add_argument("--heap", default="2g", help="JVM heap of the app, fixed so runs compare (default 2g)")
    ap.add_argument("--jar", default=os.path.join(ROOT, "target", "temporal-workflow-orch-service-0.0.1-SNAPSHOT.jar"))
    ap.add_argument("--results", default=os.path.join(HERE, "results"))
    ap.add_argument("--burst-timeout-min", type=int, default=30, help="how long past Day 1 to wait for the contacts (default 30)")
    ap.add_argument("--keep-temporal", action="store_true", help="do not wipe Temporal before the stage (data accumulates; not for baselines)")
    ap.add_argument("--no-terminate", action="store_true")
    ap.add_argument("--force", action="store_true", help="redo a stage that already has a summary.json")
    args = ap.parse_args()
    preflight(args)
    for n in [int(x) for x in args.stages.split(",")]:
        for k in range(1, args.repeat + 1):
            run_stage(args, n, k)
    log("all stages done. Compare: python load-test/compare.py")


if __name__ == "__main__":
    main()
