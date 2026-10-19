package com.hdfc.cern.workfloworch.customer;

import com.hdfc.cern.workfloworch.definition.ContactGuard;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads a flag column of {@code orch.customers}. The column name goes into the SQL text (it cannot be a bound
 * parameter), so only names from {@code orch.guard.allowed-columns} that look like plain identifiers are ever used.
 */
@Repository
public class JdbcCustomerFlags implements CustomerFlags {

	private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

	private final JdbcClient jdbc;
	private final Set<String> allowed;

	public JdbcCustomerFlags(JdbcClient jdbc, @Value("${orch.guard.allowed-columns:" + ContactGuard.DEFAULT_COLUMNS + "}") String allowedColumns) {
		this.jdbc = jdbc;
		this.allowed = ContactGuard.parseColumns(allowedColumns);
		this.allowed.forEach(column -> {
			if (!IDENTIFIER.matcher(column).matches()) {
				throw new IllegalStateException("orch.guard.allowed-columns: '" + column + "' is not a plain lower-case column name");
			}
		});
	}

	@Override
	public boolean isSet(String customerId, String column) {
		if (column == null || !allowed.contains(column)) {
			throw new IllegalArgumentException("Column '" + column + "' is not in orch.guard.allowed-columns " + allowed);
		}
		return jdbc.sql("SELECT " + column + " FROM orch.customers WHERE customer_id = :id")
				.param("id", customerId)
				.query((rs, rowNum) -> rs.getBoolean(1))
				.optional()
				.orElse(false);
	}
}
