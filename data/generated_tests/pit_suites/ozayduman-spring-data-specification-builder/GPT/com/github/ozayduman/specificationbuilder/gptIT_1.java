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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.ozayduman.specificationbuilder.SpecificationMappings.SpecificationBuilder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Transactional
@ExtendWith(SpringExtension.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ContextConfiguration(classes = TestConfiguration.class)
public class gptIT_1 {

    @Autowired
    private EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        employeeRepository.saveAll(List.of(
                employee("Alice", "Stone", "alice@example.com", LocalDate.of(1992, 1, 15), SocialSecurityType.A,
                        phone(PhoneType.HOME, "555-1000"), phone(PhoneType.BUSSINES, "555-1001")),
                employee("Bob", "Taylor", "bob@example.com", LocalDate.of(1987, 6, 20), SocialSecurityType.B,
                        phone(PhoneType.HOME, "555-2000")),
                employee("Carol", "Underwood", "carol@example.com", LocalDate.of(2001, 3, 10), SocialSecurityType.B,
                        phone(PhoneType.BUSSINES, "555-3000")),
                employee("David", "Vale", "david@example.com", LocalDate.of(1998, 9, 5), SocialSecurityType.A,
                        phone(PhoneType.HOME, "555-4000"))
        ));
        employeeRepository.flush();
    }

    @AfterEach
    void tearDown() {
        employeeRepository.deleteAll();
        employeeRepository.flush();
    }

    @Test
    void shouldFilterEmployeesByJoinedPhoneNumber() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("phoneNumber", Operator.EQ, "555-1000")));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .bindJoin("phoneNumber", Employee_.phones, Phone_.number)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);

        assertAll(
                () -> assertEquals(1, employees.size()),
                () -> assertEquals("Alice", employees.get(0).getName()),
                () -> assertEquals("alice@example.com", employees.get(0).getEmail())
        );
    }

    @Test
    void shouldCombineScalarAndJoinFiltersAcrossEmployeeAndSocialSecurity() {
        employeeRepository.save(
                employee("David", "Branch", "david.branch@example.com", LocalDate.of(1996, 4, 12), SocialSecurityType.B,
                        phone(PhoneType.BUSSINES, "555-5000"))
        );
        employeeRepository.flush();

        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new SingleValueOperation("employeeName", Operator.EQ, "David"),
                new SingleValueOperation("socialSecurityType", Operator.EQ, SocialSecurityType.A)
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind("employeeName", Employee_.name)
                .bindJoin("socialSecurityType", Employee_.socialSecurity, SocialSecurity_.socialSecurityType)
                .build();

        List<Employee> employees = employeeRepository.findAll(specification);

        assertAll(
                () -> assertEquals(1, employees.size()),
                () -> assertEquals("David", employees.get(0).getName()),
                () -> assertEquals("david@example.com", employees.get(0).getEmail()),
                () -> assertEquals(SocialSecurityType.A, employees.get(0).getSocialSecurity().getSocialSecurityType())
        );
    }

    @Test
    void shouldRestrictResultsUsingBirthDateBetweenOperation() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new RangeValueOperation("birthDate", Operator.BT,
                        new RangeDTO(LocalDate.of(1990, 1, 1), LocalDate.of(1999, 12, 31)))
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.birthDate)
                .build();

        Set<String> employeeNames = employeeRepository.findAll(specification).stream()
                .map(Employee::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("Alice", "David"), employeeNames);
    }

    @Test
    void shouldExcludeEmployeesUsingNotInOperation() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(
                new MultiValueOperation("email", Operator.NOT_IN,
                        new String[]{"alice@example.com", "carol@example.com"})
        ));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.email)
                .build();

        Set<String> employeeEmails = employeeRepository.findAll(specification).stream()
                .map(Employee::getEmail)
                .collect(Collectors.toSet());

        assertEquals(Set.of("bob@example.com", "david@example.com"), employeeEmails);
    }

    @Test
    void shouldBuildPagedAndSortedResultDtoFromRepositoryQuery() {
        employeeRepository.saveAll(List.of(
                employee("Zoe", "Zimmer", "zoe@example.com", LocalDate.of(1992, 1, 15), SocialSecurityType.B,
                        phone(PhoneType.HOME, "555-5001")),
                employee("Aaron", "Young", "aaron@example.com", LocalDate.of(1992, 1, 15), SocialSecurityType.A,
                        phone(PhoneType.BUSSINES, "555-5002"))
        ));
        employeeRepository.flush();

        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setOperations(List.of(
                new SingleValueOperation("birthDate", Operator.GE, LocalDate.of(1992, 1, 15))
        ));
        pageRequestDTO.setPage(0);
        pageRequestDTO.setSize(2);
        pageRequestDTO.setSortFields(new SortDTO[]{
                new SortDTO("birthDate", SortDTO.Direction.ASC),
                new SortDTO("name", SortDTO.Direction.ASC)
        });

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(pageRequestDTO)
                .bind(Employee_.name)
                .bind(Employee_.surname)
                .bind(Employee_.email)
                .bind(Employee_.birthDate)
                .build();

        Page<Employee> page = employeeRepository.findAll(
                specification,
                PageRequestBuilder.of(pageRequestDTO)
                        .bindSort(Employee_.birthDate)
                        .bindSort(Employee_.name)
                        .build()
        );

        PageResultDTO pageResultDTO = PageResultDTO.from(page, this::toResponseDTO);
        @SuppressWarnings("unchecked")
        List<EmployeeResponseDTO> content = (List<EmployeeResponseDTO>) pageResultDTO.getContent();

        assertAll(
                () -> assertEquals(0, pageResultDTO.getCurrentPage()),
                () -> assertEquals(2, pageResultDTO.getSize()),
                () -> assertEquals(5L, pageResultDTO.getTotalElements()),
                () -> assertEquals(3, pageResultDTO.getTotalPages()),
                () -> assertEquals(2, content.size()),
                () -> assertEquals("Aaron", content.get(0).getName()),
                () -> assertEquals("Young", content.get(0).getLastName()),
                () -> assertEquals("aaron@example.com", content.get(0).getEmail()),
                () -> assertEquals(LocalDate.of(1992, 1, 15), content.get(0).getBirthDate()),
                () -> assertEquals("Alice", content.get(1).getName()),
                () -> assertEquals("Stone", content.get(1).getLastName()),
                () -> assertEquals("alice@example.com", content.get(1).getEmail()),
                () -> assertEquals(LocalDate.of(1992, 1, 15), content.get(1).getBirthDate())
        );
    }

    @Test
    void shouldThrowWhenCriteriaContainsUnboundProperty() {
        CriteriaDTO criteriaDTO = new CriteriaDTO();
        criteriaDTO.setOperations(List.of(new SingleValueOperation("unknownProperty", Operator.EQ, "Alice")));

        Specification<Employee> specification = SpecificationBuilder.<Employee>of(criteriaDTO)
                .bind(Employee_.name)
                .build();

        assertThrows(NullPointerException.class, () -> employeeRepository.findAll(specification));
    }

    @Test
    void shouldThrowWhenSortPropertyIsNotBound() {
        PageRequestDTO pageRequestDTO = new PageRequestDTO();
        pageRequestDTO.setSortFields(new SortDTO[]{new SortDTO("unknownSort", SortDTO.Direction.ASC)});

        assertThrows(NullPointerException.class, () -> PageRequestBuilder.of(pageRequestDTO).build());
    }

    private EmployeeResponseDTO toResponseDTO(Employee employee) {
        EmployeeResponseDTO dto = new EmployeeResponseDTO();
        dto.setName(employee.getName());
        dto.setLastName(employee.getSurname());
        dto.setEmail(employee.getEmail());
        dto.setBirthDate(employee.getBirthDate());
        return dto;
    }

    private Employee employee(String name, String surname, String email, LocalDate birthDate,
                              SocialSecurityType socialSecurityType, Phone... phones) {
        Employee employee = Employee.of(name, surname, email, birthDate,
                SocialSecurity.of(socialSecurityType, name + "-security"));
        for (Phone phone : phones) {
            employee.addPhone(phone);
        }
        return employee;
    }

    private Phone phone(PhoneType phoneType, String number) {
        return Phone.builder()
                .phoneType(phoneType)
                .number(number)
                .build();
    }
}