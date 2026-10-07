package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CampaignClockTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static long ist(int month, int day, int hour, int minute) {
		return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, IST).toInstant().toEpochMilli();
	}

	private static Campaign campaign(String dayLength) {
		Campaign c = new Campaign();
		c.setMaxDays(15);
		c.setTimezone("Asia/Kolkata");
		c.setSendHour(10);
		c.setDayLength(dayLength);
		return c;
	}

	@Test
	void dayOneIsTheNextCalendarDayAtSendHour() {
		CampaignClock clock = new CampaignClock(campaign(null), ist(10, 1, 15, 30)); // Day 0 = 1 Oct, afternoon

		assertThat(clock.dayStart(1)).isEqualTo(ist(10, 2, 10, 0));
		assertThat(clock.dayStart(15)).isEqualTo(ist(10, 16, 10, 0));
	}

	@Test
	void windowEndsAtTheEndOfDayFifteen() {
		CampaignClock clock = new CampaignClock(campaign(null), ist(10, 1, 15, 30));

		assertThat(clock.expired(ist(10, 16, 23, 59))).isFalse();
		assertThat(clock.expired(ist(10, 17, 0, 0))).isTrue();
	}

	@Test
	void triggerJustAfterMidnightStillCountsAsThatCalendarDay() {
		CampaignClock clock = new CampaignClock(campaign(null), ist(10, 1, 0, 5));

		assertThat(clock.dayStart(1)).isEqualTo(ist(10, 2, 10, 0));
		assertThat(clock.deadline()).isEqualTo(ist(10, 17, 0, 0));
	}

	@Test
	void wholeDayWaitsLandOnSendHourAndOtherWaitsAreExact() {
		CampaignClock clock = new CampaignClock(campaign(null), ist(10, 1, 15, 30));
		long now = ist(10, 2, 11, 45);

		assertThat(clock.target(now, Duration.ofDays(2))).isEqualTo(ist(10, 4, 10, 0));
		assertThat(clock.target(now, Duration.ofHours(3))).isEqualTo(now + Duration.ofHours(3).toMillis());
	}

	@Test
	void dayLengthModeScalesDaysFromTheTrigger() {
		long start = ist(10, 1, 15, 30);
		CampaignClock clock = new CampaignClock(campaign("2m"), start);

		assertThat(clock.dayStart(1)).isEqualTo(start + Duration.ofMinutes(2).toMillis());
		assertThat(clock.deadline()).isEqualTo(start + Duration.ofMinutes(32).toMillis());
		assertThat(clock.target(start, Duration.ofDays(3))).isEqualTo(start + Duration.ofMinutes(6).toMillis());
		assertThat(clock.target(start, Duration.ofSeconds(30))).isEqualTo(start + 30_000);
	}

	@Test
	void scaleShortensWholeDaysOnlyInDemoMode() {
		CampaignClock demo = new CampaignClock(campaign("3m"), ist(10, 1, 15, 30));
		CampaignClock real = new CampaignClock(campaign(null), ist(10, 1, 15, 30));

		assertThat(demo.scale(Duration.ofDays(1))).isEqualTo(Duration.ofMinutes(3));
		assertThat(demo.scale(Duration.ofDays(2))).isEqualTo(Duration.ofMinutes(6));
		assertThat(demo.scale(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5));
		assertThat(real.scale(Duration.ofDays(1))).isEqualTo(Duration.ofDays(1));
	}
}
