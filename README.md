# temporal-workflow-orch-service

A small proof of concept that starts a **Temporal workflow per customer, defined in YAML**, and runs it as a **15-day campaign**.

- An external system starts the workflow (`POST /workflows/start`); that day is **Day 0**.
- On **Day 1** (10:00 IST) the customer is contacted on the channel stored in Postgres (`WHATSAPP` or `VOICE`).
- The customer's **feedback** then decides the next contact: positive (thank-you SMS, done), remind me later (their own time and channel, or defaults),
  no response (try again on the DB channel), or answered without a conclusion (talk again on the channel they answered on). An unanswered reminder is repeated with the same time and channel.
- **Nothing is sent after Day 15.** A reminder that would land beyond it is dropped and the workflow ends.
- Feedback is sent to `POST /workflows/{workflowId}/feedback` (by hand with Postman, or by a load-test simulator). The service generates none.
- No external service is called: every activity only writes a log line.

**WhatsApp is real here.** A `WHATSAPP` contact calls `whatsapp-flows-service` (port 8182), which sends the customer a WhatsApp Flow on Day 1 (reminders go out as plain WhatsApp text). The customer's answer in the Flow comes back as feedback (`REMIND_LATER`), so someone who sets a reminder gets it on WhatsApp. `VOICE` and `SMS` contacts still only write a log line. See [`docs/phase-3/`](docs/phase-3/03-architecture.md).

Docs are in [`docs/`](docs/README.md).

## Profiles: dev (minutes) and prod (days)

| Profile | How | What changes |
|---|---|---|
| `dev` (default) | nothing to set | `application-dev.yaml`: `orch.timing.day-length: 3m`. One campaign "day" lasts 3 minutes (Day 1 starts 3 minutes after the trigger, the 15-day window lasts 48 minutes) and every whole-day wait or feedback timeout in the YAML (`1d`) is scaled the same way. |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` | `application-prod.yaml`: no `day-length`. Real calendar days: Day 1 is the next day at `sendHour` (10:00 IST) and the feedback timeout is a real day. |

The YAML itself is the same in both; it never contains `dayLength`. `whatsapp-flows-service` has the same two profiles (reminder delays in minutes versus days), so run both with the same profile.

## Prerequisites

Java 21, Docker with Compose v2, `psql` is not needed on the host (the commands below use `docker exec`).

## Quick start

```bash
# 1. Infrastructure. First the shared app Postgres (it lives in the parent folder, shared with whatsapp-flows-service):
(cd .. && docker compose up -d)          # container cern-v2-postgres, port 5437, database cern_v2
# then Temporal + UI + Temporal's own Postgres (this repo):
docker compose up -d

# 2. Database: load the scripts by hand. The app never runs them. (Runs db/01_schema.sql, then db/02_default_customers.sql: C1001..C1006)
scripts/load-data.sh

# 3. App (needs whatsapp-flows-service running on :8182 for WHATSAPP contacts; without it the failure is logged and the campaign carries on)
./mvnw spring-boot:run

# 4. Start a workflow
curl -s localhost:8196/workflow-definitions
curl -s -X POST localhost:8196/workflows/start -H 'Content-Type: application/json' \
     -d '{"customerId":"C1001","definition":"OUTREACH_DEFAULT"}'
curl -s localhost:8196/workflows/OUTREACH_DEFAULT-C1001

# After the Day 1 contact (see the demo section below for a quick demo), send the customer's feedback:
curl -s -X POST localhost:8196/workflows/OUTREACH_DEFAULT-C1001/feedback -H 'Content-Type: application/json' \
     -d '{"type":"REMIND_LATER","remindAfter":"2d","channel":"WHATSAPP"}'
curl -s -X POST localhost:8196/workflows/OUTREACH_DEFAULT-C1001/feedback -H 'Content-Type: application/json' -d '{"type":"POSITIVE"}'
```

Watch it in the Temporal UI: http://localhost:8190 (namespace `default`, task queue `CERN_WORKFLOW_ORCH`).
Logs go to the console and to `logs/temporal-workflow-orch-service.log`.
The readable conversation (what we sent, what the customer replied, how the campaign ended, each with date and time) is also in `logs/conversation.log`:
`tail -f logs/conversation.log`.
To see only that conversation in the console and the main log (warnings and errors still show), set `orch.logging.communication-only: true` in `application.yaml` (or `ORCH_LOG_COMMUNICATION_ONLY=true`) and restart. Keep it `false` for load tests, which read the `event=` lines.

Default customers: `C1001`, `C1003`, `C1005` are VOICE; `C1002`, `C1004`, `C1006` are WHATSAPP. All six carry the placeholder WhatsApp number `910000000001`; edit `db/02_default_customers.sql` to use your own test number.

Stop: `Ctrl+C` the app, then `docker compose down` (add `-v` only if you want to wipe both databases, which also removes the customers).

## Ports (different from the root Chat-Bot-V3 stack on purpose, so both can run together)

| What | Here | Root stack |
|---|---|---|
| Temporal gRPC | 7235 | 7233 |
| Temporal UI | 8190 | 8088 |
| Shared app Postgres (parent folder's compose) | 5437 | (5434 is the shared chatbot Postgres) |
| Temporal Postgres (inspection only, localhost only) | 5439 | |
| App HTTP | 8196 | |

Container names are prefixed `cern-v2-orch-`.

## Inspecting the databases with DBeaver

New connection, type PostgreSQL:

| | App database (customers) | Temporal database (workflow state, history, timers) | Temporal visibility (what the UI lists) |
|---|---|---|---|
| Host / port | `localhost` / `5437` | `localhost` / `5439` | `localhost` / `5439` |
| Database | `cern_v2` (schema `orch`) | `temporal` | `temporal_visibility` |
| User / password | `cern` / `cern` (or your `.env`) | `temporal` / `temporal` | `temporal` / `temporal` |

Port 5439 is bound to `127.0.0.1` only. Tick "Read-only connection" in DBeaver (Edit Connection, General, Security) so the Temporal data cannot be changed by accident.
Tables are in schema `public`. The app's data (`orch.customers`) is in the shared Postgres on port 5437, not in this repo's compose. If you rerun `docker compose up -d` after pulling this change, only `temporal-postgres` is recreated; its data volume is kept.

## API

| Method and path | Purpose |
|---|---|
| `GET /workflow-definitions` | Loaded definitions (name, description, step ids) |
| `POST /workflows/start` | Body `{"customerId","definition"}`. 202, or 400 / 404 `DEFINITION_NOT_FOUND` / 404 `CUSTOMER_NOT_FOUND` / 409 |
| `POST /workflows/start-all` | Body `{"definition"}`. Starts it for **every** customer in the table, one after another; returns 202 with counts and one result per customer (`STARTED`, `ALREADY_RUNNING`, `FAILED`). 400 / 404 `DEFINITION_NOT_FOUND` |
| `POST /workflows/{workflowId}/feedback` | Customer feedback for a running workflow (signal). Body `{"type","remindAfter","channel"}`; `type` is `POSITIVE`, `REMIND_LATER`, `NO_RESPONSE` or `INCONCLUSIVE`; `remindAfter` (`2d`, `3h`) and `channel` (`WHATSAPP`, `VOICE`, `SMS`) are optional and only for `REMIND_LATER`. 202, 400 `BAD_REQUEST`, 404 `WORKFLOW_NOT_FOUND`. Send it **after** the contact it answers: feedback that arrives when no contact is waiting is dropped (logged). |
| `POST /workflow-definitions/reload` | Rescan the YAML folder now. Returns `{changed, removed, errors}`; a broken file keeps its previous version live. Running workflows keep their definition. |
| `GET /workflows/{workflowId}` | Status via Temporal (RUNNING, COMPLETED, ...) |
| `GET /customers/{customerId}` | Read-only view of the customer row |

Start everyone:

```bash
curl -s -X POST localhost:8196/workflows/start-all -H 'Content-Type: application/json' -d '{"definition":"OUTREACH_DEFAULT"}'
```

Workflow id is `<definition>-<customerId>`, e.g. `OUTREACH_DEFAULT-C1001`. Starting a customer whose workflow is running returns 409.

## Fast demo

Just run the default `dev` profile: a "day" is 3 minutes, so a whole campaign takes under an hour and a reminder asked for in the Flow ("within 3 days") arrives 3 minutes later.
Edits to the YAML are picked up by the running app within 10 seconds (or call `POST /workflow-definitions/reload`); only workflows started afterwards use them.
Restart the app while a workflow waits to see Temporal keep the timers.

## Tests

```bash
./mvnw test        # plain unit tests (workflow tests use Temporal's time-skipping test server), no Docker needed
```

## Postman

Import `postman/temporal-workflow-orch-service.postman_collection.json` and `postman/local.postman_environment.json`
(`baseUrl = http://localhost:8196`), select the `local` environment and run the requests top to bottom.
Customer ids are collection variables. The duplicate (409) request needs the VOICE workflow started just before it to still be running,
so run it right after "Start workflow, VOICE customer" (with the shipped 30 minute YAML there is plenty of time; with a shortened YAML, be quick).
To rerun the collection, wait for the earlier workflows to complete first, otherwise the start requests return 409.
The feedback requests at the end target the VOICE customer's workflow: run them one at a time, each after the contact it answers (with the shipped YAML that is Day 1 at 10:00 IST; for a quick run use the shortened demo copy). Run straight after the start request they return 202 but the workflow drops the feedback, because no contact has been made yet. The 400 and 404 requests work at any time.

## Load-test data

`load-test/load_customers.py` (Python 3 + Docker only) loads fake customers into `orch.customers` by piping SQL into the shared Postgres container (`cern-v2-postgres`).
It is a manual operator tool like `db/*.sql`; the service never writes to the table.

```bash
python3 load-test/load_customers.py                  # 1000 customers: 400 VOICE, 600 WHATSAPP, ids L0001..L1000
python3 load-test/load_customers.py --dry-run | head # print the SQL only
python3 load-test/load_customers.py --clean          # delete the L* customers again
```

Temporal database growth: `python3 load-test/temporal_db_stats.py snapshot before`, run the load, `snapshot after`, then `diff before after` (rows and bytes per table). `watch --interval 5` samples growth over time into a CSV. Read-only; needs Docker only.

Options: `--count`, `--voice-pct`, `--prefix`, `--seed`, `--container`. It is idempotent. The channel is stored as `VOICE` (the column only allows `VOICE` / `WHATSAPP`).
After loading, `POST /workflows/start-all` starts a workflow for every customer, including the six defaults.

## Load-test report

`load-test/report.py` turns the timing logs into one self-contained HTML report (hover and zoom charts, percentile table, failures). Times are shown in IST.

```bash
pip install -r load-test/requirements.txt        # pandas + plotly, once (a venv is recommended)
python load-test/report.py logs --list-runs      # list the start-all runs in the logs
python load-test/report.py logs --run last       # report for the last start-all run
python load-test/report.py collected-logs/run-01 --out load-test/reports/run-01.html
python load-test/report.py logs --since 2026-09-30T15:36 --until 2026-09-30T15:50   # IST window
```

It accepts files or folders, including rolled `.gz` logs, and mixes old UTC (`Z`) and new IST (`+05:30`) lines correctly.
Reports go to `load-test/reports/` (git-ignored). `--theme dark` and `--cdn` (smaller file, needs internet) are available.
A run is the window from one `API_START_ALL_RECEIVED` to the next, so keep runs apart by waiting for the workflows to finish.

## Phase branches

| Branch | Contains |
|---|---|
| `main` | Latest completed phase |
| `phase-1-channel-routing` | Phase 1 (tag `phase-1-done`) |
| `phase-1-start-all` | Phase 1 plus the ad hoc `POST /workflows/start-all` endpoint, IST logs and the load-test tools (branched from `phase-1-done`; `main` is unchanged) |
| `phase-2-feedback-switch` | Everything above plus Phase 2: the 15-day campaign window, customer feedback and feedback-driven contacts, hot reload of definitions (branched from `phase-1-start-all`; `main` is unchanged until Phase 2 is accepted) |

Demo a phase: `git switch phase-1-channel-routing`. The repo is local only; no remote is configured.

## Running with whatsapp-flows-service

0. `(cd .. && docker compose up -d)` starts the shared Postgres (both services use it, one schema each).
1. `cd ../whatsapp-flows-service && scripts/load-data.sh && ./mvnw spring-boot:run` (needs the `WA_*` environment variables, see its README).
2. Here: `docker compose up -d && scripts/load-data.sh && ./mvnw spring-boot:run`.
3. `POST /workflows/start {"customerId":"C1002","definition":"OUTREACH_DEFAULT"}`; 3 minutes later (dev) the Flow arrives on WhatsApp.
