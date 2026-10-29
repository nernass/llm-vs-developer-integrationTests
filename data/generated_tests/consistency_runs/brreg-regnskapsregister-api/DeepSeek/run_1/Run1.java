package no.brreg.regnskap.integration;

import static org.junit.jupiter.api.Assertions.*;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

class StatistikkApiIT extends EmbeddedPostgresSetup {

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    @Mock
    private HttpServletRequest httpServletRequestMock;

    private static final String SALT = "salt";
    private static final String IP1 = "10.0.0.1";
    private static final String IP2 = "10.0.0.2";
    private static final String IP3 = "10.0.0.3";
    private static final String METHOD_GET_REGNESKAP = "getRegnskap";
    private static final String METHOD_GET_REGNESKAP_BY_ID = "getRegnskapById";
    private static final String ORGNR_123 = "123456789";
    private static final String ORGNR_234 = "234567890";

    @BeforeEach
    void setUp() throws SQLException, NoSuchAlgorithmException {
        Mockito.reset(httpServletRequestMock);
        truncateTable();
        insertTestData();
    }

    private void truncateTable() throws SQLException {
        try (Connection connection = connectionManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
            connection.commit();
        }
    }

    private void insertTestData() throws SQLException, NoSuchAlgorithmException {
        // Insert 5 records with fixed timestamps
        insertRecord(IP1, METHOD_GET_REGNESKAP_BY_ID, ORGNR_123, LocalDateTime.of(2024, 1, 1, 10, 0, 0));
        insertRecord(IP1, METHOD_GET_REGNESKAP, ORGNR_234, LocalDateTime.of(2024, 1, 2, 10, 0, 0));
        insertRecord(IP2, METHOD_GET_REGNESKAP, ORGNR_123, LocalDateTime.of(2024, 1, 3, 10, 0, 0));
        insertRecord(IP3, METHOD_GET_REGNESKAP, ORGNR_123, LocalDateTime.of(2024, 1, 4, 10, 0, 0));
        insertRecord(IP3, METHOD_GET_REGNESKAP, ORGNR_123, LocalDateTime.of(2024, 1, 5, 10, 0, 0));
    }

    private void insertRecord(String ip, String method, String orgnr, LocalDateTime timestamp)
            throws SQLException, NoSuchAlgorithmException {
        RestcallLog log = new RestcallLog(SALT, ip, method, orgnr);
        String hashedIp = log.getCallerIp();

        try (Connection connection = connectionManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "INSERT INTO rregapi.restcallog (calleriphash, requestedorgnr, requestedmethod, requestedtime) " +
                     "VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, hashedIp);
            stmt.setString(2, orgnr);
            stmt.setString(3, method);
            stmt.setTimestamp(4, Timestamp.valueOf(timestamp));
            stmt.executeUpdate();
            connection.commit();
        }
    }

    private String hashIp(String ip) throws NoSuchAlgorithmException {
        return new RestcallLog(SALT, ip, null, null).getCallerIp();
    }

    // --- Tests without date filtering (null dates) ---

    @Test
    void shouldReturnStatisticsGroupedByIpInDescendingOrder() throws NoSuchAlgorithmException {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);
        assertThatResponseIsOk(response);

        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(3, body.size());

        // Expected order: IP1 (2 calls), then IP2 (1) and IP3 (1) in any order
        String ip1Hash = hashIp(IP1);
        String ip2Hash = hashIp(IP2);
        String ip3Hash = hashIp(IP3);

        assertEquals("2;" + ip1Hash, body.get(0));
        // The remaining two can appear in either order; we just verify counts
        assertTrue(body.contains("1;" + ip2Hash));
        assertTrue(body.contains("1;" + ip3Hash));
    }

    @Test
    void shouldReturnStatisticsGroupedByMethodInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);
        assertThatResponseIsOk(response);

        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());

        // Expected order: getRegnskap (4), getRegnskapById (1)
        assertEquals("4;" + METHOD_GET_REGNESKAP, body.get(0));
        assertEquals("1;" + METHOD_GET_REGNESKAP_BY_ID, body.get(1));
    }

    @Test
    void shouldReturnStatisticsGroupedByOrgnrInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);
        assertThatResponseIsOk(response);

        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());

        // Expected order: 123456789 (4), 234567890 (1)
        assertEquals("4;" + ORGNR_123, body.get(0));
        assertEquals("1;" + ORGNR_234, body.get(1));
    }

    // --- Tests with date range filtering ---

    @Test
    void shouldFilterStatisticsByDateRange() throws NoSuchAlgorithmException {
        // Filter from 2024-01-02 to 2024-01-04 inclusive
        LocalDate fraDato = LocalDate.of(2024, 1, 2);
        LocalDate tilDato = LocalDate.of(2024, 1, 4);

        // IP statistics
        ResponseEntity<List<String>> ipResponse = statistikkApi.getStatisticsByIp(httpServletRequestMock, fraDato, tilDato);
        assertThatResponseIsOk(ipResponse);
        List<String> ipBody = ipResponse.getBody();
        assertNotNull(ipBody);
        assertEquals(3, ipBody.size()); // All three IPs appear once
        String ip1Hash = hashIp(IP1);
        String ip2Hash = hashIp(IP2);
        String ip3Hash = hashIp(IP3);
        assertTrue(ipBody.contains("1;" + ip1Hash));
        assertTrue(ipBody.contains("1;" + ip2Hash));
        assertTrue(ipBody.contains("1;" + ip3Hash));

        // Method statistics
        ResponseEntity<List<String>> methodResponse = statistikkApi.getStatisticsByMethod(httpServletRequestMock, fraDato, tilDato);
        assertThatResponseIsOk(methodResponse);
        List<String> methodBody = methodResponse.getBody();
        assertNotNull(methodBody);
        assertEquals(2, methodBody.size());
        assertEquals("2;" + METHOD_GET_REGNESKAP, methodBody.get(0)); // Records 2 and 3
        assertEquals("1;" + METHOD_GET_REGNESKAP_BY_ID, methodBody.get(1)); // Record 1

        // Orgnr statistics
        ResponseEntity<List<String>> orgnrResponse = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, fraDato, tilDato);
        assertThatResponseIsOk(orgnrResponse);
        List<String> orgnrBody = orgnrResponse.getBody();
        assertNotNull(orgnrBody);
        assertEquals(2, orgnrBody.size());
        assertEquals("2;" + ORGNR_123, orgnrBody.get(0)); // Records 3 and 4
        assertEquals("1;" + ORGNR_234, orgnrBody.get(1)); // Record 2
    }

    @Test
    void shouldFilterStatisticsWithOnlyFraDato() throws NoSuchAlgorithmException {
        LocalDate fraDato = LocalDate.of(2024, 1, 3);
        // Include from 2024-01-03 onwards

        ResponseEntity<List<String>> ipResponse = statistikkApi.getStatisticsByIp(httpServletRequestMock, fraDato, null);
        assertThatResponseIsOk(ipResponse);
        List<String> ipBody = ipResponse.getBody();
        assertNotNull(ipBody);
        // Records from 2024-01-03: IP2 (once), IP3 (twice) -> IP3 count 2, IP2 count 1
        String ip2Hash = hashIp(IP2);
        String ip3Hash = hashIp(IP3);
        assertEquals(2, ipBody.size());
        assertEquals("2;" + ip3Hash, ipBody.get(0));
        assertEquals("1;" + ip2Hash, ipBody.get(1));

        // Method statistics
        ResponseEntity<List<String>> methodResponse = statistikkApi.getStatisticsByMethod(httpServletRequestMock, fraDato, null);
        assertThatResponseIsOk(methodResponse);
        List<String> methodBody = methodResponse.getBody();
        assertNotNull(methodBody);
        assertEquals(1, methodBody.size());
        assertEquals("3;" + METHOD_GET_REGNESKAP, methodBody.get(0)); // Records 3,4,5

        // Orgnr statistics
        ResponseEntity<List<String>> orgnrResponse = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, fraDato, null);
        assertThatResponseIsOk(orgnrResponse);
        List<String> orgnrBody = orgnrResponse.getBody();
        assertNotNull(orgnrBody);
        assertEquals(1, orgnrBody.size());
        assertEquals("3;" + ORGNR_123, orgnrBody.get(0));
    }

    @Test
    void shouldFilterStatisticsWithOnlyTilDato() throws NoSuchAlgorithmException {
        LocalDate tilDato = LocalDate.of(2024, 1, 2);
        // Include up to 2024-01-02

        ResponseEntity<List<String>> ipResponse = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, tilDato);
        assertThatResponseIsOk(ipResponse);
        List<String> ipBody = ipResponse.getBody();
        assertNotNull(ipBody);
        // Records up to 2024-01-02: IP1 appears twice (records 1 and 2)
        String ip1Hash = hashIp(IP1);
        assertEquals(1, ipBody.size());
        assertEquals("2;" + ip1Hash, ipBody.get(0));

        // Method statistics
        ResponseEntity<List<String>> methodResponse = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, tilDato);
        assertThatResponseIsOk(methodResponse);
        List<String> methodBody = methodResponse.getBody();
        assertNotNull(methodBody);
        assertEquals(2, methodBody.size());
        assertEquals("1;" + METHOD_GET_REGNESKAP, methodBody.get(0)); // Record 2
        assertEquals("1;" + METHOD_GET_REGNESKAP_BY_ID, methodBody.get(1)); // Record 1

        // Orgnr statistics
        ResponseEntity<List<String>> orgnrResponse = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, tilDato);
        assertThatResponseIsOk(orgnrResponse);
        List<String> orgnrBody = orgnrResponse.getBody();
        assertNotNull(orgnrBody);
        assertEquals(2, orgnrBody.size());
        assertEquals("1;" + ORGNR_123, orgnrBody.get(0)); // Record 1
        assertEquals("1;" + ORGNR_234, orgnrBody.get(1)); // Record 2
    }

    // --- Edge cases: empty results ---

    @Test
    void shouldReturnNotFoundWhenNoDataInDateRange() {
        LocalDate fraDato = LocalDate.of(2023, 1, 1);
        LocalDate tilDato = LocalDate.of(2023, 1, 31);

        ResponseEntity<List<String>> ipResponse = statistikkApi.getStatisticsByIp(httpServletRequestMock, fraDato, tilDato);
        assertEquals(HttpStatus.NOT_FOUND, ipResponse.getStatusCode());

        ResponseEntity<List<String>> methodResponse = statistikkApi.getStatisticsByMethod(httpServletRequestMock, fraDato, tilDato);
        assertEquals(HttpStatus.NOT_FOUND, methodResponse.getStatusCode());

        ResponseEntity<List<String>> orgnrResponse = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, fraDato, tilDato);
        assertEquals(HttpStatus.NOT_FOUND, orgnrResponse.getStatusCode());
    }

    @Test
    void shouldReturnNotFoundWhenNoDataAtAll() throws SQLException, NoSuchAlgorithmException {
        truncateTable(); // remove all data

        ResponseEntity<List<String>> ipResponse = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.NOT_FOUND, ipResponse.getStatusCode());

        ResponseEntity<List<String>> methodResponse = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.NOT_FOUND, methodResponse.getStatusCode());

        ResponseEntity<List<String>> orgnrResponse = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);
        assertEquals(HttpStatus.NOT_FOUND, orgnrResponse.getStatusCode());

        // Re-insert data for other tests? Not needed as each test runs in isolation due to @BeforeEach.
    }

    // --- Helper assertion ---

    private void assertThatResponseIsOk(ResponseEntity<?> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}