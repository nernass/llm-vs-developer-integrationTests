
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
import org.springframework.transaction.annotation.Transactional;

import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Transactional
class StatistikkApiIT extends EmbeddedPostgresSetup {

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

    private LocalDate seededAtDate;

    @BeforeEach
    void setUpDatabaseState() throws SQLException, NoSuchAlgorithmException {
        seededAtDate = LocalDate.now();
        truncateRestcallLogTable();
        for (RestcallLog restcallLog : TEST_DATA) {
            restcallLogRepository.persistRestcall(restcallLog);
        }
    }

    @AfterEach
    void cleanUpDatabaseState() throws SQLException {
        truncateRestcallLogTable();
    }

    @Test
    void shouldReturnStatisticsGroupedByHashedIpInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(null, null, null);

        assertOkResponse(response,
                "2;" + HASHED_IP_1,
                "1;" + HASHED_IP_2);
    }

    @Test
    void shouldReturnNotFoundForIpStatisticsWhenNoRestcallLogsExist() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(null, null, null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldFilterIpStatisticsWhenDateRangeIncludesSeededRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(
                null,
                seededAtDate.minusDays(1),
                seededAtDate.plusDays(1));

        assertOkResponse(response,
                "2;" + HASHED_IP_1,
                "1;" + HASHED_IP_2);
    }

    @Test
    void shouldReturnStatisticsGroupedByRequestedMethodInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(null, null, null);

        assertOkResponse(response,
                "2;getRegnskap",
                "1;getRegnskapById");
    }

    @Test
    void shouldReturnNotFoundForMethodStatisticsWhenNoRestcallLogsExist() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(null, null, null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnNotFoundForMethodStatisticsWhenFromDateIsAfterSeededRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(
                null,
                seededAtDate.plusDays(1),
                null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnStatisticsGroupedByRequestedOrgnrInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(null, null, null);

        assertOkResponse(response,
                "2;123456789",
                "1;234567890");
    }

    @Test
    void shouldReturnNotFoundForOrgnrStatisticsWhenNoRestcallLogsExist() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(null, null, null);

        assertNotFoundResponse(response);
    }

    @Test
    void shouldReturnNotFoundForOrgnrStatisticsWhenToDateIsBeforeSeededRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(
                null,
                null,
                seededAtDate.minusDays(1));

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
        List<String> body = response.getBody();

        assertAll(
                () -> assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> assertNotNull(body),
                () -> assertEquals(Arrays.asList(expectedRows), body)
        );
    }

    private void assertNotFoundResponse(ResponseEntity<List<String>> response) {
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
