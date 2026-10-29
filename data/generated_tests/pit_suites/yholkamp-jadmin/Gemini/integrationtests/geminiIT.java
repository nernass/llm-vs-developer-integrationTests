package integrationtests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import net.nextpulse.jadmin.CrudController;
import net.nextpulse.jadmin.JAdmin;
import net.nextpulse.jadmin.Resource;
import net.nextpulse.jadmin.exceptions.NotFoundException;
import net.nextpulse.jadmin.views.EditPost;
import net.nextpulse.jadmin.views.EditView;
import net.nextpulse.jadmin.views.ListView;
import org.apache.commons.dbutils.BasicRowProcessor;
import org.junit.After;
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

public class geminiIT extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response = mock(Response.class);

    @Before
    public void setupDatabaseAndController() throws Exception {
        Operation operation = sequenceOf(
                deleteAllFrom("locations"),
                insertInto("locations")
                        .withDefaultValue("is_active", true)
                        .columns("id", "name", "favorite_number")
                        .values(1, "Location A", 100)
                        .values(2, "Location B", 200)
                        .build());

        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();

        JAdmin jAdmin = new JAdmin();
        jAdmin.resource("locations", dataSource);
        resource = jAdmin.getResources().get("locations");
        controller = new CrudController("/adminprefix", jAdmin.getResources());
    }

    @After
    public void cleanup() {
        // Database state is reset in @Before via DbSetup which handles the tables.
    }

    @Test
    public void shouldReturnExpectedDataForListRoute() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
        ListView model = (ListView) result.getModel();
        
        assertEquals("locations", model.getResource().getTableName());
        assertTrue(model.getHeaders().containsAll(ImmutableList.of("id", "name", "is_active", "favorite_number")));
    }

    @Test
    public void shouldSuccessfullyLoadEditViewAndMapCaseInsensitively() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("1/Location A");

        ModelAndView result = controller.editRoute.handle(mockRequest, response);
        EditView model = (EditView) result.getModel();

        assertEquals("Location A", model.getObject().getProperties().getOrDefault("NAME", model.getObject().getProperties().get("name")));
        assertEquals(1, model.getObject().getProperties().getOrDefault("ID", model.getObject().getProperties().get("id")));
    }

    @Test(expected = NotFoundException.class)
    public void shouldFailToFindNonExistentRecordForEdit() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("999/NonExistent");
        controller.editRoute.handle(mockRequest, response);
    }

    @Test
    public void shouldUpdateRecordWithEditPostAndFalseBoolean() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"2"},
                "name", new String[]{"Updated Location B"},
                "is_active", new String[]{"false"},
                "favorite_number", new String[]{"250"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(2);
        assertEquals("Updated Location B", location.get("name"));
        assertEquals(250, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
    }

    @Test
    public void shouldUpdateRecordWithEditPostAndTrueBoolean() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"1"},
                "name", new String[]{"Updated Location A"},
                "is_active", new String[]{"true", "false"},
                "favorite_number", new String[]{"150"}
        )));

        EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("Updated Location A", location.get("name"));
        assertEquals(150, location.get("favorite_number"));
        assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
    }

    @Test
    public void shouldDeleteExistingRecordSuccessfully() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.params(":ids")).thenReturn("1/Location A");

        EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());
    }

    @Test
    public void shouldCreateNewRecord() throws Exception {
        Request mockRequest = createMockRequest();
        when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
                "id", new String[]{"3"},
                "name", new String[]{"Location C"},
                "is_active", new String[]{"true", "false"},
                "favorite_number", new String[]{"300"}
        )));

        EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(3);
        assertEquals("Location C", location.get("name"));
        assertEquals(300, location.get("favorite_number"));
    }

    private Request createMockRequest() {
        Request mockRequest = mock(Request.class);
        when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(resource);
        return mockRequest;
    }

    private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
        Map<String, Object> location;
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?");
            preparedStatement.setInt(1, locationId);
            ResultSet queryResult = preparedStatement.executeQuery();
            queryResult.next();
            location = new BasicRowProcessor().toMap(queryResult);
        }
        return location;
    }
}