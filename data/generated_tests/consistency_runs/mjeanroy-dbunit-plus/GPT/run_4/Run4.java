

package com.github.mjeanroy.dbunit.integration.junit4;

import com.github.mjeanroy.dbunit.core.annotations.DbUnitConnection;
import com.github.mjeanroy.dbunit.core.annotations.DbUnitDataSet;
import com.github.mjeanroy.dbunit.core.annotations.DbUnitSetup;
import com.github.mjeanroy.dbunit.core.annotations.DbUnitTearDown;
import com.github.mjeanroy.dbunit.core.jdbc.JdbcConfiguration;
import com.github.mjeanroy.dbunit.core.jdbc.JdbcConnectionFactory;
import com.github.mjeanroy.dbunit.core.operation.DbUnitOperation;
import com.github.mjeanroy.dbunit.exception.DbUnitException;
import com.github.mjeanroy.dbunit.tests.db.EmbeddedDatabaseConnectionFactory;
import com.github.mjeanroy.dbunit.tests.fixtures.WithDataSet;
import com.github.mjeanroy.dbunit.tests.fixtures.WithRunnerWithoutConfiguration;
import com.github.mjeanroy.dbunit.tests.fixtures.WithoutDataSet;
import com.github.mjeanroy.dbunit.tests.jupiter.EmbeddedDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.sql.Connection;

import static com.github.mjeanroy.dbunit.core.jdbc.JdbcConfiguration.newJdbcConfiguration;
import static com.github.mjeanroy.dbunit.tests.db.TestDbUtils.countMovies;
import static com.github.mjeanroy.dbunit.tests.db.TestDbUtils.countUsers;
import static com.github.mjeanroy.dbunit.tests.utils.TestDatasets.XML_DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.runner.Description.createSuiteDescription;
import static org.junit.runner.Description.createTestDescription;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings("SameParameterValue")
@EmbeddedDatabaseTest(
	type = EmbeddedDatabaseTest.Type.H2,
	db = "testdb",
	lifecycle = EmbeddedDatabaseTest.Lifecycle.BEFORE_EACH
)
class DbUnitRuleH2IntegrationTest {

	@BeforeEach
	void setup(EmbeddedDatabase db) throws Exception {
		assertDatabaseCounts(db, 0, 0);
	}

	@AfterEach
	void cleanup(EmbeddedDatabase db) throws Exception {
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldLoadRuleByScanningAnnotatedTestClass(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = createRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithH2DbUnitConnection.class, "test1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadRuleByScanningAnnotatedTestClassWithExplicitDriver(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = createRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithH2DbUnitConnectionAndDriver.class, "test1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadRuleWithExplicitJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("jdbc:h2:mem:testdb", "sa", "");
		DbUnitRule rule = createRule(configuration);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadRuleWithExplicitJdbcConfigurationAndDriver(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("org.h2.Driver", "jdbc:h2:mem:testdb", "sa", "");
		DbUnitRule rule = createRule(configuration);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadDatabaseForClassTestUsingConnectionFactory(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadDatabaseForClassRuleUsingSuiteDescription(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createSuiteDescription(WithDataSet.class);

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldOverrideClassLevelDataSetWithMethodLevelDataSet(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method2");

		applyAndVerifyRule(db, rule, statement, description, 2, 0);
	}

	@Test
	void shouldSkipDatasetLoadingWhenNoDatasetIsConfigured(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithoutDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 0, 0);
	}

	@Test
	void shouldRespectMethodLevelNoOpSetupAndTearDown(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method3");

		applyAndVerifyRule(db, rule, statement, description, 0, 0);
	}

	@Test
	void shouldNotExecuteOriginalStatementDuringApply(EmbeddedDatabase db) throws Exception {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull().isNotSameAs(statement);
		verifyNoInteractions(statement);
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldCleanDatabaseAfterWrappedStatementFailure(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = createRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");
		RuntimeException failure = new RuntimeException("statement failure");

		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull();
		verifyNoInteractions(statement);

		doAnswer((Answer<Void>) invocation -> {
			assertDatabaseCounts(db, 2, 3);
			throw failure;
		}).when(statement).evaluate();

		assertThatThrownBy(result::evaluate).isSameAs(failure);
		verify(statement).evaluate();
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldFailIfRuleIsBuiltWithoutParameterAndWithoutAnnotation(EmbeddedDatabase db) {
		DbUnitRule rule = createRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull();
		verifyNoInteractions(statement);

		assertThatThrownBy(result::evaluate)
			.isExactlyInstanceOf(DbUnitException.class)
			.hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");
	}

	private static DbUnitRule createRule() {
		return new DbUnitRule();
	}

	private static DbUnitRule createRule(JdbcConfiguration configuration) {
		return new DbUnitRule(configuration);
	}

	private static DbUnitRule createRule(JdbcConnectionFactory factory) {
		return new DbUnitRule(factory);
	}

	private static void applyAndVerifyRule(
		EmbeddedDatabase db,
		DbUnitRule rule,
		Statement statement,
		Description description,
		int expectedCountUsers,
		int expectedCountMovies
	) throws Throwable {
		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull().isNotSameAs(statement);
		verifyNoInteractions(statement);

		Answer<Void> answer = invocation -> {
			assertDatabaseCounts(db, expectedCountUsers, expectedCountMovies);
			return null;
		};

		doAnswer(answer).when(statement).evaluate();

		result.evaluate();
		verify(statement).evaluate();
	}

	private static void assertDatabaseCounts(EmbeddedDatabase db, long expectedCountUsers, long expectedCountMovies) throws Exception {
		try (Connection connection = db.getConnection()) {
			assertThat(countUsers(connection)).isEqualTo(expectedCountUsers);
			assertThat(countMovies(connection)).isEqualTo(expectedCountMovies);
		}
	}

	@DbUnitDataSet(XML_DATASET)
	@DbUnitConnection(url = "jdbc:h2:mem:testdb", user = "sa", password = "")
	@DbUnitSetup(DbUnitOperation.CLEAN_INSERT)
	@DbUnitTearDown(DbUnitOperation.TRUNCATE_TABLE)
	public static class WithH2DbUnitConnection {

		public void test1() {
		}
	}

	@DbUnitDataSet(XML_DATASET)
	@DbUnitConnection(
		driver = "org.h2.Driver",
		url = "jdbc:h2:mem:testdb",
		user = "sa",
		password = ""
	)
	@DbUnitSetup(DbUnitOperation.CLEAN_INSERT)
	@DbUnitTearDown(DbUnitOperation.TRUNCATE_TABLE)
	public static class WithH2DbUnitConnectionAndDriver {

		public void test1() {
		}
	}
}