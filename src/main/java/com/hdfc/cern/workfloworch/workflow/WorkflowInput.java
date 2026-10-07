package com.hdfc.cern.workfloworch.workflow;

import com.hdfc.cern.workfloworch.customer.Customer;

/**
 * Customer snapshot read from the database when the workflow was started. The workflow never
 * touches the database (determinism); Phase 2 can add step results next to it.
 */
public record WorkflowInput(Customer customer) {
}
