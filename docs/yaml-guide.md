# YAML guide

A definition file in `workflow-definitions/*.yaml` is the whole script of a workflow. One generic workflow class interprets it; the customer data
comes from the database, never from the YAML. Definitions are read at startup (since phase 1) and **rescanned while the app runs** (since phase 2, see "Hot reload").

## How customer data reaches the workflow

`customers table (orch.customers)` -> `CustomerRepository.findById` at start time -> `WorkflowInput.customer` (snapshot) -> the workflow.
The parsed definition is also passed as workflow input, so a running workflow keeps the definition it started with. The workflow never reads the database.
Customer feedback does not come from the database or the YAML: it arrives through `POST /workflows/{workflowId}/feedback` (since phase 2).

## Schema

Top level:

| Field | Required | Type | Meaning |
|---|---|---|---|
| `name` | yes | string | Unique across files. The caller names it in `POST /workflows/start`; it prefixes the workflow id. |
| `description` | no | string | Shown by `GET /workflow-definitions`. |
| `campaign` | no | block | The campaign window (since phase 2). Without it nothing limits how long the workflow runs and `untilDay` is not allowed. |
| `start` | yes | step id | First step. |
| `steps` | yes | non-empty list | The steps. |

### `campaign` (since phase 2)

| Field | Required | Meaning |
|---|---|---|
| `maxDays` | yes, at least 1 | Contacts may happen on Day 1 .. Day `maxDays` and never after. Day 0 is the day the workflow is triggered. The whole of Day `maxDays` is usable (the window ends at 00:00 of the next day). |
| `timezone` | no, default `Asia/Kolkata` | Zone that decides where a calendar day starts. |
| `sendHour` | no, default 10 | Hour (0-23) at which whole-day waits and `untilDay` wake up. |
| `dayLength` | no | Do not set it in the YAML. The Spring profile sets it (`orch.timing.day-length`, `3m` in dev, empty in prod) and overrides this field: one "day" lasts that long. Leave it out for real calendar days. Never commit it in the shipped file. |

Every step: `id` (required, unique) and `type` (required: `switch`, `activity`, `delay`, `contact`, `await-feedback`, `count`, `end`). Unknown fields anywhere are rejected.

| Type | Fields | Meaning |
|---|---|---|
| `switch` | `on` (required), `cases` (value to step id), `default` (optional step id) | Picks the next step from a path (see below). Exact, case-sensitive string match. No match and no `default` (also when the path has no value): the workflow fails with `No case for feedback.type=XYZ in step 'route-first'`. Needs `cases` or `default`. When `on` is `feedback.type`, every case must be a feedback type. |
| `activity` | `activity` (required), `params` (optional string map), `next` (required) | Runs a registered activity, then goes to `next`. Since phase 2 it is skipped (workflow ends) if the campaign window has closed. |
| `delay` | exactly one of `duration`, `fromVar` (+ `defaultDuration`), `untilDay`; `next` (required) | A durable timer, then `next`. See "Delays". If the wake-up time is outside the campaign window the workflow ends instead of sleeping. |
| `contact` (phase 2) | `channel` (required), `defaultChannel` (optional), `text` (required), `flow` (optional), `next` (required) | Contacts the customer. VOICE runs `executeVoiceCall` (the text is the call script); WHATSAPP and SMS run `sendMessage`. A WHATSAPP contact really sends: with `flow: <flow key>` (e.g. `AMB_REMINDER_RADIO`) the customer gets that WhatsApp Flow and `text` is its message body; without `flow` it is a plain WhatsApp text. SMS and VOICE only log. A WhatsApp delivery failure (for example outside the 24 hour window) is logged and the campaign carries on. Remembers the channel as `last.channel`. Feedback that arrived before the contact started is dropped. |
| `await-feedback` (phase 2) | `timeout` (required), `next` (required) | Waits for customer feedback up to `timeout`. Feedback becomes `feedback.type` etc.; no feedback in time becomes `feedback.type = NO_RESPONSE`. Follow it with a `switch` on `feedback.type`. |
| `count` (phase 2) | `counter` (required name) and either `reset: true` + `next`, or `limit` + `whenReached` + `next` | A named counter for the run. `reset: true` sets it to 0 and goes to `next`. Otherwise it adds 1; once the counter has reached `limit` the workflow goes to `whenReached`, else to `next`. Used to stop after N unanswered reminders. |
| `end` | none | Finishes the workflow normally (status COMPLETED). Never Temporal's terminate. |

### Paths: `on`, `fromVar`, `channel` (since phase 1, extended in phase 2)

Nothing else is accepted: no expressions, no operators.

| Path | Value |
|---|---|
| `customer.customerId`, `customer.name`, `customer.phone`, `customer.initialChannel` | From the customers table (`initialChannel` is VOICE or WHATSAPP). |
| `feedback.type` | POSITIVE, REMIND_LATER, NO_RESPONSE or INCONCLUSIVE: the last feedback taken. |
| `feedback.remindAfter`, `feedback.channel` | What the customer said in that feedback; empty if not given. |
| `reminder.after`, `reminder.channel` | The reminder time and channel the customer asked for in a REMIND_LATER. Remembered for the rest of the run, so an unanswered reminder can be repeated with the same time and channel. A later REMIND_LATER that omits a value keeps the earlier one. Empty until the customer asked. |
| `last.channel` | The channel of the last `contact`. This is the channel the customer responded on. |

A path with no value is empty: `switch` falls to `default` (or fails); `delay.fromVar` uses `defaultDuration`; `contact.channel` uses `defaultChannel`.
Where a channel is expected you write either a literal (`VOICE`, `WHATSAPP`, `SMS`, `EMAIL`; EMAIL and SMS are only logged for now) or one of the channel paths (`customer.initialChannel`, `feedback.channel`, `reminder.channel`, `last.channel`).
Where a duration is expected via `fromVar`: `reminder.after` or `feedback.remindAfter`.

### Feedback types (since phase 2)

| Type | Meaning | Sent with |
|---|---|---|
| `POSITIVE` | Customer will complete the process. | nothing else |
| `REMIND_LATER` | Remind me later. | optional `remindAfter` (`2d`, `3h`) and `channel` (WHATSAPP, VOICE, SMS) |
| `NO_RESPONSE` | Customer did not answer (also what a feedback timeout becomes). | nothing else |
| `INCONCLUSIVE` | Customer answered but nothing concrete. | nothing else |

### Delays (since phase 1, extended in phase 2)

| Form | Meaning |
|---|---|
| `duration: 1d` | Wait this long. |
| `fromVar: reminder.after` + `defaultDuration: 1d` | Wait the time in the variable, or the default when the customer gave none. |
| `untilDay: 1` | Wait until Day 1 of the campaign at `sendHour` (needs `campaign`, between 1 and `maxDays`). |

With a `campaign` block, a wait in **whole days** (`1d`, `2d`, a customer's `3d`) lands on `sendHour` of the target day; any other wait (`2h`, `30m`) is exact.
If the wake-up time is after the end of Day `maxDays`, nothing is slept and nothing more is sent: the workflow logs `WF_WINDOW_EXPIRED` and completes.

### Activities (since phase 1)

| Name | Params used | What it does |
|---|---|---|
| `executeVoiceCall` | `script` | Logs the call. |
| `sendMessage` | `channel`, `text`, `flowKey` (optional) | Logs the message; for channel WHATSAPP also sends it through whatsapp-flows-service (a Flow when `flowKey` is set, otherwise text). |

Both return nothing. Only a WHATSAPP `sendMessage` calls an external service (whatsapp-flows-service). A `contact` step uses them for you.

### Duration format (since phase 1)

A whole number plus one lowercase unit: `s`, `m`, `h`, `d` (`90s`, `30m`, `2h`, `3d`). `30`, `1w`, `30M`, `30 m`, `PT30M` and empty are rejected. The same format is used for `remindAfter` in feedback.

## Hot reload (since phase 2)

The folder is rescanned every 10 seconds (`orch.definitions-reload-seconds`; `orch.definitions-hot-reload: false` stops the timer) and on `POST /workflow-definitions/reload`, which returns `{changed, removed, errors}`.

- A new or edited valid file goes live for **new** starts. Running workflows keep the definition they started with.
- A broken file is reported (`errors`, log `DEFINITIONS_RELOAD_FAILED`) and the previous version of that file stays live.
- A deleted file removes its definition.
- At startup a broken file still stops the app.

## Validation

Checked at startup and on every reload; a broken file reports `Invalid workflow definition <file>: <problems separated by ;>`. Messages:

| Problem | Message contains |
|---|---|
| missing name / start / steps | `'name' is required`, `'start' is required`, `'steps' is required and must not be empty` |
| step without id or type | `every step needs an 'id'`, `step 'x': 'type' is required` |
| duplicate step id | `duplicate step id 'x'` |
| unknown step type | `step 'x': unknown step type 'sleep' (use switch, activity, delay, contact, await-feedback, count or end)` |
| unknown activity | `step 'x': unknown activity 'fax' (known: [...])` |
| bad duration / timeout | `step 'x': bad duration: ...`, `bad defaultDuration: ...`, `bad timeout: ...` |
| delay with none or several of `duration` / `fromVar` / `untilDay` | `delay needs exactly one of 'duration', 'fromVar' or 'untilDay'` |
| `fromVar` problems | `unsupported 'fromVar' 'x'`, `'fromVar' needs a 'defaultDuration' ...` |
| `untilDay` problems | `'untilDay' must be at least 1`, `'untilDay' needs a 'campaign' block`, `'untilDay' 16 is after the campaign window (maxDays 15)` |
| count problems | `count needs 'counter'`, `count needs 'reset: true' or a 'limit' of at least 1`, `count with a 'limit' needs 'whenReached'`, `a count with 'reset: true' must not have 'limit' or 'whenReached'`, `whenReached points to unknown step ...` |
| contact problems | `contact needs 'channel'`, `contact needs 'text'`, `bad 'channel' 'PIGEON' (use [...] or a path: [...])` |
| bad campaign block | `campaign: 'maxDays' must be at least 1`, `campaign: unknown 'timezone' 'x'`, `campaign: 'sendHour' must be between 0 and 23`, `campaign: bad 'dayLength': ...` |
| `start`, `next`, case or default points nowhere | `start step 'x' does not exist`, `step 'a': next points to unknown step 'b'`, `case 'POSITIVE' points to unknown step ...` |
| non-end step without a way forward | `step 'x': 'next' is required ...`, `switch needs 'cases' or 'default'` |
| unsupported `on` path | `unsupported 'on' path 'x' (use one of [...])` |
| case that is not a feedback type (on `feedback.type`) | `case 'MAYBE' is not a feedback type (use one of [...])` |
| step not reachable from `start` | `step 'x' is not reachable from start 'y'` (an error, not a warning) |
| two files with the same `name` | `definition name 'x' is already defined by other.yaml` |
| unknown YAML field, bad YAML | the parser message, first line |

## Annotated example (the shipped `outreach-default.yaml`)

The file is the reference: open [`../workflow-definitions/outreach-default.yaml`](../workflow-definitions/outreach-default.yaml). In short:

```
campaign: 15 days, IST, 10:00
wait-day-1 (delay untilDay 1) -> first-contact (contact on customer.initialChannel) -> await-first (2h) -> route-first (switch feedback.type)
   POSITIVE     -> thank-you (contact SMS) -> end
   REMIND_LATER -> reset-reminders -> wait-reminder (delay fromVar reminder.after, default 1d) -> reminder-contact (channel reminder.channel, default DB channel) -> await-reminder
                       await-reminder -> route-reminder: POSITIVE -> thank-you | REMIND_LATER -> reset-reminders | INCONCLUSIVE -> reset + wait-follow-up
                                         NO_RESPONSE -> count-unanswered-reminder: 1st -> wait-reminder (same time, same channel); 2nd in a row -> wait-final-reminder -> final-reminder (SMS) -> end
   INCONCLUSIVE -> reset-reminders-inconclusive -> wait-follow-up (1d) -> follow-up-contact (channel last.channel) -> await-first
   NO_RESPONSE  -> count-unanswered-contact: 1st and 2nd -> wait-retry (1d) -> retry-contact (DB channel) -> await-first
                                              3rd in a row -> wait-final-contact (1d) -> final-reminder (SMS) -> end
```

Loops are fine; the campaign window ends them (and the workflow stops after 500 transitions as a safety net).

## Common mistakes

- Indenting `cases` entries wrongly, or forgetting a step listed in `cases` (validation names the missing step).
- Writing `duration: 30` or `30 minutes` (use `30m`).
- Lower-case channel in `cases` (`voice:`): matching is case-sensitive; the database stores `VOICE` / `WHATSAPP`. (Feedback sent to the API is upper-cased for you.)
- Giving a `delay` both `duration` and `untilDay`, or `fromVar` without `defaultDuration`.
- Forgetting that the `unanswered` counter is shared: any reaction resets it; contacts with no answer in a row count on both paths (limit 3 after the first contact, 2 after a reminder).
- Expecting a reminder "in 20 days" to be sent: it is beyond the window and the workflow ends instead.
- Sending feedback when no contact is waiting for it (for example between two reminders): it is accepted (202) and then dropped; send it after the contact it answers.
- Leaving `dayLength` in the shipped file after a demo.
- Leaving a step nobody points to (rejected as unreachable).

## Adding a new definition file

1. Copy `outreach-default.yaml` to `workflow-definitions/<something>.yaml` and give it a new unique `name`.
2. Wait for the rescan (or `POST /workflow-definitions/reload`); watch for `event=DEFINITIONS_RELOADED` (or the error that names your file).
3. Start it with `POST /workflows/start` naming it in `definition`. Its workflow id is `<name>-<customerId>`.

## Not available

- `fromResult` and `${...}` references in `params`, and per-step result storage: not built; the run variables above replace what Phase 2 needed (decision D38). `fromResult` is rejected as an unknown YAML field.
- An API to upload or edit definitions: edit the file.
