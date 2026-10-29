
package no.brreg.regnskap.integration;

import no.brreg.regnskap.controller.StatistikkApiImpl;
import no.brreg.regnskap.model.dbo.RestcallLog;
import no.brreg.regnskap.repository.ConnectionManager;
import no.brreg.regnskap.repository.RestcallLogRepository;
import no.brreg.regnskap.utils.EmbeddedPostgresSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Transactional
class StatistikkApiImplIT extends EmbeddedPostgresSetup {

    private static final String HASHED_IP_1 = "MwEi0roqzdZjSX+WElSHwFwQ/48=";
    private static final String HASHED_IP_2 = "7n3g5s74o7oxbIe1y+wHIldfDHs=";

    private static final List<RestcallLog> TEST_DATA = Collections.unmodifiableList(Arrays.asList(
            new RestcallLog("salt", "10.0.0.1", "getRegnskapById", "123456789"),
            new RestcallLog("salt", "10.0.0.1", "getRegnskap", "234567890"),
            new RestcallLog("salt", "10.0.0.2", "getRegnskap", "123456789")
    ));

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    private MockHttpServletRequest httpServletRequest;
    private LocalDate seededDate;

    @BeforeEach
    void setUp() throws SQLException, NoSuchAlgorithmException {
        httpServletRequest = new MockHttpServletRequest();
        httpServletRequest.addHeader("Accept", "application/json");
        seededDate = LocalDate.now();

        truncateRestcallLogTable();
        for (RestcallLog restcallLog : TEST_DATA) {
            restcallLogRepository.persistRestcall(restcallLog);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        truncateRestcallLogTable();
    }

    @Test
    void shouldReturnStatisticsGroupedByIpInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequest, null, null);

        assertOkResponse(response,
                "2;" + HASHED_IP_1,
                "1;" + HASHED_IP_2);
    }

    @Test
    void shouldReturnNotFoundWhenNoIpStatisticsExist() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequest, null, null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnIpStatisticsWhenDateRangeIncludesPersistedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(
                httpServletRequest,
                seededDate.minusDays(1),
                seededDate.plusDays(1));

        assertOkResponse(response,
                "2;" + HASHED_IP_1,
                "1;" + HASHED_IP_2);
    }

    @Test
    void shouldReturnNotFoundWhenIpDateRangeExcludesPersistedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(
                httpServletRequest,
                seededDate.plusDays(1),
                null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnStatisticsGroupedByMethodInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequest, null, null);

        assertOkResponse(response,
                "2;getRegnskap",
                "1;getRegnskapById");
    }

    @Test
    void shouldReturnNotFoundWhenNoMethodStatisticsExist() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequest, null, null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnMethodStatisticsWhenDateRangeIncludesPersistedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(
                httpServletRequest,
                seededDate.minusDays(1),
                seededDate.plusDays(1));

        assertOkResponse(response,
                "2;getRegnskap",
                "1;getRegnskapById");
    }

    @Test
    void shouldReturnNotFoundWhenMethodDateRangeExcludesPersistedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(
                httpServletRequest,
                null,
                seededDate.minusDays(1));

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnStatisticsGroupedByOrgnrInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequest, null, null);

        assertOkResponse(response,
                "2;123456789",
                "1;234567890");
    }

    @Test
    void shouldReturnNotFoundWhenNoOrgnrStatisticsExist() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequest, null, null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnOrgnrStatisticsWhenDateRangeIncludesPersistedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(
                httpServletRequest,
                seededDate.minusDays(1),
                seededDate.plusDays(1));

        assertOkResponse(response,
                "2;123456789",
                "1;234567890");
    }

    @Test
    void shouldReturnNotFoundWhenOrgnrDateRangeExcludesPersistedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(
                httpServletRequest,
                seededDate.plusDays(1),
                null);

        assertNotFoundResponse(response);
    }

    private void truncateRestcallLogTable() throws SQLException {
        try (Connection connection = connectionManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            statement.executeUpdate();
            connection.commit();
        }
    }

    private void assertOkResponse(ResponseEntity<List<String>> response, String... expectedRows) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();
        assertNotNull(body);
        assertEquals(Arrays.asList(expectedRows), body);
    }

    private void assertNotFoundResponse(ResponseEntity<List<String>> response) {
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }
}
