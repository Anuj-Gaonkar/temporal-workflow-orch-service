package com.hdfc.cern.workfloworch.api;

/** A read endpoint asked for something that does not exist. */
public class NotFoundException extends RuntimeException {

	private final String code;

	public NotFoundException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
