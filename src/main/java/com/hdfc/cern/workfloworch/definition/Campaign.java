package com.hdfc.cern.workfloworch.definition;

import lombok.Data;

/**
 * The campaign window of a definition. Day 0 is the day the workflow is triggered; contacts happen
 * on Day 1 .. Day {@code maxDays} and never after. A definition without this block has no window.
 */
@Data
public class Campaign {

	/** How many days after Day 0 communication may still happen (15 = the end of Day 15 is the last moment). */
	private int maxDays;
	/** Zone that decides where a calendar day starts and ends, e.g. Asia/Kolkata. */
	private String timezone = "Asia/Kolkata";
	/** Hour of day (0-23) at which day-based waits wake up, e.g. 10 for 10:00. */
	private int sendHour = 10;
	/**
	 * Demo and load-test aid: when set (e.g. 2m), one "day" lasts this long instead of a calendar day.
	 * Day N starts at trigger + N x dayLength, the window ends after maxDays days of that length, and
	 * every whole-day duration (1d, 2d) is scaled the same way. Leave unset for real calendar days.
	 */
	private String dayLength;
}
