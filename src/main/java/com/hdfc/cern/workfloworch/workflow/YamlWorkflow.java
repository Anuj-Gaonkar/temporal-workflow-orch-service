package com.hdfc.cern.workfloworch.workflow;

import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The one generic workflow. The definition (parsed from YAML before start) is the whole script and
 * the customer snapshot is the data it runs on.
 */
@WorkflowInterface
public interface YamlWorkflow {

	@WorkflowMethod
	void run(WorkflowDefinition definition, WorkflowInput input);

	/** Customer feedback for the contact that was just made; an {@code await-feedback} step consumes it. */
	@SignalMethod
	void feedback(FeedbackSignal feedback);
}
