
/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2023 Mickael Jeanroy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mjeanroy.dbunit.integration.junit4;

import com.github.mjeanroy.dbunit.core.jdbc.JdbcConfiguration;
import com.github.mjeanroy.dbunit.core.jdbc.JdbcConnectionFactory;
import com.github.mjeanroy.dbunit.exception.DbUnitException;
import com.github.mjeanroy.dbunit.tests.db.EmbeddedDatabaseConnectionFactory;
import com.github.mjeanroy.dbunit.tests.fixtures.WithDataSet;
import com.github.mjeanroy.dbunit.tests.fixtures.WithDbUnitConnection;
import com.github.mjeanroy.dbunit.tests.fixtures.WithDbUnitConnectionAndDriver;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.runner.Description.createSuiteDescription;
import static org.junit.runner.Description.createTestDescription;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings("SameParameterValue")
@EmbeddedDatabaseTest(lifecycle = EmbeddedDatabaseTest.Lifecycle.BEFORE_EACH)
class DbUnitRuleIntegrationTest {

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
		Description description = createTestDescription(WithDbUnitConnection.class, "test1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadRuleByScanningAnnotatedTestClassWithExplicitDriver(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = createRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDbUnitConnectionAndDriver.class, "test1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadRuleWithExplicitJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
		DbUnitRule rule = createRule(configuration);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadRuleWithExplicitJdbcConfigurationAndDriver(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("org.hsqldb.jdbcDriver", "jdbc:hsqldb:mem:testdb", "sa", "");
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
	void shouldLoadRuleUsingDataSourceConstructor(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule(db);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
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

		assertThat(result).isNotNull().isNotSameAs(statement);
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
	void shouldFailIfRuleIsBuiltWithoutParameterAndWithoutAnnotation(EmbeddedDatabase db) throws Exception {
		DbUnitRule rule = createRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull().isNotSameAs(statement);
		verifyNoInteractions(statement);

		assertThatThrownBy(result::evaluate)
			.isExactlyInstanceOf(DbUnitException.class)
			.hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");

		assertDatabaseCounts(db, 0, 0);
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
}