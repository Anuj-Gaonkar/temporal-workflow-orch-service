package com.hdfc.cern.workfloworch.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks one parsed definition and returns every problem found (empty list = valid). */
public final class DefinitionValidator {

	private static final Set<String> TYPES = Set.of(
			StepDefinition.SWITCH, StepDefinition.ACTIVITY, StepDefinition.DELAY, StepDefinition.CONTACT,
			StepDefinition.AWAIT_FEEDBACK, StepDefinition.COUNT, StepDefinition.END);

	private DefinitionValidator() {
	}

	public static List<String> validate(WorkflowDefinition def, Set<String> knownActivities) {
		List<String> errors = new ArrayList<>();
		if (isBlank(def.getName())) {
			errors.add("'name' is required");
		}
		if (isBlank(def.getStart())) {
			errors.add("'start' is required");
		}
		if (def.getCampaign() != null) {
			checkCampaign(def.getCampaign(), errors);
		}
		if (def.getSteps() == null || def.getSteps().isEmpty()) {
			errors.add("'steps' is required and must not be empty");
			return errors;
		}

		Map<String, StepDefinition> byId = new HashMap<>();
		for (StepDefinition step : def.getSteps()) {
			if (step == null || isBlank(step.getId())) {
				errors.add("every step needs an 'id'");
			} else if (byId.put(step.getId(), step) != null) {
				errors.add("duplicate step id '" + step.getId() + "'");
			}
		}
		if (!isBlank(def.getStart()) && !byId.containsKey(def.getStart())) {
			errors.add("start step '" + def.getStart() + "' does not exist");
		}

		for (StepDefinition step : def.getSteps()) {
			if (step != null && !isBlank(step.getId())) {
				checkStep(step, def, byId, knownActivities, errors);
			}
		}

		if (errors.isEmpty()) {
			checkReachable(def, byId, errors);
		}
		return errors;
	}

	private static void checkCampaign(Campaign campaign, List<String> errors) {
		if (campaign.getMaxDays() < 1) {
			errors.add("campaign: 'maxDays' must be at least 1");
		}
		try {
			java.time.ZoneId.of(campaign.getTimezone());
		} catch (RuntimeException e) {
			errors.add("campaign: unknown 'timezone' '" + campaign.getTimezone() + "'");
		}
		if (campaign.getSendHour() < 0 || campaign.getSendHour() > 23) {
			errors.add("campaign: 'sendHour' must be between 0 and 23");
		}
		if (campaign.getDayLength() != null) {
			try {
				DurationParser.parse(campaign.getDayLength());
			} catch (IllegalArgumentException e) {
				errors.add("campaign: bad 'dayLength': " + e.getMessage());
			}
		}
	}

	private static void checkStep(StepDefinition step, WorkflowDefinition def, Map<String, StepDefinition> byId,
			Set<String> knownActivities, List<String> errors) {
		String where = "step '" + step.getId() + "': ";
		if (isBlank(step.getType())) {
			errors.add(where + "'type' is required");
			return;
		}
		if (!TYPES.contains(step.getType())) {
			errors.add(where + "unknown step type '" + step.getType() + "' (use switch, activity, delay, contact, await-feedback, count or end)");
			return;
		}
		switch (step.getType()) {
			case StepDefinition.SWITCH -> {
				if (isBlank(step.getOn())) {
					errors.add(where + "switch needs 'on'");
				} else if (!RunPaths.SUPPORTED.contains(step.getOn())) {
					errors.add(where + "unsupported 'on' path '" + step.getOn() + "' (use one of "
							+ new java.util.TreeSet<>(RunPaths.SUPPORTED) + ")");
				}
				boolean hasCases = step.getCases() != null && !step.getCases().isEmpty();
				if (!hasCases && isBlank(step.getDefaultNext())) {
					errors.add(where + "switch needs 'cases' or 'default'");
				}
				if (hasCases && RunPaths.FEEDBACK_TYPE.equals(step.getOn())) {
					step.getCases().keySet().stream().filter(v -> !FeedbackTypes.ALL.contains(v)).forEach(v ->
							errors.add(where + "case '" + v + "' is not a feedback type (use "
									+ new java.util.TreeSet<>(FeedbackTypes.ALL) + ")"));
				}
				if (hasCases) {
					step.getCases().forEach((value, target) -> checkTarget(where + "case '" + value + "'", target, byId, errors));
				}
				if (!isBlank(step.getDefaultNext())) {
					checkTarget(where + "default", step.getDefaultNext(), byId, errors);
				}
			}
			case StepDefinition.ACTIVITY -> {
				if (isBlank(step.getActivity())) {
					errors.add(where + "activity step needs 'activity'");
				} else if (!knownActivities.contains(step.getActivity())) {
					errors.add(where + "unknown activity '" + step.getActivity() + "' (known: "
							+ new java.util.TreeSet<>(knownActivities) + ")");
				}
				checkNext(where, step, byId, errors);
			}
			case StepDefinition.DELAY -> {
				checkDelay(where, step, def, errors);
				checkNext(where, step, byId, errors);
			}
			case StepDefinition.CONTACT -> {
				checkChannel(where, "channel", step.getChannel(), true, errors);
				checkChannel(where, "defaultChannel", step.getDefaultChannel(), false, errors);
				if (isBlank(step.getText())) {
					errors.add(where + "contact needs 'text'");
				}
				if (step.getFlow() != null && step.getFlow().isBlank()) {
					errors.add(where + "'flow' must not be blank");
				}
				checkNext(where, step, byId, errors);
			}
			case StepDefinition.COUNT -> {
				if (isBlank(step.getCounter())) {
					errors.add(where + "count needs 'counter' (a name)");
				}
				if (Boolean.TRUE.equals(step.getReset())) {
					if (step.getLimit() != null || step.getWhenReached() != null) {
						errors.add(where + "a count with 'reset: true' must not have 'limit' or 'whenReached'");
					}
				} else if (step.getLimit() == null || step.getLimit() < 1) {
					errors.add(where + "count needs 'reset: true' or a 'limit' of at least 1");
				} else if (isBlank(step.getWhenReached())) {
					errors.add(where + "count with a 'limit' needs 'whenReached' (the step to go to when the limit is reached)");
				} else {
					checkTarget(where + "whenReached", step.getWhenReached(), byId, errors);
				}
				checkNext(where, step, byId, errors);
			}
			case StepDefinition.AWAIT_FEEDBACK -> {
				try {
					DurationParser.parse(step.getTimeout());
				} catch (IllegalArgumentException e) {
					errors.add(where + "bad timeout: " + e.getMessage());
				}
				checkNext(where, step, byId, errors);
			}
			default -> {
				// end: nothing to check
			}
		}
	}

	/** A delay has exactly one of: duration, fromVar (with defaultDuration), untilDay. */
	private static void checkDelay(String where, StepDefinition step, WorkflowDefinition def, List<String> errors) {
		int kinds = (step.getDuration() != null ? 1 : 0) + (step.getFromVar() != null ? 1 : 0)
				+ (step.getUntilDay() != null ? 1 : 0);
		if (kinds != 1) {
			errors.add(where + "delay needs exactly one of 'duration', 'fromVar' or 'untilDay'");
			return;
		}
		if (step.getDuration() != null) {
			checkDuration(where + "bad duration: ", step.getDuration(), errors);
		} else if (step.getFromVar() != null) {
			if (!RunPaths.DURATION_PATHS.contains(step.getFromVar())) {
				errors.add(where + "unsupported 'fromVar' '" + step.getFromVar() + "' (use one of "
						+ new java.util.TreeSet<>(RunPaths.DURATION_PATHS) + ")");
			}
			if (step.getDefaultDuration() == null) {
				errors.add(where + "'fromVar' needs a 'defaultDuration' for when the customer gave none");
			} else {
				checkDuration(where + "bad defaultDuration: ", step.getDefaultDuration(), errors);
			}
		} else {
			if (step.getUntilDay() < 1) {
				errors.add(where + "'untilDay' must be at least 1 (Day 0 is the trigger day)");
			}
			if (def.getCampaign() == null) {
				errors.add(where + "'untilDay' needs a 'campaign' block");
			} else if (step.getUntilDay() > def.getCampaign().getMaxDays()) {
				errors.add(where + "'untilDay' " + step.getUntilDay() + " is after the campaign window (maxDays "
						+ def.getCampaign().getMaxDays() + ")");
			}
		}
		if (step.getFromVar() == null && step.getDefaultDuration() != null) {
			errors.add(where + "'defaultDuration' only makes sense with 'fromVar'");
		}
	}

	private static void checkDuration(String prefix, String text, List<String> errors) {
		try {
			DurationParser.parse(text);
		} catch (IllegalArgumentException e) {
			errors.add(prefix + e.getMessage());
		}
	}

	/** A channel is a literal (VOICE, WHATSAPP, SMS) or a path that holds one (customer.initialChannel, reminder.channel, ...). */
	private static void checkChannel(String where, String field, String value, boolean required, List<String> errors) {
		if (isBlank(value)) {
			if (required) {
				errors.add(where + "contact needs '" + field + "'");
			}
			return;
		}
		if (!Channels.ALL.contains(value) && !RunPaths.CHANNEL_PATHS.contains(value)) {
			errors.add(where + "bad '" + field + "' '" + value + "' (use " + new java.util.TreeSet<>(Channels.ALL)
					+ " or a path: " + new java.util.TreeSet<>(RunPaths.CHANNEL_PATHS) + ")");
		}
	}

	private static void checkNext(String where, StepDefinition step, Map<String, StepDefinition> byId, List<String> errors) {
		if (isBlank(step.getNext())) {
			errors.add(where + "'next' is required (only 'end' steps have no way forward)");
		} else {
			checkTarget(where + "next", step.getNext(), byId, errors);
		}
	}

	private static void checkTarget(String what, String target, Map<String, StepDefinition> byId, List<String> errors) {
		if (isBlank(target)) {
			errors.add(what + " has an empty target");
		} else if (!byId.containsKey(target)) {
			errors.add(what + " points to unknown step '" + target + "'");
		}
	}

	/** A step nobody can reach is almost certainly a typo, so it is an error, not a warning. */
	private static void checkReachable(WorkflowDefinition def, Map<String, StepDefinition> byId, List<String> errors) {
		Set<String> seen = new HashSet<>();
		java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
		todo.add(def.getStart());
		while (!todo.isEmpty()) {
			String id = todo.poll();
			if (!seen.add(id)) {
				continue;
			}
			StepDefinition step = byId.get(id);
			if (step.getCases() != null) {
				todo.addAll(step.getCases().values());
			}
			for (String target : new String[] { step.getDefaultNext(), step.getNext(), step.getWhenReached() }) {
				if (!isBlank(target)) {
					todo.add(target);
				}
			}
		}
		for (StepDefinition step : def.getSteps()) {
			if (!seen.contains(step.getId())) {
				errors.add("step '" + step.getId() + "' is not reachable from start '" + def.getStart() + "'");
			}
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
