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
 * Comprehensive integration tests for CrudController against real SQL databases.
 * Tests CRUD operations including list, edit, create, delete, and validation scenarios.
 *
 * @author generated
 */
public class CrudControllerIntegrationTest extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response = mock(Response.class);
    private JAdmin jAdmin;

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
                        .values(3, "location3", 99)
                        .build(),
                insertInto("users")
                        .columns("id", "username", "password", "is_admin")
                        .values(1, "admin", "admin123", true)
                        .values(2, "user1", "pass1", false)
                        .values(3, "user2", "pass2", false)
                        .build(),
                insertInto("compound_table")
                        .columns("id", "second_id", "name", "location_id")
                        .values(1, 10, "compound1", 1)
                        .values(2, 20, "compound2", 2)
                        .values(3, 30, "compound3", 1)
                        .build()
        );
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();

        jAdmin = new JAdmin();
        jAdmin.resource("locations", dataSource);
        jAdmin.resource("users", dataSource);
        jAdmin.resource("compound_table", dataSource);
        resource = jAdmin.getResources().get("locations");
        controller = new CrudController("/adminprefix", jAdmin.getResources());
    }

    @Test
    public void listMethod_shouldReturnListViewWithCorrectTableNameAndHeaders() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
        ListView model = (ListView) result.getModel();
        
        assertEquals("locations", model.getResource().getTableName());
        assertNotNull(model.getHeaders());
        assertTrue(model.getHeaders().size() > 0);
    }

    @Test
    public void listJsonMethod_shouldReturnDataTableResponseWithCorrectRows() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap("draw")).thenReturn(new TestQueryParamsMap(ImmutableMap.of("value", new String[]{"1"})));
        when(mockRequest.queryMap("start")).thenReturn(new TestQueryParamsMap(ImmutableMap.of("value", new String[]{"0"})));
        when(mockRequest.queryMap("length")).thenReturn(new TestQueryParamsMap(ImmutableMap.of("value", new String[]{"10"})));
        when(mockRequest.queryMap("order")).thenReturn(new TestQueryParamsMap(
                ImmutableMap.of("0", new String[]{"column=0,dir=asc"})
        ));

        Object result = controller.listJsonRoute.handle(mockRequest, response);
        assertNotNull(result);
        assertTrue(result instanceof net.nextpulse.jadmin.views.DataTableResponse);
        
        net.nextpulse.jadmin.views.DataTableResponse dataTable = 
                (net.nextpulse.jadmin.views.DataTableResponse) result;
        assertEquals(1, dataTable.getDraw());
        assertNotNull(dataTable.getData());
        assertEquals(3, dataTable.getRecordsTotal()); // 3 locations seeded
    }

    @Test
    public void editMethod_withValidId_shouldReturnEditViewWithCorrectProperties() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("2/location2");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        assertNotNull(model.getObject());
        assertNotNull(model.getObject().getProperties());
        
        // Case-insensitive property lookup for cross-database compatibility
        Object nameValue = model.getObject().getProperties().getOrDefault(
                "NAME", model.getObject().getProperties().get("name"));
        Object idValue = model.getObject().getProperties().getOrDefault(
                "ID", model.getObject().getProperties().get("id"));
        
        assertEquals("location2", nameValue);
        assertEquals(2, idValue);
        assertNotNull(model.getResource());
        assertEquals("locations", model.getResource().getTableName());
    }

    @Test
    public void editMethod_withInvalidId_shouldThrowNotFoundException() throws Exception {
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
    public void editPost_withUncheckedCheckbox_shouldUpdateRecordWithFalseBoolean() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"newName"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"71"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        assertNull(result.getMessage());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("newName", location.get("name"));
        assertEquals(71, location.get("favorite_number"));
        
        // Cross-database compatibility: H2 returns byte, PostgreSQL returns boolean
        Object isActive = location.get("is_active");
        assertTrue((isActive.equals((byte) 0) || isActive.equals(false)));
    }

    @Test
    public void editPost_withCheckedCheckbox_shouldUpdateRecordWithTrueBoolean() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"newName2"},
                "is_active", new String[]{"true", "false"},
                "favorite_number", new String[]{"72"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        assertNull(result.getMessage());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("newName2", location.get("name"));
        assertEquals(72, location.get("favorite_number"));
        
        // Cross-database compatibility: H2 returns byte, PostgreSQL returns boolean
        Object isActive = location.get("is_active");
        assertTrue((isActive.equals((byte) 1) || isActive.equals(true)));
    }

    @Test
    public void editPost_withInvalidData_shouldReturnErrorResponse() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{""}, // Empty name should fail validation
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"abc"} // Invalid number
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    public void editPost_withPartialData_shouldUpdateOnlyProvidedFields() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"2"},
                "name", new String[]{"updatedLocation"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(2);
        assertEquals("updatedLocation", location.get("name"));
        // Ensure other fields remain unchanged
        assertEquals(42, location.get("favorite_number"));
    }

    @Test
    public void deleteRoute_withValidId_shouldRemoveRecord() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("1/location1");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        assertNull(result.getMessage());

        // Verify record is deleted
        Map<String, Object> location = retrieveLocation(1);
        assertNull(location);
    }

    @Test
    public void deleteRoute_withInvalidId_shouldReturnErrorResponse() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("999/nonexistent");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    public void createRoute_shouldReturnEditViewForNewRecord() throws Exception {
        ModelAndView result = controller.createRoute.handle(createMockRequest(), response);
        EditView model = (EditView) result.getModel();

        assertNotNull(model.getObject());
        assertTrue(model.getObject().getProperties().isEmpty());
        assertNotNull(model.getResource());
        assertEquals("locations", model.getResource().getTableName());
    }

    @Test
    public void createPost_withValidData_shouldInsertNewRecord() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{"newLocation"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"100"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
        assertNull(result.getMessage());

        // Verify record was inserted
        Map<String, Object> location = retrieveLocationByName("newLocation");
        assertNotNull(location);
        assertEquals("newLocation", location.get("name"));
        assertEquals(100, location.get("favorite_number"));
        Object isActive = location.get("is_active");
        assertTrue((isActive.equals((byte) 1) || isActive.equals(true)));
    }

    @Test
    public void createPost_withInvalidData_shouldReturnErrorResponse() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "name", new String[]{""},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"invalid"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    public void createPost_withDuplicateKey_shouldReturnErrorResponse() throws Exception {
        // Try to insert a record with duplicate ID
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"duplicate"},
                "is_active", new String[]{"true"},
                "favorite_number", new String[]{"50"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    public void editPost_withCompositeKey_shouldUpdateCorrectRecord() throws Exception {
        // Test editing a record with composite primary key
        Resource compoundResource = jAdmin.getResources().get("compound_table");
        CrudController compoundController = new CrudController("/adminprefix", jAdmin.getResources());
        
        Request mockRequest = createMockRequest();
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(compoundResource);
        when(mockRequest.params(":ids")).thenReturn("1/10");
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "second_id", new String[]{"10"},
                "name", new String[]{"updatedCompound"},
                "location_id", new String[]{"2"}
        )));

        EditPost result = (EditPost) compoundController.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        // Verify the update
        Map<String, Object> compound = retrieveCompound(1, 10);
        assertNotNull(compound);
        assertEquals("updatedCompound", compound.get("name"));
        assertEquals(2, compound.get("location_id"));
    }

    @Test
    public void editPost_withBooleanTrueInDifferentFormats_shouldHandleCorrectly() throws Exception {
        // Test various ways boolean true can be submitted
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"3"},
                "name", new String[]{"boolTest"},
                "is_active", new String[]{"on"}, // HTML checkbox "on" value
                "favorite_number", new String[]{"55"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(3);
        Object isActive = location.get("is_active");
        assertTrue((isActive.equals((byte) 1) || isActive.equals(true)));
    }

    @Test
    public void listMethod_withCustomResource_shouldReturnCorrectHeaders() throws Exception {
        // Test list view for users resource
        Resource userResource = jAdmin.getResources().get("users");
        CrudController userController = new CrudController("/adminprefix", jAdmin.getResources());
        
        Request mockRequest = createMockRequest();
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(userResource);

        ModelAndView result = userController.listRoute.handle(mockRequest, response);
        ListView model = (ListView) result.getModel();

        assertEquals("users", model.getResource().getTableName());
        assertNotNull(model.getHeaders());
        assertTrue(model.getHeaders().contains("username"));
        assertTrue(model.getHeaders().contains("id"));
    }

    private Request createMockRequest() {
        Request mockRequest = mock(Request.class);
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(resource);
        return mockRequest;
    }

    private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM locations WHERE id = ?"
            );
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
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM locations WHERE name = ?"
            );
            preparedStatement.setString(1, name);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                return new BasicRowProcessor().toMap(queryResult);
            }
            return null;
        }
    }

    private Map<String, Object> retrieveCompound(int id, int secondId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement(
                    "SELECT * FROM compound_table WHERE id = ? AND second_id = ?"
            );
            preparedStatement.setInt(1, id);
            preparedStatement.setInt(2, secondId);
            ResultSet queryResult = preparedStatement.executeQuery();
            if (queryResult.next()) {
                return new BasicRowProcessor().toMap(queryResult);
            }
            return null;
        }
    }
}