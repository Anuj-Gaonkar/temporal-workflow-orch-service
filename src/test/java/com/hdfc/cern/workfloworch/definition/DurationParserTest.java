package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurationParserTest {

	@Test
	void parsesSecondsMinutesHoursDays() {
		assertThat(DurationParser.parse("90s")).isEqualTo(Duration.ofSeconds(90));
		assertThat(DurationParser.parse("30m")).isEqualTo(Duration.ofMinutes(30));
		assertThat(DurationParser.parse("2h")).isEqualTo(Duration.ofHours(2));
		assertThat(DurationParser.parse("3d")).isEqualTo(Duration.ofDays(3));
	}

	@ParameterizedTest
	@ValueSource(strings = { "30", "1w", "", " ", "m", "-5m", "30 m", "30M", "PT30M" })
	void rejectsAnythingElse(String text) {
		assertThatThrownBy(() -> DurationParser.parse(text)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNull() {
		assertThatThrownBy(() -> DurationParser.parse(null)).isInstanceOf(IllegalArgumentException.class);
	}
}
