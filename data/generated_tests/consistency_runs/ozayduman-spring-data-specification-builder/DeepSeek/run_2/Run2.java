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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

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

   /* @Test
    void whenRangePramsProvidedThenBetweenSpecCreated() {
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        RangeDTO valueOfBirdDate = new RangeDTO(LocalDate.now().minusYears(15L), LocalDate.now());
        criteriaDTO.setBetween(Map.of("birthDate", valueOfBirdDate));

        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();
        Root<Employee> rootMock = mock(Root.class);
        Path<Object> birthPathMock = mock(Path.class);
        when(rootMock.get(Employee_.birthDate.getName())).thenReturn(birthPathMock);
        CriteriaQuery<?> criteriaQueryMock = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilderMock = mock(CriteriaBuilder.class);
        Predicate betweenPredicateMock = mock(Predicate.class);
        when(criteriaBuilderMock.between(Mockito.any(Expression.class),eq((Comparable)valueOfBirdDate.getFirst()),eq((Comparable)valueOfBirdDate.getSecond()))).thenReturn(betweenPredicateMock);

        final Predicate predicate = specification.toPredicate(rootMock, criteriaQueryMock, criteriaBuilderMock);

        verify(rootMock, times(1)).get(Employee_.birthDate.getName());
        verifyNoMoreInteractions(rootMock);
        verify(criteriaBuilderMock, times(1)).between(birthPathMock, valueOfBirdDate.getFirst(), valueOfBirdDate.getSecond());
        verify(criteriaBuilderMock,never()).conjunction();
        verifyNoInteractions(criteriaQueryMock);
    }*/

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

        //PageResultDTO pageResultDTO = PageResultDTO.from(page, EmployeeMapper.INSTANCE::toDTO);

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
    void whenEqualsOperatorWithJoinAndMultipleConditionsThenCorrectResultsReturned() {
        // given: employees with different phones and social security
        SocialSecurity socialSecurity1 = SocialSecurity.of(A, "AAA");
        SocialSecurity socialSecurity2 = SocialSecurity.of(SocialSecurityType.B, "BBB");
        
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, Month.JANUARY, 15), socialSecurity1);
        Phone phone1 = Phone.builder().number("123456789").phoneType(PhoneType.HOME).build();
        employee1.addPhone(phone1);
        
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com", 
                LocalDate.of(1990, Month.JUNE, 20), socialSecurity2);
        Phone phone2 = Phone.builder().number("987654321").phoneType(PhoneType.BUSSINES).build();
        employee2.addPhone(phone2);
        
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com", 
                LocalDate.of(1975, Month.MARCH, 10), socialSecurity1);
        Phone phone3 = Phone.builder().number("123456789").phoneType(PhoneType.HOME).build();
        employee3.addPhone(phone3);
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));
        
        // when: querying for employees with phone number 123456789 and social security type A
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.EQ, "123456789"),
                new SingleValueOperation("socialSecurityType", Operator.EQ, A)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employees with matching phone number and social security type should be returned
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> 
            e.getPhones().stream().anyMatch(p -> "123456789".equals(p.getNumber())) &&
            A.equals(e.getSocialSecurity().getSocialSecurityType())
        ));
    }

    @Test
    void whenGreaterThanOperatorWithDateAndSortThenCorrectResultsReturned() {
        // given: employees with different birth dates
        LocalDate baseDate = LocalDate.of(1990, Month.JANUARY, 1);
        List<Employee> employees = List.of(
                new Employee("Alice", "Brown", "alice.brown@test.com", baseDate.minusYears(5)),
                new Employee("Bob", "White", "bob.white@test.com", baseDate.plusYears(3)),
                new Employee("Charlie", "Green", "charlie.green@test.com", baseDate.plusYears(1)),
                new Employee("Diana", "Black", "diana.black@test.com", baseDate.plusYears(2))
        );
        employeeRepository.saveAll(employees);
        
        // when: querying for employees born after base date
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GT, baseDate)
        ));
        criteriaDTO.setPage(0);
        criteriaDTO.setSize(10);
        criteriaDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.ASC)
        });
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();
        
        var pageRequest = PageRequestBuilder.of(criteriaDTO)
                .bindSort("birthDate", Employee_.birthDate)
                .build();
        
        Page<Employee> result = employeeRepository.findAll(specification, pageRequest);
        
        // then: only employees born after base date should be returned, sorted by birth date
        assertEquals(3, result.getTotalElements());
        List<LocalDate> birthDates = result.getContent().stream()
                .map(Employee::getBirthDate)
                .collect(Collectors.toList());
        assertTrue(birthDates.stream().allMatch(date -> date.isAfter(baseDate)));
        assertEquals(birthDates.stream().sorted().collect(Collectors.toList()), birthDates);
    }

    @Test
    void whenLessThanOperatorWithNumericAndSocialSecurityJoinThenCorrectResultsReturned() {
        // given: employees with different social security types
        SocialSecurity socialSecurityA = SocialSecurity.of(A, "AAA");
        SocialSecurity socialSecurityB = SocialSecurity.of(SocialSecurityType.B, "BBB");
        SocialSecurity socialSecurityC = SocialSecurity.of(SocialSecurityType.C, "CCC");
        
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, Month.JANUARY, 15), socialSecurityA);
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com", 
                LocalDate.of(1990, Month.JUNE, 20), socialSecurityB);
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com", 
                LocalDate.of(1975, Month.MARCH, 10), socialSecurityC);
        Employee employee4 = Employee.of("Alice", "Brown", "alice.brown@test.com", 
                LocalDate.of(1988, Month.DECEMBER, 5), socialSecurityA);
        
        // Add phones to some employees
        employee1.addPhone(Phone.builder().number("111").phoneType(PhoneType.HOME).build());
        employee3.addPhone(Phone.builder().number("222").phoneType(PhoneType.BUSSINES).build());
        employee4.addPhone(Phone.builder().number("333").phoneType(PhoneType.HOME).build());
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3, employee4));
        
        // when: querying for employees with social security type less than C and phone number not null
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("socialSecurityType", Operator.LT, SocialSecurityType.C),
                new SingleValueOperation("phoneNumber", Operator.IS_NOT_NULL, null)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: employees with social security type less than C (A or B) and with at least one phone
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> 
            e.getSocialSecurity().getSocialSecurityType().ordinal() < SocialSecurityType.C.ordinal() &&
            !e.getPhones().isEmpty()
        ));
    }

    @Test
    void whenIsNullOperatorWithJoinThenCorrectResultsReturned() {
        // given: employees with and without social security
        Employee employee1 = new Employee("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, Month.JANUARY, 15));
        employee1.setSocialSecurity(SocialSecurity.of(A, "AAA"));
        
        Employee employee2 = new Employee("Jane", "Smith", "jane.smith@test.com", 
                LocalDate.of(1990, Month.JUNE, 20));
        // employee2 has no social security
        
        Employee employee3 = new Employee("Bob", "Johnson", "bob.johnson@test.com", 
                LocalDate.of(1975, Month.MARCH, 10));
        employee3.setSocialSecurity(SocialSecurity.of(SocialSecurityType.B, "BBB"));
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));
        
        // when: querying for employees with social security IS NULL
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("socialSecurityType", Operator.IS_NULL, null)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employees without social security should be returned
        assertEquals(1, result.size());
        assertEquals("Jane", result.get(0).getName());
        assertNull(result.get(0).getSocialSecurity());
    }

    @Test
    void whenMultipleOperatorsCombinedThenCorrectResultsReturned() {
        // given: employees with various attributes
        SocialSecurity socialSecurityA = SocialSecurity.of(A, "AAA");
        SocialSecurity socialSecurityB = SocialSecurity.of(SocialSecurityType.B, "BBB");
        
        Employee employee1 = Employee.of("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, Month.JANUARY, 15), socialSecurityA);
        employee1.addPhone(Phone.builder().number("123").phoneType(PhoneType.HOME).build());
        
        Employee employee2 = Employee.of("Jane", "Smith", "jane.smith@test.com", 
                LocalDate.of(1990, Month.JUNE, 20), socialSecurityB);
        employee2.addPhone(Phone.builder().number("456").phoneType(PhoneType.BUSSINES).build());
        
        Employee employee3 = Employee.of("Bob", "Johnson", "bob.johnson@test.com", 
                LocalDate.of(1975, Month.MARCH, 10), socialSecurityA);
        employee3.addPhone(Phone.builder().number("789").phoneType(PhoneType.HOME).build());
        
        Employee employee4 = Employee.of("Alice", "Brown", "alice.brown@test.com", 
                LocalDate.of(1995, Month.DECEMBER, 5), socialSecurityB);
        employee4.addPhone(Phone.builder().number("123").phoneType(PhoneType.MOBILE).build());
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3, employee4));
        
        // when: querying for employees with name containing 'o', social security type A, and phone number 123
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "o"),
                new SingleValueOperation("socialSecurityType", Operator.EQ, A),
                new SingleValueOperation("phoneNumber", Operator.EQ, "123")
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employee1 should match all conditions
        assertEquals(1, result.size());
        assertEquals("John", result.get(0).getName());
        assertEquals(A, result.get(0).getSocialSecurity().getSocialSecurityType());
        assertTrue(result.get(0).getPhones().stream().anyMatch(p -> "123".equals(p.getNumber())));
    }

    @Test
    void whenComplexJoinPathWithMultipleLevelsThenCorrectResultsReturned() {
        // This test might need additional entity structure for multi-level joins
        // Testing the ability to bind through nested relationships
        SocialSecurity socialSecurity = SocialSecurity.of(A, "AAA");
        Employee employee = Employee.of("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, Month.JANUARY, 15), socialSecurity);
        employee.addPhone(Phone.builder().number("123456789").phoneType(PhoneType.HOME).build());
        employeeRepository.save(employee);
        
        // When: querying using join through socialSecurity (which is a SingularAttribute)
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("socialSecurityType", Operator.EQ, A)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // Then: employee should be found
        assertEquals(1, result.size());
        assertEquals("John", result.get(0).getName());
        assertEquals(A, result.get(0).getSocialSecurity().getSocialSecurityType());
    }

    @Test
    void whenLikeOperatorWithMultipleValuesThenCorrectResultsReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.LIKE, "a"),
                new SingleValueOperation("surname", Operator.LIKE, "m")
        ));
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        
        // Verify all returned employees match both conditions
        assertTrue(allEmployees.stream().allMatch(e -> 
            e.getName().contains("a") && e.getSurname().contains("m")
        ));
        
        // Verify at least one result is returned
        assertTrue(allEmployees.size() > 0);
    }

    @Test
    void whenNotLikeOperatorWithMultipleConditionsThenCorrectResultsReturned() {
        final var employees = TestDataGenerator.createEmployees();
        employeeRepository.saveAll(employees);
        
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_LIKE, "a"),
                new SingleValueOperation("surname", Operator.NOT_LIKE, "m")
        ));
        
        val specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .bind("surname", Employee_.surname)
                .build();
        
        val allEmployees = employeeRepository.findAll(specification);
        
        // Verify all returned employees match both conditions
        assertTrue(allEmployees.stream().allMatch(e -> 
            !e.getName().contains("a") && !e.getSurname().contains("m")
        ));
        
        // Verify at least one result is returned
        assertTrue(allEmployees.size() > 0);
    }

    @Test
    void whenIsNotNullOperatorWithJoinThenCorrectResultsReturned() {
        // given: employees with and without phones
        Employee employee1 = new Employee("John", "Doe", "john.doe@test.com", 
                LocalDate.of(1985, Month.JANUARY, 15));
        employee1.addPhone(Phone.builder().number("123").phoneType(PhoneType.HOME).build());
        
        Employee employee2 = new Employee("Jane", "Smith", "jane.smith@test.com", 
                LocalDate.of(1990, Month.JUNE, 20));
        // employee2 has no phone
        
        Employee employee3 = new Employee("Bob", "Johnson", "bob.johnson@test.com", 
                LocalDate.of(1975, Month.MARCH, 10));
        employee3.addPhone(Phone.builder().number("456").phoneType(PhoneType.BUSSINES).build());
        
        employeeRepository.saveAll(List.of(employee1, employee2, employee3));
        
        // when: querying for employees with phone number IS NOT NULL
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("phoneNumber", Operator.IS_NOT_NULL, null)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employees with at least one phone should be returned
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> !e.getPhones().isEmpty()));
    }

    @Test
    void whenInOperatorThenCorrectResultsReturned() {
        // given: employees with different names
        List<Employee> employees = List.of(
                new Employee("Alice", "Brown", "alice.brown@test.com", LocalDate.of(1990, Month.JANUARY, 1)),
                new Employee("Bob", "White", "bob.white@test.com", LocalDate.of(1985, Month.JUNE, 15)),
                new Employee("Charlie", "Green", "charlie.green@test.com", LocalDate.of(1995, Month.MARCH, 20)),
                new Employee("Diana", "Black", "diana.black@test.com", LocalDate.of(1988, Month.DECEMBER, 5))
        );
        employeeRepository.saveAll(employees);
        
        // when: querying for employees with names in a specific set
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.IN, "Alice", "Charlie")
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employees with matching names should be returned
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> 
            e.getName().equals("Alice") || e.getName().equals("Charlie")
        ));
    }

    @Test
    void whenNotInOperatorThenCorrectResultsReturned() {
        // given: employees with different names
        List<Employee> employees = List.of(
                new Employee("Alice", "Brown", "alice.brown@test.com", LocalDate.of(1990, Month.JANUARY, 1)),
                new Employee("Bob", "White", "bob.white@test.com", LocalDate.of(1985, Month.JUNE, 15)),
                new Employee("Charlie", "Green", "charlie.green@test.com", LocalDate.of(1995, Month.MARCH, 20)),
                new Employee("Diana", "Black", "diana.black@test.com", LocalDate.of(1988, Month.DECEMBER, 5))
        );
        employeeRepository.saveAll(employees);
        
        // when: querying for employees with names not in a specific set
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("name", Operator.NOT_IN, "Alice", "Charlie")
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("name", Employee_.name)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employees with non-matching names should be returned
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> 
            !e.getName().equals("Alice") && !e.getName().equals("Charlie")
        ));
    }

    @Test
    void whenBetweenOperatorWithDatesThenCorrectResultsReturned() {
        // given: employees with different birth dates
        LocalDate startDate = LocalDate.of(1985, Month.JANUARY, 1);
        LocalDate endDate = LocalDate.of(1992, Month.DECEMBER, 31);
        
        List<Employee> employees = List.of(
                new Employee("Alice", "Brown", "alice.brown@test.com", LocalDate.of(1980, Month.JANUARY, 1)),
                new Employee("Bob", "White", "bob.white@test.com", LocalDate.of(1987, Month.JUNE, 15)),
                new Employee("Charlie", "Green", "charlie.green@test.com", LocalDate.of(1990, Month.MARCH, 20)),
                new Employee("Diana", "Black", "diana.black@test.com", LocalDate.of(1995, Month.DECEMBER, 5))
        );
        employeeRepository.saveAll(employees);
        
        // when: querying for employees with birth dates between start and end
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.BETWEEN, startDate, endDate)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: only employees with birth dates within range should be returned
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> 
            e.getBirthDate().isAfter(startDate) && e.getBirthDate().isBefore(endDate) ||
            e.getBirthDate().equals(startDate) || e.getBirthDate().equals(endDate)
        ));
    }

    @Test
    void whenLessThanOrEqualOperatorWithNumericThenCorrectResultsReturned() {
        // given: employees with different ages (represented by birth dates)
        LocalDate thresholdDate = LocalDate.of(1990, Month.JANUARY, 1);
        
        List<Employee> employees = List.of(
                new Employee("Alice", "Brown", "alice.brown@test.com", LocalDate.of(1985, Month.JANUARY, 1)),
                new Employee("Bob", "White", "bob.white@test.com", LocalDate.of(1990, Month.JUNE, 15)),
                new Employee("Charlie", "Green", "charlie.green@test.com", LocalDate.of(1995, Month.MARCH, 20)),
                new Employee("Diana", "Black", "diana.black@test.com", LocalDate.of(1988, Month.DECEMBER, 5))
        );
        employeeRepository.saveAll(employees);
        
        // when: querying for employees with birth dates <= threshold
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.LE, thresholdDate)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: employees with birth dates on or before threshold should be returned
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(e -> 
            e.getBirthDate().isBefore(thresholdDate) || e.getBirthDate().equals(thresholdDate)
        ));
    }

    @Test
    void whenGreaterThanOrEqualOperatorWithNumericThenCorrectResultsReturned() {
        // given: employees with different ages (represented by birth dates)
        LocalDate thresholdDate = LocalDate.of(1990, Month.JANUARY, 1);
        
        List<Employee> employees = List.of(
                new Employee("Alice", "Brown", "alice.brown@test.com", LocalDate.of(1985, Month.JANUARY, 1)),
                new Employee("Bob", "White", "bob.white@test.com", LocalDate.of(1990, Month.JUNE, 15)),
                new Employee("Charlie", "Green", "charlie.green@test.com", LocalDate.of(1995, Month.MARCH, 20)),
                new Employee("Diana", "Black", "diana.black@test.com", LocalDate.of(1988, Month.DECEMBER, 5))
        );
        employeeRepository.saveAll(employees);
        
        // when: querying for employees with birth dates >= threshold
        final CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE, thresholdDate)
        ));
        
        final Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("birthDate", Employee_.birthDate)
                .build();
        
        List<Employee> result = employeeRepository.findAll(specification);
        
        // then: employees with birth dates on or after threshold should be returned
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> 
            e.getBirthDate().isAfter(thresholdDate) || e.getBirthDate().equals(thresholdDate)
        ));
    }
}