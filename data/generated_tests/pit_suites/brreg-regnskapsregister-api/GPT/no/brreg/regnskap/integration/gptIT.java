package no.brreg.regnskap.integration;

import no.brreg.regnskap.controller.StatistikkApiImpl;
import no.brreg.regnskap.model.dbo.RestcallLog;
import no.brreg.regnskap.repository.ConnectionManager;
import no.brreg.regnskap.repository.RestcallLogRepository;
import no.brreg.regnskap.utils.EmbeddedPostgresSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

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

public class gptIT extends EmbeddedPostgresSetup {

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    private final List<RestcallLog> seedData = Collections.unmodifiableList(Arrays.asList(
            new RestcallLog("salt", "10.0.0.1", "getRegnskapById", "123456789"),
            new RestcallLog("salt", "10.0.0.1", "getRegnskap", "234567890"),
            new RestcallLog("salt", "10.0.0.2", "getRegnskap", "123456789")));

    @BeforeEach
    void resetDatabaseAndSeedData() throws SQLException, NoSuchAlgorithmException {
        truncateRestcallLogTable();
        for (RestcallLog restcallLog : seedData) {
            restcallLogRepository.persistRestcall(restcallLog);
        }
    }

    @Test
    void shouldReturnStatisticsByIpWithHashedValuesInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(createRequest(), null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("2;MwEi0roqzdZjSX+WElSHwFwQ/48=", response.getBody().get(0));
        assertEquals("1;7n3g5s74o7oxbIe1y+wHIldfDHs=", response.getBody().get(1));
    }

    @Test
    void shouldReturnStatisticsByMethodInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(createRequest(), null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("2;getRegnskap", response.getBody().get(0));
        assertEquals("1;getRegnskapById", response.getBody().get(1));
    }

    @Test
    void shouldReturnStatisticsByOrgnrInDescendingOrder() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(createRequest(), null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("2;123456789", response.getBody().get(0));
        assertEquals("1;234567890", response.getBody().get(1));
    }

    @Test
    void shouldReturnNotFoundForIpStatisticsWhenNoDataExists() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(createRequest(), null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnNotFoundForMethodStatisticsWhenNoDataExists() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(createRequest(), null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnNotFoundForOrgnrStatisticsWhenNoDataExists() throws SQLException {
        truncateRestcallLogTable();

        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(createRequest(), null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnNotFoundWhenFraDatoExcludesAllRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(
                createRequest(),
                LocalDate.of(2099, 1, 1),
                null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldReturnAllMethodStatisticsWhenTilDatoIncludesSeedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(
                createRequest(),
                null,
                LocalDate.of(2099, 1, 1));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("2;getRegnskap", response.getBody().get(0));
        assertEquals("1;getRegnskapById", response.getBody().get(1));
    }

    @Test
    void shouldReturnAllOrgnrStatisticsWhenDateRangeCoversSeedRows() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(
                createRequest(),
                LocalDate.of(2000, 1, 1),
                LocalDate.of(2099, 1, 1));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("2;123456789", response.getBody().get(0));
        assertEquals("1;234567890", response.getBody().get(1));
    }

    private void truncateRestcallLogTable() throws SQLException {
        try (Connection connection = connectionManager.getConnection();
             PreparedStatement stmt = connection.prepareStatement("TRUNCATE rregapi.restcallog")) {
            stmt.executeUpdate();
            connection.commit();
        }
    }

    private MockHttpServletRequest createRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "application/json");
        return request;
    }
}