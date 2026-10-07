package com.hdfc.cern.workfloworch.definition;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Day arithmetic for one campaign run. Day 0 is the day of {@code startMillis}; Day N is N days
 * later at the send hour; the window ends (exclusive) at the start of Day maxDays + 1, so the whole
 * of Day maxDays is still usable. Pure and deterministic (only the arguments and the definition),
 * so the workflow may use it.
 *
 * <p>With {@code dayLength} set (demo mode) Day N simply starts at {@code startMillis + N x dayLength}.
 */
public final class CampaignClock {

	private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();

	private final ZoneId zone;
	private final int sendHour;
	private final int maxDays;
	private final long dayLengthMillis; // 0 = real calendar days
	private final long startMillis;
	private final LocalDate day0;

	public CampaignClock(Campaign campaign, long startMillis) {
		this.zone = ZoneId.of(campaign.getTimezone());
		this.sendHour = campaign.getSendHour();
		this.maxDays = campaign.getMaxDays();
		this.dayLengthMillis = campaign.getDayLength() == null ? 0 : DurationParser.parse(campaign.getDayLength()).toMillis();
		this.startMillis = startMillis;
		this.day0 = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate();
	}

	/** When Day {@code n} starts (the moment day-based contacts happen). */
	public long dayStart(int n) {
		if (dayLengthMillis > 0) {
			return startMillis + n * dayLengthMillis;
		}
		return at(day0.plusDays(n), sendHour);
	}

	/** First moment at which nothing may happen any more. */
	public long deadline() {
		if (dayLengthMillis > 0) {
			return startMillis + (maxDays + 1L) * dayLengthMillis;
		}
		return at(day0.plusDays(maxDays + 1L), 0);
	}

	public boolean expired(long millis) {
		return millis >= deadline();
	}

	/**
	 * When something that should happen "after {@code wait}" from {@code now} actually happens. A wait of
	 * whole days (1d, 2d) lands on the send hour of the target day; any other wait (30m, 2h) is exact.
	 */
	public long target(long now, Duration wait) {
		long millis = wait.toMillis();
		if (millis <= 0 || millis % DAY_MILLIS != 0) {
			return now + millis;
		}
		long days = millis / DAY_MILLIS;
		if (dayLengthMillis > 0) {
			return now + days * dayLengthMillis;
		}
		LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
		long target = at(today.plusDays(days), sendHour);
		return target > now ? target : at(today.plusDays(days + 1), sendHour);
	}

	/**
	 * A duration as it really lasts in this run: in demo mode (dayLength set) a whole number of days is
	 * shortened to that many dayLengths; anything else, and real calendar mode, is unchanged.
	 */
	public Duration scale(Duration duration) {
		long millis = duration.toMillis();
		if (dayLengthMillis > 0 && millis > 0 && millis % DAY_MILLIS == 0) {
			return Duration.ofMillis(millis / DAY_MILLIS * dayLengthMillis);
		}
		return duration;
	}

	private long at(LocalDate date, int hour) {
		return ZonedDateTime.of(date, java.time.LocalTime.of(hour, 0), zone).toInstant().toEpochMilli();
	}
}
