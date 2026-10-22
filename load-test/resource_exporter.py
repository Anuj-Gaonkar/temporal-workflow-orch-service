#!/usr/bin/env python3
"""CPU and memory of the Docker containers and of the orch service process, as Prometheus metrics. Python 3 standard library only.

cAdvisor cannot see individual containers on Docker Desktop, so this samples `docker stats` instead. The orch service
is a normal Windows process: it is found by the port it listens on (8196) and measured with PowerShell.

  python load-test/resource_exporter.py                  # port 9323, sample every 5 s

  GET  /metrics   loadtest_container_cpu_percent{container}, loadtest_container_memory_bytes{container},
                  loadtest_app_cpu_percent, loadtest_app_memory_bytes, loadtest_app_threads,
                  loadtest_app_heap_used_bytes, loadtest_app_heap_max_bytes (JVM heap, from /actuator/prometheus)
                  (container CPU is Docker's: 100 = one core, so it can reach 400 on 4 cores; app CPU is the same scale)
  GET  /stats     max / average / last of every series since the last reset (run_stage.py stores this per stage)
  POST /reset     start a new measuring window
"""
import argparse
import json
import re
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
LATEST = {}    # series name -> value now
WINDOW = {}    # series name -> [count, sum, max]
APP_PORT = 8196
UNITS = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}
PS_APP = ("$p=(Get-NetTCPConnection -LocalPort %d -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess;"
          "if($p){$q=Get-Process -Id $p;[Console]::Out.Write(('{0} {1} {2} {3}' -f $p,$q.CPU,$q.WorkingSet64,$q.Threads.Count))}")


def record(name, value):
    LATEST[name] = value
    w = WINDOW.setdefault(name, [0, 0.0, 0.0])
    w[0] += 1
    w[1] += value
    w[2] = max(w[2], value)


def to_bytes(text):
    m = re.match(r"([\d.]+)\s*([A-Za-z]+)", text.strip())
    return float(m.group(1)) * UNITS.get(m.group(2), 1) if m else 0.0


def sample_containers():
    r = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"], capture_output=True, text=True)
    if r.returncode != 0:
        return
    with LOCK:
        for line in r.stdout.splitlines():
            try:
                d = json.loads(line)
            except ValueError:
                continue
            name = d["Name"]
            record(f'loadtest_container_cpu_percent{{container="{name}"}}', float(d["CPUPerc"].rstrip("%") or 0))
            record(f'loadtest_container_memory_bytes{{container="{name}"}}', to_bytes(d["MemUsage"].split("/")[0]))


def sample_app(state):
    r = subprocess.run(["powershell", "-NoProfile", "-Command", PS_APP % APP_PORT], capture_output=True, text=True)
    parts = r.stdout.split()
    if r.returncode != 0 or len(parts) != 4:
        state.clear()
        return
    pid, cpu_s, rss, threads = parts[0], float(parts[1].replace(",", ".")), float(parts[2]), float(parts[3])
    now = time.time()
    with LOCK:
        if state.get("pid") == pid:
            record("loadtest_app_cpu_percent", max(0.0, (cpu_s - state["cpu"]) / (now - state["t"]) * 100))
        record("loadtest_app_memory_bytes", rss)
        record("loadtest_app_threads", threads)
    state.update(pid=pid, cpu=cpu_s, t=now)


def sample_heap():
    """JVM heap from the app's /actuator/prometheus (used and max, summed over the heap pools)."""
    try:
        text = urllib.request.urlopen(f"http://localhost:{APP_PORT}/actuator/prometheus", timeout=5).read().decode()
    except Exception:
        return
    used = sum(float(m.group(1)) for m in re.finditer(r'^jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\} (\S+)', text, re.M))
    top = sum(float(m.group(1)) for m in re.finditer(r'^jvm_memory_max_bytes\{[^}]*area="heap"[^}]*\} (\S+)', text, re.M) if float(m.group(1)) > 0)
    with LOCK:
        record("loadtest_app_heap_used_bytes", used)
        record("loadtest_app_heap_max_bytes", top)


def sampler(interval):
    state = {}
    while True:
        started = time.time()
        for fn in (sample_containers, lambda: sample_app(state), sample_heap):
            try:
                fn()
            except Exception as e:  # a failed sample must not stop the exporter
                print(f"sample failed: {e}", flush=True)
        time.sleep(max(0.0, interval - (time.time() - started)))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _reply(self, body, content_type="application/json"):
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        with LOCK:
            if self.path.startswith("/metrics"):
                lines = [f"{k} {v}" for k, v in sorted(LATEST.items())]
                self._reply("\n".join(lines) + "\n", "text/plain; version=0.0.4")
            else:
                self._reply(json.dumps({k: {"max": w[2], "avg": w[1] / w[0], "last": LATEST.get(k), "n": w[0]}
                                        for k, w in WINDOW.items()}))

    def do_POST(self):
        with LOCK:
            WINDOW.clear()
        self._reply('{"reset":true}')


def main():
    global APP_PORT
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=9323)
    ap.add_argument("--interval", type=float, default=5, help="seconds between samples (default 5)")
    ap.add_argument("--app-port", type=int, default=8196, help="port the orch service listens on (default 8196)")
    args = ap.parse_args()
    APP_PORT = args.app_port
    threading.Thread(target=sampler, args=(args.interval,), daemon=True).start()
    print(f"resource exporter on :{args.port}, sampling every {args.interval:g}s (GET /metrics, GET /stats, POST /reset)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
