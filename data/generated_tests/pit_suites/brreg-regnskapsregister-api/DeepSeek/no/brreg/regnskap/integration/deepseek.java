package no.brreg.regnskap.integration;

import no.brreg.regnskap.controller.StatistikkApiImpl;
import no.brreg.regnskap.model.dbo.RestcallLog;
import no.brreg.regnskap.repository.ConnectionManager;
import no.brreg.regnskap.repository.RestcallLogRepository;
import no.brreg.regnskap.utils.EmbeddedPostgresSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class deepseek extends EmbeddedPostgresSetup {

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private StatistikkApiImpl statistikkApi;

    @Autowired
    private RestcallLogRepository restcallLogRepository;

    private HttpServletRequest httpServletRequestMock;

    private List<RestcallLog> testData;

    @BeforeEach
    void setUp() throws SQLException, NoSuchAlgorithmException {
        httpServletRequestMock = Mockito.mock(HttpServletRequest.class);
        Mockito.when(httpServletRequestMock.getHeader("Accept")).thenReturn("application/json");

        clearRestcallLog();

        testData = Collections.unmodifiableList(Arrays.asList(
                new RestcallLog("salt", "10.0.0.1", "getRegnskapById", "123456789"),
                new RestcallLog("salt", "10.0.0.1", "getRegnskap", "234567890"),
                new RestcallLog("salt", "10.0.0.2", "getRegnskap", "123456789")
        ));

        for (RestcallLog log : testData) {
            restcallLogRepository.persistRestcall(log);
        }
    }

    @Test
    void shouldReturnStatisticsByIpWhenDataExists() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByIp(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();

        assertNotNull(body);
        assertEquals(2, body.size());

        assertTrue(body.get(0).startsWith("2;"));
        assertTrue(body.get(1).startsWith("1;"));
    }

    @Test
    void shouldReturnStatisticsByMethodWhenDataExists() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByMethod(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();

        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;getRegnskap", body.get(0));
        assertEquals("1;getRegnskapById", body.get(1));
    }

    @Test
    void shouldReturnStatisticsByOrgnrWhenDataExists() {
        ResponseEntity<List<String>> response = statistikkApi.getStatisticsByOrgnr(httpServletRequestMock, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> body = response.getBody();

        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("2;123456789", body.get(0));
        assertEquals("1;234567890", body.get(1));
    }




    @Test
    void shouldReturnNotFoundWhenFilteringWithFutureDateRange() {
        LocalDate futureStart = LocalDate.now().plusDays(10);
        LocalDate futureEnd = LocalDate.now().plusDays(20);

        ResponseEntity<List<String>> response =
                statistikkApi.getStatisticsByIp(httpServletRequestMock, futureStart, futureEnd);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    private void clearRestcallLog() throws SQLException {
        try (Connection conn = connectionManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM rregapi.restcallog")) {
            stmt.executeUpdate();
        }
    }
}
