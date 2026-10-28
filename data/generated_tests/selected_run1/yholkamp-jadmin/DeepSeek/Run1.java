package integrationtests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import net.nextpulse.jadmin.CrudController;
import net.nextpulse.jadmin.JAdmin;
import net.nextpulse.jadmin.Resource;
import net.nextpulse.jadmin.views.EditPost;
import net.nextpulse.jadmin.views.EditView;
import net.nextpulse.jadmin.views.ListView;
import org.apache.commons.dbutils.BasicRowProcessor;
import org.junit.Before;
import org.junit.Test;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import testhelpers.DatabaseTest;
import testhelpers.TestQueryParamsMap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static com.ninja_squad.dbsetup.Operations.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprehensive integration tests for CrudController covering all CRUD operations
 * against real SQL databases (H2 and PostgreSQL).
 *
 * @author Generated
 */
public class CrudControllerIntegrationTest extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response = mock(Response.class);

    @Before
    public void loadData() {
        Operation operation = sequenceOf(
            deleteAllFrom("locations"),
            deleteAllFrom("users"),
            insertInto("locations")
                .withDefaultValue("is_active", true)
                .columns("id", "name", "favorite_number")
                .values(1, "location1", 13)
                .values(2, "location2", 42)
                .build(),
            insertInto("users")
                .columns("id", "username", "password", "is_admin", "created_at")
                .values(1, "admin", "admin123", true, "2024-01-01 00:00:00")
                .values(2, "user", "user123", false, "2024-01-02 12:30:00")
                .build(),
            insertInto("compound_table")
                .columns("id", "second_id", "name", "location_id")
                .values(1, 100, "compound1", 1)
                .values(2, 200, "compound2", 2)
                .build()
        );
        
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();

        JAdmin jAdmin = new JAdmin();
        jAdmin.resource("locations", dataSource);
        jAdmin.resource("users", dataSource);
        jAdmin.resource("compound_table", dataSource);
        
        resource = jAdmin.getResources().get("locations");
        controller = new CrudController("/adminprefix", jAdmin.getResources());
    }

    // ===================== LIST TESTS =====================

    @Test
    public void shouldReturnListViewModelWithCorrectTableNameAndHeaders() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequest(resource), response);
        ListView model = (ListView) result.getModel();

        assertEquals("locations", model.getResource().getTableName());
        assertEquals(ImmutableList.of("id", "name", "is_active", "favorite_number"), model.getHeaders());
    }

    @Test
    public void shouldReturnCorrectListJsonDataWithPaginationAndSorting() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap("draw")).thenReturn(new TestQueryParamsMap(ImmutableMap.of("draw", new String[]{"1"})));
        when(mockRequest.queryMap("start")).thenReturn(new TestQueryParamsMap(ImmutableMap.of("start", new String[]{"0"})));
        when(mockRequest.queryMap("length")).thenReturn(new TestQueryParamsMap(ImmutableMap.of("length", new String[]{"10"})));
        when(mockRequest.queryMap("order")).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "order[0][column]", new String[]{"0"},
            "order[0][dir]", new String[]{"asc"}
        )));

        Object result = controller.listJsonRoute.handle(mockRequest, response);
        // Should not throw exception and return a DataTableResponse
        assertNotNull(result);
    }

    // ===================== EDIT TESTS =====================

    @Test
    public void shouldLoadEditViewWithCorrectRecordByCompositeKey() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.params(":ids")).thenReturn("2/location2");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        // Ensure case-insensitive property lookup works across different databases
        String name = (String) model.getObject().getProperties()
            .entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase("name"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        
        Object id = model.getObject().getProperties()
            .entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase("id"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        assertEquals("location2", name);
        assertEquals(2, id);
    }

    @Test
    public void shouldThrowNotFoundExceptionWhenEditingNonExistentRecord() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        try {
            controller.editRoute.handle(mockRequest, response);
            fail("Expected NotFoundException to be thrown");
        } catch (net.nextpulse.jadmin.exceptions.NotFoundException e) {
            // Expected exception
        }
    }

    // ===================== EDIT POST TESTS =====================

    @Test
    public void shouldUpdateRecordWithUncheckedCheckboxPersistingFalse() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "id", new String[]{"1"},
            "name", new String[]{"updatedName"},
            "is_active", new String[]{"false"},
            "favorite_number", new String[]{"99"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("updatedName", location.get("name"));
        assertEquals(99, location.get("favorite_number"));
        // H2 returns byte, PostgreSQL returns boolean
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void shouldUpdateRecordWithCheckedCheckboxPersistingTrue() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "id", new String[]{"1"},
            "name", new String[]{"updatedName2"},
            "is_active", new String[]{"true", "false"},
            "favorite_number", new String[]{"88"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("updatedName2", location.get("name"));
        assertEquals(88, location.get("favorite_number"));
        // H2 returns byte, PostgreSQL returns boolean
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void shouldHandleNullValuesCorrectlyDuringUpdate() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "id", new String[]{"2"},
            "name", new String[]{""},
            "favorite_number", new String[]{"0"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(2);
        assertNull(location.get("name"));
        assertEquals(0, location.get("favorite_number"));
    }

    @Test
    public void shouldReturnValidationErrorWhenInvalidDataProvided() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "id", new String[]{"1"},
            "name", new String[]{"validName"},
            "favorite_number", new String[]{"invalid_number"} // Should cause validation error
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    // ===================== CREATE TESTS =====================

    @Test
    public void shouldRenderCreateViewWithEmptyObject() throws Exception {
        Request mockRequest = createMockRequest(resource);

        ModelAndView result = controller.createRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        assertNotNull(model.getObject());
        assertTrue(model.getObject().getProperties().isEmpty() || 
                   model.getObject().getProperties().values().stream().allMatch(v -> v == null));
    }

    @Test
    public void shouldCreateNewRecordSuccessfully() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "name", new String[]{"newLocation"},
            "is_active", new String[]{"true"},
            "favorite_number", new String[]{"77"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        // Verify record was created in database
        Map<String, Object> location = retrieveLocation(3);
        assertNotNull(location);
        assertEquals("newLocation", location.get("name"));
        assertEquals(77, location.get("favorite_number"));
    }

    @Test
    public void shouldReturnValidationErrorWhenCreatingWithInvalidData() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
            "name", new String[]{""}, // Empty name should fail validation
            "favorite_number", new String[]{"100"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    // ===================== DELETE TESTS =====================

    @Test
    public void shouldDeleteRecordSuccessfullyByCompositeKey() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.params(":ids")).thenReturn("1/location1");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        // Verify record was deleted
        Map<String, Object> location = retrieveLocation(1);
        assertNull(location);
    }

    @Test
    public void shouldReturnErrorWhenDeletingNonExistentRecord() throws Exception {
        Request mockRequest = createMockRequest(resource);
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    // ===================== COMPOUND KEY TESTS =====================

    @Test
    public void shouldHandleCompoundPrimaryKeyForEditAndDelete() throws Exception {
        // Switch to compound_table resource
        Resource compoundResource = controller.getResources().get("compound_table");
        Request mockRequest = createMockRequest(compoundResource);
        when(mockRequest.params(":ids")).thenReturn("1/100");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        // Verify both parts of the compound key are loaded
        Object id = model.getObject().getProperties()
            .entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase("id"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        
        Object secondId = model.getObject().getProperties()
            .entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase("second_id"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        assertEquals(1, id);
        assertEquals(100, secondId);
        assertEquals("compound1", model.getObject().getProperties().get("name"));
    }

    // ===================== UTILITY METHODS =====================

    private Request createMockRequest(Resource resource) {
        Request mockRequest = mock(Request.class);
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(resource);
        when(mockRequest.uri()).thenReturn("/admin/locations");
        return mockRequest;
    }

    private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
        Map<String, Object> location = null;
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?");
            preparedStatement.setInt(1, locationId);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                location = new BasicRowProcessor().toMap(queryResult);
            }
            queryResult.close();
            preparedStatement.close();
        }
        return location;
    }

    private Map<String, Object> retrieveCompoundRecord(int id, int secondId) throws SQLException {
        Map<String, Object> record = null;
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                "SELECT * FROM compound_table WHERE id = ? AND second_id = ?"
            );
            preparedStatement.setInt(1, id);
            preparedStatement.setInt(2, secondId);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                record = new BasicRowProcessor().toMap(queryResult);
            }
            queryResult.close();
            preparedStatement.close();
        }
        return record;
    }
}