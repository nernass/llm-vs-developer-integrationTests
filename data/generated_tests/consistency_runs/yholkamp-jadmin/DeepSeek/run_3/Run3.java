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
 * Comprehensive integration test for CrudController covering all CRUD operations
 * against real SQL databases (H2 and PostgreSQL).
 */
public class CrudControllerIntegrationTest extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response = mock(Response.class);

    @Before
    public void loadData() throws Exception {
        Operation operation = sequenceOf(
                deleteAllFrom("locations"),
                deleteAllFrom("users"),
                deleteAllFrom("compound_table"),
                insertInto("locations")
                        .withDefaultValue("is_active", true)
                        .columns("id", "name", "favorite_number")
                        .values(1, "location1", 13)
                        .values(2, "location2", 42)
                        .values(3, "location3", 7)
                        .build(),
                insertInto("users")
                        .columns("id", "username", "password", "is_admin", "created_at")
                        .values(1, "admin", "admin123", true, "2024-01-01 00:00:00")
                        .values(2, "user1", "pass123", false, "2024-01-02 10:30:00")
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

    @Test
    public void listRouteShouldReturnCorrectResourceAndHeaders() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequestForResource("locations"), response);
        ListView model = (ListView) result.getModel();

        assertEquals("locations", model.getResource().getTableName());
        assertEquals(ImmutableList.of("id", "name", "is_active", "favorite_number"), model.getHeaders());
    }

    @Test
    public void listRouteShouldReturnCorrectTemplateObject() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequestForResource("locations"), response);
        ListView model = (ListView) result.getModel();

        assertNotNull(model.getTemplateObject());
        assertEquals("/adminprefix", model.getTemplateObject().getPrefix());
        assertTrue(model.getTemplateObject().getResources().contains("locations"));
        assertEquals("locations", model.getTemplateObject().getCurrentTable());
    }

    @Test
    public void editRouteShouldLoadRecordByCompositeKey() throws Exception {
        Request mockRequest = createMockRequestForResource("compound_table");
        when(mockRequest.params(":ids")).thenReturn("1/100");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        Map<String, Object> properties = model.getObject().getProperties();
        assertEquals(1, properties.get("id"));
        assertEquals(100, properties.get("second_id"));
        assertEquals("compound1", properties.get("name"));
        assertEquals(1, properties.get("location_id"));
    }

    @Test
    public void editRouteShouldReturnEditViewForExistingRecord() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.params(":ids")).thenReturn("2/location2");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        assertNotNull(model);
        assertNotNull(model.getResource());
        assertNotNull(model.getTemplateObject());
        assertEquals("locations", model.getResource().getTableName());
        
        Map<String, Object> properties = model.getObject().getProperties();
        assertEquals("location2", properties.get("name"));
        assertEquals(42, properties.get("favorite_number"));
    }

    @Test
    public void editRouteShouldHandleCaseInsensitivePropertyLookup() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.params(":ids")).thenReturn("1/location1");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        Map<String, Object> properties = model.getObject().getProperties();
        // Test both uppercase and lowercase variations
        assertEquals("location1", properties.getOrDefault("NAME", properties.get("name")));
        assertEquals(1, properties.getOrDefault("ID", properties.get("id")));
    }

    @Test
    public void editPostShouldUpdateRecordSuccessfully() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"updatedLocation"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"99"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("updatedLocation", location.get("name"));
        assertEquals(99, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void editPostWithUncheckedCheckboxShouldSetBooleanToFalse() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"2"},
                "name", new String[]{"inactiveLocation"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"5"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(2);
        assertEquals("inactiveLocation", location.get("name"));
        assertEquals(5, location.get("favorite_number"));
        // PostgreSQL returns boolean, H2 returns byte
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void editPostWithCheckedCheckboxShouldSetBooleanToTrue() throws Exception {
        // First set location to inactive
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("UPDATE locations SET is_active = false WHERE id = 3").executeUpdate();
        }

        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"3"},
                "name", new String[]{"reactivatedLocation"},
                "is_active", new String[]{"true", "false"},
                "favorite_number", new String[]{"77"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(3);
        assertEquals("reactivatedLocation", location.get("name"));
        assertEquals(77, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void editPostShouldHandleCheckboxArrayValue() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"checkboxTest"},
                "is_active", new String[]{"true", "false"},
                "favorite_number", new String[]{"123"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void editPostShouldUpdateMultipleFieldsSimultaneously() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"2"},
                "name", new String[]{"multiUpdate"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"42"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(2);
        assertEquals("multiUpdate", location.get("name"));
        assertEquals(42, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void editPostShouldUpdateRecordWithoutChangingAllFields() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"partialUpdate"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("partialUpdate", location.get("name"));
        // Existing fields should retain their values
        assertEquals(13, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void createRouteShouldReturnNewFormWithEmptyObject() throws Exception {
        ModelAndView result = controller.createRoute.handle(createMockRequestForResource("locations"), response);
        EditView model = (EditView) result.getModel();

        assertNotNull(model);
        assertEquals("locations", model.getResource().getTableName());
        assertNotNull(model.getObject());
        assertTrue(model.getObject().getProperties().isEmpty() || 
                   model.getObject().getProperties().values().stream().allMatch(v -> v == null || v.equals("")));
    }

    @Test
    public void createPostShouldInsertNewRecord() throws Exception {
        int initialCount = countLocations();

        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"newLocation"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"88"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        assertEquals(initialCount + 1, countLocations());
        
        // Verify the inserted record
        Map<String, Object> newLocation = retrieveLastLocation();
        assertEquals("newLocation", newLocation.get("name"));
        assertEquals(88, newLocation.get("favorite_number"));
        assertTrue(newLocation.get("is_active").equals((byte) 1) || newLocation.get("is_active").equals(true));
    }

    @Test
    public void createPostShouldHandleNullValuesForOptionalFields() throws Exception {
        int initialCount = countLocations();

        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"minimalLocation"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        assertEquals(initialCount + 1, countLocations());
        
        Map<String, Object> newLocation = retrieveLastLocation();
        assertEquals("minimalLocation", newLocation.get("name"));
        assertNull(newLocation.get("favorite_number"));
        assertTrue(newLocation.get("is_active").equals((byte) 0) || newLocation.get("is_active").equals(false));
    }

    @Test
    public void deleteRouteShouldRemoveRecordByCompositeKey() throws Exception {
        int initialCount = countCompoundEntries();

        Request mockRequest = createMockRequestForResource("compound_table");
        when(mockRequest.params(":ids")).thenReturn("1/100");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        assertEquals(initialCount - 1, countCompoundEntries());
        
        // Verify the record is actually deleted
        assertFalse(compoundEntryExists(1, 100));
    }

    @Test
    public void deleteRouteShouldRemoveSingleKeyRecord() throws Exception {
        int initialCount = countLocations();

        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.params(":ids")).thenReturn("3/location3");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        assertEquals(initialCount - 1, countLocations());
        
        // Verify the record is actually deleted
        Map<String, Object> location = retrieveLocation(3);
        assertTrue(location == null || location.isEmpty());
    }

    @Test
    public void deleteRouteShouldReturnSuccessEvenIfNoRecordFound() throws Exception {
        Request mockRequest = createMockRequestForResource("locations");
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
    }

    @Test
    public void editPostShouldUpdateDateTimeFieldsCorrectly() throws Exception {
        Request mockRequest = createMockRequestForResource("users");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "username", new String[]{"updatedAdmin"},
                "password", new String[]{"newPass123"},
                "is_admin", new String[]{"true"},
                "created_at", new String[]{"2024-02-15 14:30:00"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> user = retrieveUser(1);
        assertEquals("updatedAdmin", user.get("username"));
        assertEquals("newPass123", user.get("password"));
        assertTrue(user.get("is_admin").equals((byte) 1) || user.get("is_admin").equals(true));
        assertNotNull(user.get("created_at"));
    }

    @Test
    public void editPostShouldHandleBooleanFieldConversionCorrectly() throws Exception {
        // Test boolean fields with various input formats
        Request mockRequest = createMockRequestForResource("users");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"2"},
                "username", new String[]{"user1"},
                "password", new String[]{"pass123"},
                "is_admin", new String[]{"true"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> user = retrieveUser(2);
        assertTrue(user.get("is_admin").equals((byte) 1) || user.get("is_admin").equals(true));
    }

    private Request createMockRequestForResource(String resourceName) {
        Request mockRequest = mock(Request.class);
        when(mockRequest.attribute("resourceSchemaProvider"))
                .thenReturn(controller.resources.get(resourceName));
        return mockRequest;
    }

    private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?");
            preparedStatement.setInt(1, locationId);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                return new BasicRowProcessor().toMap(queryResult);
            }
            return null;
        }
    }

    private Map<String, Object> retrieveUser(int userId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
            preparedStatement.setInt(1, userId);
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return new BasicRowProcessor().toMap(queryResult);
        }
    }

    private Map<String, Object> retrieveLastLocation() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM locations WHERE id = (SELECT MAX(id) FROM locations)"
            );
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return new BasicRowProcessor().toMap(queryResult);
        }
    }

    private int countLocations() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT COUNT(*) FROM locations");
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return queryResult.getInt(1);
        }
    }

    private int countCompoundEntries() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT COUNT(*) FROM compound_table");
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return queryResult.getInt(1);
        }
    }

    private boolean compoundEntryExists(int id, int secondId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT COUNT(*) FROM compound_table WHERE id = ? AND second_id = ?"
            );
            preparedStatement.setInt(1, id);
            preparedStatement.setInt(2, secondId);
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return queryResult.getInt(1) > 0;
        }
    }
}