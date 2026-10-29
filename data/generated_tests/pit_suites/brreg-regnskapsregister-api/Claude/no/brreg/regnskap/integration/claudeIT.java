package no.brreg.regnskap.integration;

import no.brreg.regnskap.controller.StatistikkApiImpl;
import no.brreg.regnskap.model.dbo.RestcallLog;
import no.brreg.regnskap.repository.ConnectionManager;
import no.brreg.regnskap.repository.RestcallLogRepository;
import no.brreg.regnskap.utils.EmbeddedPostgresSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class claudeIT extends EmbeddedPostgresSetup {

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
            new RestcallLog("salt", "10.0.0.2", "getRegnskap", "123456789")));

    @BeforeEach
    void resetMocksAndSeedData() throws SQLException, NoSuchAlgorithmException {
        Mockito.reset(httpServletRequestMock);
        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");

        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
        }
        connection.commit();

        for (RestcallLog restcallLog : testData) {
            restcallLogRepository.persistRestcall(restcallLog);
        }
    }

    // --- getStatisticsByIp ---

    @Test
    void shouldReturnStatisticsGroupedByIpInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertTrue(body.get(0).startsWith("2;"), "Most active IP should have count 2");
        assertTrue(body.get(1).startsWith("1;"), "Least active IP should have count 1");
    }

    @Test
    void shouldReturnIpStatisticsWithHashedIpValues() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals("2;MwEi0roqzdZjSX+WElSHwFwQ/48=", body.get(0));
        assertEquals("1;7n3g5s74o7oxbIe1y+wHIldfDHs=", body.get(1));
    }

    // --- getStatisticsByMethod ---

    @Test
    void shouldReturnStatisticsGroupedByMethodInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;getRegnskap", body.get(0));
        assertEquals("1;getRegnskapById", body.get(1));
    }

    // --- getStatisticsByOrgnr ---

    @Test
    void shouldReturnStatisticsGroupedByOrgnrInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;123456789", body.get(0));
        assertEquals("1;234567890", body.get(1));
    }

    // --- Empty database ---

    @Test
    void shouldReturnNotFoundWhenNoRestcallLogRecordsExist() throws SQLException {
        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
        }
        connection.commit();

        ResponseEntity<List<String>> responseByIp = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.NOT_FOUND, responseByIp.getStatusCode());

        ResponseEntity<List<String>> responseByMethod = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.NOT_FOUND, responseByMethod.getStatusCode());

        ResponseEntity<List<String>> responseByOrgnr = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.NOT_FOUND, responseByOrgnr.getStatusCode());
    }

    // --- Date filtering ---

    @Test
    void shouldReturnNotFoundWhenDateRangeMatchesNoRecords() {
        LocalDate farFuture = LocalDate.of(2099, 1, 1);
        LocalDate farFutureEnd = LocalDate.of(2099, 12, 31);

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, farFuture, farFutureEnd);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnAllRecordsWhenDateRangeCoversAllData() {
        LocalDate pastDate = LocalDate.of(2000, 1, 1);
        LocalDate futureDate = LocalDate.of(2099, 12, 31);

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequestMock, pastDate, futureDate);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;getRegnskap", body.get(0));
        assertEquals("1;getRegnskapById", body.get(1));
    }

    @Test
    void shouldFilterByFraDatoOnly() {
        LocalDate pastDate = LocalDate.of(2000, 1, 1);

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, pastDate, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
    }

    @Test
    void shouldFilterByTilDatoOnly() {
        LocalDate futureDate = LocalDate.of(2099, 12, 31);

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, futureDate);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
    }

    // --- Single record ---

    @Test
    void shouldReturnSingleEntryWhenOnlyOneRecordExists() throws SQLException, NoSuchAlgorithmException {
        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
        }
        connection.commit();

        restcallLogRepository.persistRestcall(new RestcallLog("salt", "192.168.1.1", "getRegnskap", "999999999"));

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        assertEquals("1;getRegnskap", body.get(0));
    }

    // --- Null orgnr ---

    @Test
    void shouldHandleRecordWithNullOrgnr() throws SQLException, NoSuchAlgorithmException {
        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
        }
        connection.commit();

        restcallLogRepository.persistRestcall(new RestcallLog("salt", "10.0.0.1", "getRegnskap", null));

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        assertTrue(body.get(0).startsWith("1;"));
    }

    // --- Persistence verification ---

    @Test
    void shouldPersistAndRetrieveMultipleCallsFromSameIp() throws SQLException, NoSuchAlgorithmException {
        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
        }
        connection.commit();

        for (int i = 0; i < 5; i++) {
            restcallLogRepository.persistRestcall(new RestcallLog("salt", "10.0.0.1", "getRegnskap", "111111111"));
        }

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        assertTrue(body.get(0).startsWith("5;"), "Should have count of 5 for the single IP");
    }

    // --- Database readiness ---

    @Test
    void shouldReportDatabaseAsReady() {
        assertTrue(restcallLogRepository.isDatabaseReady());
    }
}
