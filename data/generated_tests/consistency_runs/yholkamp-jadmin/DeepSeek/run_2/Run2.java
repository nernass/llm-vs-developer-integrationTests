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
 * Integration tests for CrudController that validate CRUD operations against real databases.
 * Tests are parameterized to run against H2 (MySQL mode), H2 (PostgreSQL mode), and real PostgreSQL.
 *
 * @author Generated
 */
public class CrudControllerIntegrationTest extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response = mock(Response.class);

    @Before
    public void loadData() throws Exception {
        // Clean and seed the locations table with test data
        Operation operation = sequenceOf(
                deleteAllFrom("locations"),
                deleteAllFrom("compound_table"),
                deleteAllFrom("users"),
                insertInto("locations")
                        .withDefaultValue("is_active", true)
                        .columns("id", "name", "favorite_number")
                        .values(1, "location1", 13)
                        .values(2, "location2", 42)
                        .build(),
                insertInto("compound_table")
                        .columns("id", "second_id", "name", "location_id")
                        .values(1, 100, "compound1", 1)
                        .values(2, 200, "compound2", 2)
                        .build(),
                insertInto("users")
                        .columns("id", "username", "password", "is_admin")
                        .values(1, "admin", "secret", true)
                        .values(2, "user", "pass", false)
                        .build()
        );
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();

        // Initialize JAdmin with the dataSource and create CrudController
        JAdmin jAdmin = new JAdmin();
        jAdmin.resource("locations", dataSource);
        jAdmin.resource("compound_table", dataSource);
        jAdmin.resource("users", dataSource);
        
        resource = jAdmin.getResources().get("locations");
        controller = new CrudController("/adminprefix", jAdmin.getResources());
    }

    @Test
    public void listMethod_shouldReturnCorrectTableNameAndHeaders() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
        ListView model = (ListView) result.getModel();
        
        assertEquals("locations", model.getResource().getTableName());
        assertEquals(ImmutableList.of("id", "name", "is_active", "favorite_number"), model.getHeaders());
    }

    @Test
    public void editMethod_withValidCompositeKey_shouldLoadCorrectRecord() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("2/location2");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        // Ensure the name and id match, both in case-insensitive fashion
        assertEquals("location2", model.getObject().getProperties().getOrDefault("NAME", 
                model.getObject().getProperties().get("name")));
        assertEquals(2, model.getObject().getProperties().getOrDefault("ID", 
                model.getObject().getProperties().get("id")));
    }

    @Test
    public void editMethod_withNonExistentRecord_shouldThrowNotFoundException() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        try {
            controller.editRoute.handle(mockRequest, response);
            fail("Expected NotFoundException was not thrown");
        } catch (net.nextpulse.jadmin.exceptions.NotFoundException e) {
            // Expected exception
        }
    }

    @Test
    public void editPost_uncheckedCheckbox_shouldPersistBooleanFalse() throws Exception {
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
        // PG returns boolean, H2 returns byte
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void editPost_checkedCheckbox_shouldPersistBooleanTrue() throws Exception {
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
        // PG returns boolean, H2 returns byte
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void editPost_withInvalidData_shouldReturnFailure() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"newName"},
                "favorite_number", new String[]{"not_a_number"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void editPost_withMissingRequiredField_shouldReturnFailure() throws Exception {
        Request mockRequest = createMockRequest();
        // Missing name field which might be required in the schema
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"42"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        // Should fail because name is required (not null in schema)
        assertFalse(result.isSuccess());
    }

    @Test
    public void editPost_withCompositeKey_shouldUpdateCorrectRecord() throws Exception {
        // First verify initial state of compound_table
        Map<String, Object> initialCompound = retrieveCompoundRecord(1, 100);
        assertEquals("compound1", initialCompound.get("name"));
        
        Request mockRequest = createMockRequest();
        Resource compoundResource = controller.getResources().get("compound_table");
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(compoundResource);
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "second_id", new String[]{"100"},
                "name", new String[]{"updatedCompound"},
                "location_id", new String[]{"2"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        
        Map<String, Object> updatedCompound = retrieveCompoundRecord(1, 100);
        assertEquals("updatedCompound", updatedCompound.get("name"));
        assertEquals(2, updatedCompound.get("location_id"));
    }

    @Test
    public void deleteTest_shouldRemoveRecordSuccessfully() throws Exception {
        // Verify record exists before deletion
        Map<String, Object> location = retrieveLocation(1);
        assertNotNull(location);
        assertEquals("location1", location.get("name"));
        
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("1/location1");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        
        // Verify record was deleted
        Map<String, Object> deletedLocation = retrieveLocation(1);
        assertNull(deletedLocation);
    }

    @Test
    public void deleteTest_withNonExistentRecord_shouldReturnFailure() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void deleteTest_withCompositeKey_shouldRemoveCompoundRecord() throws Exception {
        // Verify compound record exists
        Map<String, Object> compound = retrieveCompoundRecord(2, 200);
        assertNotNull(compound);
        assertEquals("compound2", compound.get("name"));
        
        Request mockRequest = createMockRequest();
        Resource compoundResource = controller.getResources().get("compound_table");
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(compoundResource);
        when(mockRequest.params(":ids")).thenReturn("2/200");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        
        // Verify record was deleted
        Map<String, Object> deletedCompound = retrieveCompoundRecord(2, 200);
        assertNull(deletedCompound);
    }

    @Test
    public void createRoute_shouldReturnEditViewWithEmptyObject() throws Exception {
        Request mockRequest = createMockRequest();
        ModelAndView result = controller.createRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();
        
        assertNotNull(model.getObject());
        assertTrue(model.getObject().getProperties().isEmpty() || 
                model.getObject().getProperties().values().stream().allMatch(v -> v == null));
    }

    @Test
    public void createPost_shouldInsertNewRecordSuccessfully() throws Exception {
        // Count initial records
        int initialCount = countLocations();
        
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"newLocation"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"99"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        
        // Verify record was inserted
        int newCount = countLocations();
        assertEquals(initialCount + 1, newCount);
        
        // Retrieve the last inserted record to verify data
        Map<String, Object> newLocation = retrieveLocationByName("newLocation");
        assertNotNull(newLocation);
        assertEquals("newLocation", newLocation.get("name"));
        assertEquals(99, newLocation.get("favorite_number"));
        // PG returns boolean, H2 returns byte
        assertTrue(newLocation.get("is_active").equals((byte) 1) || newLocation.get("is_active").equals(true));
    }

    @Test
    public void createPost_withInvalidData_shouldReturnFailure() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"invalidLocation"},
                "favorite_number", new String[]{"not_a_number"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void listJsonRoute_shouldReturnDataTableResponse() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "draw", new String[]{"1"},
                "start", new String[]{"0"},
                "length", new String[]{"10"},
                "order[0][column]", new String[]{"0"},
                "order[0][dir]", new String[]{"asc"}
        )));

        Object result = controller.listJsonRoute.handle(mockRequest, response);
        assertNotNull(result);
        assertTrue(result instanceof net.nextpulse.jadmin.views.DataTableResponse);
        
        net.nextpulse.jadmin.views.DataTableResponse response = 
                (net.nextpulse.jadmin.views.DataTableResponse) result;
        assertEquals(1, response.getDraw());
        assertEquals(2, response.getData().size());
        assertEquals(2, response.getRecordsTotal());
    }

    private Request createMockRequest() {
        Request mockRequest = mock(Request.class);
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(resource);
        return mockRequest;
    }

    private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
        Map<String, Object> location = null;
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM locations WHERE id = ?");
            preparedStatement.setInt(1, locationId);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                location = new BasicRowProcessor().toMap(queryResult);
            }
        }
        return location;
    }

    private Map<String, Object> retrieveLocationByName(String name) throws SQLException {
        Map<String, Object> location = null;
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM locations WHERE name = ?");
            preparedStatement.setString(1, name);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                location = new BasicRowProcessor().toMap(queryResult);
            }
        }
        return location;
    }

    private Map<String, Object> retrieveCompoundRecord(int id, int secondId) throws SQLException {
        Map<String, Object> record = null;
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM compound_table WHERE id = ? AND second_id = ?");
            preparedStatement.setInt(1, id);
            preparedStatement.setInt(2, secondId);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                record = new BasicRowProcessor().toMap(queryResult);
            }
        }
        return record;
    }

    private int countLocations() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT COUNT(*) FROM locations");
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            return queryResult.getInt(1);
        }
    }
}