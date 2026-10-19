package com.hdfc.cern.workfloworch.definition;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * Optional {@code contactGuard} block: before each contact (except the listed ones) the workflow reads a boolean
 * column of the customer's row. If it is true the contact is not sent and the workflow goes to {@code onMet} instead.
 */
@Data
public class ContactGuard {

	/** Columns the YAML may name when the server does not say otherwise ({@code orch.guard.allowed-columns}). */
	public static final String DEFAULT_COLUMNS = "balance_maintained";

	/** Boolean column of {@code orch.customers}; must be one of the allowed columns. */
	private String column;
	/** Contact step to go to when the column is true. It is never guarded itself, so there is no loop. */
	private String onMet;
	/** Contact steps that are never checked (for example the first contact and the normal thank-you). */
	private List<String> except;

	/** True if the contact step {@code stepId} is checked before it is sent. */
	public boolean appliesTo(String stepId) {
		return !stepId.equals(onMet) && (except == null || !except.contains(stepId));
	}

	/** Parses the comma-separated {@code orch.guard.allowed-columns} setting. */
	public static Set<String> parseColumns(String csv) {
		Set<String> columns = new LinkedHashSet<>();
		Arrays.stream(csv == null ? new String[0] : csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(columns::add);
		return columns;
	}
}
