package com.hdfc.cern.workfloworch.customer;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {

	private final JdbcClient jdbc;

	public JdbcCustomerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Customer> findById(String customerId) {
		return jdbc.sql("""
				SELECT customer_id, name, phone, wa_id, initial_channel
				FROM orch.customers
				WHERE customer_id = :id
				""")
				.param("id", customerId)
				.query((rs, rowNum) -> new Customer(
						rs.getString("customer_id"),
						rs.getString("name"),
						rs.getString("phone"),
						rs.getString("wa_id"),
						rs.getString("initial_channel")))
				.optional();
	}

	@Override
	public List<Customer> findAll() {
		return jdbc.sql("""
				SELECT customer_id, name, phone, wa_id, initial_channel
				FROM orch.customers
				ORDER BY customer_id
				""")
				.query((rs, rowNum) -> new Customer(
						rs.getString("customer_id"),
						rs.getString("name"),
						rs.getString("phone"),
						rs.getString("wa_id"),
						rs.getString("initial_channel")))
				.list();
	}
}
