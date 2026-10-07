package com.hdfc.cern.workfloworch.starter;

import org.springframework.http.HttpStatus;

/** A start request that was refused on purpose; carries the HTTP status and the reason code. */
public class StartRejectedException extends RuntimeException {

	public enum Reason {
		BAD_REQUEST(HttpStatus.BAD_REQUEST),
		DEFINITION_NOT_FOUND(HttpStatus.NOT_FOUND),
		CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND),
		ALREADY_RUNNING(HttpStatus.CONFLICT),
		WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND);

		private final HttpStatus status;

		Reason(HttpStatus status) {
			this.status = status;
		}

		public HttpStatus status() {
			return status;
		}
	}

	private final Reason reason;

	public StartRejectedException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
