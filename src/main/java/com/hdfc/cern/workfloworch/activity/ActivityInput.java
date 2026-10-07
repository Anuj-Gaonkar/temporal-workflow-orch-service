package com.hdfc.cern.workfloworch.activity;

import com.hdfc.cern.workfloworch.customer.Customer;
import java.util.Map;

/** What every activity receives: the step it runs for, the customer snapshot and the step's params. */
public record ActivityInput(String stepId, Customer customer, Map<String, String> params) {
}
