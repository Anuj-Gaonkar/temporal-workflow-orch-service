package com.hdfc.cern.workfloworch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hdfc.cern.workfloworch.definition.ContactGuard;
import com.hdfc.cern.workfloworch.entity.CustomerEntity;
import com.hdfc.cern.workfloworch.model.Customer;
import com.hdfc.cern.workfloworch.repository.CustomerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class CustomerServiceImplTest {

	private final CustomerRepository repository = mock(CustomerRepository.class);
	private final CustomerServiceImpl service = new CustomerServiceImpl(repository);

	private static CustomerEntity entity(String id, boolean balanceMaintained) {
		return new CustomerEntity(id, "Test " + id, "+910000000001", "910000000001", "WHATSAPP", balanceMaintained);
	}

	@Test
	void findByIdMapsTheEntityToTheWorkflowSnapshot() {
		when(repository.findById("C1001")).thenReturn(Optional.of(entity("C1001", false)));

		assertThat(service.findById("C1001"))
				.contains(new Customer("C1001", "Test C1001", "+910000000001", "910000000001", "WHATSAPP"));
	}

	@Test
	void findByIdIsEmptyForAnUnknownCustomer() {
		when(repository.findById("nobody")).thenReturn(Optional.empty());

		assertThat(service.findById("nobody")).isEmpty();
	}

	@Test
	void findAllAsksForCustomerIdOrderAndMapsEveryRow() {
		when(repository.findAll(any(Sort.class))).thenReturn(List.of(entity("C1001", false), entity("C1002", true)));

		assertThat(service.findAll()).extracting(Customer::customerId).containsExactly("C1001", "C1002");
	}

	@Test
	void balanceMaintainedFlagIsReadFromTheEntity() {
		when(repository.findById("C1001")).thenReturn(Optional.of(entity("C1001", true)));
		when(repository.findById("C1002")).thenReturn(Optional.of(entity("C1002", false)));

		assertThat(service.isFlagSet("C1001", "balance_maintained")).isTrue();
		assertThat(service.isFlagSet("C1002", "balance_maintained")).isFalse();
	}

	@Test
	void everyColumnTheYamlMayNameCanBeRead() {
		when(repository.findById("C1001")).thenReturn(Optional.of(entity("C1001", true)));

		ContactGuard.SUPPORTED_COLUMNS.forEach(column -> assertThat(service.isFlagSet("C1001", column)).isTrue());
	}

	@Test
	void flagIsFalseWhenTheCustomerHasNoRow() {
		when(repository.findById("nobody")).thenReturn(Optional.empty());

		assertThat(service.isFlagSet("nobody", "balance_maintained")).isFalse();
	}

	@Test
	void aColumnThatIsNotAFlagIsRejected() {
		assertThatThrownBy(() -> service.isFlagSet("C1001", "phone"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone");
		assertThatThrownBy(() -> service.isFlagSet("C1001", null)).isInstanceOf(IllegalArgumentException.class);
	}
}
