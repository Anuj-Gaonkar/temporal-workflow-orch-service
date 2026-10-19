package com.hdfc.cern.workfloworch.definition;

import java.util.Set;

/**
 * The built-in channels, used by a definition that has no {@code channels:} block. A definition can declare more
 * (for example CHAT_BOT) under {@code channels:}; see {@link WorkflowDefinition#knownChannels()}. SMS and EMAIL are for
 * messages like the thank-you or a notification (logged, not delivered yet).
 */
public final class Channels {

	public static final String VOICE = "VOICE";
	public static final String WHATSAPP = "WHATSAPP";
	public static final String SMS = "SMS";
	public static final String EMAIL = "EMAIL";
	public static final Set<String> ALL = Set.of(VOICE, WHATSAPP, SMS, EMAIL);
	/** Not a channel: a contact {@code channel} of AUTO is decided by {@code channelDefaults} and {@code channelRules}. */
	public static final String AUTO = "AUTO";
	/** Not a channel: in {@code channelDefaults} it means the customer's channel from the database. */
	public static final String DB = "DB";
	/** Not a channel: the {@code channelDefaults} block used by every declared channel that has no block of its own. Quote it in YAML ({@code "*"}). */
	public static final String FALLBACK_BLOCK = "*";

	private Channels() {
	}
}
