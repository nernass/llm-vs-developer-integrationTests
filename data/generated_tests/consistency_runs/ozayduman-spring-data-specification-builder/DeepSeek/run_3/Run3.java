package com.github.ozayduman.specificationbuilder;

import com.github.ozayduman.specificationbuilder.dto.CriteriaDTO;
import com.github.ozayduman.specificationbuilder.dto.EmployeeResponseDTO;
import com.github.ozayduman.specificationbuilder.dto.Operator;
import com.github.ozayduman.specificationbuilder.dto.PageRequestDTO;
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

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.NoSuchElementException;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static com.github.ozayduman.specificationbuilder.entity.SocialSecurityType.A;
import static com.github.ozayduman.specificationbuilder.entity.SocialSecurityType.B;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {TestConfiguration.class})
class SpecificationBuilderIntegrationTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Test
    void shouldBuildSpecificationWithValidCriteriaDTO() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "John")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        assertNotNull(specification);
    }

    @Test
    void shouldThrowExceptionWhenCriteriaDTOIsNull() {
        assertThrows(RuntimeException.class, () -> SpecificationBuilder.of(null).build());
    }

    @Test
    void shouldReturnAllEmployeesWhenCriteriaDTOHasNoOperations() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        employeeRepository.saveAll(List.of(employee1, employee2));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(2, employees.size());
    }

    @Test
    void shouldFilterBySingleEqualCondition() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        employeeRepository.saveAll(List.of(employee1, employee2));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "John")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(1, employees.size());
        assertEquals("John", employees.get(0).getName());
    }

    @Test
    void shouldFilterByMultipleEqualConditions() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        Employee employee3 = Employee.of("John", "Smith", "john.smith@test.com",
                LocalDate.of(1988, Month.MARCH, 10), null);
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "John"),
                new SingleValueOperation("surname", Operator.EQ, "Smith")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(1, employees.size());
        assertEquals("John", employees.get(0).getName());
        assertEquals("Smith", employees.get(0).getSurname());
    }

    @Test
    void shouldFilterByGreaterThanCondition() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com",
                LocalDate.of(1985, Month.MARCH, 10), null);
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, LocalDate.of(1991, Month.JANUARY, 1))
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(1, employees.size());
        assertEquals("Jane", employees.get(0).getName());
    }

    @Test
    void shouldFilterByLessThanCondition() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com",
                LocalDate.of(1985, Month.MARCH, 10), null);
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.LT, LocalDate.of(1991, Month.JANUARY, 1))
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(2, employees.size());
        assertTrue(employees.stream().allMatch(e -> 
            e.getBirthDate().isBefore(LocalDate.of(1991, Month.JANUARY, 1))));
    }

    @Test
    void shouldFilterByLikeCondition() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com",
                LocalDate.of(1985, Month.MARCH, 10), null);
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("email", Operator.LIKE, "smith")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.email)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(1, employees.size());
        assertTrue(employees.get(0).getEmail().contains("smith"));
    }

    @Test
    void shouldFilterByNotLikeCondition() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        employeeRepository.saveAll(List.of(employee1, employee2));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("email", Operator.NOT_LIKE, "smith")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.email)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(1, employees.size());
        assertFalse(employees.get(0).getEmail().contains("smith"));
    }

    @Test
    void shouldFilterByJoinConditionWithPhoneNumber() {
        Employee employee = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Phone phone1 = Phone.builder()
                .number("555-1234")
                .phoneType(PhoneType.HOME)
                .build();
        Phone phone2 = Phone.builder()
                .number("555-5678")
                .phoneType(PhoneType.BUSSINES)
                .build();
        employee.addPhone(phone1);
        employee.addPhone(phone2);
        employeeRepository.save(employee);

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "555-5678")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        Employee foundEmployee = employeeRepository.findOne(specification)
                .orElseThrow(() -> new NoSuchElementException());
        assertEquals("John", foundEmployee.getName());
        assertTrue(foundEmployee.getPhones().stream()
                .anyMatch(p -> "555-5678".equals(p.getNumber())));
    }

    @Test
    void shouldFilterByJoinConditionWithSocialSecurity() {
        SocialSecurity socialSecurity = SocialSecurity.of(A, "AAA-123");
        Employee employee = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), socialSecurity);
        employeeRepository.save(employee);

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("socialSecurityType", Operator.EQ, A)
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin(Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        Employee foundEmployee = employeeRepository.findOne(specification)
                .orElseThrow(() -> new NoSuchElementException());
        assertEquals("John", foundEmployee.getName());
        assertEquals(A, foundEmployee.getSocialSecurity().getSocialSecurityType());
    }

    @Test
    void shouldFilterByMultipleJoinConditions() {
        SocialSecurity socialSecurity1 = SocialSecurity.of(A, "AAA-123");
        SocialSecurity socialSecurity2 = SocialSecurity.of(B, "BBB-456");
        
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), socialSecurity1);
        Phone phone1 = Phone.builder().number("555-1234").phoneType(PhoneType.HOME).build();
        employee1.addPhone(phone1);
        
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), socialSecurity2);
        Phone phone2 = Phone.builder().number("555-5678").phoneType(PhoneType.BUSSINES).build();
        employee2.addPhone(phone2);
        
        employeeRepository.saveAll(List.of(employee1, employee2));

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("socialSecurityType", Operator.EQ, A),
                new SingleValueOperation("phoneNumber", Operator.EQ, "555-1234")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin(Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertEquals(1, employees.size());
        assertEquals("John", employees.get(0).getName());
    }

    @Test
    void shouldThrowExceptionWhenUnboundPropertyUsed() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("unboundProperty", Operator.EQ, "value")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        assertThrows(RuntimeException.class, () -> 
            employeeRepository.findOne(specification));
    }

    @Test
    void shouldHandlePaginationWithSorting() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1985, Month.JUNE, 15), null);
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com",
                LocalDate.of(1992, Month.MARCH, 10), null);
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(2);
        pageRequestDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, LocalDate.of(1988, Month.JANUARY, 1))
        ));
        pageRequestDTO.setSortFields(new PageRequestDTO.SortDTO[]{
                new PageRequestDTO.SortDTO("name", PageRequestDTO.SortDTO.Direction.ASC)
        });

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .bind(Employee_.birthDate)
                .build();

        var pageRequest = PageRequestDTO.PageRequestBuilder.of(pageRequestDTO)
                .bindSort("name", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);
        
        assertEquals(2, page.getContent().size());
        assertEquals(2, page.getTotalElements());
        assertEquals("John", page.getContent().get(0).getName());
        assertEquals("Bob", page.getContent().get(1).getName());
    }

    @Test
    void shouldHandleMultipleSortFields() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com",
                LocalDate.of(1985, Month.MARCH, 10), null);
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new PageRequestDTO.SortDTO[]{
                new PageRequestDTO.SortDTO("birthDate", PageRequestDTO.SortDTO.Direction.DESC),
                new PageRequestDTO.SortDTO("name", PageRequestDTO.SortDTO.Direction.ASC)
        });

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .bind(Employee_.birthDate)
                .build();

        var pageRequest = PageRequestDTO.PageRequestBuilder.of(pageRequestDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .bindSort("name", Employee_.name)
                .build();

        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);
        
        assertEquals(3, page.getContent().size());
        assertEquals("Bob", page.getContent().get(0).getName());
        assertEquals("Jane", page.getContent().get(1).getName());
        assertEquals("John", page.getContent().get(2).getName());
    }

    @Test
    void shouldMapToPageResultDTO() {
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com",
                LocalDate.of(1992, Month.JUNE, 15), null);
        employeeRepository.saveAll(List.of(employee1, employee2));

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .build();

        var pageRequest = PageRequestDTO.PageRequestBuilder.of(pageRequestDTO).build();
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
        assertEquals(2, pageResultDTO.getTotalElements());
        assertEquals(0, pageResultDTO.getCurrentPage());
        assertEquals(10, pageResultDTO.getSize());
        assertEquals(1, pageResultDTO.getTotalPages());
        assertEquals(2, pageResultDTO.getContent().size());
    }

    @Test
    void shouldHandleEmptyResultSet() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "NonExistent")
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertTrue(employees.isEmpty());
    }

    @Test
    void shouldHandleNullValuesInCriteria() {
        Employee employee = Employee.of("John", "Doe", "john.doe@test.com",
                LocalDate.of(1990, Month.JANUARY, 1), null);
        employeeRepository.save(employee);

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("surname", Operator.EQ, null)
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.surname)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);
        assertTrue(employees.isEmpty());
    }
}