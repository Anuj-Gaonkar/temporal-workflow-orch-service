# Phase 2: architecture

Everything in [`../phase-1/03-architecture.md`](../phase-1/03-architecture.md) still holds (components, ports, task queue, namespace, databases, start-all). This doc shows what Phase 2 adds.

## Components added

```
 external system / Postman / Python simulator
        |  POST /workflows/start                  (Day 0: trigger)
        |  POST /workflows/{id}/feedback          (customer feedback)
        |  POST /workflow-definitions/reload
        v
 REST API (OrchController) -- FeedbackService --signal--> Temporal server --> worker (YamlWorkflowImpl)
                                                                                |  contact / await-feedback / delay steps
 workflow-definitions/*.yaml <--rescan-- DefinitionLoader                       |  activities: executeVoiceCall, sendMessage (log only)
```

The database is unchanged: the service still only reads `orch.customers`. Feedback is not stored by the service; it lives in the workflow history (visible in the Temporal UI as signals).

## Sequence: a VOICE customer asks for a reminder

```
Day 0   external system -> POST /workflows/start -> workflow starts, sleeps until Day 1 10:00 IST (durable timer)
Day 1   10:00  contact(VOICE) -> executeVoiceCall (log) -> await-feedback (up to 2h)
        10:20  simulator -> POST /workflows/{id}/feedback {REMIND_LATER, remindAfter 2d, channel WHATSAPP}
               -> signal -> WF_FEEDBACK -> reminder.after=2d, reminder.channel=WHATSAPP
        wait: Day 3 10:00 (2 whole days land on the send hour; inside the window, so it sleeps)
Day 3   10:00  contact(WHATSAPP) -> sendMessage (log) -> await-feedback
        no feedback in 2h -> NO_RESPONSE -> wait 2d (same reminder.after) -> Day 5 10:00 contact(WHATSAPP) ...
Day 15  a reminder that would be sent on Day 16 or later: delay target outside the window -> WF_WINDOW_EXPIRED -> workflow COMPLETED
```

## Sequence: a customer who never answers the reminders

```
Day 1  contact(DB channel) -> feedback: REMIND_LATER 2d WHATSAPP -> count reset
Day 3  reminder 1 (WHATSAPP) -> no feedback in 2h -> count = 1 -> wait 2d
Day 5  reminder 2 (WHATSAPP) -> no feedback in 2h -> count = 2 = limit -> wait 2d
Day 7  final reminder (SMS) -> end (COMPLETED)
```

## Sequence: a customer who never answers at all

```
Day 1  first contact (DB channel) -> no feedback in 2h -> unanswered = 1 -> wait 1d
Day 2  retry -> no feedback -> unanswered = 2 -> wait 1d
Day 3  retry -> no feedback -> unanswered = 3 = limit -> wait 1d
Day 4  final SMS -> end (COMPLETED)
```

## Sequence: positive feedback

```
Day 1 10:00 contact(DB channel) -> await-feedback -> POST feedback {POSITIVE} -> thank-you contact(SMS) -> end (COMPLETED)
```

## Where the 15-day rule is checked

| Place | Check |
|---|---|
| `delay` | wake-up time outside the window: no sleep, workflow ends |
| `contact`, `activity` | now is past the deadline: workflow ends instead of sending |
| `await-feedback` | wait is cut at the deadline; if the deadline comes first the workflow ends |

## Failure and restart behaviour (changes only)

- **App restart while waiting:** timers and the queued signals are in Temporal; on restart the worker replays and continues. Feedback sent while the app is down is queued by Temporal and delivered when the worker is back (the feedback endpoint itself needs the app, so in practice the external system gets a connection error and retries).
- **Feedback to a finished or unknown workflow:** 404 `WORKFLOW_NOT_FOUND`.
- **Feedback with no contact waiting:** accepted (202) and then dropped by the workflow (D42), logged as `WF_FEEDBACK_DROPPED`.
- **Bad signal sent straight to Temporal** (not through the API): the workflow never throws for it; the bad part is ignored and logged.
- **Broken YAML edit while running:** reported, the old version of that file stays live (D45). A broken file at startup stops the app.
- **Activity failure:** unchanged (30 s start-to-close, 3 attempts); a failed contact fails the workflow as in Phase 1.
- **Unexpected customer channel in the DB** (not VOICE / WHATSAPP / SMS): the first `contact` fails with `No usable channel ...`.
- **Timezone/DST:** calendar days use `Asia/Kolkata` from the YAML; no DST there. A zone with DST would still land on the send hour of the target date.

## Where time is measured

Same as Phase 1 for the API and activities. Campaign time: `WF_STEP_EXIT.stepMs` of a `delay` or `await-feedback` step is the wait; `WF_COMPLETED.totalMs` is the whole campaign (days).

## Deliberately not in Phase 2

- Storing feedback or contact history in the database (reporting). Not requested.
- A retry cap, quiet hours other than the single send hour, or per-customer calendars.
- Upload/edit API for definitions (hot reload is a folder rescan).
- A feedback simulator in this repository. It is a separate client of the feedback endpoint (load-test effort).
- `fromResult`, `${...}` params and per-step result storage from the instruction guide's sketch (D38).
