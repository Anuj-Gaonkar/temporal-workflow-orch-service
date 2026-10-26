package com.hdfc.cern.workfloworch.temporal.activity;

/**
 * How the orchestrator reaches a customer on WhatsApp. The real implementation calls
 * whatsapp-flows-service; tests plug in a recording one. A failure is reported by throwing; the
 * activity decides what to do with it (it logs and carries on).
 */
public interface WhatsAppGateway {

	/** Sends the customer a WhatsApp Flow. {@code workflowId} is stored with the Flow session so the answers come back to this workflow. */
	void sendFlow(String waId, String flowKey, String bodyText, String customerId, String workflowId);

	/** Sends a plain WhatsApp text message. */
	void sendText(String waId, String message);
}
