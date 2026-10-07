package com.hdfc.cern.workfloworch.definition;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a number plus unit s, m, h or d (90s, 30m, 2h, 3d). Pure, so workflow-safe. */
public final class DurationParser {

	private static final Pattern FORMAT = Pattern.compile("^(\\d+)([smhd])$");

	private DurationParser() {
	}

	public static Duration parse(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("duration is empty");
		}
		Matcher matcher = FORMAT.matcher(text);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("'" + text + "' is not a duration (use a number plus s, m, h or d, e.g. 90s, 30m, 2h, 3d)");
		}
		long value;
		try {
			value = Long.parseLong(matcher.group(1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + text + "' is too large to be a duration");
		}
		return switch (matcher.group(2)) {
			case "s" -> Duration.ofSeconds(value);
			case "m" -> Duration.ofMinutes(value);
			case "h" -> Duration.ofHours(value);
			default -> Duration.ofDays(value);
		};
	}
}
