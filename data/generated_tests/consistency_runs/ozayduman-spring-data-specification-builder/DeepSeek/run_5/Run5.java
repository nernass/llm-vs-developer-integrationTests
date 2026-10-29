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
    void whenGTAndLTOperatorsGivenThenResultFilteredByRange() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("employeeBirthDate", Operator.GT, LocalDate.of(1990, Month.JANUARY, 1)),
                new SingleValueOperation("employeeBirthDate", Operator.LT, LocalDate.of(2000, Month.JANUARY, 1))
        ));
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("employeeBirthDate", Employee_.birthDate)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        allEmployees.forEach(employee -> {
            assertTrue(employee.getBirthDate().isAfter(LocalDate.of(1990, Month.JANUARY, 1)));
            assertTrue(employee.getBirthDate().isBefore(LocalDate.of(2000, Month.JANUARY, 1)));
        });
    }

    @Test
    void whenMultipleOperatorsGivenThenResultFilteredByAllConditions() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("employeeName", Operator.LIKE, "a"),
                new SingleValueOperation("employeeSurname", Operator.LIKE, "o")
        ));
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("employeeName", Employee_.name)
                .bind("employeeSurname", Employee_.surname)
                .bind("employeeEmail", Employee_.email)
                .bind("employeeBirthDate", Employee_.birthDate)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        allEmployees.forEach(employee -> {
            assertTrue(employee.getName().contains("a"));
            assertTrue(employee.getSurname().contains("o"));
        });
    }

    @Test
    void whenMultipleJoinBindingsUsedThenComplexJoinQueryExecuted() {
        final var socialSecurity = SocialSecurity.of(A, "AAA");
        final var employee = Employee.of("test", "user", "test@test.com",
                LocalDate.of(1995, Month.JANUARY, 1), socialSecurity);
        final var phone = Phone.builder()
                .number("123456789")
                .phoneType(PhoneType.HOME)
                .build();
        employee.addPhone(phone);
        employeeRepository.save(employee);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("socialSecurityType", Operator.EQ, "A"),
                new SingleValueOperation("phoneNumber", Operator.EQ, "123456789")
        ));

        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        val result = employeeRepository.findOne(specification)
                .orElseThrow(() -> new NoSuchElementException());

        assertNotNull(result);
        assertEquals("test", result.getName());
        assertEquals("user", result.getSurname());
        assertEquals("test@test.com", result.getEmail());
        assertEquals("A", result.getSocialSecurity().getSocialSecurityType().name());
        assertEquals(1, result.getPhones().size());
        assertEquals("123456789", result.getPhones().get(0).getNumber());
    }

    @Test
    void whenEmptyOperationListProvidedThenAllEmployeesReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of());
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        assertEquals(employees.size(), allEmployees.size());
    }

    @Test
    void whenCriteriaDTOIsEmptyThenAllEmployeesReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .bind("email", Employee_.email)
                .bind("birthDate", Employee_.birthDate)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        assertEquals(employees.size(), allEmployees.size());
    }

    @Test
    void whenCaseInsensitiveLikeOperatorGivenThenResultRestrictedByProperty() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "S")));
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        val hasNamesNotContainingValue = allEmployees.stream()
                .anyMatch(employee -> !employee.getName().toLowerCase().contains("s"));
        assertFalse(hasNamesNotContainingValue);
    }

    @Test
    void whenNoMatchingCriteriaGivenThenEmptyResultReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "nonexistent")
        ));
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        assertEquals(0, allEmployees.size());
    }

    @Test
    void whenNotNullAndNullOperatorsGivenThenFilterWorksAsExpected() {
        final var socialSecurity = SocialSecurity.of(A, "AAA");
        final var employee = Employee.of("test", "user", "test@test.com",
                LocalDate.of(1995, Month.JANUARY, 1), socialSecurity);
        employeeRepository.save(employee);

        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_NULL, null)
        ));

        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        val allEmployees = employeeRepository.findAll(specification);
        assertTrue(allEmployees.size() > 0);
        allEmployees.forEach(e -> assertNotNull(e.getName()));
    }
}