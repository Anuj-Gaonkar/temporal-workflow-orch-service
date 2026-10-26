package com.hdfc.cern.workfloworch.model;

/** One row of orch.customers. {@code waId} is the WhatsApp number (digits only, e.g. 910000000001). Also the customer snapshot handed to the workflow as input. */
public record Customer(String customerId, String name, String phone, String waId, String initialChannel) {
}
