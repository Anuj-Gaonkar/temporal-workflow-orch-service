package com.hdfc.cern.workfloworch.temporal.activity;

/** Test-only gateway that sends nothing. */
public class NoOpWhatsAppGateway implements WhatsAppGateway {

	@Override
	public void sendFlow(String waId, String flowKey, String bodyText, String customerId, String workflowId) {
	}

	@Override
	public void sendText(String waId, String message) {
	}
}
