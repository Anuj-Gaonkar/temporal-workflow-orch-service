# Load test: start-all and the Day 1 burst

Measures `temporal-workflow-orch-service` with `OUTREACH_20_DAY` at 1k, 5k, 10k, 25k and 50k customers. Only the first
contact (Day 1) is tested; after it the workflows are terminated. WhatsApp is replaced by a stub, nothing is sent.

| Phase | What happens | Main numbers |
|---|---|---|
| A: start-all | one `POST /workflows/start-all` starts a workflow per customer | workflows/s, per-start latency, total time |
| B: Day 1 burst | one day-length (1h) after its start every workflow wakes and sends its first contact | wait for a worker (`scheduleToStartMs`), timer lateness, contacts/s, drain time |
| Cleanup | `POST /workflows/terminate-all` | time to terminate |
| Always | | Temporal DB growth per workflow, WAL, CPU and memory |

A stage takes about one day-length (1h) plus start, drain and terminate time, so the five baseline stages take roughly 6 to 7 hours.

## One-time setup

```bash
# from this folder (temporal-workflow-orch-service), Docker running
(cd .. && docker compose up -d)                                   # shared Postgres (customers)
scripts/load-data.sh                                              # schema + default customers C1001..C1006
./mvnw -DskipTests package                                        # target/*.jar that run_stage.py starts
python -m venv load-test/.venv && load-test/.venv/Scripts/python -m pip install -r load-test/requirements.txt
docker compose -f docker-compose.monitoring.yml up -d             # Prometheus :9091, Grafana :3001
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d   # Temporal with metrics (run_stage.py also does this)
```

## Start the helpers (each in its own terminal; leave them running)

```bash
python load-test/stub/whatsapp_stub.py          # fake whatsapp-flows-service on :8182 (100 ms per request, --delay-ms to change)
python load-test/resource_exporter.py           # container and app CPU/memory for Grafana and the results
```

## Run

```bash
python load-test/run_stage.py --label smoke --stages 200 --day-length 2m                  # 5 minute check of everything
python load-test/run_stage.py --label baseline --stages 1000,5000,10000,25000,50000       # the baseline (day-length 1h)
python load-test/run_stage.py --label baseline --stages 10000 --repeat 2 --force          # repeat a stage
```

`run_stage.py` does everything per stage, so do not start the app yourself (it needs port 8196 free):
wipes Temporal (`docker compose down -v`, `up -d`), loads N customers (the 6 defaults stay, so N+6 workflows), starts the jar
with `--orch.timing.day-length=1h`, the stub URL and a fixed heap (`--heap 2g`), takes DB snapshots, runs start-all, waits until the stub has
received one contact per customer, terminates all workflows, stops the app and parses its log.
Results: `load-test/results/<label>/<N>-run<k>/` (`summary.json`, `stub-series.csv`, `snapshots/`, `logs/`).
The app is the unmodified code of the branch you built the jar from; change nothing between baseline stages.

## Look at it

- **Live:** Grafana http://localhost:3001, dashboard **Load test** (anonymous viewing; login `admin`/`admin`). Vertical markers show where each
  phase begins and ends. Set the time range to the run.
- **After:** `load-test/.venv/Scripts/python load-test/compare.py` writes `load-test/reports/compare.html` (one line per label, x axis = customers).
  `--labels baseline,parallel-start` compares series. Per-stage log report: `report.py` (see the main README).
- **Prometheus:** http://localhost:9091 (targets: Status, Targets).

## Comparing a change with the baseline

1. Make the change on a branch, `./mvnw -DskipTests package`.
2. `python load-test/run_stage.py --label <new-name> --stages ...` with the same stages, day-length and heap.
3. `python load-test/compare.py`: both labels appear in every chart.

## Ports and containers added

| What | Port |
|---|---|
| Temporal server metrics (override compose) | 9464 |
| Temporal Postgres exporter | 9188 |
| Prometheus / Grafana | 9091 / 3001 |
| WhatsApp stub | 8182 |
| Resource exporter | 9323 |

## Notes

- Docker Desktop here has 4 CPUs and about 8 GB; 50k workflows will show how far that goes. CPU numbers use Docker's scale (100 = one core).
- The app exposes `/actuator/prometheus` (JVM heap, GC, threads, DB pool; `spring-boot-starter-actuator` and `micrometer-registry-prometheus` in the pom, `management.endpoints.web.exposure.include: prometheus` in `application.yaml`). Prometheus scrapes it (job `orch-app`) and the stage summary stores peak heap. This is the only change to the app; baseline and later runs must use a jar that has it. Temporal SDK (worker) metrics `temporal_*` come from the same endpoint: `temporal-spring-boot-starter` 1.39 builds a metrics scope from the Micrometer registry by itself (`MetricsScopeAutoConfiguration`), so no Java change is needed. Check once with the smoke stage: `curl localhost:8196/actuator/prometheus | grep temporal_` while the app runs; if empty, the SDK panels stay blank. Each stage's `summary.json` also gets a `monitor` block (min/avg/max of app threads, Hikari, Postgres connections, Temporal goroutines, SDK slots, Temporal DB size) that `compare.py` charts. App CPU and process memory come from the OS.
- Two dashboards: **Load test** (simple) and **Load test (detailed)** with Temporal internals.
- The stub answers after a fixed delay. A real WhatsApp service would be slower and would move the activity numbers.
- If a stage reports `INCOMPLETE`, fewer contacts reached the stub than customers; check `logs/` of that stage and the Grafana panels.
