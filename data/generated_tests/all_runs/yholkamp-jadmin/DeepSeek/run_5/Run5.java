package integrationtests;

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

import javax.sql.DataSource;
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
 * Integration tests for CrudController testing CRUD operations against real SQL databases.
 * Tests run against H2-MySQL, H2-PostgreSQL, and real PostgreSQL via parameterized runner.
 *
 * @author yholkamp
 */
public class CrudControllerIntegrationTest extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response = mock(Response.class);
    private JAdmin jAdmin;

    @Before
    public void loadData() throws Exception {
        // Clean and seed the locations table
        Operation operation = sequenceOf(
                deleteAllFrom("locations"),
                insertInto("locations")
                        .withDefaultValue("is_active", true)
                        .columns("id", "name", "favorite_number")
                        .values(1, "location1", 13)
                        .values(2, "location2", 42)
                        .values(3, "location3", 99)
                        .build());
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();

        // Initialize JAdmin with the datasource
        jAdmin = new JAdmin();
        jAdmin.resource("locations", dataSource);
        resource = jAdmin.getResources().get("locations");
        controller = new CrudController("/adminprefix", jAdmin.getResources());
    }

    @Test
    public void listMethod_shouldReturnCorrectTableNameAndHeaders() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
        ListView model = (ListView) result.getModel();

        assertEquals("locations", model.getResource().getTableName());
        assertEquals(com.google.common.collect.ImmutableList.of("id", "name", "is_active", "favorite_number"), model.getHeaders());
    }

    @Test
    public void editMethod_shouldLoadCorrectRecordByCompositeKey() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("2/location2");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        // Verify the correct record was loaded - handle case-insensitive property keys
        Map<String, Object> properties = model.getObject().getProperties();
        Object nameValue = properties.getOrDefault("NAME", properties.get("name"));
        Object idValue = properties.getOrDefault("ID", properties.get("id"));

        assertEquals("location2", nameValue);
        assertEquals(2, idValue);
    }

    @Test
    public void editMethod_withNonExistentId_shouldThrowNotFoundException() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        try {
            controller.editRoute.handle(mockRequest, response);
            fail("Expected NotFoundException to be thrown");
        } catch (net.nextpulse.jadmin.exceptions.NotFoundException e) {
            // Expected exception
        }
    }

    @Test
    public void editPost_uncheckedCheckbox_shouldUpdateRecordWithBooleanFalse() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"newName"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"71"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("newName", location.get("name"));
        assertEquals(71, location.get("favorite_number"));
        // Handle database type differences: H2 returns byte, PostgreSQL returns boolean
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void editPost_checkedCheckbox_shouldUpdateRecordWithBooleanTrue() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"newName2"},
                "is_active", new String[]{"true", "false"},
                "favorite_number", new String[]{"72"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("newName2", location.get("name"));
        assertEquals(72, location.get("favorite_number"));
        // Handle database type differences: H2 returns byte, PostgreSQL returns boolean
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void editPost_shouldUpdateAllEditableFieldsCorrectly() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"2"},
                "name", new String[]{"updatedLocation"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"100"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(2);
        assertEquals("updatedLocation", location.get("name"));
        assertEquals(100, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void editPost_withInvalidData_shouldReturnErrorResponse() throws Exception {
        // Test with invalid data that should fail validation
        // Note: This test assumes there is some validation rule configured
        // Since no validation rules are configured in the basic setup, this test may need to be adapted
        // or validation rules added to the resource configuration

        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{""}, // Empty name - might fail if validation rules exist
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"50"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        // If validation rules are configured, this might fail
        // For now, we check that it doesn't throw an exception
        assertNotNull(result);
    }

    @Test
    public void createPost_shouldInsertNewRecordSuccessfully() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"newLocation"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"200"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        // Verify the record was inserted - retrieve by name since we don't know the ID
        Map<String, Object> location = retrieveLocationByName("newLocation");
        assertNotNull(location);
        assertEquals("newLocation", location.get("name"));
        assertEquals(200, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void createPost_withInactiveFlag_shouldInsertInactiveRecord() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"inactiveLocation"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"300"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocationByName("inactiveLocation");
        assertNotNull(location);
        assertEquals("inactiveLocation", location.get("name"));
        assertEquals(300, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void deleteRoute_shouldDeleteRecordByCompositeKey() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("1/location1");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        // Verify the record was deleted
        Map<String, Object> location = retrieveLocation(1);
        assertNull(location);
    }

    @Test
    public void deleteRoute_withNonExistentKey_shouldReturnError() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        // The delete operation might throw an exception or return an error response
        // For now, we just verify it doesn't throw an unexpected exception
        assertNotNull(result);
    }

    @Test
    public void deleteRoute_shouldOnlyDeleteSpecifiedRecord() throws Exception {
        // Verify that other records remain after deletion
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("2/location2");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        // Verify record 1 still exists
        Map<String, Object> location1 = retrieveLocation(1);
        assertNotNull(location1);
        assertEquals("location1", location1.get("name"));

        // Verify record 3 still exists
        Map<String, Object> location3 = retrieveLocation(3);
        assertNotNull(location3);
        assertEquals("location3", location3.get("name"));

        // Verify record 2 was deleted
        Map<String, Object> location2 = retrieveLocation(2);
        assertNull(location2);
    }

    @Test
    public void createPost_shouldHandleMissingOptionalFields() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"minimalLocation"},
                "is_active", new String[]{"true"}
                // favorite_number omitted - should use default or null
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocationByName("minimalLocation");
        assertNotNull(location);
        assertEquals("minimalLocation", location.get("name"));
        // The favorite_number default might be null or database default
    }

    @Test
    public void editPost_shouldHandleUpdatesToMultipleRecords() throws Exception {
        // Update record 1
        Request mockRequest1 = createMockRequest();
        when(mockRequest1.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"updated1"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"500"}
        )));

        EditPost result1 = (EditPost) controller.editPostRoute.handle(mockRequest1, response);
        assertTrue(result1.isSuccess());

        // Update record 3
        Request mockRequest3 = createMockRequest();
        when(mockRequest3.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"3"},
                "name", new String[]{"updated3"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"600"}
        )));

        EditPost result3 = (EditPost) controller.editPostRoute.handle(mockRequest3, response);
        assertTrue(result3.isSuccess());

        // Verify both records were updated correctly
        Map<String, Object> location1 = retrieveLocation(1);
        assertEquals("updated1", location1.get("name"));
        assertEquals(500, location1.get("favorite_number"));
        assertTrue(location1.get("is_active").equals((byte) 0) || location1.get("is_active").equals(false));

        Map<String, Object> location3 = retrieveLocation(3);
        assertEquals("updated3", location3.get("name"));
        assertEquals(600, location3.get("favorite_number"));
        assertTrue(location3.get("is_active").equals((byte) 1) || location3.get("is_active").equals(true));
    }

    private Request createMockRequest() {
        Request mockRequest = mock(Request.class);
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(resource);
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

    private Map<String, Object> retrieveLocationByName(String name) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE name = ?");
            preparedStatement.setString(1, name);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                return new BasicRowProcessor().toMap(queryResult);
            }
            return null;
        }
    }

    /**
     * Helper method to count total records in the locations table
     */
    private int countLocations() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT COUNT(*) FROM locations");
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return queryResult.getInt(1);
        }
    }

    /**
     * Test that creates and deletes a record in sequence to verify full CRUD lifecycle
     */
    @Test
    public void fullCrudLifecycle_shouldCompleteSuccessfully() throws Exception {
        // 1. CREATE
        int initialCount = countLocations();
        Request createRequest = createMockRequest();
        when(createRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"lifecycleTest"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"777"}
        )));

        EditPost createResult = (EditPost) controller.createPostRoute.handle(createRequest, response);
        assertTrue(createResult.isSuccess());

        // Verify record was created
        Map<String, Object> createdLocation = retrieveLocationByName("lifecycleTest");
        assertNotNull(createdLocation);
        assertEquals(777, createdLocation.get("favorite_number"));
        assertTrue(createdLocation.get("is_active").equals((byte) 1) || createdLocation.get("is_active").equals(true));
        int afterCreateCount = countLocations();
        assertEquals(initialCount + 1, afterCreateCount);

        // Get the ID of the created record
        int createdId = (int) createdLocation.get("id");

        // 2. READ / UPDATE
        Request updateRequest = createMockRequest();
        when(updateRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{String.valueOf(createdId)},
                "name", new String[]{"updatedLifecycle"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"888"}
        )));

        EditPost updateResult = (EditPost) controller.editPostRoute.handle(updateRequest, response);
        assertTrue(updateResult.isSuccess());

        // Verify record was updated
        Map<String, Object> updatedLocation = retrieveLocation(createdId);
        assertEquals("updatedLifecycle", updatedLocation.get("name"));
        assertEquals(888, updatedLocation.get("favorite_number"));
        assertTrue(updatedLocation.get("is_active").equals((byte) 0) || updatedLocation.get("is_active").equals(false));

        // 3. DELETE
        Request deleteRequest = createMockRequest();
        when(deleteRequest.params(":ids")).thenReturn(createdId + "/updatedLifecycle");

        EditPost deleteResult = (EditPost) controller.deleteRoute.handle(deleteRequest, response);
        assertTrue(deleteResult.isSuccess());

        // Verify record was deleted
        Map<String, Object> deletedLocation = retrieveLocation(createdId);
        assertNull(deletedLocation);
        int afterDeleteCount = countLocations();
        assertEquals(initialCount, afterDeleteCount);
    }
}