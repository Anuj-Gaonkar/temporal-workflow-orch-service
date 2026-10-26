package com.hdfc.cern.workfloworch.entity;

import com.hdfc.cern.workfloworch.model.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One row of orch.customers. Read-only: the table is written by another system, so the service never inserts or updates it. */
@Entity
@Immutable
@Table(schema = "orch", name = "customers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CustomerEntity {

	@Id
	@Column(name = "customer_id")
	private String customerId;

	@Column(name = "name")
	private String name;

	@Column(name = "phone")
	private String phone;

	@Column(name = "wa_id")
	private String waId;

	@Column(name = "initial_channel")
	private String initialChannel;

	/** Set by another system when the customer keeps the required balance; a workflow's contactGuard reads it before each reminder. */
	@Column(name = "balance_maintained")
	private boolean balanceMaintained;

	/** The snapshot handed to the workflow; the entity itself never leaves the service layer. */
	public Customer toModel() {
		return new Customer(customerId, name, phone, waId, initialChannel);
	}
}
