# Documentation index

| Doc | What it is |
|---|---|
| [`yaml-guide.md`](yaml-guide.md) | The YAML definition format. A living doc; each feature notes the phase that added it. Start here if you want to write or change a definition. |
| [`yaml-rules.md`](yaml-rules.md) | The YAML rules on one page: what must hold, what is validated, runtime gotchas, and a checklist to run before saving a definition. |
| [`open-questions-20-day-workflow.md`](open-questions-20-day-workflow.md) | Unanswered business questions that decide how the 20-day ladder reacts to a response. |
| [`phase-2/01-coding.md`](phase-2/01-coding.md) | What Phase 2 changed: campaign window, feedback signal, new step types, hot reload, tests, and the **new log events**. |
| [`phase-2/02-decisions.md`](phase-2/02-decisions.md) | Decisions D34 onward (feedback via signal, Day 0/Day 1/15 days, run variables, `dayLength`, hot reload, ...). |
| [`phase-2/03-architecture.md`](phase-2/03-architecture.md) | What Phase 2 adds to the architecture: sequences, where the window is checked, failure behaviour. |
| [`phase-1/01-coding.md`](phase-1/01-coding.md) | Code walkthrough, request flow, determinism, how to add an activity, tests, and the **log events reference** the load-test scripts rely on. |
| [`phase-1/02-decisions.md`](phase-1/02-decisions.md) | Decision log (D1, D2, ...) including every deviation from the instruction guide. |
| [`phase-1/03-architecture.md`](phase-1/03-architecture.md) | Components, sequence diagrams, ports, failure and restart behaviour, where each latency is measured. |

Reading order for a newcomer: top-level `../README.md` (run it), then `phase-1/03-architecture.md`, `phase-2/03-architecture.md`, `yaml-rules.md`, `yaml-guide.md`, `phase-1/01-coding.md`, `phase-2/01-coding.md`; read the decision log when you wonder "why".
Each later phase adds a `phase-N/` folder; earlier phases stay readable as they were.

| [`phase-3/`](phase-3/03-architecture.md) | WhatsApp integration with `whatsapp-flows-service`: Day-1 Flow, Flow answer as feedback, reminder as WhatsApp text, dev/prod profiles. |
