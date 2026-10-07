package com.hdfc.cern.workfloworch.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ConversationLogTest {

	private static final long AT = ZonedDateTime.of(2026, 10, 1, 12, 10, 59, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();

	@Test
	void messageLineSaysWhatWasSentToWhomAndWhen() {
		assertThat(ConversationLog.contact("WHATSAPP", "C1001", "+910000000001", "reminder-contact", AT, "Hello there"))
				.isEqualTo("Sending WHATSAPP to C1001 (+910000000001) [reminder-contact] at 2026-10-01 12:10:59 IST >> Hello there");
	}

	@Test
	void voiceContactIsACall() {
		assertThat(ConversationLog.contact("VOICE", "C1001", "+910000000001", "first-contact", AT, "Script"))
				.startsWith("Calling VOICE to C1001");
	}

	@Test
	void feedbackLineShowsTimeAndChannelOfAReminderRequest() {
		assertThat(ConversationLog.feedback("C1001", AT, "REMIND_LATER", "2d", "WHATSAPP", false))
				.isEqualTo("Customer C1001 replied at 2026-10-01 12:10:59 IST >> REMIND_LATER (in 2d on WHATSAPP)");
		assertThat(ConversationLog.feedback("C1001", AT, "REMIND_LATER", null, "SMS", false)).endsWith(">> REMIND_LATER (on SMS)");
		assertThat(ConversationLog.feedback("C1001", AT, "POSITIVE", null, null, false)).endsWith(">> POSITIVE");
		assertThat(ConversationLog.feedback("C1001", AT, "NO_RESPONSE", null, null, true)).endsWith(">> NO_RESPONSE (no feedback within the wait)");
	}

	@Test
	void summaryLineShowsOutcomeDurationAndContacts() {
		assertThat(ConversationLog.ended("C1001", AT, "COMPLETED", 970_812, "VOICE, WHATSAPP, SMS", 3))
				.isEqualTo("Campaign for C1001 ended at 2026-10-01 12:10:59 IST >> COMPLETED after 16m, 3 contacts (VOICE, WHATSAPP, SMS)");
		assertThat(ConversationLog.ended("C1002", AT, "WINDOW_EXPIRED", 3_000, "", 0))
				.endsWith(">> WINDOW_EXPIRED after 3s, 0 contacts");
		assertThat(ConversationLog.ended("C1003", AT, "WINDOW_EXPIRED", 15L * 24 * 3600 * 1000, "VOICE", 1))
				.contains("after 15d 0h, 1 contact (VOICE)");
	}
}
