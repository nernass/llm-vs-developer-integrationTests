package no.brreg.regnskap.integration;

import jakarta.servlet.http.HttpServletRequest;
import no.brreg.regnskap.controller.StatistikkApiImpl;
import no.brreg.regnskap.model.dbo.RestcallLog;
import no.brreg.regnskap.repository.ConnectionManager;
import no.brreg.regnskap.repository.RestcallLogRepository;
import no.brreg.regnskap.utils.EmbeddedPostgresSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class geminiIT_1 extends EmbeddedPostgresSetup {

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    @Mock
    private HttpServletRequest httpServletRequestMock;

    @BeforeEach
    void setupDatabaseAndMocks() throws SQLException, NoSuchAlgorithmException {
        Mockito.reset(httpServletRequestMock);

        truncateRestcallLogTable();

        List<RestcallLog> seedData = Arrays.asList(
                new RestcallLog("salt", "10.0.0.1", "getRegnskapById", "123456789"),
                new RestcallLog("salt", "10.0.0.1", "getRegnskap", "234567890"),
                new RestcallLog("salt", "10.0.0.2", "getRegnskap", "123456789"),
                new RestcallLog("salt", "10.0.0.3", "getRegnskap", "999999999")
        );

        for (RestcallLog log : seedData) {
            restcallLogRepository.persistRestcall(log);
        }
    }

    @AfterEach
    void cleanupEnvironment() throws SQLException {
        truncateRestcallLogTable();
    }

    private void truncateRestcallLogTable() throws SQLException {
        try (Connection connection = connectionManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
            connection.commit();
        }
    }

    @Test
    public void shouldReturnStatisticsGroupedByIpAddress() {
        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(3, body.size(), "Should contain 3 unique IP groups");
        assertTrue(body.get(0).startsWith("2;"), "Most active IP should have 2 calls");
        assertTrue(body.get(1).startsWith("1;"), "Other IPs should have 1 call");
    }

    @Test
    public void shouldReturnStatisticsGroupedByApiMethod() {
        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size(), "Should contain 2 unique methods (getRegnskap, getRegnskapById)");
        assertEquals("3;getRegnskap", body.get(0), "getRegnskap should have 3 calls");
        assertEquals("1;getRegnskapById", body.get(1), "getRegnskapById should have 1 call");
    }

    @Test
    public void shouldReturnStatisticsGroupedByOrganizationNumber() {
        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(3, body.size(), "Should contain 3 unique organization numbers");
        assertEquals("2;123456789", body.get(0), "orgnr 123456789 should have 2 calls");
        assertEquals("1;234567890", body.get(1));
    }

    @Test
    public void shouldReturnEmptyListWhenDatabaseHasNoRecords() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}