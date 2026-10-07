# Phase 3 - Coding notes (orchestrator side)

- `customer/Customer` has `waId`; `JdbcCustomerRepository` reads `wa_id`; `RunPaths` allows `customer.waId`.
- `StepDefinition.flow` (optional, `contact` only) and the validator check that it is not blank.
- `activity/WhatsAppGateway` (interface) with `HttpWhatsAppGateway` (calls `orch.whatsapp.base-url`, default `http://localhost:8182`): `sendFlow`, `sendText`. `OrchActivitiesImpl.sendMessage` logs the conversation line as before and, for channel `WHATSAPP`, delivers through the gateway; a `RuntimeException` is logged as `ACT_WHATSAPP_FAILED`. New log events `ACT_WHATSAPP_SENT`, `ACT_WHATSAPP_FAILED`.
- `YamlWorkflowImpl.contact` puts `flowKey` into the activity params when the step has `flow` and the channel is `WHATSAPP`. `awaitFeedback` scales its timeout with `CampaignClock.scale`.
- `DefinitionLoader` applies `orch.timing.day-length` to the campaign before validating.
- Config: `application.yaml`, `application-dev.yaml`, `application-prod.yaml`.
- Tests added: Flow on Day 1 and plain-text reminder (`YamlWorkflowTest`), VOICE customer never gets WhatsApp, `CampaignClock.scale`. Tests use a recording gateway and never call Meta. `./mvnw test`: 79 tests.
- Verified by hand: both apps and all containers, `whatsapp-flows-service` pointed at a local mock of Meta's API. Day 1 Flow message, answer "Within 3 days" via `/screen`, reminder text 3 minutes later, 11:03:36 and 11:06:39 IST. Real WhatsApp delivery needs your credentials and has not been run.
