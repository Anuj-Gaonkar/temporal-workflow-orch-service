package com.hdfc.cern.workfloworch.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Switched on by {@code orch.logging.communication-only: true}. Then every log call is dropped except
 * the conversation lines (logger {@link ConversationLog#LOGGER}) and warnings and errors, so real
 * problems never disappear. Applies to the console and to both log files. Off by default.
 * The {@code event=...} timing lines are INFO and are hidden too: the load-test report needs the flag off.
 */
public class CommunicationOnlyFilter extends TurboFilter {

	private boolean enabled;

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@Override
	public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
		if (!enabled || level == null || level.isGreaterOrEqual(Level.WARN) || ConversationLog.LOGGER.equals(logger.getName())) {
			return FilterReply.NEUTRAL;
		}
		return FilterReply.DENY;
	}
}
