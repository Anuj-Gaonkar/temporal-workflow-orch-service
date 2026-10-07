# Phase 1: coding doc

## Packages (`com.hdfc.cern.workfloworch`)

| Package | Contents |
|---|---|
| (root) | `WorkflowOrchApplication`: forces JVM timezone UTC (pgjdbc rejects the legacy `Asia/Calcutta` alias on Postgres 16, same fix as `temporal-workflow-service`). |
| `api` | `OrchController` (4 endpoints), `StartRequest`, `ApiErrors` (JSON error body `{status, code, message}`), `NotFoundException`. |
| `customer` | `Customer` record (also the workflow's customer snapshot), `CustomerRepository` interface, `JdbcCustomerRepository` (read-only `SELECT` on `orch.customers` via `JdbcClient`). |
| `definition` | `WorkflowDefinition` / `StepDefinition` (Lombok POJOs parsed from YAML), `DefinitionLoader` (reads the folder once at startup), `DefinitionValidator`, `DurationParser`, `CustomerPaths` (the `on` paths). |
| `workflow` | `YamlWorkflow` interface, `YamlWorkflowImpl` (the step-pointer interpreter), `WorkflowInput`, `OrchTaskQueue` (`CERN_WORKFLOW_ORCH`). |
| `activity` | `OrchActivities` (interface and name registry), `OrchActivitiesImpl` (log only), `ActivityInput`. |
| `logging` | `LogEvents`: event-name constants and the `event=NAME key=value` line builder. |
| `starter` | `WorkflowStarterService` (definition check, customer lookup, workflow start, `API_START_*` logs), `StartResult`, `StartRejectedException`. |

## Request flow: `POST /workflows/start`

1. `OrchController.start` calls `WorkflowStarterService.start(customerId, definition)`; logs `API_START_RECEIVED`.
2. Blank `customerId` or `definition`: `BAD_REQUEST` (400).
3. The definition is looked up in `DefinitionLoader`: missing gives `DEFINITION_NOT_FOUND` (404). Definition is checked before the customer.
4. `CustomerRepository.findById`: missing gives `CUSTOMER_NOT_FOUND` (404). Timed as `lookupMs`.
5. Workflow id `<definition>-<customerId>`; `WorkflowClient.start(YamlWorkflow::run, definition, new WorkflowInput(customer))`, timed as `startMs`.
   `WorkflowExecutionAlreadyStarted` gives `ALREADY_RUNNING` (409).
6. `API_START_DONE`, response 202 `{workflowId, runId, customerId, initialChannel}`. The `ApiErrors` advice turns rejections into JSON bodies.
7. A worker in the same app picks up the workflow task on `CERN_WORKFLOW_ORCH`; the workflow runs the steps. Each `activity` step schedules an activity that logs the business line.

## Request flow: `POST /workflows/start-all` (branch `phase-1-start-all`)

`WorkflowStarterService.startAll(definition)`: blank definition is 400, unknown is 404 `DEFINITION_NOT_FOUND`; then one `CustomerRepository.findAll()`,
then a plain sequential loop calling the same `startWorkflow` as the single start. Per customer it logs the usual `API_START_DONE` (with `startMs` and `totalMs`, no `lookupMs`)
or `API_START_REJECTED` (`ALREADY_RUNNING`, or `START_FAILED` with `error`); one customer failing never stops the loop. It ends with `API_START_ALL_DONE`.
The response is 202 with `{definition, total, started, alreadyRunning, failed, results[]}`; each result has `customerId`, `initialChannel`, `status`, `workflowId`, `runId`, `reason`.
The request runs in the HTTP thread and returns when every start call has returned, so it grows linearly with the number of customers.

## How the workflow interprets steps

`YamlWorkflowImpl.interpret` keeps a step pointer: start at `definition.start`; look up the step; run it; the step returns the next id (`switch` picks from `cases`/`default`,
`activity` and `delay` return `next`, `end` returns null and the loop stops, so the workflow method returns normally and the status is COMPLETED).
A cap of 100 transitions stops a looping definition. Unknown step id, type, activity, or no matching case fail the workflow with a non-retryable `ApplicationFailure` with a clear message.
The definition is validated at startup, so those failures only happen if a definition and the workflow code disagree.
Results per step are not stored; Phase 2 can add a per-run map next to the pointer.

## Determinism

The workflow code uses no clock, randomness, IO or database. Time comes from `Workflow.currentTimeMillis()`, the customer arrives as input,
the definition arrives as input, sleeping is `Workflow.sleep`, logging goes through `Workflow.getLogger` and only when `!Workflow.isReplaying()`.
`DurationParser` and `CustomerPaths` are pure.

## Add a new activity

1. Add a method to `OrchActivities` (with `@ActivityMethod`) and a name constant; add the constant to `NAMES`.
2. Implement it in `OrchActivitiesImpl` (use the `run(...)` helper to get the `ACT_*` timing lines).
3. Add a `case` for the name in `YamlWorkflowImpl.execute` calling the stub method.
4. Use `activity: <name>` in a YAML file. The validator knows the name through `NAMES`.

## Run and debug

`./mvnw spring-boot:run` (see the README for Docker and SQL). Logs: console and `logs/temporal-workflow-orch-service.log`, format
`<ISO timestamp IST, e.g. 2026-09-30T15:06:12.894+05:30> LEVEL [thread] logger [wf=<workflowId> cust=<customerId>] - message`. The MDC ids are set in API and activity code only
(workflow threads log ids as fields instead). The Temporal UI at http://localhost:8190 shows each workflow's history and its timers.
Override the definitions folder with `ORCH_DEFINITIONS_PATH`, DB credentials with `APP_DB_USER` / `APP_DB_PASSWORD`.

## Tests (`./mvnw test`, no Docker)

| Test | Covers |
|---|---|
| `DefinitionLoaderTest` | shipped YAML loads; duplicate id, unknown type, unknown activity, bad duration, dangling next/case, missing start, unreachable step, missing next, unsupported `on` path, file name in the error |
| `DurationParserTest` | `90s 30m 2h 3d` parse; `30`, `1w`, empty and other formats rejected |
| `YamlWorkflowTest` | Time-skipping environment with the real log-only activities: VOICE order (call, message, at least 30 min apart); WHATSAPP (message, message, no call); unknown channel fails with the expected message |
| `LogEventsTest` | line builder format |
| `WorkflowStarterServiceTest` | 400 / 404 / 404 / 409, definition checked before customer, other customers run in parallel; start-all: everyone started, running customers skipped, blank/unknown definition. Uses a hand-written fake `CustomerRepository` and the Temporal test environment |

`RecordingActivities` (test sources) wraps the real activities to record call order and workflow time. The `JdbcCustomerRepository` query is covered by the manual end-to-end run, not by a unit test.

## Log events reference

Every line is `event=NAME key=value ...` after the standard pattern prefix. Fields with a null value are omitted; values containing spaces are double-quoted.
INFO level (`WF_FAILED` and `ACT_FAILED` are ERROR). Names are constants in `LogEvents`; the load-test scripts depend on them.

| Event | Where | Fields |
|---|---|---|
| `API_START_RECEIVED` | starter | `customerId`, `definition` |
| `API_START_DONE` | starter | `workflowId`, `runId`, `customerId`, `channel`, `lookupMs`, `startMs`, `totalMs` |
| `API_START_ALL_RECEIVED` | starter | `definition` |
| `API_START_ALL_DONE` | starter | `definition`, `total`, `started`, `alreadyRunning`, `failed`, `lookupMs` (the one `findAll` query), `totalMs` |
| `API_START_REJECTED` | starter | `customerId`, `definition`, `reason` (`BAD_REQUEST`, `DEFINITION_NOT_FOUND`, `CUSTOMER_NOT_FOUND`, `ALREADY_RUNNING`), `totalMs` |
| `WF_STARTED` | workflow | `workflowId`, `customerId`, `channel`, `definition`, `startLagMs` |
| `WF_STEP_ENTER` | workflow | `workflowId`, `stepId`, `stepType` |
| `WF_STEP_EXIT` | workflow | `workflowId`, `stepId`, `stepType`, `stepMs` |
| `WF_COMPLETED` | workflow | `workflowId`, `customerId`, `totalMs` |
| `WF_FAILED` | workflow | `workflowId`, `customerId`, `totalMs`, `error` |
| `ACT_START` | activity | `workflowId`, `stepId`, `activity`, `attempt`, `scheduleToStartMs` |
| `ACT_DONE` | activity | `workflowId`, `stepId`, `activity`, `attempt`, `execMs` |
| `ACT_FAILED` | activity | as `ACT_DONE` plus `error` |
| `DEFINITIONS_LOADED` | startup | `count`, `names`, `loadMs` |

(Phase 1 only. Phase 2 replaced these lines with the readable conversation lines, see `../phase-2/01-coding.md`.) The activity business lines have no `event=` prefix: `Voice call: customerId=.. phone=.. script="..."` and `Send message: customerId=.. phone=.. channel=.. text="..."`.
Phone numbers appear only there.

How to compute latencies from the log file:

| Latency | From |
|---|---|
| API latency | `API_START_DONE.totalMs` (also `lookupMs`, `startMs`); rejected requests: `API_START_REJECTED.totalMs` |
| Start lag (queueing before the first workflow task runs) | `WF_STARTED.startLagMs` |
| Activity queueing time | `ACT_START.scheduleToStartMs` |
| Activity run time | `ACT_DONE.execMs` |
| Step time | `WF_STEP_EXIT.stepMs`; for `delay` it is the sleep length (about 1800000 for `wait-30-min`) |
| Step time excluding waits | `stepMs` of activity steps includes activity queueing, execution and the workflow-task round trips |
| End-to-end time | `WF_COMPLETED.totalMs` (workflow time, includes the delay), or timestamp of `WF_COMPLETED` minus timestamp of `API_START_RECEIVED` for the same `workflowId`/`customerId` |
| Time beyond the configured delay | `WF_COMPLETED.totalMs` minus the delay |

Notes: `API_START_RECEIVED` and `API_START_REJECTED` are logged with the MDC `customerId` only; join to workflow lines via `customerId`
or the deterministic `workflowId` (`<definition>-<customerId>`). `API_START_DONE.totalMs` measures the starter service, which is the whole request except HTTP parsing and JSON serialisation.
Workflow-side lines are written only on first execution, not on replay, so each event appears once per run. Workflow time is Temporal's history time; activity `execMs` uses `System.nanoTime()`.
