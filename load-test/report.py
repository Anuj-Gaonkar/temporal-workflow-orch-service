#!/usr/bin/env python3
"""Turn the service's timing logs into one self-contained HTML load-test report.

Reads the `event=NAME key=value` lines (see docs/phase-1/01-coding.md, "Log events reference")
from log files or folders, including rolled `.gz` files, and writes an HTML report with charts
(plotly, hover + zoom), a percentile table and a failures table. Times are shown in IST.

Setup (once):   pip install -r load-test/requirements.txt
Usage examples:
  python load-test/report.py                                   # all of logs/, all runs
  python load-test/report.py logs/ --run last                  # only the last start-all run
  python load-test/report.py collected-logs/run-01 --out load-test/reports/run-01.html
  python load-test/report.py logs/ --since 2026-09-30T15:36 --until 2026-09-30T15:50   # IST
  python load-test/report.py logs/ --list-runs                 # show start-all runs and exit

The report is one file (plotly.js is embedded, so it opens offline). Use --cdn for a much smaller
file that needs internet to render the charts.
"""
import argparse
import glob
import gzip
import html
import os
import re
import sys

import pandas as pd
import plotly.graph_objects as go

IST = "Asia/Kolkata"
KV = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)')
NUMERIC = ["lookupMs", "startMs", "totalMs", "startLagMs", "stepMs", "scheduleToStartMs", "execMs",
           "attempt", "count", "loadMs", "total", "started", "alreadyRunning", "failed"]

# Both palettes were checked with the dataviz validator (slots 1 and 2 of the reference palette).
THEMES = {
    "light": dict(bg="#fcfcfb", text="#0b0b0b", muted="#52514e", grid="#e9e8e4", card="#ffffff", s1="#2a78d6", s2="#eb6834"),
    "dark": dict(bg="#1a1a19", text="#ffffff", muted="#c3c2b7", grid="#2e2e2c", card="#222221", s1="#3987e5", s2="#d95926"),
}


# ----------------------------------------------------------------------------- reading the logs

def find_files(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            found = [f for f in glob.glob(os.path.join(p, "*")) if os.path.isfile(f)
                     and (".log" in os.path.basename(f) or f.endswith((".gz", ".txt")))]
            files.extend(sorted(found))
        elif os.path.isfile(p):
            files.append(p)
        else:
            sys.exit(f"not found: {p}")
    if not files:
        sys.exit("no log files found")
    return files


def parse(files):
    rows = []
    for f in files:
        opener = gzip.open if f.endswith(".gz") else open
        with opener(f, "rt", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                i = line.find(" event=")
                if i < 0:
                    continue
                row = {"ts": line.split(" ", 1)[0]}
                for key, value in KV.findall(line[i + 1:]):
                    if value.startswith('"'):
                        value = value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
                    row[key] = value
                rows.append(row)
    if not rows:
        sys.exit("no `event=` lines found in the given logs")
    df = pd.DataFrame(rows)
    # Old lines end in Z (UTC), new ones in +05:30: parse the offset, then show everything in IST.
    df["ts"] = pd.to_datetime(df["ts"], utc=True, format="ISO8601", errors="coerce").dt.tz_convert(IST)
    df = df.dropna(subset=["ts"])
    for col in NUMERIC:
        if col in df:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df.sort_values("ts", kind="stable").reset_index(drop=True)


def run_starts(df):
    return df.loc[df["event"] == "API_START_ALL_RECEIVED", "ts"].tolist()


def select_window(df, args):
    starts = run_starts(df)
    since = pd.Timestamp(args.since, tz=IST) if args.since else None
    until = pd.Timestamp(args.until, tz=IST) if args.until else None
    if args.run != "all":
        if not starts:
            sys.exit("no API_START_ALL_RECEIVED in the logs, so --run cannot pick a run (use --since/--until)")
        n = len(starts) if args.run == "last" else int(args.run)
        if not 1 <= n <= len(starts):
            sys.exit(f"--run must be 1..{len(starts)} or last")
        since = starts[n - 1]
        until = starts[n] if n < len(starts) else None
    if since is not None:
        df = df[df["ts"] >= since]
    if until is not None:
        df = df[df["ts"] < until]
    return df.reset_index(drop=True)


# ----------------------------------------------------------------------------- derived data

def workflows_frame(df):
    """One row per workflow run: channel, start lag, total time, delay time, overhead. Joined by
    (workflowId, occurrence) so a workflow id reused across runs does not mix runs."""
    def take(event, cols):
        d = df[df["event"] == event]
        keep = ["workflowId", "ts"] + [c for c in cols if c in d]
        d = d[keep].copy()
        d["n"] = d.groupby("workflowId").cumcount()
        return d

    started = take("WF_STARTED", ["channel", "customerId", "startLagMs"]).rename(columns={"ts": "startedAt"})
    done = take("WF_COMPLETED", ["totalMs"]).rename(columns={"ts": "completedAt"})
    wf = started.merge(done, on=["workflowId", "n"], how="left")
    if "stepType" in df.columns:
        delay = df[(df["event"] == "WF_STEP_EXIT") & (df.get("stepType") == "delay")]
        delay = delay[["workflowId", "stepMs"]].copy()
        delay["n"] = delay.groupby("workflowId").cumcount()
        wf = wf.merge(delay.rename(columns={"stepMs": "delayMs"}), on=["workflowId", "n"], how="left")
    if "delayMs" in wf:
        wf["overheadMs"] = wf["totalMs"] - wf["delayMs"]
        wf["timerLatenessMs"] = wf["delayMs"] - wf["delayMs"].min()
    return wf


def pct_row(label, series):
    s = pd.to_numeric(series, errors="coerce").dropna()
    if s.empty:
        return None
    return {"metric": label, "count": len(s), "mean": s.mean(), "p50": s.quantile(0.5), "p95": s.quantile(0.95),
            "p99": s.quantile(0.99), "max": s.max()}


def percentile_table(df, wf):
    e = lambda name, col: df.loc[df["event"] == name, col] if col in df else pd.Series(dtype=float)
    rows = [
        pct_row("API start: total per customer (ms)", e("API_START_DONE", "totalMs")),
        pct_row("API start: Temporal start call (ms)", e("API_START_DONE", "startMs")),
        pct_row("Workflow start lag (ms)", e("WF_STARTED", "startLagMs")),
        pct_row("Activity queueing, schedule to start (ms)", e("ACT_START", "scheduleToStartMs")),
        pct_row("Activity execution (ms)", e("ACT_DONE", "execMs")),
        pct_row("Workflow time excluding the delay (ms)", wf["overheadMs"]) if "overheadMs" in wf else None,
        pct_row("Delay timer lateness vs fastest (ms)", wf["timerLatenessMs"]) if "timerLatenessMs" in wf else None,
        pct_row("Workflow end to end incl. delay (ms)", wf["totalMs"]),
    ]
    return pd.DataFrame([r for r in rows if r])


def bucket_seconds(df):
    span = (df["ts"].max() - df["ts"].min()).total_seconds()
    for b in (1, 2, 5, 10, 30, 60, 120, 300, 600, 1800, 3600):
        if span / b <= 120:
            return b
    return 3600


# ----------------------------------------------------------------------------- charts

def base_layout(t, height=330, **extra):
    layout = dict(
        template="none", height=height, margin=dict(l=56, r=24, t=16, b=48),
        paper_bgcolor=t["card"], plot_bgcolor=t["card"],
        font=dict(family="system-ui, -apple-system, Segoe UI, Roboto, sans-serif", size=12, color=t["muted"]),
        xaxis=dict(gridcolor=t["grid"], linecolor=t["grid"], zeroline=False),
        yaxis=dict(gridcolor=t["grid"], linecolor=t["grid"], zeroline=False, rangemode="tozero", tickformat=","),
        legend=dict(orientation="h", y=1.12, x=0),
        hoverlabel=dict(font=dict(color=t["text"]), bgcolor=t["card"]),
    )
    layout.update(extra)
    return layout


def chart_starts(df, wf, b, t):
    ts = wf["startedAt"] if len(wf) else df.loc[df["event"] == "API_START_DONE", "ts"]
    if ts.empty:
        return None, None
    counts = ts.dt.floor(f"{b}s").value_counts().sort_index()
    fig = go.Figure(go.Bar(x=counts.index, y=counts.values, marker_color=t["s1"], marker_cornerradius=3,
                           hovertemplate="%{x|%H:%M:%S}<br>%{y} workflows started<extra></extra>"))
    fig.update_layout(**base_layout(t, bargap=0.15, yaxis_title=f"workflows started per {b}s", xaxis_tickformat="%H:%M:%S"))
    table = counts.rename("started").reset_index().rename(columns={"index": "time", "ts": "time", "startedAt": "time"})
    return fig, table


def chart_in_flight(wf, t):
    if wf.empty:
        return None, None
    ev = pd.concat([pd.DataFrame({"ts": wf["startedAt"], "d": 1}),
                    pd.DataFrame({"ts": wf["completedAt"].dropna(), "d": -1})]).sort_values("ts")
    ev["inFlight"] = ev["d"].cumsum()
    fig = go.Figure(go.Scatter(x=ev["ts"], y=ev["inFlight"], mode="lines", line=dict(color=t["s1"], width=2, shape="hv"),
                               hovertemplate="%{x|%H:%M:%S}<br>%{y} in flight<extra></extra>"))
    fig.update_layout(**base_layout(t, yaxis_title="workflows running", xaxis_tickformat="%H:%M:%S"))
    return fig, ev.rename(columns={"ts": "time"})[["time", "inFlight"]]


def chart_latency_over_time(df, b, t):
    def p95(event, col):
        d = df[df["event"] == event]
        if col not in d or d.empty:
            return pd.Series(dtype=float)
        return d.groupby(d["ts"].dt.floor(f"{b}s"))[col].quantile(0.95)

    lag, queue = p95("WF_STARTED", "startLagMs"), p95("ACT_START", "scheduleToStartMs")
    if lag.empty and queue.empty:
        return None, None
    # Every bucket in the range, so a quiet period shows as a gap instead of a line joining two busy periods.
    full = pd.date_range(min(x.index.min() for x in (lag, queue) if not x.empty),
                         max(x.index.max() for x in (lag, queue) if not x.empty), freq=f"{b}s")
    lag, queue = lag.reindex(full), queue.reindex(full)
    fig = go.Figure()
    for name, s, color in (("Workflow start lag", lag, t["s1"]), ("Activity queueing", queue, t["s2"])):
        if s.notna().any():
            fig.add_trace(go.Scatter(x=s.index, y=s.values, name=name, mode="lines+markers", connectgaps=False, line=dict(color=color, width=2),
                                     marker=dict(size=7, line=dict(color=t["card"], width=2)),
                                     hovertemplate=name + "<br>%{x|%H:%M:%S}<br>p95 %{y:,.0f} ms<extra></extra>"))
    fig.update_layout(**base_layout(t, yaxis_title=f"p95 per {b}s (ms)", xaxis_tickformat="%H:%M:%S", hovermode="x unified"))
    table = pd.concat({"startLagP95Ms": lag, "activityQueueP95Ms": queue}, axis=1).reset_index()
    table.columns = ["time"] + list(table.columns[1:])
    return fig, table


def chart_api_over_time(df, t):
    d = df[df["event"] == "API_START_DONE"]
    if d.empty or "startMs" not in d:
        return None, None
    fig = go.Figure(go.Scatter(x=d["ts"], y=d["startMs"], mode="markers", marker=dict(color=t["s1"], size=7, line=dict(color=t["card"], width=2)),
                               text=d.get("customerId"), hovertemplate="%{text}<br>%{x|%H:%M:%S.%L}<br>%{y} ms<extra></extra>"))
    fig.update_layout(**base_layout(t, yaxis_title="Temporal start call (ms)", xaxis_tickformat="%H:%M:%S"))
    return fig, None


def chart_api_histogram(df, t):
    d = df[df["event"] == "API_START_DONE"]
    if d.empty or "startMs" not in d:
        return None, None
    fig = go.Figure(go.Histogram(x=d["startMs"], nbinsx=30, marker_color=t["s1"], marker_cornerradius=3,
                                 hovertemplate="%{x} ms: %{y} customers<extra></extra>"))
    fig.update_layout(**base_layout(t, bargap=0.1, xaxis_title="Temporal start call (ms)", yaxis_title="customers"))
    return fig, None


def chart_channels(df, wf, t):
    if wf.empty or "channel" not in wf:
        return None, None
    channel_of = wf.drop_duplicates("workflowId", keep="last").set_index("workflowId")["channel"]
    act = df[df["event"] == "ACT_START"].copy()
    act["channel"] = act["workflowId"].map(channel_of)
    groups = {"Workflow start lag p95": wf.groupby("channel")["startLagMs"].quantile(0.95),
              "Activity queueing p95": act.groupby("channel")["scheduleToStartMs"].quantile(0.95)}
    if "overheadMs" in wf:
        groups["Workflow time excl. delay p95"] = wf.groupby("channel")["overheadMs"].quantile(0.95)
    table = pd.DataFrame(groups)
    if table.empty:
        return None, None
    fig = go.Figure()
    for channel, color in (("VOICE", t["s1"]), ("WHATSAPP", t["s2"])):
        if channel in table.index:
            fig.add_trace(go.Bar(name=channel, x=list(table.columns), y=table.loc[channel].values, marker_color=color,
                                 marker_cornerradius=3, text=[f"{v:,.0f}" for v in table.loc[channel].values], textposition="outside",
                                 textfont=dict(color=t["muted"]), hovertemplate=channel + "<br>%{x}: %{y:,.0f} ms<extra></extra>"))
    fig.update_layout(**base_layout(t, barmode="group", bargap=0.35, bargroupgap=0.08, yaxis_title="ms"))
    return fig, table.reset_index()


# ----------------------------------------------------------------------------- HTML

def fmt_table(d, max_rows=300):
    if d is None or d.empty:
        return ""
    d = d.head(max_rows).copy()
    for col in d.columns:
        if pd.api.types.is_datetime64_any_dtype(d[col]):
            d[col] = d[col].dt.strftime("%H:%M:%S")
        elif pd.api.types.is_float_dtype(d[col]):
            d[col] = d[col].round(1)
    return d.to_html(index=False, border=0, na_rep="", classes="tbl")


def card(title, subtitle, fig, table, first_plot):
    if fig is None:
        return (f'<section class="card"><h2>{html.escape(title)}</h2><p class="sub">No data for this chart in the selected window.</p></section>'), first_plot
    div = fig.to_html(full_html=False, include_plotlyjs=first_plot, config={"displaylogo": False, "responsive": True})
    tbl = fmt_table(table)
    details = f'<details><summary>Table view</summary>{tbl}</details>' if tbl else ""
    return (f'<section class="card"><h2>{html.escape(title)}</h2><p class="sub">{html.escape(subtitle)}</p>{div}{details}</section>'), False


def tile(label, value, note=""):
    n = f'<div class="note">{html.escape(note)}</div>' if note else ""
    return f'<div class="tile"><div class="label">{html.escape(label)}</div><div class="value">{html.escape(str(value))}</div>{n}</div>'


def fmt_ms(v):
    return "n/a" if pd.isna(v) else f"{v:,.0f} ms"


def build_html(df, args, t, files):
    wf = workflows_frame(df)
    b = bucket_seconds(df)
    ptable = percentile_table(df, wf)
    peak = "n/a"
    if not wf.empty:
        ev = pd.concat([pd.Series(1, index=wf["startedAt"]), pd.Series(-1, index=wf["completedAt"].dropna())]).sort_index()
        peak = int(ev.cumsum().max())
    all_done = df[df["event"] == "API_START_ALL_DONE"]
    tiles = [tile("Workflows started", len(wf)), tile("Completed", int(wf["completedAt"].notna().sum()) if len(wf) else 0),
             tile("Peak running at once", peak)]
    if not all_done.empty:
        r = all_done.iloc[-1]
        tiles += [tile("start-all customers", int(r["total"]), f'{int(r["started"])} started, {int(r["alreadyRunning"])} already running, {int(r["failed"])} failed'),
                  tile("start-all request time", fmt_ms(r["totalMs"]), f'customer query {fmt_ms(r["lookupMs"])}')]
    api = df.loc[df["event"] == "API_START_DONE", "startMs"] if "startMs" in df else pd.Series(dtype=float)
    if not api.empty:
        tiles.append(tile("Start call p95", fmt_ms(api.quantile(0.95)), "per customer, Temporal start"))
    if "overheadMs" in wf and wf["overheadMs"].notna().any():
        tiles.append(tile("Workflow time excl. delay p95", fmt_ms(wf["overheadMs"].quantile(0.95))))

    failures = df[df["event"].isin(["WF_FAILED", "ACT_FAILED"]) | ((df["event"] == "API_START_REJECTED") & (df.get("reason") != "ALREADY_RUNNING"))]
    fail_cols = [c for c in ("ts", "event", "workflowId", "customerId", "reason", "activity", "attempt", "error") if c in failures]
    fail_html = (f"<p class=\"sub\">{len(failures)} failure or rejection event(s), excluding ALREADY_RUNNING.</p>" + fmt_table(failures[fail_cols], 50)) \
        if len(failures) else '<p class="sub">No WF_FAILED, ACT_FAILED or non-duplicate API rejections in this window.</p>'

    first = "cdn" if args.cdn else True
    cards = []
    specs = [
        ("Workflow starts over time", f"Workflows started per {b}s. With the sequential start-all this is the client's start rate.", chart_starts(df, wf, b, t)),
        ("Workflows running over time", "Started minus completed. Flat top = the delay; the drop = timers firing.", chart_in_flight(wf, t)),
        ("Worker latency over time", f"p95 per {b}s. If these grow, the worker cannot keep up with the load.", chart_latency_over_time(df, b, t)),
        ("Start call latency, one dot per customer", "Time of each Temporal start call during the sequential start-all. A rising trend means the start path is slowing down.", chart_api_over_time(df, t)),
        ("Start call latency distribution", "How many customers fell into each latency bucket.", chart_api_histogram(df, t)),
        ("VOICE vs WHATSAPP", "The two channels take the same path after the first step, so they should look alike.", chart_channels(df, wf, t)),
    ]
    for title, sub, (fig, table) in specs:
        html_card, first = card(title, sub, fig, table, first)
        cards.append(html_card)

    t0, t1 = df["ts"].min(), df["ts"].max()
    css = f"""
:root{{--bg:{t['bg']};--card:{t['card']};--text:{t['text']};--muted:{t['muted']};--grid:{t['grid']}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}}
main{{max-width:1100px;margin:0 auto;padding:24px 16px 48px}}h1{{font-size:22px;margin:0 0 4px}}h2{{font-size:16px;margin:0 0 2px}}
.sub{{color:var(--muted);margin:0 0 8px}}.tiles{{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin:16px 0}}
.tile,.card{{background:var(--card);border:1px solid var(--grid);border-radius:10px;padding:12px 14px}}.card{{margin:16px 0;overflow:hidden}}
.label{{color:var(--muted);font-size:12px}}.value{{font-size:26px;font-weight:600}}.note{{color:var(--muted);font-size:12px}}
.tbl{{border-collapse:collapse;width:100%;font-size:13px}}.tbl th,.tbl td{{text-align:right;padding:4px 8px;border-bottom:1px solid var(--grid)}}
.tbl th:first-child,.tbl td:first-child{{text-align:left}}details{{margin-top:8px}}summary{{cursor:pointer;color:var(--muted)}}
.scroll{{overflow-x:auto}}
"""
    percentiles = fmt_table(ptable)
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Load test report</title><style>{css}</style></head><body><main>
<h1>Load test report</h1>
<p class="sub">{html.escape(t0.strftime('%Y-%m-%d %H:%M:%S'))} to {html.escape(t1.strftime('%H:%M:%S'))} IST &middot; {len(df):,} log events from {len(files)} file(s) &middot; chart resolution {b}s</p>
<div class="tiles">{''.join(tiles)}</div>
<section class="card"><h2>Latency summary</h2><p class="sub">Milliseconds. "Workflow time excluding the delay" is total workflow time minus the delay step, so it is the platform overhead. Timer lateness is how much later than the fastest timer each timer fired.</p><div class="scroll">{percentiles}</div></section>
{''.join(cards)}
<section class="card"><h2>Failures</h2>{fail_html}</section>
</main></body></html>"""


# ----------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", default=["logs"], help="log files or folders (default: logs)")
    ap.add_argument("--out", default="load-test/reports/report.html", help="output HTML file")
    ap.add_argument("--run", default="all", help="all (default), last, or N: the Nth start-all run (window until the next start-all)")
    ap.add_argument("--since", help="only events at/after this IST time, e.g. 2026-09-30T15:36")
    ap.add_argument("--until", help="only events before this IST time")
    ap.add_argument("--theme", choices=list(THEMES), default="light")
    ap.add_argument("--cdn", action="store_true", help="load plotly.js from a CDN instead of embedding it (smaller file)")
    ap.add_argument("--list-runs", action="store_true", help="list start-all runs found in the logs and exit")
    args = ap.parse_args()

    files = find_files(args.paths)
    df = parse(files)
    if args.list_runs:
        starts = run_starts(df)
        done = df[df["event"] == "API_START_ALL_DONE"].reset_index(drop=True)
        for i, ts in enumerate(starts, start=1):
            extra = ""
            if i <= len(done):
                r = done.iloc[i - 1]
                extra = f"  total={int(r['total'])} started={int(r['started'])} alreadyRunning={int(r['alreadyRunning'])} failed={int(r['failed'])} totalMs={int(r['totalMs'])}"
            print(f"run {i}: {ts.strftime('%Y-%m-%d %H:%M:%S')} IST{extra}")
        if not starts:
            print("no start-all runs found")
        return
    df = select_window(df, args)
    if df.empty:
        sys.exit("no events in the selected window")

    out = build_html(df, args, THEMES[args.theme], files)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(out)
    print(f"wrote {args.out} ({os.path.getsize(args.out) / 1e6:.1f} MB) from {len(df):,} events in {len(files)} file(s)")


if __name__ == "__main__":
    main()
