package com.hdfc.cern.workfloworch.service;

import com.hdfc.cern.workfloworch.model.Customer;
import java.util.List;
import java.util.Optional;

/** Read-only view of the customers, handed out as {@link Customer} snapshots. */
public interface CustomerService {

	Optional<Customer> findById(String customerId);

	/** Every customer, ordered by id. */
	List<Customer> findAll();

	/**
	 * Reads one boolean flag of a customer's row, fresh from the database (the {@link Customer} in the workflow is only a
	 * snapshot from the start).
	 *
	 * @return the value of {@code column}; false when the customer has no row
	 * @throws IllegalArgumentException if {@code column} is not a flag the service knows
	 */
	boolean isFlagSet(String customerId, String column);
}
