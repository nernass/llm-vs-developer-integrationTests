package com.github.ozayduman.specificationbuilder;

import com.github.ozayduman.specificationbuilder.dto.*;
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

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.NoSuchElementException;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {TestConfiguration.class})
public class geminiIT_1 {

    @Autowired
    private EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();

        Employee emp1 = new Employee("Ozay", "Duman", "ozay.duman@gmail.com", LocalDate.of(1980, Month.JANUARY, 1));
        Phone p1 = Phone.builder().number("5555").phoneType(PhoneType.BUSSINES).build();
        emp1.addPhone(p1);
        emp1.setSocialSecurity(SocialSecurity.of(SocialSecurityType.A, "SSN-1"));

        Employee emp2 = new Employee("John", "Doe", "john.doe@test.com", LocalDate.of(1990, Month.FEBRUARY, 15));
        Phone p2 = Phone.builder().number("1234").phoneType(PhoneType.HOME).build();
        emp2.addPhone(p2);
        emp2.setSocialSecurity(SocialSecurity.of(SocialSecurityType.B, "SSN-2"));
        
        Employee emp3 = new Employee("Jane", "Smith", "jane.smith@test.com", LocalDate.of(2000, Month.MARCH, 10));

        employeeRepository.saveAll(List.of(emp1, emp2, emp3));
    }

    @Test
    void shouldBuildASpecificationWithDTO() {
        assertAll("build",
                () -> assertThrows(RuntimeException.class, () -> SpecificationBuilder.<Employee>of(null).build()),
                () -> assertNotNull(SpecificationBuilder.<Employee>of(new CriteriaDTO()).build())
        );
    }

    @Test
    void whenOperationInvalidThenAnIllegalArgumentExceptionThrown() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new NoValueOperation("name", Operator.EQ)));
        assertThrows(IllegalArgumentException.class, () -> SpecificationBuilder.<Employee>of(criteriaDTO).build());
    }

    @Test
    void whenEQPramsProvidedThenResultRestrictedByEQProperty() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("name", Operator.EQ, "Ozay")));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
        
        List<Employee> results = employeeRepository.findAll(specification);
        
        assertEquals(1, results.size());
        assertEquals("Ozay", results.get(0).getName());
    }

    @Test
    void whenDTOPropertyNotBoundThenExceptionThrown() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("surname", Operator.EQ, "Duman")));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();

        assertThrows(RuntimeException.class, () -> employeeRepository.findAll(specification));
    }

    @Test
    void whenMultiEQPramsProvidedThenResultRestrictedByMultipleProperties() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.EQ, "John"),
                new SingleValueOperation("surname", Operator.EQ, "Doe")
        ));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .build();
        
        List<Employee> results = employeeRepository.findAll(specification);
        
        assertEquals(1, results.size());
        assertEquals("John", results.get(0).getName());
        assertEquals("Doe", results.get(0).getSurname());
    }

    @Test
    void whenDTOPropertyBoundViaJoinThenJoinSpecExecutedAndRestricted() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("phoneNumber", Operator.EQ, "5555")));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        Employee customerFromDB = employeeRepository.findOne(specification).orElseThrow(NoSuchElementException::new);

        assertEquals("Ozay", customerFromDB.getName());
        assertEquals("Duman", customerFromDB.getSurname());
    }

    @Test
    void whenSortDTOSuppliedThenResultSortedByGivenSortInfo() {
        final PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(10);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("employeeBirthDate", SortDTO.Direction.DESC)
        });

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind("employeeBirthDate", Employee_.birthDate)
                .build();

        var pageRequest = PageRequestBuilder.of(pageRequestDTO)
                .bindSort("employeeBirthDate", Employee_.birthDate)
                .build();

        Page<Employee> page = employeeRepository.findAll(specification, pageRequest);

        assertEquals(3, page.getTotalElements());
        assertEquals("Jane", page.getContent().get(0).getName());
        assertEquals("Ozay", page.getContent().get(2).getName());
    }

    @Test
    void whenLikeOperatorGivenThenResultRestrictedByLikeProperty() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("name", Operator.LIKE, "a")));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
                
        List<Employee> results = employeeRepository.findAll(specification);
        
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(e -> e.getName().equals("Ozay")));
        assertTrue(results.stream().anyMatch(e -> e.getName().equals("Jane")));
    }

    @Test
    void whenNotLikeOperatorGivenThenResultRestrictedByNotLikeProperty() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("name", Operator.NOT_LIKE, "a")));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
                
        List<Employee> results = employeeRepository.findAll(specification);
        
        assertEquals(1, results.size());
        assertEquals("John", results.get(0).getName());
    }
}
