package com.hdfc.cern.workfloworch.service;

import com.hdfc.cern.workfloworch.entity.CustomerEntity;
import com.hdfc.cern.workfloworch.model.Customer;
import com.hdfc.cern.workfloworch.repository.CustomerRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

	/** The flag columns of orch.customers a workflow's contactGuard may read, and where each one lives on the entity. */
	private static final Map<String, Predicate<CustomerEntity>> FLAGS = Map.of(
			"balance_maintained", CustomerEntity::isBalanceMaintained);

	private final CustomerRepository customers;

	public CustomerServiceImpl(CustomerRepository customers) {
		this.customers = customers;
	}

	@Override
	public Optional<Customer> findById(String customerId) {
		return customers.findById(customerId).map(CustomerEntity::toModel);
	}

	@Override
	public List<Customer> findAll() {
		return customers.findAll(Sort.by("customerId")).stream().map(CustomerEntity::toModel).toList();
	}

	@Override
	public boolean isFlagSet(String customerId, String column) {
		Predicate<CustomerEntity> flag = column == null ? null : FLAGS.get(column);
		if (flag == null) {
			throw new IllegalArgumentException("Column '" + column + "' is not a customer flag; supported: " + FLAGS.keySet());
		}
		return customers.findById(customerId).map(flag::test).orElse(false);
	}
}
