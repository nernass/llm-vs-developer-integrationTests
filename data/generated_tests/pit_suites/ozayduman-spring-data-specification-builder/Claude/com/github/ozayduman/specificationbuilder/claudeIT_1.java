package com.github.ozayduman.specificationbuilder;

import com.github.ozayduman.specificationbuilder.dto.CriteriaDTO;
import com.github.ozayduman.specificationbuilder.dto.EmployeeResponseDTO;
import com.github.ozayduman.specificationbuilder.dto.Operator;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.PageRequestBuilder;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.SortDTO;
import com.github.ozayduman.specificationbuilder.dto.PageResultDTO;
import com.github.ozayduman.specificationbuilder.dto.RangeDTO;
import com.github.ozayduman.specificationbuilder.dto.operation.MultiValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.NoValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.RangeValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.SingleValueOperation;
import com.github.ozayduman.specificationbuilder.entity.Employee;
import com.github.ozayduman.specificationbuilder.entity.Employee_;
import com.github.ozayduman.specificationbuilder.entity.Phone;
import com.github.ozayduman.specificationbuilder.entity.PhoneType;
import com.github.ozayduman.specificationbuilder.entity.Phone_;
import com.github.ozayduman.specificationbuilder.entity.SocialSecurity;
import com.github.ozayduman.specificationbuilder.entity.SocialSecurityType;
import com.github.ozayduman.specificationbuilder.entity.SocialSecurity_;
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

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {TestConfiguration.class})
class claudeIT_1 {

    @Autowired
    EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAllInBatch();

        var emp1 = Employee.of("Alice", "Martin", "alice@test.com",
                LocalDate.of(1990, Month.MARCH, 15),
                SocialSecurity.of(SocialSecurityType.A, "SSA-Alice"));
        emp1.addPhone(Phone.builder().number("1111").phoneType(PhoneType.HOME).build());
        emp1.addPhone(Phone.builder().number("1112").phoneType(PhoneType.BUSSINES).build());

        var emp2 = Employee.of("Bob", "Nelson", "bob@test.com",
                LocalDate.of(1985, Month.JULY, 20),
                SocialSecurity.of(SocialSecurityType.B, "SSB-Bob"));
        emp2.addPhone(Phone.builder().number("2222").phoneType(PhoneType.HOME).build());

        var emp3 = Employee.of("Charlie", "Olsen", "charlie@test.com",
                LocalDate.of(2002, Month.JANUARY, 5),
                SocialSecurity.of(SocialSecurityType.A, "SSA-Charlie"));
        emp3.addPhone(Phone.builder().number("3333").phoneType(PhoneType.BUSSINES).build());

        var emp4 = Employee.of("Diana", "Pearson", "diana@test.com",
                LocalDate.of(1995, Month.DECEMBER, 30),
                SocialSecurity.of(SocialSecurityType.B, "SSB-Diana"));
        emp4.addPhone(Phone.builder().number("4444").phoneType(PhoneType.HOME).build());

        var emp5 = new Employee("Elisa", "Ross", "elisa@test.com",
                LocalDate.of(2000, Month.JUNE, 10));

        employeeRepository.saveAll(List.of(emp1, emp2, emp3, emp4, emp5));
        employeeRepository.flush();
    }

    // --- EQ operator against real DB ---

    @Test
    void shouldFindEmployeeByExactNameUsingEQOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "Alice")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).getName());
        assertEquals("alice@test.com", result.get(0).getEmail());
    }

    // --- NOT_EQ operator ---

    @Test
    void shouldExcludeEmployeeByNameUsingNOT_EQOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_EQ, "Alice")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertEquals(4, result.size());
        assertTrue(result.stream().noneMatch(e -> "Alice".equals(e.getName())));
    }

    // --- GT operator ---

    @Test
    void shouldFindEmployeesBornAfterDateUsingGTOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT,
                        LocalDate.of(2000, Month.JANUARY, 1))));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Charlie", "Elisa"), names);
    }

    // --- LT operator ---

    @Test
    void shouldFindEmployeesBornBeforeDateUsingLTOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.LT,
                        LocalDate.of(1991, Month.JANUARY, 1))));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "Bob"), names);
    }

    // --- GE operator ---

    @Test
    void shouldFindEmployeesBornOnOrAfterDateUsingGEOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE,
                        LocalDate.of(2000, Month.JUNE, 10))));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Charlie", "Elisa"), names);
    }

    // --- LE operator ---

    @Test
    void shouldFindEmployeesBornOnOrBeforeDateUsingLEOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.LE,
                        LocalDate.of(1990, Month.MARCH, 15))));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "Bob"), names);
    }

    // --- BT (between) operator ---

    @Test
    void shouldFindEmployeesBornBetweenDatesUsingBTOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new RangeValueOperation("birthDate", Operator.BT,
                        new RangeDTO(LocalDate.of(1990, 1, 1), LocalDate.of(1999, 12, 31)))));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "Diana"), names);
    }

    // --- IN operator ---

    @Test
    void shouldFindEmployeesByNameUsingINOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new MultiValueOperation("name", Operator.IN,
                        new String[]{"Alice", "Charlie", "Elisa"})));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "Charlie", "Elisa"), names);
    }

    // --- NOT_IN operator ---

    @Test
    void shouldExcludeEmployeesByEmailUsingNOT_INOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new MultiValueOperation("email", Operator.NOT_IN,
                        new String[]{"alice@test.com", "bob@test.com"})));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.email)
                .build();

        Set<String> emails = employeeRepository.findAll(spec).stream()
                .map(Employee::getEmail)
                .collect(Collectors.toSet());

        assertEquals(Set.of("charlie@test.com", "diana@test.com", "elisa@test.com"), emails);
    }

    // --- LIKE operator ---

    @Test
    void shouldFindEmployeesWhoseNameContainsSubstringUsingLIKEOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "li")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertTrue(result.size() > 0);
        assertTrue(result.stream().allMatch(e -> e.getName().contains("li")));
        Set<String> names = result.stream().map(Employee::getName).collect(Collectors.toSet());
        assertEquals(Set.of("Alice", "Charlie", "Elisa"), names);
    }

    // --- NOT_LIKE operator ---

    @Test
    void shouldExcludeEmployeesWhoseNameContainsSubstringUsingNOT_LIKEOperator() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_LIKE, "li")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertTrue(result.size() > 0);
        assertTrue(result.stream().noneMatch(e -> e.getName().contains("li")));
        Set<String> names = result.stream().map(Employee::getName).collect(Collectors.toSet());
        assertEquals(Set.of("Bob", "Diana"), names);
    }

    // --- Join via PluralAttribute (Employee -> Phone) ---

    @Test
    void shouldFindEmployeeByPhoneNumberViaJoin() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "1111")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        Employee employee = employeeRepository.findOne(spec)
                .orElseThrow(NoSuchElementException::new);

        assertAll(
                () -> assertEquals("Alice", employee.getName()),
                () -> assertEquals("alice@test.com", employee.getEmail())
        );
    }

    @Test
    void shouldReturnNoResultWhenPhoneNumberDoesNotExist() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "9999")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        assertTrue(employeeRepository.findOne(spec).isEmpty());
    }

    // --- Join via SingularAttribute (Employee -> SocialSecurity) ---

    @Test
    void shouldFindEmployeesBySocialSecurityTypeViaJoin() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("ssType", Operator.EQ, SocialSecurityType.A)));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("ssType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "Charlie"), names);
    }

    // --- Combined scalar + join filters ---

    @Test
    void shouldCombineScalarFilterAndJoinFilterInSingleQuery() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "a"),
                new SingleValueOperation("ssType", Operator.EQ, SocialSecurityType.B)));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bindJoin("ssType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertEquals(1, result.size());
        assertEquals("Diana", result.get(0).getName());
    }

    // --- Multiple scalar operations ---

    @Test
    void shouldCombineMultipleScalarOperationsWithAND() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE, LocalDate.of(1990, 1, 1)),
                new SingleValueOperation("birthDate", Operator.LT, LocalDate.of(2000, 1, 1))));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> names = employeeRepository.findAll(spec).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "Diana"), names);
    }

    // --- Empty criteria returns all ---

    @Test
    void shouldReturnAllEmployeesWhenCriteriaHasNoOperations() {
        var criteriaDTO = new CriteriaDTO();

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertEquals(5, result.size());
    }

    // --- Paging and sorting ---

    @Test
    void shouldPageAndSortResultsByBirthDateASC() {
        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE, LocalDate.of(1990, 1, 1))));
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(2);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.ASC)});

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .bind(Employee_.birthDate)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);

        assertAll(
                () -> assertEquals(0, page.getNumber()),
                () -> assertEquals(2, page.getSize()),
                () -> assertEquals(4, page.getTotalElements()),
                () -> assertEquals(2, page.getTotalPages()),
                () -> assertEquals("Alice", page.getContent().get(0).getName()),
                () -> assertEquals("Diana", page.getContent().get(1).getName())
        );
    }

    @Test
    void shouldSortResultsByNameDESC() {
        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("name", SortDTO.Direction.DESC)});

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("name", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);
        List<String> names = page.getContent().stream()
                .map(Employee::getName)
                .collect(Collectors.toList());

        assertEquals(List.of("Elisa", "Diana", "Charlie", "Bob", "Alice"), names);
    }

    @Test
    void shouldReturnSecondPageOfResults() {
        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE, LocalDate.of(1990, 1, 1))));
        pageRequestDTO.setPage(1);
        pageRequestDTO.setSize(2);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.ASC)});

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.birthDate)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);

        assertAll(
                () -> assertEquals(1, page.getNumber()),
                () -> assertEquals(2, page.getContent().size()),
                () -> assertEquals("Elisa", page.getContent().get(0).getName())
        );
    }

    // --- PageResultDTO mapping ---

    @Test
    void shouldMapPageResultDTOFieldsCorrectly() {
        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(3);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("name", SortDTO.Direction.ASC)});

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("name", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);

        PageResultDTO pageResultDTO = PageResultDTO.from(page, e -> {
            EmployeeResponseDTO dto = new EmployeeResponseDTO();
            dto.setName(e.getName());
            dto.setLastName(e.getSurname());
            dto.setEmail(e.getEmail());
            dto.setBirthDate(e.getBirthDate());
            return dto;
        });

        @SuppressWarnings("unchecked")
        List<EmployeeResponseDTO> content = (List<EmployeeResponseDTO>) pageResultDTO.getContent();

        assertAll(
                () -> assertEquals(0, pageResultDTO.getCurrentPage()),
                () -> assertEquals(3, pageResultDTO.getSize()),
                () -> assertEquals(5L, pageResultDTO.getTotalElements()),
                () -> assertEquals(2, pageResultDTO.getTotalPages()),
                () -> assertEquals(3, content.size()),
                () -> assertEquals("Alice", content.get(0).getName()),
                () -> assertEquals("Martin", content.get(0).getLastName()),
                () -> assertEquals("alice@test.com", content.get(0).getEmail()),
                () -> assertEquals(LocalDate.of(1990, Month.MARCH, 15), content.get(0).getBirthDate())
        );
    }

    // --- Multi-field sort ---

    @Test
    void shouldSortByMultipleFieldsCorrectly() {
        employeeRepository.deleteAll();

        var e1 = new Employee("Zara", "Adams", "z@test.com", LocalDate.of(2000, 1, 1));
        var e2 = new Employee("Anna", "Adams", "a@test.com", LocalDate.of(2000, 1, 1));
        var e3 = new Employee("Zara", "Brown", "z2@test.com", LocalDate.of(1999, 1, 1));
        employeeRepository.saveAll(List.of(e1, e2, e3));
        employeeRepository.flush();

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.ASC),
                new SortDTO("name", SortDTO.Direction.ASC)});

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .bind(Employee_.birthDate)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .bindSort("name", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(spec, pageRequest);
        List<String> names = page.getContent().stream()
                .map(Employee::getName)
                .collect(Collectors.toList());

        assertEquals(List.of("Zara", "Anna", "Zara"), names);
        assertEquals("Brown", page.getContent().get(0).getSurname());
    }

    // --- Error / edge cases ---

    @Test
    void shouldThrowNullPointerExceptionWhenCriteriaDTOIsNull() {
        assertThrows(NullPointerException.class,
                () -> SpecificationBuilder.<Employee>of(null).build());
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenNoValueOperationUsedWithEQ() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new NoValueOperation("name", Operator.EQ)));

        assertThrows(IllegalArgumentException.class,
                () -> SpecificationBuilder.<Employee>of(criteriaDTO).build());
    }

    @Test
    void shouldThrowWhenDTOPropertyIsNotBoundInSpecificationBuilder() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("unboundProp", Operator.EQ, "value")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        assertThrows(RuntimeException.class, () -> employeeRepository.findAll(spec));
    }

    @Test
    void shouldThrowWhenSortPropertyNotBoundInPageRequestBuilder() {
        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("unboundSort", SortDTO.Direction.ASC)});

        assertThrows(NullPointerException.class,
                () -> PageRequestBuilder.of(pageRequestDTO).build());
    }

    @Test
    void shouldReturnEmptyListWhenNoEmployeesMatchCriteria() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "NonExistentName")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldBuildSpecificationWithEmptyCriteriaDTO() {
        Specification<Employee> spec = SpecificationBuilder.<Employee>of(new CriteriaDTO()).build();
        assertNotNull(spec);
    }

    @Test
    void shouldCombineJoinPhoneFilterWithScalarNameFilter() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "Alice"),
                new SingleValueOperation("phoneNumber", Operator.EQ, "1112")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        Employee employee = employeeRepository.findOne(spec)
                .orElseThrow(NoSuchElementException::new);

        assertEquals("Alice", employee.getName());
        assertEquals("alice@test.com", employee.getEmail());
    }

    @Test
    void shouldReturnEmptyWhenJoinFilterAndScalarFilterContradict() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "Bob"),
                new SingleValueOperation("phoneNumber", Operator.EQ, "1111")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        assertTrue(employeeRepository.findOne(spec).isEmpty());
    }

    @Test
    void shouldHandleLIKEOperatorWithSingleCharacterSubstring() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("surname", Operator.LIKE, "s")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.surname)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertTrue(result.size() > 0);
        assertTrue(result.stream().allMatch(e ->
                e.getSurname().toLowerCase().contains("s")));
    }

    @Test
    void shouldFilterByNOT_LIKEWithJoinBindingPresent() {
        var criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_LIKE, "li")));

        Specification<Employee> spec = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bindJoin(Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        List<Employee> result = employeeRepository.findAll(spec);

        assertTrue(result.size() > 0);
        assertTrue(result.stream().noneMatch(e -> e.getName().contains("li")));
    }
}
