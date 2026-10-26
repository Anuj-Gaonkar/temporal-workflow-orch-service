# YAML rules

The short rulebook for writing a workflow definition in `workflow-definitions/*.yaml`. It lists what must hold; the reasons, every field and the
validation messages are in [`yaml-guide.md`](yaml-guide.md). A file that breaks a rule marked **(validated)** is rejected at startup and on every reload
(`Invalid workflow definition <file>: ...`); the rest are runtime behaviours to keep in mind.

## 1. File

1. One definition per file, in `workflow-definitions/`, extension `.yaml`. **(validated)**
2. `name` is required and unique across all files. It becomes the workflow id prefix (`<name>-<customerId>`). **(validated)**
3. `start` and a non-empty `steps` list are required. **(validated)**
4. Unknown fields anywhere are rejected, so a typo is an error, not a silent no-op. **(validated)**
5. A broken file on reload is reported and the previous version stays live; a broken file at startup stops the app.
6. A new or edited file only affects workflows started afterwards. Running workflows keep the definition they started with.
7. Customer data never goes in the YAML; it comes from `orch.customers`.

## 2. Steps (all types)

1. Every step has a unique `id` and a `type`: `switch`, `activity`, `delay`, `contact`, `await-feedback`, `count`, `end`. **(validated)**
2. Every step except `end` needs a way forward: `next` (or `cases` / `default` for `switch`). **(validated)**
3. `start`, every `next`, every `cases` value, `default` and `whenReached` must name an existing step id. **(validated)**
4. Every step must be reachable from `start`. An orphan step is an error, not a warning. **(validated)**
5. Loops are allowed. The campaign window ends them; the workflow also stops after 500 transitions as a safety net.
6. End a path with an `end` step. It completes the workflow normally (never Temporal's terminate).

## 3. Rules per step type

| Type | Rules |
|---|---|
| `delay` | Exactly one of `duration`, `fromVar` or `untilDay`. `fromVar` needs `defaultDuration`, and only `reminder.after` or `feedback.remindAfter` are allowed. `untilDay` needs a `campaign` block and a value from 1 to `maxDays`. **(validated)** |
| `contact` | `channel`, `text` and `next` are required. `channel` is a literal, a channel path or `AUTO`. `flow` is optional and only used by WHATSAPP. `defaultChannel` is the fallback when a channel path is empty. **(validated)** |
| `await-feedback` | `timeout` and `next` are required. No answer in time becomes `feedback.type = NO_RESPONSE`. Follow it with a `switch` on `feedback.type`. **(validated)** |
| `switch` | `on` is required, plus `cases` or `default`. Matching is exact and case-sensitive. No match and no `default` makes the workflow fail. When `on` is `feedback.type`, every case must be a feedback type. **(validated)** |
| `count` | Either `counter` + `reset: true` + `next`, or `counter` + `limit` (at least 1) + `whenReached` + `next`. `reset: true` must not be mixed with `limit` or `whenReached`. A counted step adds 1 and goes to `whenReached` once the count has reached `limit`. **(validated)** |
| `activity` | `activity` (a known name: `executeVoiceCall`, `sendMessage`) and `next` are required. `params` is a string map. Prefer `contact`, which uses these for you. **(validated)** |
| `end` | No other fields. |

## 4. Value formats

1. **Durations**: a whole number plus one lowercase unit `s`, `m`, `h`, `d` (`90s`, `30m`, `2h`, `3d`). `30`, `1w`, `30M`, `30 m`, `PT30M` and empty are rejected. The same format is used for `remindAfter` in feedback. **(validated)**
2. **Channel codes**: upper-case letters, digits and `_`. `AUTO` and `DB` are reserved. **(validated)**
3. **Quote `"*"`** in `channelDefaults`; a bare `*` is a YAML alias.
4. **Paths** are only these, with no expressions or operators: `customer.customerId`, `customer.name`, `customer.phone`, `customer.initialChannel`, `feedback.type`, `feedback.remindAfter`, `feedback.channel`, `reminder.after`, `reminder.channel`, `last.channel`. **(validated)**
5. A path with no value is empty: `switch` falls to `default` (or fails), `fromVar` uses `defaultDuration`, `contact.channel` uses `defaultChannel`.
6. **Feedback types** are `POSITIVE`, `REMIND_LATER`, `NO_RESPONSE`, `INCONCLUSIVE`. Nothing else.

## 5. Campaign window

1. `campaign` is optional, but without it `untilDay` is not allowed and nothing limits the run. **(validated)**
2. `maxDays` is required (at least 1). `sendHour` is 0 to 23 (default 10). `timezone` must be a real zone (default `Asia/Kolkata`). **(validated)**
3. **Never put `dayLength` in a shipped file.** The Spring profile sets it (`3m` in dev, none in prod) and overrides the YAML. Leaving it in after a demo makes real campaigns run in minutes.
4. Day 0 is the trigger day. Contacts happen on Day 1 to Day `maxDays` and never after.
5. A wait in whole days (`1d`, `2d`, a customer's `3d`) lands on `sendHour` of the target day; any other wait (`2h`, `30m`) is exact.
6. A wake-up past the end of Day `maxDays` is dropped: the workflow logs `WF_WINDOW_EXPIRED` and completes. A reminder "in 20 days" in a 15-day campaign is never sent.

## 6. Channels, defaults and rules (optional blocks)

1. A definition accepts only the channels in its `channels` block (default `VOICE`, `WHATSAPP`, `SMS`, `EMAIL`). Every channel named anywhere in the file must be listed. **(validated)**
2. A customer whose DB channel is not listed is refused at start with `422 CHANNEL_NOT_SUPPORTED`; `start-all` reports that customer as failed and carries on.
3. `channelDefaults` and `channelRules` only make sense with at least one `channel: AUTO` contact, and `channelDefaults` can only target contact steps that use `AUTO`. **(validated)**
4. `channelRules.onFeedback` actions: `DEFAULT`, `SAME`, `FLIP`, `REQUESTED`. `DEFAULT` is not allowed for `REMIND_LATER`. `REQUESTED` is only valid for `REMIND_LATER`. **(validated)**
5. If `FLIP` is used, every listed channel needs a flip target, and no channel may flip to itself. **(validated)**
6. A literal channel on a step always wins over `AUTO` resolution.
7. Only WHATSAPP really leaves the service today. `VOICE`, `SMS` and `EMAIL` are logged only; a new channel needs a sender in code as well as a line in `channels`.

## 7. Contact guard (optional)

1. `column`, `onMet` and `except` are checked: `onMet` and every `except` entry must be contact steps. **(validated)**
2. `column` must be in the server setting `orch.guard.allowed-columns` (default `balance_maintained`, env `ORCH_GUARD_COLUMNS`). **(validated)**
3. List the first contact in `except`, so every customer is contacted once.
4. The flag is read fresh from the database just before each contact, not from the start snapshot, and only then. A customer who meets the condition while the workflow waits is thanked at the next contact.
5. The flag is set by another system. This service only reads it.

## 8. Runtime gotchas

1. Feedback is accepted (202) but **dropped** when no contact is waiting for it. Send it after the contact it answers.
2. The `unanswered` counter is shared by all paths. Any customer reaction resets it, and the limits count contacts with no answer in a row.
3. A WhatsApp delivery failure (for example outside the 24 hour window) is logged and the campaign carries on.
4. Edits are picked up within 10 seconds, or at once with `POST /workflow-definitions/reload`.

## 9. Before you save: checklist

- [ ] Unique `name`; `start` points to a real step
- [ ] Every step has `id` and `type`; every non-`end` step has `next` / `cases` / `default`
- [ ] No orphan steps; every path reaches an `end`
- [ ] Durations look like `30m`, `2h`, `1d`
- [ ] `switch` cases are exact, upper-case feedback types, and each leads somewhere
- [ ] `delay` has exactly one of `duration` / `fromVar` / `untilDay`; `fromVar` has `defaultDuration`
- [ ] `count` limits and `whenReached` are set, with a `reset` on every reaction path
- [ ] `untilDay` values fit inside `maxDays`
- [ ] No `dayLength` in the file
- [ ] All channels are in `channels`; `"*"` is quoted
- [ ] Reload (`POST /workflow-definitions/reload`) returns no `errors` for your file
- [ ] Start one customer and read `logs/conversation.log` to see the path it takes
