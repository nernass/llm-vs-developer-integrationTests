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
import java.util.Collections;
import java.util.Map;

import static com.ninja_squad.dbsetup.Operations.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extended integration tests for CrudController covering additional CRUD scenarios,
 * edge cases, and failure conditions against real databases.
 */
public class claudeIT extends DatabaseTest {

  private Resource resource;
  private CrudController controller;
  private Response response = mock(Response.class);

  @Before
  public void loadData() throws Exception {
    Operation operation =
        sequenceOf(
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

    JAdmin jAdmin = new JAdmin();
    jAdmin.resource("locations", dataSource);
    resource = jAdmin.getResources().get("locations");
    controller = new CrudController("/adminprefix", jAdmin.getResources());
  }

  // ========== LIST ROUTE TESTS ==========

  @Test
  public void shouldReturnCorrectTableNameOnList() throws Exception {
    ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
    ListView model = (ListView) result.getModel();
    assertEquals("locations", model.getResource().getTableName());
  }

  @Test
  public void shouldReturnAllExpectedHeadersOnList() throws Exception {
    ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
    ListView model = (ListView) result.getModel();
    assertTrue(model.getHeaders().contains("id"));
    assertTrue(model.getHeaders().contains("name"));
    assertTrue(model.getHeaders().contains("is_active"));
    assertTrue(model.getHeaders().contains("favorite_number"));
  }

  @Test
  public void shouldReturnNonNullTemplateObjectOnList() throws Exception {
    ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
    ListView model = (ListView) result.getModel();
    assertNotNull(model.getTemplateObject());
  }

  // ========== EDIT ROUTE TESTS ==========

  @Test
  public void shouldLoadCorrectRecordOnEdit() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("1");

    ModelAndView result = controller.editRoute.handle(mockRequest, response);
    EditView model = (EditView) result.getModel();

    String name = (String) model.getObject().getProperties().getOrDefault("NAME",
        model.getObject().getProperties().get("name"));
    assertEquals("location1", name);
  }

  @Test
  public void shouldLoadSecondRecordOnEdit() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("2");

    ModelAndView result = controller.editRoute.handle(mockRequest, response);
    EditView model = (EditView) result.getModel();

    String name = (String) model.getObject().getProperties().getOrDefault("NAME",
        model.getObject().getProperties().get("name"));
    assertEquals("location2", name);
  }

  @Test
  public void shouldLoadThirdRecordOnEdit() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("3");

    ModelAndView result = controller.editRoute.handle(mockRequest, response);
    EditView model = (EditView) result.getModel();

    Object favoriteNumber = model.getObject().getProperties().getOrDefault("FAVORITE_NUMBER",
        model.getObject().getProperties().get("favorite_number"));
    assertEquals(99, favoriteNumber);
  }

  @Test
  public void shouldReturnResourceOnEditView() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("1");

    ModelAndView result = controller.editRoute.handle(mockRequest, response);
    EditView model = (EditView) result.getModel();

    assertNotNull(model.getResource());
    assertEquals("locations", model.getResource().getTableName());
  }

  // ========== EDIT POST ROUTE TESTS ==========

  @Test
  public void shouldUpdateNameSuccessfully() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"updatedName"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"13"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    Map<String, Object> location = retrieveLocation(1);
    assertEquals("updatedName", location.get("name"));
  }

  @Test
  public void shouldUpdateFavoriteNumberSuccessfully() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"2"},
        "name", new String[]{"location2"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"999"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    Map<String, Object> location = retrieveLocation(2);
    assertEquals(999, location.get("favorite_number"));
  }

  @Test
  public void shouldSetBooleanToFalseWhenUnchecked() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"location1"},
        "is_active", new String[]{"false"},
        "favorite_number", new String[]{"13"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    Map<String, Object> location = retrieveLocation(1);
    assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
  }

  @Test
  public void shouldSetBooleanToTrueWhenChecked() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"location1"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"13"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    Map<String, Object> location = retrieveLocation(1);
    assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
  }

  @Test
  public void shouldUpdateMultipleFieldsAtOnce() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"3"},
        "name", new String[]{"brandNewName"},
        "is_active", new String[]{"false"},
        "favorite_number", new String[]{"777"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    Map<String, Object> location = retrieveLocation(3);
    assertEquals("brandNewName", location.get("name"));
    assertEquals(777, location.get("favorite_number"));
    assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
  }

  @Test
  public void shouldReturnNullErrorMessageOnSuccessfulUpdate() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"updatedName"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"50"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());
    assertNull(result.getErrorMessage());
  }

  // ========== DELETE ROUTE TESTS ==========

  @Test
  public void shouldDeleteExistingRecordSuccessfully() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("1");

    EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());
  }

  @Test
  public void shouldDeleteRecordAndReduceCount() throws Exception {
    int initialCount = countLocations();

    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("2");

    EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    int newCount = countLocations();
    assertEquals(initialCount - 1, newCount);
  }

  @Test
  public void shouldDeleteRecordSoItCannotBeRetrieved() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("3");

    EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    assertNull(retrieveLocationOrNull(3));
  }

  @Test
  public void shouldReturnNullErrorMessageOnSuccessfulDelete() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("1");

    EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());
    assertNull(result.getErrorMessage());
  }

  // ========== CREATE ROUTE TESTS ==========

  @Test
  public void shouldReturnEmptyObjectOnCreateView() throws Exception {
    ModelAndView result = controller.createRoute.handle(createMockRequest(), response);
    EditView model = (EditView) result.getModel();
    assertNotNull(model.getObject());
    assertTrue(model.getObject().getProperties().isEmpty());
  }

  @Test
  public void shouldReturnResourceOnCreateView() throws Exception {
    ModelAndView result = controller.createRoute.handle(createMockRequest(), response);
    EditView model = (EditView) result.getModel();
    assertNotNull(model.getResource());
    assertEquals("locations", model.getResource().getTableName());
  }

  // ========== CREATE POST ROUTE TESTS ==========

  @Test
  public void shouldInsertNewRecordSuccessfully() throws Exception {
    int initialCount = countLocations();

    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"100"},
        "name", new String[]{"newLocation"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"55"}
    )));

    EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    int newCount = countLocations();
    assertEquals(initialCount + 1, newCount);
  }

  @Test
  public void shouldInsertNewRecordWithCorrectValues() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"101"},
        "name", new String[]{"insertedLocation"},
        "is_active", new String[]{"false"},
        "favorite_number", new String[]{"321"}
    )));

    EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);
    assertTrue(result.isSuccess());

    Map<String, Object> location = retrieveLocation(101);
    assertEquals("insertedLocation", location.get("name"));
    assertEquals(321, location.get("favorite_number"));
    assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
  }

  // ========== DASHBOARD ROUTE TEST ==========

  @Test
  public void shouldReturnDashboardViewSuccessfully() throws Exception {
    Request mockRequest = mock(Request.class);
    ModelAndView result = controller.dashboardRoute.handle(mockRequest, response);
    assertNotNull(result);
    assertNotNull(result.getModel());
  }

  // ========== DATA INTEGRITY TESTS ==========

  @Test
  public void shouldNotAffectOtherRecordsOnUpdate() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"changedName"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"111"}
    )));

    controller.editPostRoute.handle(mockRequest, response);

    Map<String, Object> untouchedLocation = retrieveLocation(2);
    assertEquals("location2", untouchedLocation.get("name"));
    assertEquals(42, untouchedLocation.get("favorite_number"));
  }

  @Test
  public void shouldNotAffectOtherRecordsOnDelete() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("1");

    controller.deleteRoute.handle(mockRequest, response);

    Map<String, Object> untouchedLocation = retrieveLocation(2);
    assertNotNull(untouchedLocation);
    assertEquals("location2", untouchedLocation.get("name"));
  }

  @Test
  public void shouldPersistUpdatedValueAcrossReads() throws Exception {
    // Update
    Request updateRequest = createMockRequest();
    when(updateRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"persistedName"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"500"}
    )));
    controller.editPostRoute.handle(updateRequest, response);

    // Read back via editRoute
    Request readRequest = createMockRequest();
    when(readRequest.params(":ids")).thenReturn("1");
    ModelAndView result = controller.editRoute.handle(readRequest, response);
    EditView model = (EditView) result.getModel();

    String name = (String) model.getObject().getProperties().getOrDefault("NAME",
        model.getObject().getProperties().get("name"));
    assertEquals("persistedName", name);
  }

  // ========== HELPER METHODS ==========

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

  private Map<String, Object> retrieveLocationOrNull(int locationId) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?");
      preparedStatement.setInt(1, locationId);
      ResultSet queryResult = preparedStatement.executeQuery();
      if (queryResult.next()) {
        return new BasicRowProcessor().toMap(queryResult);
      }
    }
    return null;
  }

  private int countLocations() throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      PreparedStatement preparedStatement = conn.prepareStatement("SELECT COUNT(*) FROM locations");
      ResultSet queryResult = preparedStatement.executeQuery();
      queryResult.next();
      return queryResult.getInt(1);
    }
  }
}
