# Phase 2: coding doc

Phase 2 (branch `phase-2-feedback-switch`) adds the 15-day campaign window, customer feedback and feedback-driven contacts, plus hot reload of the
YAML definitions. It sits on top of Phase 1 and the `phase-1-start-all` extras; read [`../phase-1/01-coding.md`](../phase-1/01-coding.md) first for
the package layout, the request flow of `POST /workflows/start`, the step pointer, determinism and the Phase 1 log events. This doc covers what changed.

## What changed, by package (`com.hdfc.cern.workfloworch`)

| Package | Change |
|---|---|
| `definition` | `Campaign` (the `campaign:` block), `CampaignClock` (Day 0 / Day N / deadline arithmetic, pure), `RunPaths` (replaces `CustomerPaths`: customer fields plus run variables), `Channels`, `FeedbackTypes`; `StepDefinition` gets the fields of the new step types; `DefinitionValidator` checks them; `DefinitionLoader` rescans the folder (hot reload). |
| `workflow` | `YamlWorkflow` gets a `@SignalMethod feedback(FeedbackSignal)`. `YamlWorkflowImpl` gets the `contact` and `await-feedback` steps, the window checks and the run variables. Transition cap raised from 100 to 500 (a 15-day loop needs about 4 transitions per day). |
| `starter` | `FeedbackService` validates a feedback request and sends it to the workflow as a signal. |
| `api` | `POST /workflows/{workflowId}/feedback`, `POST /workflow-definitions/reload`. |
| `logging` | New events (below). |

The activities (`executeVoiceCall`, `sendMessage`) are unchanged: they still only log and return nothing. Feedback does **not** come from an activity.
It comes from outside, through the feedback endpoint. The service contains no generated or mock feedback.

## The campaign in one picture

```
Day 0  workflow started by an external system (POST /workflows/start)
Day 1  first contact at 10:00 IST, on the channel stored in the DB
       -> await feedback (2h)
            POSITIVE      -> thank-you SMS -> end
            REMIND_LATER  -> wait (customer's time, default 1d) -> contact (customer's channel, default DB channel) -> await feedback
            NO_RESPONSE   -> wait 1d -> contact on the DB channel -> await feedback          (also what a timeout becomes)
                             first contact + 2 unanswered retries -> wait 1d -> 1 final SMS -> end (D52)
            INCONCLUSIVE  -> wait 1d -> contact on the channel of the last contact -> await feedback
       reminder not answered -> remind again after the same time on the same channel
Reminders: after 2 unanswered ones in a row -> wait the reminder interval -> 1 final SMS reminder -> end (D51)
Day 15 23:59 IST  last moment; anything that would happen later is dropped and the workflow ends (COMPLETED)
```

All the numbers (15 days, 10:00, 2h, the 1 day defaults) live in the YAML, not in code. See [`../yaml-guide.md`](../yaml-guide.md).

## Request flow: `POST /workflows/{workflowId}/feedback`

1. `OrchController.feedback` -> `FeedbackService.send`.
2. Validate: `type` is one of POSITIVE, REMIND_LATER, NO_RESPONSE, INCONCLUSIVE (case-insensitive, stored upper case); `remindAfter` and `channel` are only
   allowed with REMIND_LATER; `remindAfter` must be a valid duration (`2d`, `3h`); `channel` is WHATSAPP, VOICE or SMS. A bad request is 400 `BAD_REQUEST`.
3. Send the signal to the running workflow (`newWorkflowStub(YamlWorkflow.class, workflowId).feedback(...)`). A workflow that does not exist or has finished is 404 `WORKFLOW_NOT_FOUND`.
4. 202 with the normalised feedback. The signal is only *delivered*; the workflow decides what to do with it (see the next section).

## How the workflow handles feedback

- The signal handler checks the values again (a signal can be sent straight to Temporal) and **never throws**: a bad type is dropped (`WF_FEEDBACK_DROPPED`), a bad `remindAfter` or `channel` is ignored.
- Valid feedback is queued. An `await-feedback` step takes the oldest queued feedback, or waits up to its `timeout`; a timeout becomes `NO_RESPONSE`.
- Feedback that arrives **before** a contact is made (for example on Day 0) belongs to no contact and is dropped when the next `contact` step starts (`WF_FEEDBACK_DROPPED reason=STALE_BEFORE_CONTACT`).
  Feedback that arrives while the contact activity is still running is kept.
- When feedback is taken it becomes run variables the YAML can read (`feedback.type`, `feedback.remindAfter`, `feedback.channel`). For REMIND_LATER the values the customer gave are also
  remembered as `reminder.after` / `reminder.channel`; a later reminder request that leaves a value out keeps the earlier one. This is what makes "remind again with the same duration and channel" work.
- Every `contact` step records the channel it used as `last.channel`; INCONCLUSIVE uses it to answer on the channel the customer responded on.

## How the 15-day window is enforced

`CampaignClock` is built from the `campaign:` block and the time the run started (`WorkflowInfo.getRunStartedTimestampMillis`, which is in the history, so replays agree).

- Day 0 is the IST date of the start. Day N is N days later at `sendHour`. The window ends (exclusive) at 00:00 of Day `maxDays + 1`, so all of Day 15 can still be used.
- A `delay` computes its wake-up time first. If that time is outside the window it does **not** sleep: the workflow logs `WF_WINDOW_EXPIRED` and ends normally.
  This is how "remind me in 20 days" is dropped.
- A wait in whole days (`1d`, `2d`) lands on `sendHour` of the target day. Any other wait (`2h`) is exact.
- `contact` and `activity` steps check the window again before sending, so nothing is ever sent after the deadline even if a step was reached late.
- An `await-feedback` is never longer than the time left in the window; if the window closes first, the workflow ends instead of counting a NO_RESPONSE.
- Caps (D51, D52): a customer who never answers gets the first contact, 2 retries and 1 final SMS (Day 1 .. Day 4), then the workflow completes. After 2 unanswered reminders, 1 final SMS and complete. The window is the outer bound.

Demo and load-test aid: `campaign.dayLength: 2m` makes one "day" last 2 minutes (see decision D40). Leave it out for real days.

## Hot reload

`DefinitionLoader` still loads and validates every file at startup and fails the app on a broken one. After that it rescans the folder every `orch.definitions-reload-seconds`
(default 10; `orch.definitions-hot-reload: false` turns the timer off) and on `POST /workflow-definitions/reload`.

- A new or edited valid file goes live. Its `name` shows up in `changed`.
- A broken file is reported (in `errors`, and as `DEFINITIONS_RELOAD_FAILED`, once per distinct problem) and the **previous version of that file stays live**.
- A deleted file removes its definition (`removed`).
- Running workflows are not affected: the definition is their input. New starts use the new version.
- "Editing at runtime" means editing the file on disk; there is no upload API.

## Add or change a step type

Same three places as in Phase 1 plus validation: `StepDefinition` constant and fields, `DefinitionValidator.checkStep`, `YamlWorkflowImpl.execute`. Update `yaml-guide.md`.

## Tests (`./mvnw test`, no Docker)

| Test class | Covers |
|---|---|
| `CampaignClockTest` | Day 1 = next calendar day at the send hour, window end at the end of Day 15, whole-day waits vs exact waits, `dayLength` mode. |
| `DefinitionLoaderTest` | The shipped YAML loads; every validation message of the new step types and the `campaign` block; hot reload (valid edit goes live, broken edit keeps the old version, new file appears, unchanged folder reports nothing). |
| `YamlWorkflowTest` | The final-SMS-after-two-unanswered-reminders rule and the count reset. The real workflow in the time-skipping environment, with feedback sent as the same signal the REST endpoint sends: first contact on Day 1 at 10:00 by channel, POSITIVE (thank-you SMS), REMIND_LATER with and without details, re-reminder with the same time and channel, NO_RESPONSE (timeout and explicit), INCONCLUSIVE answers on the last channel, a reminder beyond Day 15 is dropped, a reminder on Day 15 is sent, never-answering customer gets exactly 15 contacts, stale feedback is dropped. |
| `FeedbackServiceTest` | Validation (400s) and the unknown-workflow 404. |
| `WorkflowStarterServiceTest`, `LogEventsTest`, `DurationParserTest` | Unchanged from Phase 1. |

Test-only helper `RecordingActivities` wraps the real log-only activities and records the activity, workflow time and channel of each call. There is no mock feedback source: tests send signals.

## Log events reference (additions to Phase 1)

| Event | Where | Fields |
|---|---|---|
| `API_FEEDBACK_RECEIVED` | feedback service | `workflowId`, `type`, `remindAfter`, `channel` (as sent) |
| `API_FEEDBACK_DONE` | feedback service | `workflowId`, `type`, `totalMs` |
| `API_FEEDBACK_REJECTED` | feedback service | `workflowId`, `reason` (`BAD_REQUEST`, `WORKFLOW_NOT_FOUND`), `totalMs` |
| `WF_FEEDBACK` | workflow | `workflowId`, `stepId` (the await step), `type`, `remindAfter`, `channel`. Logged when feedback is taken, including a timeout (`type=NO_RESPONSE`). |
| `WF_FEEDBACK_DROPPED` | workflow | `workflowId`, `reason` (`UNKNOWN_TYPE`, `STALE_BEFORE_CONTACT`; `BAD_REMIND_AFTER_IGNORED` and `BAD_CHANNEL_IGNORED` when only that value was ignored) and the offending value or `count` |
| `WF_COUNTER` | workflow | `workflowId`, `stepId`, `counter`, `value`, and for a limit check `limit`, `reached` |
| `WF_WINDOW_EXPIRED` | workflow | `workflowId`, `stepId` (the step that would have crossed the line), `deadline` (UTC instant). Followed by `WF_COMPLETED`. |
| `DEFINITIONS_RELOADED` | reload | `count`, `changed`, `removed`, `loadMs` |
| `DEFINITIONS_RELOAD_FAILED` | reload (WARN) | `error` |

Useful derived numbers: time from contact to feedback = `WF_FEEDBACK` timestamp minus the activity business line of the same `workflowId`; contacts per workflow = count of
`ACT_DONE` per `workflowId`; campaigns that ran out of time = count of `WF_WINDOW_EXPIRED`. `WF_STEP_EXIT.stepMs` of a `delay` or `await-feedback` step is the time spent waiting.
The `WF_COMPLETED.totalMs` of a campaign is days long unless `dayLength` is used.

## Readable conversation log

Besides the `event=...` lines (unchanged; the load-test report parses them), the service writes human-readable "conversation" lines, built by `ConversationLog` and logged through the logger named `conversation`.
They replace the Phase 1 business lines (`Voice call: customerId=..` and `Send message: customerId=..`), which nothing parsed.

| Line | Written by | Example |
|---|---|---|
| What we sent | the two activities | `Sending WHATSAPP to C1001 (+910000000001) [reminder-contact] at 2026-10-01 12:10:59 IST >> This is the reminder you asked for...` (a VOICE contact says `Calling VOICE ...`) |
| What the customer replied | the workflow, when feedback is taken | `Customer C1001 replied at 2026-10-01 12:00:59 IST >> REMIND_LATER (in 2d on WHATSAPP)`; a timeout reads `NO_RESPONSE (no feedback within the wait)` |
| How the campaign ended | the workflow, at the end | `Campaign for C1001 ended at 2026-10-01 12:11:26 IST >> COMPLETED after 16m, 3 contacts (VOICE, WHATSAPP, SMS)`; `WINDOW_EXPIRED` when the 15-day window closed it |

Times are IST with the date, taken from the activity clock or from workflow time (so replays do not change them), and are written only when not replaying.
`src/main/resources/logback-spring.xml` keeps Spring Boot's console and main file appenders (patterns and rolling still come from `logging.*` in `application.yaml`) and adds a second file,
`logs/conversation.log` (10 MB / 7 days, each line is just the text above). The same lines also appear in the main log. `ConversationLogTest` covers the formats.

### Show only the communication (flag)

`orch.logging.communication-only` in `application.yaml` (default `false`; or `ORCH_LOG_COMMUNICATION_ONLY=true`). When `true` the console and the log files contain only the conversation lines plus WARN and ERROR; the `event=` timing lines and the framework output are hidden. Restart the app after changing it.
Do not use it for load tests: `load-test/report.py` reads the `event=` lines. `logs/conversation.log` always has the conversation lines either way. Implemented by `CommunicationOnlyFilter` (a Logback `TurboFilter`, wired in `logback-spring.xml`); covered by `CommunicationOnlyFilterTest`. See D53.
