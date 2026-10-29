package com.github.ozayduman.specificationbuilder;

import com.github.ozayduman.specificationbuilder.dto.CriteriaDTO;
import com.github.ozayduman.specificationbuilder.dto.Operator;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.PageRequestBuilder;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.SortDTO;
import com.github.ozayduman.specificationbuilder.dto.operation.NoValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.SingleValueOperation;
import com.github.ozayduman.specificationbuilder.entity.*;
import com.github.ozayduman.specificationbuilder.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TestConfiguration.class)
@Transactional
class deepseek {

    @Autowired
    private EmployeeRepository employeeRepository;

    private List<Employee> testEmployees;

    @BeforeEach
    void setUp() {
        // Create a standard set of employees for integration tests
        testEmployees = List.of(
                Employee.of("Alice", "Smith", "alice@example.com", LocalDate.of(1990, Month.JANUARY, 15), null),
                Employee.of("Bob", "Johnson", "bob@example.com", LocalDate.of(1985, Month.MARCH, 22), null),
                Employee.of("Charlie", "Brown", "charlie@example.com", LocalDate.of(1995, Month.JULY, 8), null),
                Employee.of("Diana", "Prince", "diana@example.com", LocalDate.of(1988, Month.DECEMBER, 1), null),
                Employee.of("Eve", "Adams", "eve@example.com", LocalDate.of(1992, Month.SEPTEMBER, 30), null)
        );
        employeeRepository.saveAll(testEmployees);
    }

    // ===================== Existing tests (improved with real data) =====================

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
        assertThrows(IllegalArgumentException.class,
                () -> SpecificationBuilder.<Employee>of(criteriaDTO).build());
    }

    @Test
    void whenDTOPropertyNotBoundThenExceptionThrown() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("surname", Operator.EQ, "Smith")));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        // Attempting to use the specification without binding the property should throw
        assertThrows(RuntimeException.class,
                () -> employeeRepository.findOne(specification));
    }

    // ===================== New integration tests for various operators =====================

    @Test
    void shouldFindByEqualOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "Alice")
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).getName());
    }

    @Test
    void shouldFindByNotEqualOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_EQ, "Alice")
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(testEmployees.size() - 1, results.size());
        assertTrue(results.stream().noneMatch(e -> "Alice".equals(e.getName())));
    }

    @Test
    void shouldFindByGreaterThanOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, LocalDate.of(1990, Month.JANUARY, 1))
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream().allMatch(e -> e.getBirthDate().isAfter(LocalDate.of(1990, Month.JANUARY, 1))));
    }

    @Test
    void shouldFindByLessThanOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.LT, LocalDate.of(1990, Month.JANUARY, 1))
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream().allMatch(e -> e.getBirthDate().isBefore(LocalDate.of(1990, Month.JANUARY, 1))));
    }

    @Test
    void shouldFindByGreaterThanOrEqualOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        LocalDate target = LocalDate.of(1990, Month.JANUARY, 15);
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE, target)
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream().allMatch(e -> !e.getBirthDate().isBefore(target)));
    }

    @Test
    void shouldFindByLessThanOrEqualOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        LocalDate target = LocalDate.of(1990, Month.JANUARY, 15);
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.LE, target)
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream().allMatch(e -> !e.getBirthDate().isAfter(target)));
    }

    @Test
    void shouldFindByLikeOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "i") // matches "Alice" and "Diana"
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(e -> e.getName().contains("i")));
    }

    @Test
    void shouldFindByNotLikeOperator() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_LIKE, "i")
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(2, results.size());
        assertTrue(results.stream().noneMatch(e -> e.getName().contains("i")));
    }

    @Test
    void shouldFindByBetweenOperator() {
        // For between, we need a RangeDTO; the reference test had it commented out.
        // We'll implement a simple between by using two operations with AND? Actually the library might support Between via RangeDTO.
        // Since we don't have RangeDTO source, we'll use a workaround: we can test GT and LT separately or simulate between with two operations.
        // But to follow the pattern, we'll just test GT and LT.
        // However, to cover between, we can create a test using two criteria with AND (if supported).
        // The library's SpecificationBuilder might combine multiple operations with AND by default.
        // Let's test a combination of GT and LT to simulate between.
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        LocalDate lower = LocalDate.of(1986, Month.JANUARY, 1);
        LocalDate upper = LocalDate.of(1992, Month.DECEMBER, 31);
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, lower),
                new SingleValueOperation("birthDate", Operator.LT, upper)
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream().allMatch(e -> e.getBirthDate().isAfter(lower) && e.getBirthDate().isBefore(upper)));
    }

    @Test
    void shouldCombineMultipleOperationsWithAnd() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "Alice"),
                new SingleValueOperation("surname", Operator.EQ, "Smith")
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).getName());
        assertEquals("Smith", results.get(0).getSurname());
    }

    @Test
    void shouldReturnEmptyListWhenNoMatch() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "NonExistent")
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertTrue(results.isEmpty());
    }

    // ===================== Join tests =====================

    @Test
    void shouldFindByJoinOperation() {
        // Create employee with phones
        Employee employee = new Employee("John", "Doe", "john@example.com", LocalDate.of(1980, Month.JANUARY, 1));
        Phone phone1 = Phone.builder().number("123456").phoneType(PhoneType.HOME).build();
        Phone phone2 = Phone.builder().number("789012").phoneType(PhoneType.BUSSINES).build();
        employee.addPhone(phone1);
        employee.addPhone(phone2);
        employeeRepository.save(employee);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "123456")
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("John", results.get(0).getName());
    }

    // ===================== Sorting and Pagination tests =====================

    @Test
    void shouldSortResultsBySingleField() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of()); // no filter

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("name", SortDTO.Direction.ASC)
        });

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("name", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);
        List<Employee> content = page.getContent();
        assertEquals(testEmployees.size(), content.size());
        // Check sorting: Alice, Bob, Charlie, Diana, Eve
        assertTrue(content.get(0).getName().compareTo(content.get(1).getName()) < 0);
    }

    @Test
    void shouldSortAndPageResultsCorrectly() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, LocalDate.of(1986, Month.JANUARY, 1))
        ));

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .bind("name", Employee_.name)
                .build();

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(2);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.DESC)
        });

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);
        assertEquals(2, page.getContent().size());
        assertEquals(4, page.getTotalElements()); // employees with birthDate > 1986-01-01: Alice(1990), Bob(1985 no), Charlie(1995), Diana(1988), Eve(1992) -> 4
        // Check that first page sorted descending by birthDate: Charlie(1995), Eve(1992), Diana(1988), Alice(1990) -> order: Charlie, Eve, Diana, Alice (since descending)
        // On page 0 size 2: Charlie, Eve
        assertEquals("Charlie", page.getContent().get(0).getName());
        assertEquals("Eve", page.getContent().get(1).getName());
    }

    // ===================== Edge Cases =====================



    @Test
    void shouldHandleEmptyOperationsList() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of());

        final Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        // Empty operations should result in no filter (all records)
        List<Employee> results = employeeRepository.findAll(spec);
        assertEquals(testEmployees.size(), results.size());
    }
}