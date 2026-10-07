package com.hdfc.cern.workfloworch.definition;

import java.util.Set;

/** Channels a contact step can use. The customers table only stores VOICE and WHATSAPP; SMS is for messages like the thank-you. */
public final class Channels {

	public static final String VOICE = "VOICE";
	public static final String WHATSAPP = "WHATSAPP";
	public static final String SMS = "SMS";
	public static final Set<String> ALL = Set.of(VOICE, WHATSAPP, SMS);

	private Channels() {
	}
}
