package com.hdfc.cern.workfloworch.definition;

import java.util.Set;

/** Channels a contact step can use. The customers table only stores VOICE and WHATSAPP; SMS and EMAIL are for messages like the thank-you or a notification (logged, not delivered yet). */
public final class Channels {

	public static final String VOICE = "VOICE";
	public static final String WHATSAPP = "WHATSAPP";
	public static final String SMS = "SMS";
	public static final String EMAIL = "EMAIL";
	public static final Set<String> ALL = Set.of(VOICE, WHATSAPP, SMS, EMAIL);

	private Channels() {
	}
}
