package com.hdfc.cern.workfloworch.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls whatsapp-flows-service's internal API (plain HTTP, no auth). */
@Component
public class HttpWhatsAppGateway implements WhatsAppGateway {

	private final RestClient restClient;

	public HttpWhatsAppGateway(@Value("${orch.whatsapp.base-url}") String baseUrl) {
		this.restClient = RestClient.builder().baseUrl(baseUrl).build();
	}

	@Override
	public void sendFlow(String waId, String flowKey, String bodyText, String customerId, String workflowId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("waId", waId);
		body.put("flowKey", flowKey);
		body.put("bodyText", bodyText);
		body.put("customerId", customerId);
		body.put("workflowId", workflowId);
		post("/internal/flows/send", body);
	}

	@Override
	public void sendText(String waId, String message) {
		post("/internal/messages/text", Map.of("waId", waId, "message", message));
	}

	private void post(String uri, Map<String, Object> body) {
		restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
	}
}
