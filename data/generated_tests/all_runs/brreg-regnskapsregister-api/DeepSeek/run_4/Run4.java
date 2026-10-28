package no.brreg.regnskap.integration;

import jakarta.servlet.http.HttpServletRequest;
import no.brreg.regnskap.controller.StatistikkApiImpl;
import no.brreg.regnskap.model.dbo.RestcallLog;
import no.brreg.regnskap.repository.ConnectionManager;
import no.brreg.regnskap.repository.RestcallLogRepository;
import no.brreg.regnskap.utils.EmbeddedPostgresSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatistikkApiImplIT extends EmbeddedPostgresSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatistikkApiImplIT.class);

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    @Mock
    private HttpServletRequest httpServletRequestMock;

    private final List<RestcallLog> testData = Collections.unmodifiableList(Arrays.asList(
            new RestcallLog("salt", "10.0.0.1", "getRegnskapById", "123456789"),
            new RestcallLog("salt", "10.0.0.1", "getRegnskap", "234567890"),
            new RestcallLog("salt", "10.0.0.2", "getRegnskap", "123456789"),
            new RestcallLog("salt", "10.0.0.3", "getRegnskap", "123456789") // extra record for more variety
    ));

    @BeforeEach
    void setUp() throws SQLException, NoSuchAlgorithmException {
        Mockito.reset(httpServletRequestMock);

        // Truncate the table to ensure clean state
        try (Connection connection = connectionManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
            connection.commit();
        }

        // Insert predefined test data
        for (RestcallLog log : testData) {
            restcallLogRepository.persistRestcall(log);
        }
    }

    // === Positive test cases ===

    @Test
    void shouldReturnStatisticsGroupedByIpInDescendingOrder() {
        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertEquals(3, body.size()); // three distinct IPs

        // Expected hashes based on the IPs and the salt "salt"
        // IP "10.0.0.1" appears twice -> count 2
        // IP "10.0.0.2" appears once -> count 1
        // IP "10.0.0.3" appears once -> count 1
        // Order: count descending, then by hash (not guaranteed, but we can check counts)
        assertEquals("2;MwEi0roqzdZjSX+WElSHwFwQ/48=", body.get(0)); // 10.0.0.1
        // The other two could be in any order; we'll just check counts
        assertEquals(1, Integer.parseInt(body.get(1).split(";")[0]));
        assertEquals(1, Integer.parseInt(body.get(2).split(";")[0]));
    }

    @Test
    void shouldReturnStatisticsGroupedByMethodInDescendingOrder() {
        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertEquals(2, body.size()); // two distinct methods

        assertEquals("3;getRegnskap", body.get(0)); // called 3 times
        assertEquals("1;getRegnskapById", body.get(1));
    }

    @Test
    void shouldReturnStatisticsGroupedByOrgnrInDescendingOrder() {
        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertEquals(2, body.size()); // two distinct orgnr

        assertEquals("3;123456789", body.get(0)); // appears 3 times
        assertEquals("1;234567890", body.get(1));
    }

    // === Edge cases ===

    @Test
    void shouldReturnNotFoundWhenNoDataMatchesDateRange() {
        // Use a date range in the future that excludes all records
        LocalDate futureFra = LocalDate.now().plusDays(10);
        LocalDate futureTil = LocalDate.now().plusDays(20);

        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, futureFra, futureTil);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void shouldReturnNotFoundWhenTableIsEmpty() throws SQLException, NoSuchAlgorithmException {
        // Clear all data
        try (Connection connection = connectionManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
            connection.commit();
        }

        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void shouldReturnAllRecordsWhenDateRangeIsNull() {
        // This is already covered by the positive tests, but we can explicitly test that null dates return all.
        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().size());
    }

    // Optional: test with a date range that includes some records (if we can control insertion timestamps)
    // Since the persistRestcall uses current time, we cannot set arbitrary timestamps without direct JDBC.
    // However, we could insert records with custom timestamps using raw SQL to test this scenario.
    // For brevity, we skip that here, but it could be added as an improvement.
}