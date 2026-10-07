package com.hdfc.cern.workfloworch.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hdfc.cern.workfloworch.activity.OrchActivitiesImpl;
import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.customer.CustomerRepository;
import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.starter.StartRejectedException.Reason;
import com.hdfc.cern.workfloworch.workflow.OrchTaskQueue;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflowImpl;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowStarterServiceTest {

	/** Hand-written fake, test sources only. */
	private static final class FakeCustomers implements CustomerRepository {
		private final Map<String, Customer> rows = new java.util.TreeMap<>(Map.of(
				"C1001", new Customer("C1001", "Test One", "+910000000001", "910000000001", "VOICE"),
				"C1002", new Customer("C1002", "Test Two", "+910000000002", "910000000001", "WHATSAPP")));

		@Override
		public List<Customer> findAll() {
			return List.copyOf(rows.values());
		}

		@Override
		public Optional<Customer> findById(String customerId) {
			return Optional.ofNullable(rows.get(customerId));
		}
	}

	private TestWorkflowEnvironment env;
	private WorkflowStarterService starter;

	@BeforeEach
	void setUp() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		env = TestWorkflowEnvironment.newInstance();
		Worker worker = env.newWorker(OrchTaskQueue.NAME);
		worker.registerWorkflowImplementationTypes(YamlWorkflowImpl.class);
		worker.registerActivitiesImplementations(new OrchActivitiesImpl(new com.hdfc.cern.workfloworch.activity.NoOpWhatsAppGateway()));
		env.start();
		starter = new WorkflowStarterService(env.getWorkflowClient(), loader, new FakeCustomers());
	}

	@AfterEach
	void tearDown() {
		env.close();
	}

	@Test
	void startsWorkflowWithDefinitionAndCustomerId() {
		StartResult result = starter.start("C1001", "OUTREACH_DEFAULT");

		assertThat(result.workflowId()).isEqualTo("OUTREACH_DEFAULT-C1001");
		assertThat(result.runId()).isNotBlank();
		assertThat(result.initialChannel()).isEqualTo("VOICE");
	}

	@Test
	void unknownDefinitionIs404() {
		assertRejected("C1001", "NOPE", Reason.DEFINITION_NOT_FOUND, 404);
	}

	@Test
	void unknownCustomerIs404() {
		assertRejected("C0000", "OUTREACH_DEFAULT", Reason.CUSTOMER_NOT_FOUND, 404);
	}

	@Test
	void definitionIsCheckedBeforeCustomer() {
		assertRejected("C0000", "NOPE", Reason.DEFINITION_NOT_FOUND, 404);
	}

	@Test
	void blankFieldsAre400() {
		assertRejected(" ", "OUTREACH_DEFAULT", Reason.BAD_REQUEST, 400);
		assertRejected("C1001", "", Reason.BAD_REQUEST, 400);
		assertRejected(null, null, Reason.BAD_REQUEST, 400);
	}

	@Test
	void startingSameCustomerWhileRunningIs409ButOthersRunInParallel() {
		starter.start("C1001", "OUTREACH_DEFAULT");

		assertRejected("C1001", "OUTREACH_DEFAULT", Reason.ALREADY_RUNNING, 409);
		assertThat(starter.start("C1002", "OUTREACH_DEFAULT").workflowId()).isEqualTo("OUTREACH_DEFAULT-C1002");
	}

	@Test
	void startAllStartsEveryCustomer() {
		StartAllResult result = starter.startAll("OUTREACH_DEFAULT");

		assertThat(result.total()).isEqualTo(2);
		assertThat(result.started()).isEqualTo(2);
		assertThat(result.alreadyRunning()).isZero();
		assertThat(result.failed()).isZero();
		assertThat(result.results()).extracting(StartAllResult.Item::workflowId)
				.containsExactly("OUTREACH_DEFAULT-C1001", "OUTREACH_DEFAULT-C1002");
		assertThat(result.results()).extracting(StartAllResult.Item::status).containsOnly("STARTED");
	}

	@Test
	void startAllSkipsRunningCustomersAndStartsTheRest() {
		starter.start("C1001", "OUTREACH_DEFAULT");

		StartAllResult result = starter.startAll("OUTREACH_DEFAULT");

		assertThat(result.started()).isEqualTo(1);
		assertThat(result.alreadyRunning()).isEqualTo(1);
		assertThat(result.results()).extracting(StartAllResult.Item::status)
				.containsExactly("ALREADY_RUNNING", "STARTED");
	}

	@Test
	void startAllRejectsBlankAndUnknownDefinition() {
		assertThatThrownBy(() -> starter.startAll(" "))
				.isInstanceOfSatisfying(StartRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_REQUEST));
		assertThatThrownBy(() -> starter.startAll("NOPE"))
				.isInstanceOfSatisfying(StartRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DEFINITION_NOT_FOUND));
	}

	private void assertRejected(String customerId, String definition, Reason reason, int status) {
		assertThatThrownBy(() -> starter.start(customerId, definition))
				.isInstanceOfSatisfying(StartRejectedException.class, e -> {
					assertThat(e.reason()).isEqualTo(reason);
					assertThat(e.reason().status().value()).isEqualTo(status);
				});
	}
}
