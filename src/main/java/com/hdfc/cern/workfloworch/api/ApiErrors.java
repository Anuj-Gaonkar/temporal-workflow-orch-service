package com.hdfc.cern.workfloworch.api;

import com.hdfc.cern.workfloworch.starter.StartRejectedException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns refusals into a small JSON body: status, code and a human-readable message. */
@RestControllerAdvice
public class ApiErrors {

	@ExceptionHandler(StartRejectedException.class)
	public ResponseEntity<Map<String, Object>> rejected(StartRejectedException e) {
		return body(e.reason().status(), e.reason().name(), e.getMessage());
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
		return body(HttpStatus.NOT_FOUND, e.code(), e.getMessage());
	}

	@ExceptionHandler(StatusRuntimeException.class)
	public ResponseEntity<Map<String, Object>> temporal(StatusRuntimeException e) {
		if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
			return body(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", "No such workflow");
		}
		return body(HttpStatus.BAD_GATEWAY, "TEMPORAL_ERROR", e.getStatus().getCode().name());
	}

	private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status.value());
		body.put("code", code);
		body.put("message", message);
		return ResponseEntity.status(status).body(body);
	}
}
