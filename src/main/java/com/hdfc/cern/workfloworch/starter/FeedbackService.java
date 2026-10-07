package com.hdfc.cern.workfloworch.starter;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.api.FeedbackRequest;
import com.hdfc.cern.workfloworch.definition.Channels;
import com.hdfc.cern.workfloworch.definition.DurationParser;
import com.hdfc.cern.workfloworch.definition.FeedbackTypes;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import com.hdfc.cern.workfloworch.starter.StartRejectedException.Reason;
import com.hdfc.cern.workfloworch.workflow.FeedbackSignal;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Hands customer feedback to a running workflow as a Temporal signal. This is the only way feedback
 * enters the system: the service itself never invents it. An external system (or a person with
 * Postman, or the load-test simulator) calls the feedback endpoint.
 */
@Service
@Slf4j
public class FeedbackService {

	private final WorkflowClient workflowClient;

	public FeedbackService(WorkflowClient workflowClient) {
		this.workflowClient = workflowClient;
	}

	public FeedbackResult send(String workflowId, FeedbackRequest request) {
		long startedNanos = System.nanoTime();
		try {
			MDC.put("workflowId", workflowId);
			log.info(line(LogEvents.API_FEEDBACK_RECEIVED, "workflowId", workflowId, "type", request == null ? null : request.type(),
					"remindAfter", request == null ? null : request.remindAfter(), "channel", request == null ? null : request.channel()));
			FeedbackSignal signal = validate(request);

			try {
				workflowClient.newWorkflowStub(YamlWorkflow.class, workflowId).feedback(signal);
			} catch (WorkflowNotFoundException e) {
				throw new StartRejectedException(Reason.WORKFLOW_NOT_FOUND,
						"No running workflow '" + workflowId + "' (unknown or already finished)");
			}
			log.info(line(LogEvents.API_FEEDBACK_DONE, "workflowId", workflowId, "type", signal.type(),
					"totalMs", elapsedMs(startedNanos)));
			return new FeedbackResult(workflowId, signal.type(), signal.remindAfter(), signal.channel());
		} catch (StartRejectedException e) {
			log.info(line(LogEvents.API_FEEDBACK_REJECTED, "workflowId", workflowId, "reason", e.reason(),
					"totalMs", elapsedMs(startedNanos)));
			throw e;
		} finally {
			MDC.remove("workflowId");
		}
	}

	private static FeedbackSignal validate(FeedbackRequest request) {
		if (request == null || isBlank(request.type())) {
			throw bad("type is required (one of " + new java.util.TreeSet<>(FeedbackTypes.ALL) + ")");
		}
		String type = request.type().trim().toUpperCase();
		if (!FeedbackTypes.ALL.contains(type)) {
			throw bad("unknown type '" + request.type() + "' (use one of " + new java.util.TreeSet<>(FeedbackTypes.ALL) + ")");
		}
		String remindAfter = isBlank(request.remindAfter()) ? null : request.remindAfter().trim();
		String channel = isBlank(request.channel()) ? null : request.channel().trim().toUpperCase();
		if (!FeedbackTypes.REMIND_LATER.equals(type) && (remindAfter != null || channel != null)) {
			throw bad("remindAfter and channel are only allowed with type REMIND_LATER");
		}
		if (remindAfter != null) {
			try {
				DurationParser.parse(remindAfter);
			} catch (IllegalArgumentException e) {
				throw bad("bad remindAfter: " + e.getMessage());
			}
		}
		if (channel != null && !Channels.ALL.contains(channel)) {
			throw bad("unknown channel '" + request.channel() + "' (use one of " + new java.util.TreeSet<>(Channels.ALL) + ")");
		}
		return new FeedbackSignal(type, remindAfter, channel);
	}

	private static StartRejectedException bad(String message) {
		return new StartRejectedException(Reason.BAD_REQUEST, message);
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}
}
