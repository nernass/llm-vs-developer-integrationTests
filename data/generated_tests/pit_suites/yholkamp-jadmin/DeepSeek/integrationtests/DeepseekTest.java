package integrationtests;

import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import net.nextpulse.jadmin.CrudController;
import net.nextpulse.jadmin.JAdmin;
import net.nextpulse.jadmin.Resource;
import net.nextpulse.jadmin.dao.DatabaseEntry;
import net.nextpulse.jadmin.views.DataTableResponse;
import net.nextpulse.jadmin.views.EditPost;
import net.nextpulse.jadmin.views.EditView;
import net.nextpulse.jadmin.views.ListView;
import org.apache.commons.dbutils.BasicRowProcessor;
import org.junit.Before;
import org.junit.Test;
import spark.ModelAndView;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import testhelpers.DatabaseTest;
import testhelpers.TestQueryParamsMap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ninja_squad.dbsetup.Operations.deleteAllFrom;
import static com.ninja_squad.dbsetup.Operations.insertInto;
import static com.ninja_squad.dbsetup.Operations.sequenceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for CrudController using the real DatabaseTest datasource.
 */
public class DeepseekTest extends DatabaseTest {

    private Resource resource;
    private CrudController controller;
    private Response response;

    @Before
    public void loadData() throws Exception {
        Operation operation = sequenceOf(
                deleteAllFrom("locations"),
                insertInto("locations")
                        .columns("id", "name", "is_active", "favorite_number")
                        .values(1, "location1", true, 13)
                        .values(2, "location2", true, 42)
                        .build()
        );

        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();

        JAdmin jAdmin = new JAdmin();
        jAdmin.resource("locations", dataSource);

        resource = jAdmin.getResources().get("locations");
        controller = new CrudController("/adminprefix", jAdmin.getResources());
        response = mock(Response.class);
    }

    @Test
    public void listRoute_shouldReturnViewWithResourceAndHeaders() throws Exception {
        ModelAndView result = controller.listRoute.handle(createMockRequest(), response);

        ListView model = (ListView) result.getModel();
        assertEquals("locations", model.getResource().getTableName());
        assertEquals(Arrays.asList("id", "name", "is_active", "favorite_number"), model.getHeaders());
    }

    @Test
    public void listJsonRoute_shouldReturnRowsFromDatabase() throws Exception {
        Request request = createMockRequestWithQueryParams(params(
                "draw", "1",
                "start", "0",
                "length", "10",
                "order[0][column]", "0",
                "order[0][dir]", "asc"
        ));

        DataTableResponse result = (DataTableResponse) controller.listJsonRoute.handle(request, response);

        assertEquals(1, result.draw);
        assertEquals(2, result.recordsFiltered);
        assertEquals(2, result.data.size());

        Map<String, Object> firstRow = castRow(result.data.get(0));
        assertEquals(1, asInt(firstRow.get("id")));
        assertEquals("location1", firstRow.get("name"));
    }

    @Test
    public void listJsonRoute_withPagination_shouldReturnLimitedRows() throws Exception {
        Request request = createMockRequestWithQueryParams(params(
                "draw", "2",
                "start", "1",
                "length", "1",
                "order[0][column]", "0",
                "order[0][dir]", "asc"
        ));

        DataTableResponse result = (DataTableResponse) controller.listJsonRoute.handle(request, response);

        assertEquals(2, result.draw);
        assertEquals(2, result.recordsFiltered);
        assertEquals(1, result.data.size());

        Map<String, Object> row = castRow(result.data.get(0));
        assertEquals(2, asInt(row.get("id")));
        assertEquals("location2", row.get("name"));
    }

    @Test
    public void createRoute_shouldReturnEmptyEditView() throws Exception {
        ModelAndView result = controller.createRoute.handle(createMockRequest(), response);

        EditView model = (EditView) result.getModel();
        assertNotNull(model.getObject());
        assertTrue(model.getObject().getProperties().isEmpty());
        assertEquals("locations", model.getResource().getTableName());
    }

    @Test
    public void createPostRoute_shouldInsertNewRecord() throws Exception {
        Request request = createMockRequestWithQueryParams(params(
                "id", "3",
                "name", "location3",
                "is_active", "true",
                "favorite_number", "99"
        ));

        EditPost result = (EditPost) controller.createPostRoute.handle(request, response);

        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(3);
        assertEquals("location3", value(location, "name"));
        assertEquals(99, asInt(value(location, "favorite_number")));
        assertTrue(asBoolean(value(location, "is_active")));
    }

    @Test
    public void editRoute_shouldReturnViewWithExistingData() throws Exception {
        Request request = createMockRequest();
        when(request.params(":ids")).thenReturn("2");

        ModelAndView result = controller.editRoute.handle(request, response);

        EditView model = (EditView) result.getModel();
        DatabaseEntry object = model.getObject();

        assertEquals("location2", value(object.getProperties(), "name"));
        assertEquals(2, asInt(value(object.getProperties(), "id")));
    }

    @Test
    public void editPostRoute_shouldUpdateExistingRecord() throws Exception {
        Request request = createMockRequestWithQueryParams(params(
                "id", "1",
                "name", "updatedName",
                "is_active", "false",
                "favorite_number", "55"
        ));

        EditPost result = (EditPost) controller.editPostRoute.handle(request, response);

        assertTrue(result.isSuccess());

        Map<String, Object> location = retrieveLocation(1);
        assertEquals("updatedName", value(location, "name"));
        assertEquals(55, asInt(value(location, "favorite_number")));
        assertTrue(!asBoolean(value(location, "is_active")));
    }

    @Test
    public void deleteRoute_shouldRemoveExistingRecord() throws Exception {
        Request request = createMockRequest();
        when(request.params(":ids")).thenReturn("1");

        EditPost result = (EditPost) controller.deleteRoute.handle(request, response);

        assertTrue(result.isSuccess());
        assertNull(retrieveLocationOrNull(1));
    }

    private Request createMockRequest() {
        Request request = mock(Request.class);
        when(request.attribute("resourceSchemaProvider")).thenReturn(resource);
        return request;
    }

    private Request createMockRequestWithQueryParams(Map<String, String[]> params) {
        Request request = createMockRequest();
        TestQueryParamsMap queryMap = new TestQueryParamsMap(params);

        when(request.queryMap()).thenReturn(queryMap);
        when(request.queryMap("draw")).thenReturn(queryMap.get("draw"));
        when(request.queryMap("start")).thenReturn(queryMap.get("start"));
        when(request.queryMap("length")).thenReturn(queryMap.get("length"));
        when(request.queryMap("order")).thenReturn(queryMap.get("order"));

        return request;
    }

    private Map<String, String[]> params(String... pairs) {
        Map<String, String[]> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put(pairs[i], new String[]{pairs[i + 1]});
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castRow(Object row) {
        return (Map<String, Object>) row;
    }

    private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
        Map<String, Object> location = retrieveLocationOrNull(locationId);
        assertNotNull("Location with id " + locationId + " not found", location);
        return location;
    }

    private Map<String, Object> retrieveLocationOrNull(int locationId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?")) {
            statement.setInt(1, locationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new BasicRowProcessor().toMap(resultSet);
                }
                return null;
            }
        }
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return row.get(key.toUpperCase());
    }

    private int asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
