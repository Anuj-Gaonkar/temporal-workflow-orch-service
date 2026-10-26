package com.hdfc.cern.workfloworch.service;

import com.hdfc.cern.workfloworch.model.Customer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Hand-written fake, test sources only: customers from a map, every flag false. */
public class FakeCustomerService implements CustomerService {

	public final Map<String, Customer> rows = new TreeMap<>();

	public FakeCustomerService() {
	}

	public FakeCustomerService(Map<String, Customer> initial) {
		rows.putAll(initial);
	}

	@Override
	public Optional<Customer> findById(String customerId) {
		return Optional.ofNullable(rows.get(customerId));
	}

	@Override
	public List<Customer> findAll() {
		return List.copyOf(rows.values());
	}

	@Override
	public boolean isFlagSet(String customerId, String column) {
		return false;
	}
}
