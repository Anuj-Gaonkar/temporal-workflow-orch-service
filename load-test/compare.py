#!/usr/bin/env python3
"""Compares load-test stages (and series such as baseline vs a later change) in one self-contained HTML page.

Reads load-test/results/<label>/<N>-run<k>/summary.json written by run_stage.py. One line per label (series), the
x axis is the number of customers; repeated runs of a stage are averaged and shown as faint dots.

  pip install -r load-test/requirements.txt        # plotly (and pandas for report.py)
  python load-test/compare.py                       # all labels -> load-test/reports/compare.html
  python load-test/compare.py --labels baseline,parallel-start --out load-test/reports/start-change.html
  python load-test/compare.py --cdn                 # smaller file, loads plotly.js from the internet
"""
import argparse
import csv
import glob
import json
import os
import sys
from collections import defaultdict
from datetime import datetime

try:
    import plotly.graph_objects as go
except ImportError:
    sys.exit("plotly is not installed: pip install -r load-test/requirements.txt")

HERE = os.path.dirname(os.path.abspath(__file__))
PALETTE = ["#2563eb", "#dc2626", "#059669", "#d97706", "#7c3aed", "#0891b2", "#be185d", "#4b5563"]
DASH = {"p50": "solid", "p95": "dash", "p99": "dot", "max": "dashdot"}


def load(results, labels):
    runs = []
    for path in sorted(glob.glob(os.path.join(results, "*", "*", "summary.json"))):
        with open(path, encoding="utf-8") as fh:
            s = json.load(fh)
        if labels and s["label"] not in labels:
            continue
        s["_dir"] = os.path.dirname(path)
        runs.append(s)
    if not runs:
        sys.exit(f"no summary.json found under {results}")
    return runs


def dig(d, path):
    for key in path.split("."):
        if not isinstance(d, dict) or key not in d or d[key] is None:
            return None
        d = d[key]
    return d


def series_of(runs, path):
    """{label: {customers: [values over runs]}}"""
    out = defaultdict(lambda: defaultdict(list))
    for s in runs:
        v = dig(s, path)
        if v is not None:
            out[s["label"]][s["customers_loaded"]].append(v)
    return out


def figure(title, yaxis, curves, runs, log_y=False, scale=1.0):
    """curves = [(legend suffix, path, dash)]; one trace per label and curve."""
    labels = sorted({s["label"] for s in runs})
    fig = go.Figure()
    for i, label in enumerate(labels):
        color = PALETTE[i % len(PALETTE)]
        for suffix, path, dash in curves:
            data = series_of(runs, path).get(label, {})
            xs = sorted(data)
            if not xs:
                continue
            name = f"{label} {suffix}".strip()
            fig.add_trace(go.Scatter(x=xs, y=[sum(data[x]) / len(data[x]) / scale for x in xs], mode="lines+markers", name=name,
                                     line=dict(color=color, dash=dash), legendgroup=name))
            if any(len(v) > 1 for v in data.values()):
                fig.add_trace(go.Scatter(x=[x for x in xs for _ in data[x]], y=[v / scale for x in xs for v in data[x]], mode="markers",
                                         marker=dict(color=color, size=5, opacity=0.35), showlegend=False, legendgroup=name,
                                         hoverinfo="skip"))
    fig.update_layout(title=title, xaxis_title="customers loaded (plus 6 default)", yaxis_title=yaxis, template="plotly_white",
                      height=380, margin=dict(l=60, r=20, t=50, b=50), hovermode="x unified",
                      xaxis=dict(type="log" if len(sorted({s["customers_loaded"] for s in runs})) > 2 else "linear"),
                      yaxis=dict(type="log" if log_y else "linear"))
    return fig


def burst_curves(runs):
    fig = go.Figure()
    for i, s in enumerate(sorted(runs, key=lambda r: (r["label"], r["customers_loaded"], r["run"]))):
        path = os.path.join(s["_dir"], "stub-series.csv")
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        rows = [r for r in rows if int(r["received"]) > 0]
        if not rows:
            continue
        t0 = float(rows[0]["elapsed_s"])
        name = f"{s['label']} {s['customers_loaded']}" + (f" run{s['run']}" if s["run"] > 1 else "")
        fig.add_trace(go.Scatter(x=[float(r["elapsed_s"]) - t0 for r in rows], y=[int(r["received"]) for r in rows], mode="lines", name=name))
    fig.update_layout(title="Day 1 burst: contacts received by the stub, seconds from the first contact", xaxis_title="seconds",
                      yaxis_title="contacts received (cumulative)", template="plotly_white", height=420,
                      margin=dict(l=60, r=20, t=50, b=50), hovermode="x unified")
    return fig


def resource_max(runs, container_part=None, app=None):
    """Adds a synthetic 'peak' field to each run: max over the matching resource series."""
    for s in runs:
        res = s.get("resources", {})
        for key, value in res.items():
            pass
        for tag, needle in (("temporal_cpu", 'loadtest_container_cpu_percent{container="cern-v2-orch-temporal"}'),
                            ("temporal_mem", 'loadtest_container_memory_bytes{container="cern-v2-orch-temporal"}'),
                            ("tpg_cpu", 'loadtest_container_cpu_percent{container="cern-v2-orch-temporal-postgres"}'),
                            ("tpg_mem", 'loadtest_container_memory_bytes{container="cern-v2-orch-temporal-postgres"}'),
                            ("app_cpu", "loadtest_app_cpu_percent"), ("app_mem", "loadtest_app_memory_bytes"),
                            ("app_heap", "loadtest_app_heap_used_bytes")):
            if needle in res:
                s.setdefault("_peak", {})[tag] = res[needle]["max"]
                s.setdefault("_avg", {})[tag] = res[needle]["avg"]


def table(runs):
    head = ["label", "customers", "run", "status", "start/s", "start-all s", "start p95 ms", "day-1 s2s p95 ms", "day-1 s2s max ms",
            "timer late p95 ms", "peak contacts/s", "drain s", "terminate s", "DB B/wf", "DB MiB after", "peak threads",
            "peak pg conns (temporal/app)", "failures"]
    rows = []
    for s in sorted(runs, key=lambda r: (r["label"], r["customers_loaded"], r["run"])):
        a, b = s.get("start_all", {}), s.get("burst", {})
        fails = (b.get("activity_failures") or 0) + (b.get("workflow_failures") or 0) + (a.get("failed") or 0)
        cells = [s["label"], f"{s['customers_loaded']:,}", s["run"], s.get("status", ""), a.get("rate_per_s"), a.get("wall_s"),
                 dig(a, "start_ms.p95"), dig(b, "sched_to_start_ms.p95"), dig(b, "sched_to_start_ms.max"),
                 dig(b, "timer_lateness_ms.p95"), b.get("app_peak_per_s"), b.get("wait_after_start_all_s"),
                 dig(s, "terminate.wall_s"), dig(s, "db.bytes_per_workflow_total"),
                 None if dig(s, "db.bytes_after_terminate") is None else round(dig(s, "db.bytes_after_terminate") / 1048576),
                 None if dig(s, "monitor.app_threads_live.max") is None else int(dig(s, "monitor.app_threads_live.max")),
                 f"{int(dig(s, 'monitor.temporal_pg_conn.max'))} / {int(dig(s, 'monitor.app_pg_conn.max'))}"
                 if dig(s, "monitor.temporal_pg_conn.max") is not None and dig(s, "monitor.app_pg_conn.max") is not None else None, fails]
        rows.append("<tr>" + "".join(f"<td>{'' if c is None else (f'{c:,}' if isinstance(c, int) else c)}</td>" for c in cells) + "</tr>")
    return "<table><tr>" + "".join(f"<th>{h}</th>" for h in head) + "</tr>" + "".join(rows) + "</table>"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results", default=os.path.join(HERE, "results"))
    ap.add_argument("--labels", help="comma separated labels to include (default: all)")
    ap.add_argument("--out", default=os.path.join(HERE, "reports", "compare.html"))
    ap.add_argument("--cdn", action="store_true", help="load plotly.js from a CDN instead of embedding it")
    args = ap.parse_args()
    runs = load(args.results, set(args.labels.split(",")) if args.labels else None)
    resource_max(runs)
    for s in runs:  # peaks as plain paths for figure()
        s["peak"] = s.get("_peak", {})

    figs = [
        figure("Phase A: start-all throughput", "workflows started per second", [("", "start_all.rate_per_s", "solid")], runs),
        figure("Phase A: start-all total time", "seconds", [("", "start_all.wall_s", "solid")], runs),
        figure("Phase A: latency of one start (API_START_DONE startMs)", "ms",
               [("p50", "start_all.start_ms.p50", DASH["p50"]), ("p95", "start_all.start_ms.p95", DASH["p95"]),
                ("p99", "start_all.start_ms.p99", DASH["p99"])], runs, log_y=True),
        figure("Phase B: wait for a worker (activity scheduleToStart, Day 1 contact)", "ms",
               [("p50", "burst.sched_to_start_ms.p50", DASH["p50"]), ("p95", "burst.sched_to_start_ms.p95", DASH["p95"]),
                ("p99", "burst.sched_to_start_ms.p99", DASH["p99"]), ("max", "burst.sched_to_start_ms.max", DASH["max"])], runs, log_y=True),
        figure("Phase B: Day 1 timer lateness (actual wait minus one day-length)", "ms",
               [("p50", "burst.timer_lateness_ms.p50", DASH["p50"]), ("p95", "burst.timer_lateness_ms.p95", DASH["p95"]),
                ("max", "burst.timer_lateness_ms.max", DASH["max"])], runs),
        figure("Phase B: contacts per second at the peak", "contacts / s",
               [("app log", "burst.app_peak_per_s", "solid"), ("stub", "burst.stub_peak_per_s", "dash")], runs),
        figure("Phase B: time from end of start-all until the last contact", "seconds", [("", "burst.wait_after_start_all_s", "solid")], runs),
        figure("Terminate-all total time", "seconds", [("", "terminate.wall_s", "solid")], runs),
        figure("Temporal DB: bytes per workflow", "bytes / workflow",
               [("after start", "db.bytes_per_workflow_start", "solid"), ("after terminate", "db.bytes_per_workflow_total", "dash")], runs),
        figure("Temporal Postgres: WAL written per workflow (start phase)", "bytes / workflow", [("", "db.wal_bytes_per_workflow_start", "solid")], runs),
        figure("Peak CPU (100 = one core)", "percent",
               [("app", "peak.app_cpu", "solid"), ("temporal", "peak.temporal_cpu", "dash"), ("temporal-postgres", "peak.tpg_cpu", "dot")], runs),
        figure("Peak memory", "MiB",
               [("app process", "peak.app_mem", "solid"), ("app JVM heap", "peak.app_heap", "dashdot"), ("temporal", "peak.temporal_mem", "dash"),
                ("temporal-postgres", "peak.tpg_mem", "dot")],
               runs, scale=1024 * 1024),
        figure("Temporal DB size (temporal + temporal_visibility)", "MiB",
               [("after start", "db.bytes_after_start", "solid"), ("after burst", "db.bytes_after_burst", "dash"),
                ("after terminate", "db.bytes_after_terminate", "dot")], runs, scale=1024 * 1024),
        figure("App (Spring Boot) JVM threads, peak", "threads",
               [("live", "monitor.app_threads_live.max", "solid"), ("daemon", "monitor.app_threads_daemon.max", "dash")], runs),
        figure("App DB pool (HikariCP), peak", "connections",
               [("active", "monitor.hikari_active.max", "solid"), ("pending", "monitor.hikari_pending.max", "dash"),
                ("pool max", "monitor.hikari_max.max", "dot")], runs),
        figure("Postgres connections, peak", "connections",
               [("temporal", "monitor.temporal_pg_conn.max", "solid"), ("temporal_visibility", "monitor.temporal_vis_pg_conn.max", "dash"),
                ("app cern_v2", "monitor.app_pg_conn.max", "dot")], runs),
        figure("Temporal server goroutines, peak", "goroutines",
               [("history", "monitor.goroutines_history.max", "solid"), ("matching", "monitor.goroutines_matching.max", "dash"),
                ("frontend", "monitor.goroutines_frontend.max", "dot"), ("worker", "monitor.goroutines_worker.max", "dashdot")], runs),
        figure("Temporal SDK worker: fewest free task slots (0 = worker saturated)", "free slots",
               [("activity", "monitor.sdk_activity_slots_free.min", "solid"), ("workflow task", "monitor.sdk_workflow_slots_free.min", "dash")], runs),
        burst_curves(runs),
    ]
    parts = []
    for i, fig in enumerate(figs):
        parts.append(fig.to_html(full_html=False, include_plotlyjs=("cdn" if args.cdn else True) if i == 0 else False))
    html = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Load test comparison</title>
<style>body{{font-family:system-ui,sans-serif;margin:0 auto;max-width:1200px;padding:16px;color:#111;background:#fff}}
h1{{font-size:1.4rem}} .grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(520px,1fr));gap:12px}}
table{{border-collapse:collapse;font-size:.8rem;margin:12px 0}} th,td{{border:1px solid #ddd;padding:3px 7px;text-align:right}}
th{{background:#f3f4f6}} td:first-child,th:first-child{{text-align:left}} .scroll{{overflow-x:auto}}
.note{{color:#555;font-size:.85rem}}</style></head><body>
<h1>Load test comparison</h1>
<p class="note">Generated {datetime.now():%Y-%m-%d %H:%M}. One line per label; repeated runs are averaged (faint dots). Only the first contact (Day 1) is tested, then the workflows are terminated.</p>
<div class="scroll">{table(runs)}</div>
<div class="grid">{''.join(parts[:-1])}</div>
{parts[-1]}
</body></html>"""
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(html)
    print(f"wrote {args.out} ({len(runs)} runs, labels: {', '.join(sorted({s['label'] for s in runs}))})")


if __name__ == "__main__":
    main()
