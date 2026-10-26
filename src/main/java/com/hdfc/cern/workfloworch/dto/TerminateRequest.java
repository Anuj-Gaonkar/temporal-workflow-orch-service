package com.hdfc.cern.workfloworch.dto;

/** Optional body of the terminate calls. {@code reason} is kept in the workflow's Temporal history; {@code definition} limits terminate-all. */
public record TerminateRequest(String reason, String definition) {
}
