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

import static com.ninja_squad.dbsetup.Operations.deleteAllFrom;
import static com.ninja_squad.dbsetup.Operations.insertInto;
import static com.ninja_squad.dbsetup.Operations.sequenceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class gptIT extends DatabaseTest {

  private Resource resource;
  private CrudController controller;
  private final Response response = mock(Response.class);

  @Before
  public void loadData() throws Exception {
    Operation operation = sequenceOf(
        deleteAllFrom("locations"),
        insertInto("locations")
            .withDefaultValue("is_active", true)
            .columns("id", "name", "favorite_number")
            .values(1, "location1", 13)
            .values(2, "location2", 42)
            .values(3, "location3", 77)
            .build());
    DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
    dbSetup.launch();

    JAdmin jAdmin = new JAdmin();
    jAdmin.resource("locations", dataSource);
    resource = jAdmin.getResources().get("locations");
    controller = new CrudController("/adminprefix", jAdmin.getResources());
  }

  @After
  public void cleanupData() {
    Operation cleanup = deleteAllFrom("locations");
    DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), cleanup);
    dbSetup.launch();
  }

  @Test
  public void shouldReturnConfiguredHeadersOnListRoute() throws Exception {
    ModelAndView result = controller.listRoute.handle(createMockRequest(), response);
    ListView model = (ListView) result.getModel();

    assertEquals("locations", model.getResource().getTableName());
    assertEquals(ImmutableList.of("id", "name", "is_active", "favorite_number"), model.getHeaders());
    assertNotNull(model.getTemplateObject());
  }

  @Test
  public void shouldLoadExistingLocationInEditView() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("2/location2");

    ModelAndView result = controller.editRoute.handle(mockRequest, response);
    EditView model = (EditView) result.getModel();

    assertEquals("location2", model.getObject().getProperties().getOrDefault("NAME", model.getObject().getProperties().get("name")));
    assertEquals(2, model.getObject().getProperties().getOrDefault("ID", model.getObject().getProperties().get("id")));
    assertEquals("locations", model.getResource().getTableName());
  }

  @Test(expected = NotFoundException.class)
  public void shouldThrowWhenEditingMissingLocation() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("999/missing");

    controller.editRoute.handle(mockRequest, response);
  }

  @Test
  public void shouldReturnEmptyObjectForCreateRoute() throws Exception {
    ModelAndView result = controller.createRoute.handle(createMockRequest(), response);
    EditView model = (EditView) result.getModel();

    assertNotNull(model.getObject());
    assertTrue(model.getObject().getProperties().isEmpty());
    assertEquals("locations", model.getResource().getTableName());
  }

  @Test
  public void shouldCreateLocationWithoutExplicitId() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "name", new String[]{"createdLocation"},
        "is_active", new String[]{"false"},
        "favorite_number", new String[]{"88"}
    )));

    EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);

    assertTrue(result.isSuccess());
    Map<String, Object> location = retrieveLocationByName("createdLocation");
    assertNotNull(location);
    assertEquals(88, location.get("favorite_number"));
    assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
  }

  @Test
  public void shouldRejectDuplicatePrimaryKeyOnCreate() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"duplicateLocation"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"99"}
    )));

    EditPost result = (EditPost) controller.createPostRoute.handle(mockRequest, response);

    assertFalse(result.isSuccess());
    assertNotNull(result.getErrorMessage());
    assertNull(retrieveLocationByName("duplicateLocation"));
  }

  @Test
  public void shouldUpdateLocationWhenCheckboxUnchecked() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"1"},
        "name", new String[]{"updatedOne"},
        "is_active", new String[]{"false"},
        "favorite_number", new String[]{"71"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);

    assertTrue(result.isSuccess());
    Map<String, Object> location = retrieveLocation(1);
    assertEquals("updatedOne", location.get("name"));
    assertEquals(71, location.get("favorite_number"));
    assertTrue(location.get("is_active").equals((byte) 0) || location.get("is_active").equals(false));
  }

  @Test
  public void shouldUpdateLocationWhenCheckboxChecked() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"2"},
        "name", new String[]{"updatedTwo"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"72"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);

    assertTrue(result.isSuccess());
    Map<String, Object> location = retrieveLocation(2);
    assertEquals("updatedTwo", location.get("name"));
    assertEquals(72, location.get("favorite_number"));
    assertTrue(location.get("is_active").equals((byte) 1) || location.get("is_active").equals(true));
  }

  @Test
  public void shouldOnlyUpdateTargetRow() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.queryMap()).thenReturn(new TestQueryParamsMap(ImmutableMap.of(
        "id", new String[]{"3"},
        "name", new String[]{"onlyThisRowChanges"},
        "is_active", new String[]{"true", "false"},
        "favorite_number", new String[]{"123"}
    )));

    EditPost result = (EditPost) controller.editPostRoute.handle(mockRequest, response);

    assertTrue(result.isSuccess());
    Map<String, Object> updatedLocation = retrieveLocation(3);
    Map<String, Object> untouchedLocation = retrieveLocation(1);
    assertEquals("onlyThisRowChanges", updatedLocation.get("name"));
    assertEquals("location1", untouchedLocation.get("name"));
    assertEquals(13, untouchedLocation.get("favorite_number"));
  }

  @Test
  public void shouldDeleteExistingLocation() throws Exception {
    Request mockRequest = createMockRequest();
    when(mockRequest.params(":ids")).thenReturn("1/location1");

    EditPost result = (EditPost) controller.deleteRoute.handle(mockRequest, response);

    assertTrue(result.isSuccess());
    assertNull(result.getErrorMessage());
    assertNull(retrieveLocationOrNull(1));
    assertEquals(2, countLocations());
  }

  private Request createMockRequest() {
    Request mockRequest = mock(Request.class);
    when(mockRequest.attribute("resourceSchemaProvider")).thenReturn(resource);
    return mockRequest;
  }

  private Map<String, Object> retrieveLocation(int locationId) throws SQLException {
    try(Connection conn = dataSource.getConnection()) {
      PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?");
      preparedStatement.setInt(1, locationId);
      ResultSet queryResult = preparedStatement.executeQuery();
      queryResult.next();
      return new BasicRowProcessor().toMap(queryResult);
    }
  }

  private Map<String, Object> retrieveLocationByName(String name) throws SQLException {
    try(Connection conn = dataSource.getConnection()) {
      PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE name = ?");
      preparedStatement.setString(1, name);
      ResultSet queryResult = preparedStatement.executeQuery();
      if(queryResult.next()) {
        return new BasicRowProcessor().toMap(queryResult);
      }
      return null;
    }
  }

  private Map<String, Object> retrieveLocationOrNull(int locationId) throws SQLException {
    try(Connection conn = dataSource.getConnection()) {
      PreparedStatement preparedStatement = conn.prepareStatement("SELECT * FROM locations WHERE id = ?");
      preparedStatement.setInt(1, locationId);
      ResultSet queryResult = preparedStatement.executeQuery();
      if(queryResult.next()) {
        return new BasicRowProcessor().toMap(queryResult);
      }
      return null;
    }
  }

  private int countLocations() throws SQLException {
    try(Connection conn = dataSource.getConnection()) {
      PreparedStatement preparedStatement = conn.prepareStatement("SELECT COUNT(*) FROM locations");
      ResultSet queryResult = preparedStatement.executeQuery();
      queryResult.next();
      return queryResult.getInt(1);
    }
  }
}