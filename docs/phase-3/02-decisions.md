# Phase 3 - Decisions

**D60 Two new projects, copied with fresh git history.** `whatsapp-flows-service` (from `whatsapp-flow-engine-service-v3` main) and `temporal-workflow-orch-service` (from `cern-temporal-workflow-orch-service` phase-2). Java packages are unchanged (`com.hdfc.flowengine`, `com.hdfc.cern.workfloworch`).

**D61 The old `temporal-workflow-service` is dropped.** The orchestrator replaces it: no `/reminders`, no `/messages/reminder`. The reminder is the full YAML campaign (D62).

**D62 Full YAML reminder model.** A reminder is the existing `REMIND_LATER` path of `outreach-default.yaml`, not a separate simple workflow. The YAML did not need new step types, only a `flow` field on `contact`.

**D63 Campaign starts from the orchestrator.** The first WhatsApp contact (Day 1) sends the Flow message, not plain text, so the customer can answer. Retry and follow-up contacts also carry `flow`; the reminder contact is plain text.

**D64 Customers table has `wa_id`.** `orch.customers.wa_id` (digits only, checked by the schema). `phone` stays. The default six customers all use the placeholder number `910000000001`. Load-test customers get obviously fake numbers.

**D65 Profiles instead of a mode switch.** `application-dev.yaml` (minutes) and `application-prod.yaml` (days) in both services; `dev` is the default. Orchestrator: `orch.timing.day-length` (3m in dev) overrides `campaign.dayLength` when a definition is loaded and also scales whole-day feedback timeouts (`CampaignClock.scale`). Flows: `reminder.timing.*` replaces `FUNDS_REMINDER_MODE`. The YAML never contains `dayLength`.

**D66 Plain HTTP, no auth between services.** Owner's choice (keep it simple).

**D67 WhatsApp delivery failures are logged, not retried.** The 24 hour window rule means a failure is expected sometimes. The activity catches it, logs `ACT_WHATSAPP_FAILED` and the workflow carries on.

**D68 No seed data in the apps.** SQL under `db/` is loaded by hand with `scripts/load-data.sh`; compose mounts no init scripts. The flow graph SQL is the same as the old service's.

**D69 Credentials only from environment variables.** No token, app secret or phone number id is committed; defaults are empty. `.env.example` lists them. The Meta API base URL is overridable (`WA_CLOUD_API_BASE_URL`) so tests can point at a local mock.

**D70 `flow_session` records the workflow.** New columns `workflow_id`, `customer_id` so a Flow answer finds its workflow.

**D71 One shared app Postgres, one schema per service.** Context: three Postgres containers was more than needed. Decision (owner's): a single container `cern-v2-postgres` (host port 5437, database `cern_v2`, user `cern`) defined in `../docker-compose.yml`, which neither repo owns, so neither depends on the other's compose. Schemas `flow_engine` (flows) and `orch` (customers) keep the data apart. Temporal and its own Postgres stay in this repo's compose (host 7235 / 8190 / 5439) so wiping app data never touches Temporal history. Consequence: one login (`cern`) for both services, and the shared stack must be started first. Supersedes the per-service Postgres of D68's setup (the scripts still load by hand).
