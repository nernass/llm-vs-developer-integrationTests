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
import org.mockito.MockitoAnnotations;
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

import static org.junit.jupiter.api.Assertions.*;


class StatistikkApiIT extends EmbeddedPostgresSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatistikkApiIT.class);

    // Pre-computed SHA-1/Base64 hashes produced by RestcallLog("salt", ip, ...)
    private static final String HASHED_IP_1 = "MwEi0roqzdZjSX+WElSHwFwQ/48="; // "10.0.0.1"
    private static final String HASHED_IP_2 = "7n3g5s74o7oxbIe1y+wHIldfDHs="; // "10.0.0.2"

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    @Mock
    private HttpServletRequest httpServletRequestMock;

    /**
     * Seed data distributions:
     *   IP:     10.0.0.1 → 2 calls  |  10.0.0.2 → 1 call
     *   Method: getRegnskap → 2      |  getRegnskapById → 1
     *   Orgnr:  123456789 → 2        |  234567890 → 1
     */
    private static final List<RestcallLog> SEED_DATA = Collections.unmodifiableList(Arrays.asList(
            new RestcallLog("salt", "10.0.0.1", "getRegnskapById", "123456789"),
            new RestcallLog("salt", "10.0.0.1", "getRegnskap",     "234567890"),
            new RestcallLog("salt", "10.0.0.2", "getRegnskap",     "123456789")));

    @BeforeEach
    void setUpMocksAndSeedDatabase() throws SQLException, NoSuchAlgorithmException {
        MockitoAnnotations.openMocks(this);
        truncateRestcallLog();
        for (RestcallLog log : SEED_DATA) {
            restcallLogRepository.persistRestcall(log);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getStatisticsByIp
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnIpStatisticsInDescendingOrderOfCallCount() {
        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;" + HASHED_IP_1, body.get(0), "Most active IP should appear first");
        assertEquals("1;" + HASHED_IP_2, body.get(1), "Least active IP should appear second");
    }

    @Test
    void shouldReturnNotFoundByIpWhenTableIsEmpty() throws SQLException {
        truncateRestcallLog();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnIpStatisticsWhenFromDateMatchesTodaysRecords() {
        LocalDate today = LocalDate.now();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, today, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size(), "All records inserted today should be included");
    }

    @Test
    void shouldReturnNotFoundByIpWhenTilDateIsBeforeAllRecords() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, null, yesterday);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Records inserted today must not match a tilDato of yesterday");
    }

    @Test
    void shouldReturnNotFoundByIpWhenFromDateIsAfterAllRecords() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, tomorrow, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Records inserted today must not match a fraDato of tomorrow");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getStatisticsByMethod
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnMethodStatisticsInDescendingOrderOfCallCount() {
        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;getRegnskap",     body.get(0), "Most-called method should appear first");
        assertEquals("1;getRegnskapById", body.get(1), "Least-called method should appear second");
    }

    @Test
    void shouldReturnNotFoundByMethodWhenTableIsEmpty() throws SQLException {
        truncateRestcallLog();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnMethodStatisticsWhenFromDateMatchesTodaysRecords() {
        LocalDate today = LocalDate.now();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByMethod(httpServletRequestMock, today, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size(), "All records inserted today should be included");
    }

    @Test
    void shouldReturnNotFoundByMethodWhenTilDateIsBeforeAllRecords() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, yesterday);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Records inserted today must not match a tilDato of yesterday");
    }

    @Test
    void shouldReturnNotFoundByMethodWhenFromDateIsAfterAllRecords() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByMethod(httpServletRequestMock, tomorrow, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Records inserted today must not match a fraDato of tomorrow");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // getStatisticsByOrgnr
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnOrgnrStatisticsInDescendingOrderOfCallCount() {
        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;123456789", body.get(0), "Most-queried orgnr should appear first");
        assertEquals("1;234567890", body.get(1), "Least-queried orgnr should appear second");
    }

    @Test
    void shouldReturnNotFoundByOrgnrWhenTableIsEmpty() throws SQLException {
        truncateRestcallLog();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnOrgnrStatisticsWhenFromDateMatchesTodaysRecords() {
        LocalDate today = LocalDate.now();

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, today, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size(), "All records inserted today should be included");
    }

    @Test
    void shouldReturnNotFoundByOrgnrWhenTilDateIsBeforeAllRecords() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, yesterday);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Records inserted today must not match a tilDato of yesterday");
    }

    @Test
    void shouldReturnNotFoundByOrgnrWhenFromDateIsAfterAllRecords() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, tomorrow, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "Records inserted today must not match a fraDato of tomorrow");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void truncateRestcallLog() throws SQLException {
        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
        }
        connection.commit();
    }
}