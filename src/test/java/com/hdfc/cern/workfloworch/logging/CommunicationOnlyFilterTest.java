package com.hdfc.cern.workfloworch.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

class CommunicationOnlyFilterTest {

	private final LoggerContext context = new LoggerContext();

	private FilterReply decide(boolean enabled, String logger, Level level) {
		CommunicationOnlyFilter filter = new CommunicationOnlyFilter();
		filter.setEnabled(enabled);
		Logger l = context.getLogger(logger);
		return filter.decide(null, l, level, "x", null, null);
	}

	@Test
	void offByDefaultEverythingPasses() {
		assertThat(decide(false, "com.hdfc.cern.workfloworch.api.OrchController", Level.INFO)).isEqualTo(FilterReply.NEUTRAL);
		assertThat(decide(false, "org.apache.catalina", Level.DEBUG)).isEqualTo(FilterReply.NEUTRAL);
	}

	@Test
	void whenOnOnlyConversationAndProblemsPass() {
		assertThat(decide(true, ConversationLog.LOGGER, Level.INFO)).isEqualTo(FilterReply.NEUTRAL);
		assertThat(decide(true, "com.hdfc.cern.workfloworch.workflow.YamlWorkflowImpl", Level.WARN)).isEqualTo(FilterReply.NEUTRAL);
		assertThat(decide(true, "org.springframework.boot", Level.ERROR)).isEqualTo(FilterReply.NEUTRAL);
	}

	@Test
	void whenOnTimingLinesAndFrameworkInfoAreHidden() {
		assertThat(decide(true, "com.hdfc.cern.workfloworch.workflow.YamlWorkflowImpl", Level.INFO)).isEqualTo(FilterReply.DENY);
		assertThat(decide(true, "org.apache.catalina.core.StandardService", Level.INFO)).isEqualTo(FilterReply.DENY);
		assertThat(decide(true, "io.temporal.internal.worker", Level.DEBUG)).isEqualTo(FilterReply.DENY);
	}
}
