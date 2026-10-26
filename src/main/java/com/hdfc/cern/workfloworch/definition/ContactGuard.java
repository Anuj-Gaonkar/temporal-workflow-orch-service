package com.hdfc.cern.workfloworch.definition;

import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * Optional {@code contactGuard} block: before each contact (except the listed ones) the workflow reads a boolean
 * column of the customer's row. If it is true the contact is not sent and the workflow goes to {@code onMet} instead.
 */
@Data
public class ContactGuard {

	/** The flag columns of {@code orch.customers} the YAML may name. Each one has a matching field on {@code CustomerEntity} (see {@code CustomerServiceImpl}). */
	public static final Set<String> SUPPORTED_COLUMNS = Set.of("balance_maintained");

	/** Boolean column of {@code orch.customers}; must be one of {@link #SUPPORTED_COLUMNS}. */
	private String column;
	/** Contact step to go to when the column is true. It is never guarded itself, so there is no loop. */
	private String onMet;
	/** Contact steps that are never checked (for example the first contact and the normal thank-you). */
	private List<String> except;

	/** True if the contact step {@code stepId} is checked before it is sent. */
	public boolean appliesTo(String stepId) {
		return !stepId.equals(onMet) && (except == null || !except.contains(stepId));
	}
}
