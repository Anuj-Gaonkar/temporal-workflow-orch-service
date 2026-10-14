# Open questions: 20 day outreach workflow

Workflow file: `workflow-definitions/outreach-20-day.yaml` (`OUTREACH_20_DAY`).
Status: first draft. It runs the fixed ladder below, and the questions here decide how a customer's response should change it.

| Day | Contact | Channel |
|---|---|---|
| 1 | Message 1, with the answer Flow | WhatsApp |
| 4 | Reminder, with the answer Flow | WhatsApp |
| 7 | Notification | Email |
| 10 | Reminder | SMS |
| 15 | Relationship manager call / outreach | Voice |
| 20 | Final notice | WhatsApp + SMS |

The fixed days apply to a customer who does not respond. Once a customer responds, the schedule should change based on the feedback. The questions below are about exactly how.

How the draft behaves today: a response only changes what happens right after it (a positive answer ends the campaign, a reminder request sends one reminder). After that the schedule returns to the fixed days. That does not match the intent above, so it will change once these are answered.

## How to answer

Write your answer under each question. Mark who answered and when. Where a question has options, pick one or write your own.

---

## Q1. After a customer asks to be reminded (REMIND_LATER) and the reminder gets no answer, what happens to the rest of the schedule?

Context: the customer picks a time and channel, for example "in 3 days on WhatsApp". We send one reminder at that time.

- [ ] **A. Resume the ladder, re-timed.** Carry on with the remaining contacts in the same order, with the same gaps counted from the reminder instead of fixed days. Nobody is skipped or doubled up. YAML change only.
- [ ] **B. Repeat the reminder, then stop.** Remind again at the customer's chosen time, up to 2 unanswered, then one final SMS and the campaign ends. The rest of the ladder is dropped. YAML change only.
- [ ] **C. Back to the original fixed days.** Return to Day 7, 10, 15, 20. A day the reminder already covered is skipped, so the customer does not get two messages that day. Needs a small Java change (a new `ifPassed` option on the day wait).
- [ ] **D. Something else:**

Answer:
Answered by / date:

---

## Q2. What should an INCONCLUSIVE answer do?

Context: the customer replied but nothing concrete came out of it.

- [ ] **A. One follow-up after 1 day, then the re-timed ladder.** The follow-up goes on the channel the customer replied on. After it, the remaining contacts continue re-timed from the follow-up.
- [ ] **B. Treat it like no answer.** Ignore the reply for scheduling and continue on the original fixed days.
- [ ] **C. Re-timed ladder with no follow-up.** The next contact stays in order but its gap is counted from the reply.
- [ ] **D. Something else:**

Answer:
Answered by / date:

---

## Q3. Can a responder's contacts run past Day 20?

Context: if the ladder is re-timed after a response, later contacts, including the Day 20 final notice, can fall after Day 20. The campaign window currently stops everything at the end of Day 20.

- [ ] **A. Extend the window to 30 days.** Responders still get every contact including the final notice. Non-responders still finish on Day 20.
- [ ] **B. Keep the Day 20 limit.** A responder's later contacts, possibly including the final notice, are dropped when the window closes.
- [ ] **C. Always send the final notice.** When the window is about to close, jump straight to the final notice (WhatsApp + SMS) and end.
- [ ] **D. Something else:**

Answer:
Answered by / date:

---

## Q4. Email on Day 7: is real delivery planned?

Context: `EMAIL` is a new channel in the system. Like SMS and voice, it is only written to the log today and nothing is sent. Customers also have no email address stored.

- [ ] Is a real email sender planned? Which service?
- [ ] Where will each customer's email address come from (a new column in `orch.customers`, another system)?
- [ ] Can a customer give feedback by email, and if so how does it reach the feedback API?

Answer:
Answered by / date:

---

## Q5. Voice and SMS delivery

Context: only WhatsApp messages are really delivered today. The Day 10 SMS, the Day 15 RM call, the Day 20 SMS and the thank-you SMS are logged only.

- [ ] Is real SMS delivery planned? Which provider?
- [ ] For the Day 15 RM call: does the system place the call, or does it create a task for a relationship manager? Who records the call outcome, and how does it reach the feedback API (`POSITIVE`, `REMIND_LATER`, `INCONCLUSIVE`, `NO_RESPONSE`)?

Answer:
Answered by / date:

---

## Q6. Wait for an answer after each contact

Context: in the draft, the wait for feedback after a contact lasts until the next scheduled contact (3 days after Days 1, 4, 7, 5 days after Days 10, 15, 1 day after Day 20). After a reminder the draft waits 1 day. An answer that arrives when no contact is waiting for it is dropped.

- [ ] Are these wait times right for each channel?
- [ ] Should a late answer (after the wait ends) still change the schedule?

Answer:
Answered by / date:

---

## Q7. Two smaller points

- [ ] **Reminder channel.** If the customer asks for a reminder but names no channel, the draft uses the channel of the last contact. Is that right, or should it be their stored channel (WhatsApp or voice)?
- [ ] **Thank-you.** A positive answer sends a thank-you SMS, as in the existing default workflow. Should the thank-you go on the channel the customer used instead?

Answer:
Answered by / date:

---

## Decision log

Fill this in once the questions are answered, then give it back so the workflow can be finalised.

| Question | Decision | Decided by | Date |
|---|---|---|---|
| Q1 | | | |
| Q2 | | | |
| Q3 | | | |
| Q4 | | | |
| Q5 | | | |
| Q6 | | | |
| Q7 | | | |
