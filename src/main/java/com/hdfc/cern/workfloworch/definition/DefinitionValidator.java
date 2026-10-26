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

	private static final java.util.regex.Pattern CHANNEL_CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

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

		checkChannels(def, errors);
		checkChannelDefaults(def, byId, errors);
		checkChannelRules(def, errors);
		checkContactGuard(def, byId, errors);
		boolean usesAuto = def.getSteps().stream().anyMatch(s -> s != null && Channels.AUTO.equals(s.getChannel()));
		if (!usesAuto && (def.getChannelDefaults() != null || def.getChannelRules() != null)) {
			errors.add("'channelDefaults' / 'channelRules' are defined but no contact step uses 'channel: AUTO'");
		}

		if (errors.isEmpty()) {
			checkReachable(def, byId, errors);
		}
		return errors;
	}

	/** contactGuard: a supported column, an existing contact step to go to, and existing contact steps to leave unchecked. */
	private static void checkContactGuard(WorkflowDefinition def, Map<String, StepDefinition> byId,
			List<String> errors) {
		ContactGuard guard = def.getContactGuard();
		if (guard == null) {
			return;
		}
		if (isBlank(guard.getColumn())) {
			errors.add("contactGuard: 'column' is required");
		} else if (!ContactGuard.SUPPORTED_COLUMNS.contains(guard.getColumn())) {
			errors.add("contactGuard: column '" + guard.getColumn() + "' is not supported (supported: "
					+ new java.util.TreeSet<>(ContactGuard.SUPPORTED_COLUMNS) + ")");
		}
		if (isBlank(guard.getOnMet())) {
			errors.add("contactGuard: 'onMet' is required (the contact step to go to when the flag is true)");
		} else if (!isContact(byId.get(guard.getOnMet()))) {
			errors.add("contactGuard: onMet '" + guard.getOnMet() + "' is not a contact step");
		}
		if (guard.getExcept() != null) {
			guard.getExcept().forEach(stepId -> {
				if (!isContact(byId.get(stepId))) {
					errors.add("contactGuard: except '" + stepId + "' is not a contact step");
				}
			});
		}
	}

	private static boolean isContact(StepDefinition step) {
		return step != null && StepDefinition.CONTACT.equals(step.getType());
	}

	/** channels: each code is a plain upper-case name and each flip target is another declared channel. */
	private static void checkChannels(WorkflowDefinition def, List<String> errors) {
		if (def.getChannels() == null) {
			return;
		}
		if (def.getChannels().isEmpty()) {
			errors.add("'channels' must list at least one channel (or be left out to get the built-in ones)");
			return;
		}
		Set<String> known = def.knownChannels();
		def.getChannels().forEach((code, spec) -> {
			String where = "channels '" + code + "': ";
			if (code == null || !CHANNEL_CODE.matcher(code).matches()) {
				errors.add(where + "a channel code is upper-case letters, digits and '_' (for example CHAT_BOT)");
			} else if (Channels.AUTO.equals(code) || Channels.DB.equals(code)) {
				errors.add(where + "'" + code + "' is reserved and cannot be a channel");
			}
			String flip = spec == null ? null : spec.getFlip();
			if (flip != null && !known.contains(flip)) {
				errors.add(where + "flip '" + flip + "' is not a declared channel (use " + new java.util.TreeSet<>(known) + ")");
			} else if (flip != null && flip.equals(code)) {
				errors.add(where + "must not flip to itself");
			}
		});
	}

	/** channelDefaults: DB channel (or "*" for every other declared channel) to (contact step id to DB or a channel). */
	private static void checkChannelDefaults(WorkflowDefinition def, Map<String, StepDefinition> byId, List<String> errors) {
		if (def.getChannelDefaults() == null) {
			return;
		}
		Set<String> known = def.knownChannels();
		def.getChannelDefaults().forEach((dbChannel, perStep) -> {
			String where = "channelDefaults '" + dbChannel + "': ";
			if (!Channels.FALLBACK_BLOCK.equals(dbChannel) && !known.contains(dbChannel)) {
				errors.add("channelDefaults: '" + dbChannel + "' is not a channel declared for this definition (use "
						+ new java.util.TreeSet<>(known) + " or \"" + Channels.FALLBACK_BLOCK + "\")");
			}
			if (perStep == null || perStep.isEmpty()) {
				errors.add(where + "needs at least one contact step");
				return;
			}
			perStep.forEach((stepId, channel) -> {
				StepDefinition step = byId.get(stepId);
				if (step == null || !StepDefinition.CONTACT.equals(step.getType())) {
					errors.add(where + "'" + stepId + "' is not a contact step");
				} else if (!Channels.AUTO.equals(step.getChannel())) {
					errors.add(where + "step '" + stepId + "' must use 'channel: AUTO' to take a default");
				}
				if (!Channels.DB.equals(channel) && !known.contains(channel)) {
					errors.add(where + "bad channel '" + channel + "' for '" + stepId + "' (use DB or "
							+ new java.util.TreeSet<>(known) + ")");
				}
			});
		});
	}

	/** channelRules: feedback type to action; flip: channel to channel. */
	private static void checkChannelRules(WorkflowDefinition def, List<String> errors) {
		ChannelRules rules = def.getChannelRules();
		if (rules == null) {
			return;
		}
		Set<String> known = def.knownChannels();
		if (rules.getOnFeedback() != null) {
			rules.getOnFeedback().forEach((type, action) -> {
				String where = "channelRules.onFeedback '" + type + "': ";
				if (!FeedbackTypes.ALL.contains(type)) {
					errors.add(where + "not a feedback type (use " + new java.util.TreeSet<>(FeedbackTypes.ALL) + ")");
				} else if (action == null || !(ChannelActions.ALL.contains(action) || known.contains(action))) {
					errors.add(where + "bad action '" + action + "' (use " + new java.util.TreeSet<>(ChannelActions.ALL)
							+ " or a channel " + new java.util.TreeSet<>(known) + ")");
				} else if (ChannelActions.DEFAULT.equals(action) && FeedbackTypes.REMIND_LATER.equals(type)) {
					errors.add(where + "DEFAULT is not allowed (the next contact is a reminder, not a scheduled one)");
				} else if (ChannelActions.REQUESTED.equals(action) && !FeedbackTypes.REMIND_LATER.equals(type)) {
					errors.add(where + "REQUESTED only makes sense for REMIND_LATER (only a reminder request names a channel)");
				}
			});
		}
		if (rules.getFlip() != null) {
			rules.getFlip().forEach((from, to) -> {
				if (!known.contains(from) || to == null || !known.contains(to)) {
					errors.add("channelRules.flip: '" + from + ": " + to + "' must map a channel to a channel (use "
							+ new java.util.TreeSet<>(known) + ")");
				} else if (from.equals(to)) {
					errors.add("channelRules.flip: '" + from + "' must not flip to itself");
				}
			});
		}
		boolean usesFlip = rules.getOnFeedback() != null && rules.getOnFeedback().containsValue(ChannelActions.FLIP);
		if (usesFlip) {
			for (String channel : new java.util.TreeSet<>(known)) {
				boolean overridden = rules.getFlip() != null && rules.getFlip().get(channel) != null;
				if (!overridden && def.flipOf(channel) == null) {
					errors.add("channelRules uses FLIP but channel '" + channel
							+ "' has no flip target (set 'flip' under channels or an entry in channelRules.flip)");
				}
			}
		}
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
				if (!Channels.AUTO.equals(step.getChannel())) {
					checkChannel(where, "channel", step.getChannel(), true, def.knownChannels(), errors);
				}
				checkChannel(where, "defaultChannel", step.getDefaultChannel(), false, def.knownChannels(), errors);
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

	/** A channel is a declared literal (VOICE, WHATSAPP, SMS, ...) or a path that holds one (customer.initialChannel, reminder.channel, ...). */
	private static void checkChannel(String where, String field, String value, boolean required, Set<String> known,
			List<String> errors) {
		if (isBlank(value)) {
			if (required) {
				errors.add(where + "contact needs '" + field + "'");
			}
			return;
		}
		if (!known.contains(value) && !RunPaths.CHANNEL_PATHS.contains(value)) {
			errors.add(where + "bad '" + field + "' '" + value + "' (use " + new java.util.TreeSet<>(known)
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
		if (def.getContactGuard() != null && !isBlank(def.getContactGuard().getOnMet())) {
			todo.add(def.getContactGuard().getOnMet()); // reached through the guard, not through a next
		}
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
