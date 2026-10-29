package com.github.ozayduman.specificationbuilder;

import com.github.ozayduman.specificationbuilder.dto.*;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.PageRequestBuilder;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.SortDTO;
import com.github.ozayduman.specificationbuilder.dto.operation.MultiValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.NoValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.RangeValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.SingleValueOperation;
import com.github.ozayduman.specificationbuilder.entity.*;
import com.github.ozayduman.specificationbuilder.repository.EmployeeRepository;
import lombok.val;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.*;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static com.github.ozayduman.specificationbuilder.entity.SocialSecurityType.A;
import static java.util.Comparator.comparing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests exercising {@link SpecificationBuilder}, {@link EmployeeRepository},
 * {@link PageRequestBuilder} and {@link PageResultDTO} against a real, H2-backed
 * Spring Data JPA persistence layer (including joins to {@link Phone} and {@link SocialSecurity}).
 *
 * <p>Only the JPA Criteria API objects ({@link Root}, {@link Path}, {@link CriteriaBuilder},
 * {@link CriteriaQuery}, {@link Predicate}) are mocked, for builder-only unit-style scenarios that
 * do not require the database. Every other component (repository, database, entities, mapping)
 * is real. Each test method relies on {@code @DataJpaTest}'s implicit transactional rollback so
 * that no manual database cleanup is required between tests.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {TestConfiguration.class})
@Transactional
class SpecificationBuilderIntegrationTest {

    @Autowired
    EmployeeRepository employeeRepository;

    @Test
    void shouldBuildASpecificationWithDTO() {
        assertAll("build",
                () -> assertThrows(RuntimeException.class, () -> SpecificationBuilder.<Employee>of(null).build()),
                () -> assertNotNull(SpecificationBuilder.<Employee>of(new CriteriaDTO()).build())
        );
    }

    @Test
    void whenOperationInvalidThenAnIllegalStateExceptionThrown() {
        final var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new NoValueOperation("name", Operator.EQ)));
        assertThrows(IllegalArgumentException.class, () -> SpecificationBuilder.<Employee>of(criteriaDTO).build());
    }

    @Test
    @Disabled
    void whenEQPramsProvidedThenEQSpecCreated() {

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        final String valueOfName = "Ozay";
        var operation = new SingleValueOperation("name", Operator.EQ, valueOfName);
        criteriaDTO.setOperations(List.of(operation));


        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
        Root<Employee> rootMock = mock(Root.class);
        Path<String> namePathMock = mock(Path.class);
        when(rootMock.get(Employee_.name)).thenReturn(namePathMock);
        CriteriaQuery<?> criteriaQueryMock = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilderMock = mock(CriteriaBuilder.class);
        Predicate namePredicateMock = mock(Predicate.class);
        when(criteriaBuilderMock.equal(Mockito.any(), Mockito.any())).thenReturn(namePredicateMock);

        final Predicate predicate = specification.toPredicate(rootMock, criteriaQueryMock, criteriaBuilderMock);

        verify(rootMock, times(1)).get(Employee_.name);
        verifyNoMoreInteractions(rootMock);
        verify(criteriaBuilderMock, times(1)).equal(namePathMock, valueOfName);
        verify(criteriaBuilderMock, never()).conjunction();
        verifyNoInteractions(criteriaQueryMock);
    }

    @Test
    void whenDTOPropertyNotBoundThenExceptionThrown() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        final String valueOfLastName = "Duman";
        var operation = new SingleValueOperation("surname", Operator.EQ, valueOfLastName);
        criteriaDTO.setOperations(List.of(operation));


        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        Root<Employee> rootMock = mock(Root.class);
        CriteriaQuery<?> criteriaQueryMock = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilderMock = mock(CriteriaBuilder.class);

        assertThrows(RuntimeException.class,
                () -> specification.toPredicate(rootMock, criteriaQueryMock, criteriaBuilderMock));
    }

    @Test
    void whenMultiEQPramsProvidedThenEQSpecCreated() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        final String valueOfName = "Ozay";
        final String valueOfLastName = "Duman";
        var operationForName = new SingleValueOperation("name", Operator.EQ, valueOfName);
        var operationForSurname = new SingleValueOperation("surname", Operator.EQ, valueOfLastName);
        criteriaDTO.setOperations(List.of(operationForName, operationForSurname));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .build();
        Root<Employee> rootMock = mock(Root.class);
        Path<Object> namePathMock = mock(Path.class);
        Path<Object> surnamePathMock = mock(Path.class);
        when(rootMock.get(Employee_.name.getName())).thenReturn(namePathMock);
        when(rootMock.get(Employee_.surname.getName())).thenReturn(surnamePathMock);
        CriteriaQuery<?> criteriaQueryMock = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilderMock = mock(CriteriaBuilder.class);
        Predicate namePredicateMock = mock(Predicate.class);
        Predicate surnamePredicateMock = mock(Predicate.class);
        when(criteriaBuilderMock.equal(Mockito.any(), eq(valueOfName))).thenReturn(namePredicateMock);
        when(criteriaBuilderMock.equal(Mockito.any(), eq(valueOfLastName))).thenReturn(surnamePredicateMock);

        final Predicate predicate = specification.toPredicate(rootMock, criteriaQueryMock, criteriaBuilderMock);

        verify(rootMock, times(1)).get(Employee_.name.getName());
        verify(rootMock, times(1)).get(Employee_.surname.getName());
        verifyNoMoreInteractions(rootMock);
        verify(criteriaBuilderMock, times(1)).equal(namePathMock, valueOfName);
        verify(criteriaBuilderMock, times(1)).equal(surnamePathMock, valueOfLastName);
        verify(criteriaBuilderMock, never()).conjunction();
    }

    @Test
    void whenDTOPropertyBoundViaJoinThenJoinSpecExecuted() {

        var employee = new Employee("özay", "duman", "ozay.duman@gmail.com", LocalDate.now().minusYears(20L));
        final var phoneBusiness = Phone.builder()
                .number("5555")
                .phoneType(PhoneType.BUSSINES)
                .build();
        final var phoneHome = Phone.builder()
                .number("55555")
                .phoneType(PhoneType.HOME)
                .build();
        employee.addPhone(phoneBusiness);
        employee.addPhone(phoneHome);
        employeeRepository.save(employee);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        var operation = new SingleValueOperation("phoneNumber", Operator.EQ, "5555");
        criteriaDTO.setOperations(List.of(operation));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        var customerFromDB = employeeRepository.findOne(specification)
                .orElseThrow(() -> new NoSuchElementException());

        assertNotNull(customerFromDB);
        assertEquals("özay", customerFromDB.getName());
        assertEquals("duman", customerFromDB.getSurname());
        assertEquals("ozay.duman@gmail.com", customerFromDB.getEmail());
    }

    @Test
    void whenSortDTOSuppliedThenResultSortedByGivenSortInfo() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setOperations(List.of(
                new SingleValueOperation("employeeBirthDate", Operator.GT, LocalDate.of(2000, Month.JANUARY, 1))));
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("employeeBirthDate", SortDTO.Direction.ASC),
                new SortDTO("employeeName", SortDTO.Direction.ASC)
        });

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind("employeeName", Employee_.name)
                .bind("employeeSurname", Employee_.surname)
                .bind("employeeEmail", Employee_.email)
                .bind("employeeBirthDate", Employee_.birthDate)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("employeeBirthDate", Employee_.birthDate)
                .bindSort("employeeName", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);

        PageResultDTO pageResultDTO = PageResultDTO.from(page, e -> {
            EmployeeResponseDTO dto = new EmployeeResponseDTO();
            dto.setName(e.getName());
            dto.setLastName(e.getSurname());
            dto.setEmail(e.getEmail());
            dto.setBirthDate(e.getBirthDate());
            return dto;
        });

        assertNotNull(pageResultDTO);
        assertEquals(0, pageRequestDTO.getPage());
        assertEquals(10, pageRequestDTO.getSize());

        final var originalContent = (List<EmployeeResponseDTO>) pageResultDTO.getContent();
        final var employeeResponseDTOS = new ArrayList<>(originalContent);
        final var sortedEmployeeDTOs = employeeResponseDTOS.stream()
                .sorted(
                        comparing(EmployeeResponseDTO::getBirthDate)
                                .thenComparing(EmployeeResponseDTO::getName))
                .collect(Collectors.toList());
        for (int i = 0; i < originalContent.size(); i++) {
            assertEquals(originalContent.get(i), sortedEmployeeDTOs.get(i));
        }
    }

    @Test
    void whenLikeOperatorGivenThenResultRestrictedByLikeProperty() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "s")));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .build();
        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        val hasNamesNotContainingValue = allEmployees.stream()
                .anyMatch(employee -> !employee.getName().contains("s"));
        assertFalse(hasNamesNotContainingValue);
    }

    @Test
    void whenNotLikeOperatorGivenThenResultRestrictedByNotLikeProperty() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_LIKE, "s")));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .build();
        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        val hasNamesContainingValue = allEmployees.stream()
                .anyMatch(employee -> employee.getName().contains("s"));
        assertFalse(hasNamesContainingValue);
    }

    @Test
    void whenOperatorGivenThenResultRestrictedByNotLikeProperty() {
        final var socialSecurity = SocialSecurity.of(A, "AAA");
        final var employee = Employee.of("özay", "duman", "o.d@test.com",
                LocalDate.of(1900, Month.JANUARY, 1), socialSecurity);
        employeeRepository.save(employee);
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_LIKE, "s")));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .bindJoin(Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();
        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        val hasNamesContainingValue = allEmployees.stream()
                .anyMatch(e -> e.getName().contains("s"));
        assertFalse(hasNamesContainingValue);
    }

    // ---------------------------------------------------------------------
    // Additional scenarios extending the reference test class coverage
    // ---------------------------------------------------------------------

    @Test
    void whenGTOperatorGivenThenOnlyEmployeesBornAfterCutoffDateReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final LocalDate cutoff = LocalDate.of(2000, Month.JANUARY, 1);
        final long expectedCount = employees.stream()
                .filter(e -> e.getBirthDate().isAfter(cutoff))
                .count();

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("birthDate", Operator.GT, cutoff)));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        val result = employeeRepository.findAll(specification);

        assertEquals(expectedCount, result.size());
        assertTrue(result.stream().allMatch(e -> e.getBirthDate().isAfter(cutoff)));
    }

    @Test
    void whenBetweenOperatorGivenThenResultsRestrictedWithinDateRange() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final LocalDate low = LocalDate.of(1990, Month.JANUARY, 1);
        final LocalDate high = LocalDate.of(2000, Month.DECEMBER, 31);
        final long expectedCount = employees.stream()
                .filter(e -> !e.getBirthDate().isBefore(low) && !e.getBirthDate().isAfter(high))
                .count();

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new RangeValueOperation("birthDate", Operator.BT, new RangeDTO(low, high))));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        val result = employeeRepository.findAll(specification);

        assertEquals(expectedCount, result.size());
        assertTrue(result.stream().allMatch(e -> !e.getBirthDate().isBefore(low) && !e.getBirthDate().isAfter(high)));
    }

    @Test
    void whenInOperatorGivenThenResultsMatchOnlyProvidedNames() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final Set<String> namesToInclude = Set.of("Doloritas", "April", "Blinny");

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new MultiValueOperation("name", Operator.IN, namesToInclude.toArray())));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        val result = employeeRepository.findAll(specification);

        assertEquals(namesToInclude.size(), result.size());
        assertTrue(result.stream().allMatch(e -> namesToInclude.contains(e.getName())));
    }

    @Test
    void whenNotInOperatorGivenThenResultsExcludeProvidedNames() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        final Set<String> namesToExclude = Set.of("Doloritas", "April", "Blinny");

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new MultiValueOperation("name", Operator.NOT_IN, namesToExclude.toArray())));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        val result = employeeRepository.findAll(specification);

        assertEquals(employees.size() - namesToExclude.size(), result.size());
        assertTrue(result.stream().noneMatch(e -> namesToExclude.contains(e.getName())));
    }

    @Test
    void whenNoRecordMatchesCriteriaThenEmptyResultReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("name", Operator.EQ, "NonExistentName-XYZ")));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        val result = employeeRepository.findAll(specification);

        assertTrue(result.isEmpty());
    }

    @Test
    void whenPageRequestHasNoSortFieldsThenResultsReturnedUnsorted() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);

        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(5);

        val specification = SpecificationBuilder.<Employee>of(pageRequestDTO).build();
        val pageRequest = PageRequestBuilder.of(pageRequestDTO).build();

        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);

        assertNotNull(page);
        assertEquals(5, page.getSize());
        assertEquals(employees.size(), page.getTotalElements());
    }

    @Test
    void whenSortPropertyNotBoundViaBindSortThenRuntimeExceptionThrown() {
        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(5);
        pageRequestDTO.setSortFields(new SortDTO[]{new SortDTO("unboundProperty", SortDTO.Direction.ASC)});

        assertThrows(RuntimeException.class, () -> PageRequestBuilder.of(pageRequestDTO).build());
    }

    @Test
    void shouldReturnCorrectPageMetadataWhenPaginatingResults() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);

        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{new SortDTO("employeeName", SortDTO.Direction.ASC)});

        val specification = SpecificationBuilder.<Employee>of(pageRequestDTO).build();
        val pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("employeeName", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);
        PageResultDTO pageResultDTO = PageResultDTO.from(page, e -> e.getName());

        final int expectedTotalPages = (int) Math.ceil(employees.size() / 10.0);
        assertEquals(employees.size(), pageResultDTO.getTotalElements());
        assertEquals(expectedTotalPages, pageResultDTO.getTotalPages());
        assertEquals(0, pageResultDTO.getCurrentPage());
        assertEquals(10, pageResultDTO.getSize());
        assertEquals(10, pageResultDTO.getContent().size());
    }

    @Test
    void whenMultipleJoinBoundEmployeesQueriedThenPhoneAndSocialSecurityFiltersBothApply() {
        final var socialSecurity = SocialSecurity.of(A, "BBB");
        final var employee = Employee.of("Ayşe", "Yilmaz", "ayse.yilmaz@test.com",
                LocalDate.of(1995, Month.MARCH, 5), socialSecurity);
        final var phone = Phone.builder()
                .number("12345")
                .phoneType(PhoneType.HOME)
                .build();
        employee.addPhone(phone);
        employeeRepository.save(employee);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "12345"),
                new SingleValueOperation("ssType", Operator.EQ, A)));
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .bindJoin("ssType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        val result = employeeRepository.findOne(specification)
                .orElseThrow(NoSuchElementException::new);

        assertEquals("Ayşe", result.getName());
        assertEquals("Yilmaz", result.getSurname());
    }
}s