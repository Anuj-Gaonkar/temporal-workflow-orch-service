package com.hdfc.cern.workfloworch.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LogEventsTest {

	@Test
	void buildsEventThenKeyValuePairsSeparatedBySingleSpaces() {
		String line = LogEvents.line(LogEvents.API_START_DONE, "workflowId", "OUTREACH_DEFAULT-C1001", "lookupMs", 4, "totalMs", 45L);

		assertThat(line).isEqualTo("event=API_START_DONE workflowId=OUTREACH_DEFAULT-C1001 lookupMs=4 totalMs=45");
	}

	@Test
	void skipsNullValuesAndQuotesValuesWithSpaces() {
		String line = LogEvents.line(LogEvents.ACT_FAILED, "error", "boom happened", "attempt", null, "stepId", "voice-call");

		assertThat(line).isEqualTo("event=ACT_FAILED error=\"boom happened\" stepId=voice-call");
	}

	@Test
	void rejectsOddNumberOfFields() {
		assertThatThrownBy(() -> LogEvents.line(LogEvents.WF_STARTED, "workflowId"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
