package com.hdfc.cern.workfloworch.repository;

import com.hdfc.cern.workfloworch.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to orch.customers. Only the read methods are used: the service never inserts or updates. */
public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {
}
