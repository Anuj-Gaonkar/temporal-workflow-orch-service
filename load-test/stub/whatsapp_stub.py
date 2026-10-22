#!/usr/bin/env python3
"""Stand-in for whatsapp-flows-service during load tests. Python 3 standard library only.

The orch service sends every contact (WHATSAPP, and the simulated VOICE / SMS / EMAIL ones) to two internal endpoints:
  POST /internal/flows/send
  POST /internal/messages/text
This stub answers both with 200 after a fixed delay and counts what arrives, so the load test measures the orch service
and Temporal, not WhatsApp. Nothing leaves your machine.

  python load-test/stub/whatsapp_stub.py                    # port 8182, 100 ms per request
  python load-test/stub/whatsapp_stub.py --delay-ms 200 --port 8182

Other endpoints:
  GET  /stats     counters as JSON (total, flows, texts, first/last arrival, in-flight, peak requests per second)
  GET  /metrics   the same as Prometheus text (scraped by load-test/monitoring/prometheus.yml)
  POST /reset     zero all counters (run_stage.py does this at the start of every stage)
"""
import argparse
import json
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
STATE = {}
DELAY_S = 0.1


def reset():
    STATE.clear()
    STATE.update(flows=0, texts=0, inflight=0, max_inflight=0, first_ts=None, last_ts=None, per_second=Counter())


def snapshot():
    with LOCK:
        total = STATE["flows"] + STATE["texts"]
        peak = max(STATE["per_second"].values(), default=0)
        return {"total": total, "flows": STATE["flows"], "texts": STATE["texts"], "inflight": STATE["inflight"],
                "max_inflight": STATE["max_inflight"], "first_ts": STATE["first_ts"], "last_ts": STATE["last_ts"],
                "peak_per_second": peak, "delay_ms": DELAY_S * 1000}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, so the client's connections are reused

    def log_message(self, *args):  # silence the per-request console line
        pass

    def _reply(self, code, body, content_type="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/stats"):
            self._reply(200, json.dumps(snapshot()))
        elif self.path.startswith("/metrics"):
            s = snapshot()
            lines = [
                "# TYPE whatsapp_stub_requests_total counter",
                f'whatsapp_stub_requests_total{{kind="flow"}} {s["flows"]}',
                f'whatsapp_stub_requests_total{{kind="text"}} {s["texts"]}',
                "# TYPE whatsapp_stub_inflight gauge",
                f'whatsapp_stub_inflight {s["inflight"]}',
                "# TYPE whatsapp_stub_max_inflight gauge",
                f'whatsapp_stub_max_inflight {s["max_inflight"]}',
            ]
            self._reply(200, "\n".join(lines) + "\n", "text/plain; version=0.0.4")
        else:
            self._reply(404, '{"error":"not found"}')

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)  # the body is not needed, but it must be consumed for keep-alive
        if self.path == "/reset":
            with LOCK:
                reset()
            self._reply(200, '{"reset":true}')
            return
        if self.path not in ("/internal/flows/send", "/internal/messages/text"):
            self._reply(404, '{"error":"not found"}')
            return
        with LOCK:
            now = time.time()
            if STATE["first_ts"] is None:
                STATE["first_ts"] = now
            STATE["inflight"] += 1
            STATE["max_inflight"] = max(STATE["max_inflight"], STATE["inflight"])
        try:
            time.sleep(DELAY_S)
        finally:
            with LOCK:
                now = time.time()
                STATE["inflight"] -= 1
                STATE["last_ts"] = now
                STATE["per_second"][int(now)] += 1
                STATE["flows" if self.path.endswith("/flows/send") else "texts"] += 1
        self._reply(200, "{}")


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 2048


def main():
    global DELAY_S
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8182)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--delay-ms", type=float, default=100, help="time to hold each request before answering 200 (default 100)")
    args = ap.parse_args()
    DELAY_S = args.delay_ms / 1000
    reset()
    print(f"whatsapp stub on {args.host}:{args.port}, {args.delay_ms:g} ms per request (GET /stats, GET /metrics, POST /reset)", flush=True)
    Server((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
