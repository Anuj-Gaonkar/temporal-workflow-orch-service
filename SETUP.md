# Setup guide

How to get `temporal-workflow-orch-service` running on your machine and run your first workflow. About 15 minutes the first time.

What you end up with: a Spring Boot service on port 8196 that starts one Temporal workflow per customer, reads the workflow script from a YAML file, and moves through the campaign as you send it feedback. For the full reference see [`README.md`](README.md) and [`docs/yaml-guide.md`](docs/yaml-guide.md).

## 1. What you need

| Tool | Version | Check |
|---|---|---|
| Java (JDK) | 21 or newer | `java -version` |
| Docker with Compose v2 | any recent | `docker compose version` |
| `curl` | any | `curl --version` |
| `bash` | any | Windows users: use WSL |

You do not need Maven (the project ships `./mvnw`) or `psql` (the scripts use `docker exec`).

These host ports must be free:

| Port | What |
|---|---|
| 8196 | this service (HTTP) |
| 7235 | Temporal gRPC |
| 8190 | Temporal web UI |
| 5437 | app Postgres (customers) |
| 5439 | Temporal's own Postgres (inspection only) |

## 2. Get the code

```bash
git clone git@github.com:Anuj-Gaonkar/temporal-workflow-orch-service.git
cd temporal-workflow-orch-service
```

## 3. Start the infrastructure

Two Docker stacks are involved. The first holds the customer data, the second is Temporal.

### 3a. App Postgres (customers)

The project expects a Postgres named `cern-v2-postgres` on port 5437 with a database `cern_v2`. In the original setup it comes from a shared `docker-compose.yml` in the parent folder (shared with `whatsapp-flows-service`). If you only cloned this repo, that file is not there, so start the same container directly:

```bash
docker run -d --name cern-v2-postgres \
  -e POSTGRES_DB=cern_v2 -e POSTGRES_USER=cern -e POSTGRES_PASSWORD=cern \
  -p 5437:5432 -v cern-v2-postgres-data:/var/lib/postgresql/data \
  postgres:16
```

If you do have the parent folder's compose file, run `(cd .. && docker compose up -d)` instead. Do not do both.

Default credentials are user `cern`, password `cern`. To change them, copy `.env.example` to `.env`, edit it, and export `APP_DB_USER` and `APP_DB_PASSWORD` in the shell that runs the app and the load script.

### 3b. Temporal

```bash
docker compose up -d
```

This starts Temporal, its web UI and Temporal's own Postgres. Give it about 30 seconds, then open http://localhost:8190. When the UI loads with the `default` namespace, Temporal is ready.

Check everything is up:

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

You should see `cern-v2-postgres`, `cern-v2-orch-temporal`, `cern-v2-orch-temporal-ui` and `cern-v2-orch-temporal-postgres`.

## 4. Load the customers

The service only reads the `orch.customers` table. It never creates or changes it, so you load it once by hand:

```bash
scripts/load-data.sh
```

This creates the `orch` schema and six customers:

| Customer | Channel |
|---|---|
| `C1001`, `C1003`, `C1005` | VOICE |
| `C1002`, `C1004`, `C1006` | WHATSAPP |

All six use a placeholder phone number (`910000000001`). You only need to change it if you also run `whatsapp-flows-service` and want a real WhatsApp message (edit `db/02_default_customers.sql` before loading). Do not commit a real phone number.

Check the load:

```bash
docker exec -it cern-v2-postgres psql -U cern -d cern_v2 -c 'select customer_id, initial_channel from orch.customers order by 1;'
```

## 5. Run the service

```bash
./mvnw spring-boot:run
```

The first run downloads dependencies. Wait for a line like `Started WorkflowOrchApplication`. Leave this terminal open.

Quick check from another terminal:

```bash
curl -s localhost:8196/workflow-definitions
```

You should see the loaded workflow definitions with their step ids. If you see `OUTREACH_DEFAULT`, you are ready.

### The dev profile makes time short

The default profile is `dev`: one campaign "day" lasts **3 minutes**. So "Day 1" starts 3 minutes after you trigger the workflow, and a 15-day campaign fits in 48 minutes. To use real days, start with `SPRING_PROFILES_ACTIVE=prod`.

## 6. Run your first workflow

We use `C1001`, a VOICE customer. VOICE contacts are only written to the log, so you do not need WhatsApp or any other service for this walk-through.

Open two terminals next to each other.

**Terminal A: watch the conversation**

```bash
tail -f logs/conversation.log
```

**Terminal B: do the steps**

1. Start the workflow (this is Day 0):

   ```bash
   curl -s -X POST localhost:8196/workflows/start \
     -H 'Content-Type: application/json' \
     -d '{"customerId":"C1001","definition":"OUTREACH_DEFAULT"}'
   ```

   You get HTTP 202 and a JSON body with `workflowId` (`OUTREACH_DEFAULT-C1001`) and a `runId`.

2. Check its status:

   ```bash
   curl -s localhost:8196/workflows/OUTREACH_DEFAULT-C1001
   ```

   Status `RUNNING` is expected. The workflow is asleep, waiting for Day 1.

3. About 3 minutes later (Day 1) terminal A shows the first contact, for example `Calling VOICE to C1001 ... [first-contact]`.

4. Answer as the customer. Send feedback only after that line appears, because feedback sent while no contact is waiting for it is dropped:

   ```bash
   curl -s -X POST localhost:8196/workflows/OUTREACH_DEFAULT-C1001/feedback \
     -H 'Content-Type: application/json' -d '{"type":"POSITIVE"}'
   ```

5. Terminal A shows the reply and a thank-you SMS, then the campaign summary. Check the status again: it is now `COMPLETED`.

You can see the same run in Temporal: open http://localhost:8190, namespace `default`, and click the workflow `OUTREACH_DEFAULT-C1001` to see its event history.

### Try the other answers

Start a fresh workflow (a finished one can be started again; a running one returns 409), wait for the Day 1 contact, then send one of these:

| Feedback body | What happens next |
|---|---|
| `{"type":"POSITIVE"}` | Thank-you SMS, campaign ends |
| `{"type":"REMIND_LATER","remindAfter":"2d","channel":"WHATSAPP"}` | A reminder after the chosen time on the chosen channel (2 "days" = 6 minutes in dev). Both fields are optional. |
| `{"type":"INCONCLUSIVE"}` | A follow-up the next "day" on the channel the customer last used |
| `{"type":"NO_RESPONSE"}` | Treated as no answer: a retry the next "day" |

Valid `type` values are `POSITIVE`, `REMIND_LATER`, `NO_RESPONSE`, `INCONCLUSIVE`. `remindAfter` and `channel` are only allowed with `REMIND_LATER`.

If a customer never answers, the workflow waits for the answer before retrying. In `workflow-definitions/outreach-default.yaml` that wait is `timeout: 2h`, which is a real 2 hours even in dev (only whole days such as `1d` are shortened). To watch the no-answer path quickly, change it to `timeout: 5m` while testing and save. The service picks up YAML edits within 10 seconds, and only workflows started afterwards use the new version. Do not commit that change.

### Start everyone at once

```bash
curl -s -X POST localhost:8196/workflows/start-all \
  -H 'Content-Type: application/json' -d '{"definition":"OUTREACH_DEFAULT"}'
```

This starts one workflow per customer in the table and reports `STARTED`, `ALREADY_RUNNING` or `FAILED` for each.

## 7. Useful URLs and files

| What | Where |
|---|---|
| Temporal UI | http://localhost:8190 |
| Loaded workflow definitions | `GET localhost:8196/workflow-definitions` |
| Workflow status | `GET localhost:8196/workflows/{workflowId}` |
| Customer row | `GET localhost:8196/customers/{customerId}` |
| Reload YAML now | `POST localhost:8196/workflow-definitions/reload` |
| Readable conversation log | `logs/conversation.log` |
| Full technical log | `logs/temporal-workflow-orch-service.log` |
| Workflow scripts | `workflow-definitions/*.yaml` |
| Postman collection | `postman/` (import both files, select the `local` environment) |

Workflow ids are `<definition name>-<customerId>`.

## 8. Run the tests

```bash
./mvnw test
```

No Docker is needed. The workflow tests run the real YAML in Temporal's time-skipping test server, so a 15 day campaign finishes in seconds.

## 9. Optional: real WhatsApp messages

A `WHATSAPP` contact calls a separate service, `whatsapp-flows-service` (port 8182, its own repository). Without it the call fails, the failure is logged as `ACT_WHATSAPP_FAILED`, and the campaign carries on, so everything above still works. To get real messages, start that service with the same profile (both default to `dev`), put your own test number in `db/02_default_customers.sql` before step 4, and start a WHATSAPP customer such as `C1002`. The customer's answer in the WhatsApp Flow comes back as feedback automatically. See that service's README for its `WA_*` settings.

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Connection errors to `127.0.0.1:7235` in the app log, or starts return a 502 `TEMPORAL_ERROR` | The app cannot reach Temporal. Check that the Temporal containers are up and http://localhost:8190 loads, then restart the app. |
| App fails to start with a database connection or password error | The `cern-v2-postgres` container is not running, or its password differs from `APP_DB_PASSWORD`. Run `docker ps`, and check step 3a. |
| `CUSTOMER_NOT_FOUND` (404) on start | The customers are not loaded. Run `scripts/load-data.sh` (step 4). |
| `DEFINITION_NOT_FOUND` (404) on start | The name is wrong or the YAML is invalid. Check `GET /workflow-definitions`, and the app log for `DEFINITIONS_RELOAD_FAILED`. |
| 409 on start | A workflow for that customer is still running. Wait for it to finish, or use another customer. |
| 404 `WORKFLOW_NOT_FOUND` on feedback | The workflow does not exist or has already finished. |
| Feedback returns 202 but nothing happens | No contact was waiting for it, so it was dropped. Send it after the Day 1 contact appears in `conversation.log`. |
| `bind: address already in use` | A port from step 1 is taken. Stop whatever uses it. |
| `scripts/load-data.sh: Permission denied` | Run `bash scripts/load-data.sh`. |

## 11. Stop and reset

```bash
# stop the app with Ctrl+C, then:
docker compose down                       # stops Temporal; keeps its data
docker stop cern-v2-postgres              # stops the customer database; keeps its data
```

To wipe everything and start from scratch (this also deletes the customers and all workflow history):

```bash
docker compose down -v
docker rm -f cern-v2-postgres && docker volume rm cern-v2-postgres-data
```

Then repeat from step 3.
