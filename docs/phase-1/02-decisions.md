# Phase 1: decision log

Each decision: Context, Decision, Alternatives considered, Consequences. D24 onward are deviations from, or additions to, the instruction guide.

**D1 Definition passed as workflow input (snapshot).** Context: workflows must be deterministic and definitions live in files. Decision: the parsed definition is a workflow argument. Alternatives: read the file inside the workflow (non-deterministic), load in an activity (extra hop). Consequences: a running workflow keeps the definition it started with, even after a file edit and restart; the history holds the whole definition.

**D2 Customer snapshot as input; the workflow never reads the DB.** Context: DB reads in workflow code break determinism. Decision: the starter reads the row and passes it as `WorkflowInput`. Alternatives: an activity that reads the customer. Consequences: fewer moving parts, but the data can be stale if the row changes during the 30 minute wait (acceptable for the POC). A DB outage fails the start request (see architecture doc), not a running workflow.

**D3 One YAML with channel routing.** Context: VOICE and WHATSAPP customers share everything after the first step. Decision: one definition with a `switch` on `customer.initialChannel`. Alternatives: one YAML per channel (duplicated tail, caller must pre-decide). Consequences: the routing is data-driven; the DB is the single source of the channel.

**D4 `switch`, `end` and `next` in Phase 1.** Context: the guide needs channel routing now. Decision: implement four step types (`switch`, `activity`, `delay`, `end`) with explicit `next`. Alternatives: an implicit sequential list (cannot express the branch). Consequences: the engine is a small step-pointer loop; Phase 2 adds result-based `switch` and `fromResult` on top.

**D5 No hot reload and no runtime YAML editing.** Decision: definitions are read once at startup; to change one, edit and restart. Alternatives: scheduled rescan like `yaml-workflow-engine-service`. Consequences: simple and predictable; fast demos need a YAML edit and restart. Both features are Phase 2.

**D6 Definition named in the start request.** Decision: `POST /workflows/start` requires `definition`. Consequences: ready for more YAML files; a wrong name is a 404 before any customer lookup.

**D7 Fixed activity registry.** Decision: activity names are a constant set in `OrchActivities.NAMES`; the validator rejects any other name. Alternatives: reflection or a plugin mechanism. Consequences: adding an activity means a code change in three places (documented in the coding doc).

**D8 Workflow id `<definition>-<customerId>`; duplicates refused.** Decision: deterministic id; while one is running the start returns 409 (`WorkflowExecutionAlreadyStarted`). Temporal's default id-reuse policy is kept, so the same customer can be started again after the previous run has closed. Consequences: no separate lock table; different customers run in parallel.

**D9 Duration format.** Decision: number plus one of `s m h d`, lowercase, nothing else. Alternatives: ISO-8601 (the sibling accepts both). Consequences: one obvious format; `PT30M`, `1w`, `30` are rejected.

**D10 Log-only activities.** Decision: `executeVoiceCall` and `sendMessage` write log lines, return `void`, call nothing. Consequences: no external dependency; the result-returning contract arrives in Phase 2. Activity options: start-to-close 30s, max 3 attempts.

**D11 Service-owned database, manual SQL, seed-data exception.** Decision: schema `orch`, table `orch.customers` in database `cern_orch_v2`; `db/01_schema.sql` and `db/02_default_customers.sql` are run by hand with `docker exec ... psql`. The app, JPA, Flyway and `data.sql` never run them. `db/02_default_customers.sql` (idempotent, obviously fake phones) is the only seed data, owner-approved. Consequences: starting the app against an empty database works but a start request fails until the scripts have run. (Database and port superseded by D71 in phase-3: now the shared Postgres.)

**D12 Read-only DB access.** Decision: `JdbcClient` with a single `SELECT`; no JPA, so no `ddl-auto` question. The app's DB user is the container's owner user (`cern`); a stricter read-only role was not created (out of scope).

**D13 Own docker-compose and ports.** Decision: compose file in the project, container names prefixed `cern-v2-orch-`, host ports 7235 / 8190 / 5438 chosen not to collide with the root stack (7233 / 8088) or the shared Postgres (5434). Checked before starting: nothing in the sibling projects uses 7235, 8190, 5438 or 8196, and the host ports were free. Temporal's Postgres is not published. Named volumes keep data across `docker compose down`. (The app Postgres part is superseded by D71 in phase-3.)

**D14 Branch-per-phase git model.** Decision: `phase-1-channel-routing`, tag `phase-1-done`, `main` fast-forwarded to it; local repo only, no remote. Later phases branch from the previous tag.

**D15 Test approach.** Decision: plain JUnit unit tests; Temporal's time-skipping test environment for workflow and starter tests; no Testcontainers, no Docker. A test-only `RecordingActivities` wraps the real log-only activities to record order and workflow time; a test-only fake implements `CustomerRepository`. The JDBC query is verified manually end to end.

**D16 Reachability is an error.** Context: the guide says "warn or fail; decide and record". Decision: fail startup. Alternative: warn. Consequences: an unreachable step is almost certainly a typo, so it is refused with a message naming the file and step.

**D17 Unknown YAML fields fail the load.** Decision: Jackson `FAIL_ON_UNKNOWN_PROPERTIES`, as the engine sibling does. Consequences: Phase 2 keywords (`fromResult`) cannot sneak into a Phase 1 file silently. Step `type` is kept as text so an unknown type is reported by the validator with a friendly message.

**D18 YAML parser and Lombok.** Decision: Jackson YAML (`jackson-dataformat-yaml`), as `yaml-workflow-engine-service` does (the guide said to check and match); Lombok `@Data` on the two definition POJOs and `@Slf4j`, records elsewhere, as the siblings use Lombok.

**D19 Package layout.** Decision: the layout suggested in the guide, base package `com.hdfc.cern.workfloworch`. Siblings use underscore package names (`com.hdfc.yaml_workflow_engine_service`), but the owner confirmed the given package. No layout changes were needed except that `api` also holds `NotFoundException` and `ApiErrors`.

**D20 Timing-log design.** Decision: plain SLF4J `event=NAME key=value` lines built by `LogEvents.line`, millisecond ISO timestamps (originally UTC, changed to IST by D32), MDC `workflowId`/`customerId` in the pattern, console plus rolling file `logs/` (10MB, 7 days). Workflow lines use `Workflow.getLogger` guarded by `!isReplaying`, times from `Workflow.currentTimeMillis()`. `startLagMs` uses `WorkflowInfo.getRunStartedTimestampMillis()`.

**D21 JVM timezone.** Decision: `TimeZone.setDefault(UTC)` in `main`, as `temporal-workflow-service` does (the sibling engine maps deprecated aliases instead; forcing UTC also matches the UTC log pattern).

**D22 Transition cap.** Decision: 100 transitions, then a non-retryable failure `TooManyTransitions`.

**D23 Workflow failure type.** Decision: workflow failures for a bad definition/customer value are non-retryable `ApplicationFailure`s (workflow fails, no endless task retries). The `WF_FAILED` line logs the original message.

## Ad hoc addition (branch `phase-1-start-all`)

**D31 `POST /workflows/start-all`.** Context: the owner asked, after Phase 1 was done, for a single call that triggers the workflow for every customer in the database. Decision: a new endpoint alongside `POST /workflows/start` (which stays), body `{definition}`, reading all customers with a new read-only `findAll()` and starting them sequentially. Already-running customers are reported as `ALREADY_RUNNING` and do not fail the call, so it can be re-run safely; other failures are reported per customer as `FAILED`. Alternatives: replace the single endpoint (rejected by the owner), parallel starts or a background job (deferred; sequential first). Consequences: response time grows linearly with the customer count and the whole table is loaded in memory; revisit if the load tests use thousands of customers. Delivered on its own branch from `phase-1-done` because it is outside the Phase 1 scope in the guide; `main` and `phase-1-done` are unchanged.

## Deviations from, and small additions to, the instruction guide

**D24 `WorkflowInput` is nested.** The guide says `WorkflowInput` "carries `customerId`, `name`, `phone`, `initialChannel`". It is `WorkflowInput(Customer customer)`, so the field paths in the YAML (`customer.initialChannel`) map directly. The same four fields are carried.

**D25 API log lines come from the starter service.** `API_START_*` events are logged in `WorkflowStarterService`, not the controller, so the durations sit next to the work being timed. `totalMs` therefore excludes HTTP parsing and JSON writing.

**D26 Extra error codes.** `GET /workflows/{id}` returns 404 `WORKFLOW_NOT_FOUND` for an unknown workflow and 502 `TEMPORAL_ERROR` for other Temporal gRPC errors; `GET /customers/{id}` returns 404 `CUSTOMER_NOT_FOUND`. The guide does not specify these.

**D27 `DEFAULT_NAMESPACE: default` in compose.** Set explicitly on the Temporal container for clarity (it is the image default anyway).

**D28 Postman verified with newman.** Postman itself is not available in the build environment. The collection and environment were run with `newman` (fetched with `npx`) against the running app: 10 requests, 17 assertions, 0 failures. The collection is standard v2.1 JSON.

**D29 Attribution trailer.** The first commit carries `Co-Authored-By: Claude Opus 5.5`; the remaining commits carry `Co-Authored-By: Claude Sonnet 5.5`, because the session's attribution instruction changed part-way through. History was not rewritten.

**D30 `DefinitionLoader.load()` is public.** So tests in other packages can call it without Spring.

**D32 Log timestamps in IST.** Context: the guide asked for UTC log timestamps; the owner asked for IST on the branch `phase-1-start-all`, because the logs are read by people in IST. Decision: the log pattern (console and file) renders the timestamp in `Asia/Kolkata`, same ISO-8601 format with milliseconds, offset `+05:30` (for example `2026-09-30T15:06:12.894+05:30`). The JVM default timezone stays UTC (D21), so the database connection is unaffected; only the log rendering changed. Alternatives: keep UTC and convert in the report script. Consequences: the timestamps of logs written before this change (`Z` suffix) and after it (`+05:30`) are the same instants in different notation; the ISO offset makes both parse correctly (pandas `pd.to_datetime(..., utc=True)`), but do not mix files from before and after in one visual timeline without parsing the offset. Workflow-time fields (`stepMs`, `totalMs`) are durations and are unaffected. JSON API responses (`startTime`, `closeTime`) are still UTC (`Z`).

**D33 Temporal's Postgres published on localhost:5439.** Context: the guide said Temporal's own Postgres is not published to the host (D13). The owner wants to browse it with DBeaver during load tests (row counts per table, history size). Decision: publish it as `127.0.0.1:5439:5432` in `docker-compose.yml`, bound to localhost only; Temporal itself still connects over the compose network. Alternatives: `docker exec psql` only (works, but no GUI); publish on all interfaces (rejected, credentials are `temporal`/`temporal`). Consequences: port 5439 is now used (checked free; no sibling project uses it); applying the change recreated only the `temporal-postgres` container (its volume, and so the data, was kept and Temporal reconnected on its own). Recommend a read-only connection in DBeaver. Same branch: `load-test/temporal_db_stats.py` (read-only snapshot, diff and watch of the Temporal tables via `docker exec`).
