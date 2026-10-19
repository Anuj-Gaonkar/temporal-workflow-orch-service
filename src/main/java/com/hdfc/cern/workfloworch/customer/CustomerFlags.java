package com.hdfc.cern.workfloworch.customer;

/** Reads one boolean column of a customer's row, fresh from the database (the {@link Customer} in the workflow is only a snapshot from the start). */
@FunctionalInterface
public interface CustomerFlags {

	/** The value of {@code column} for the customer; false when the customer has no row or the value is null. */
	boolean isSet(String customerId, String column);
}
