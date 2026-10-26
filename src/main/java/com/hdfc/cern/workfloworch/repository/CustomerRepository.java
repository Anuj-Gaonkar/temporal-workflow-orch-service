package com.hdfc.cern.workfloworch.repository;

import com.hdfc.cern.workfloworch.model.Customer;
import java.util.List;
import java.util.Optional;

/** Read-only access to orch.customers. The service never inserts or updates. */
public interface CustomerRepository {

	Optional<Customer> findById(String customerId);

	/** Every customer, ordered by id. */
	List<Customer> findAll();
}
