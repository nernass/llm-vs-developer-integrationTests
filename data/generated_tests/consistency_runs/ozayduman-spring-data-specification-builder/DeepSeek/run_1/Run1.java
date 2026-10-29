package com.github.ozayduman.specificationbuilder;

import com.github.ozayduman.specificationbuilder.dto.*;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.PageRequestBuilder;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO.SortDTO;
import com.github.ozayduman.specificationbuilder.dto.operation.NoValueOperation;
import com.github.ozayduman.specificationbuilder.dto.operation.SingleValueOperation;
import com.github.ozayduman.specificationbuilder.entity.*;
import com.github.ozayduman.specificationbuilder.repository.EmployeeRepository;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.persistence.criteria.*;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static com.github.ozayduman.specificationbuilder.entity.SocialSecurityType.A;
import static java.util.Comparator.comparing;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {TestConfiguration.class})
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

    @Test
    void whenMultipleJoinBindingsWithAndOrOperatorsThenCorrectResultsReturned() {
        // Given
        final var employee1 = new Employee("Alice", "Smith", "alice@test.com", LocalDate.of(1990, 1, 1));
        final var phone1 = Phone.builder().number("123456").phoneType(PhoneType.HOME).build();
        employee1.addPhone(phone1);
        
        final var employee2 = new Employee("Bob", "Johnson", "bob@test.com", LocalDate.of(1985, 5, 15));
        final var phone2 = Phone.builder().number("789012").phoneType(PhoneType.BUSSINES).build();
        employee2.addPhone(phone2);
        
        final var employee3 = new Employee("Charlie", "Brown", "charlie@test.com", LocalDate.of(1995, 10, 20));
        final var phone3 = Phone.builder().number("345678").phoneType(PhoneType.HOME).build();
        employee3.addPhone(phone3);
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "a"),
                new SingleValueOperation("phoneNumber", Operator.EQ, "123456"),
                new SingleValueOperation("birthDate", Operator.GT, LocalDate.of(1980, 1, 1))
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).getName());
        assertEquals("Smith", result.get(0).getSurname());
    }

    @Test
    void whenMultipleCriteriaOnSameJoinPropertyThenCorrectResultsReturned() {
        // Given
        final var employee1 = new Employee("John", "Doe", "john@test.com", LocalDate.of(1988, 3, 10));
        final var phone1 = Phone.builder().number("111222").phoneType(PhoneType.HOME).build();
        final var phone2 = Phone.builder().number("333444").phoneType(PhoneType.BUSSINES).build();
        employee1.addPhone(phone1);
        employee1.addPhone(phone2);
        
        final var employee2 = new Employee("Jane", "Doe", "jane@test.com", LocalDate.of(1992, 7, 25));
        final var phone3 = Phone.builder().number("555666").phoneType(PhoneType.HOME).build();
        employee2.addPhone(phone3);
        
        employeeRepository.saveAll(List.of(employee1, employee2));

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "111222"),
                new SingleValueOperation("phoneType", Operator.EQ, PhoneType.HOME)
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .bindJoin("phoneType", Employee_.phones, Phone_.phoneType)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertEquals(1, result.size());
        assertEquals("John", result.get(0).getName());
    }

    @Test
    void whenEqualOperatorWithStringAndNumberTypesThenCorrectResultsReturned() {
        // Given
        final var employee1 = new Employee("Alice", "Wonder", "alice@test.com", LocalDate.of(1990, 5, 15));
        final var employee2 = new Employee("Bob", "Builder", "bob@test.com", LocalDate.of(1990, 5, 15));
        final var employee3 = new Employee("Charlie", "Chaplin", "charlie@test.com", LocalDate.of(1990, 5, 15));
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("surname", Operator.EQ, "Builder"),
                new SingleValueOperation("birthDate", Operator.EQ, LocalDate.of(1990, 5, 15))
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertEquals(1, result.size());
        assertEquals("Bob", result.get(0).getName());
        assertEquals("Builder", result.get(0).getSurname());
    }

    @Test
    void whenCombinedAndAndOrOperatorsThenCorrectResultsReturned() {
        // Given
        final var employee1 = new Employee("Alice", "Smith", "alice.smith@test.com", LocalDate.of(1988, 3, 10));
        final var socialSecurity1 = SocialSecurity.of(A, "AAA");
        employee1.setSocialSecurity(socialSecurity1);
        
        final var employee2 = new Employee("Bob", "Smith", "bob.smith@test.com", LocalDate.of(1988, 3, 10));
        final var socialSecurity2 = SocialSecurity.of(A, "BBB");
        employee2.setSocialSecurity(socialSecurity2);
        
        final var employee3 = new Employee("Charlie", "Brown", "charlie.brown@test.com", LocalDate.of(1990, 5, 15));
        final var socialSecurity3 = SocialSecurity.of(SocialSecurityType.B, "CCC");
        employee3.setSocialSecurity(socialSecurity3);
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("surname", Operator.EQ, "Smith"),
                new SingleValueOperation("socialSecurityType", Operator.EQ, A)
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.getSurname().equals("Smith")));
        assertTrue(result.stream().allMatch(e -> e.getSocialSecurity().getSocialSecurityType() == A));
    }

    @Test
    void whenNotEqualOperatorGivenThenResultsExcludeMatchingRecords() {
        // Given
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NE, "özay")
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertTrue(result.size() > 0);
        assertTrue(result.stream().noneMatch(e -> e.getName().equals("özay")));
    }

    @Test
    void whenGreaterThanOrEqualOperatorGivenThenCorrectResultsReturned() {
        // Given
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GTE, LocalDate.of(1990, Month.JANUARY, 1))
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertTrue(result.size() > 0);
        assertTrue(result.stream().allMatch(e -> 
            e.getBirthDate().isAfter(LocalDate.of(1990, Month.JANUARY, 1)) || 
            e.getBirthDate().isEqual(LocalDate.of(1990, Month.JANUARY, 1))
        ));
    }

    @Test
    void whenMultipleSortFieldsWithDifferentDirectionsThenCorrectOrderReturned() {
        // Given
        final var employee1 = new Employee("Alice", "Aardvark", "alice@test.com", LocalDate.of(1988, 1, 1));
        final var employee2 = new Employee("Alice", "Builder", "alice.builder@test.com", LocalDate.of(1988, 1, 1));
        final var employee3 = new Employee("Bob", "Aardvark", "bob@test.com", LocalDate.of(1990, 5, 15));
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("name", SortDTO.Direction.ASC),
                new SortDTO("surname", SortDTO.Direction.DESC)
        });

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("name", Employee_.name)
                .bindSort("surname", Employee_.surname)
                .build();

        // When
        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);

        // Then
        List<Employee> content = page.getContent();
        assertEquals(3, content.size());
        assertEquals("Alice", content.get(0).getName());
        assertEquals("Builder", content.get(0).getSurname());
        assertEquals("Alice", content.get(1).getName());
        assertEquals("Aardvark", content.get(1).getSurname());
        assertEquals("Bob", content.get(2).getName());
        assertEquals("Aardvark", content.get(2).getSurname());
    }

    @Test
    void whenPaginationWithSortingAndFilteringThenCorrectPageReturned() {
        // Given
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);

        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, LocalDate.of(1980, Month.JANUARY, 1))
        ));
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(2);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.DESC)
        });

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .build();

        // When
        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);

        // Then
        assertNotNull(page);
        assertEquals(2, page.getContent().size());
        assertTrue(page.getTotalElements() > 0);
        assertTrue(page.getTotalPages() > 0);
        assertEquals(0, page.getNumber());
        assertEquals(2, page.getSize());
    }

    @Test
    void whenChainedBindJoinWithMultipleLevelsThenCorrectResultsReturned() {
        // Given
        final var socialSecurity = SocialSecurity.of(A, "AAA");
        final var employee = Employee.of("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, 6, 20), socialSecurity);
        final var phone = Phone.builder().number("987654").phoneType(PhoneType.BUSSINES).build();
        employee.addPhone(phone);
        employeeRepository.save(employee);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "987654"),
                new SingleValueOperation("socialSecurityType", Operator.EQ, A)
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        // When
        final List<Employee> result = employeeRepository.findAll(specification);

        // Then
        assertEquals(1, result.size());
        assertEquals("John", result.get(0).getName());
        assertEquals("Doe", result.get(0).getSurname());
        assertEquals(A, result.get(0).getSocialSecurity().getSocialSecurityType());
        assertTrue(result.get(0).getPhones().stream().anyMatch(p -> p.getNumber().equals("987654")));
    }
}