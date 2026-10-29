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
import com.github.mjeanroy.dbunit.tests.fixtures.WithRunnerWithoutConfiguration;
import com.github.mjeanroy.dbunit.tests.fixtures.WithoutDataSet;
import com.github.mjeanroy.dbunit.tests.jupiter.EmbeddedDatabaseTest;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings("SameParameterValue")
@EmbeddedDatabaseTest
class ClaudeIT {

	@BeforeEach
	void setup(EmbeddedDatabase db) throws Exception {
		Connection connection = db.getConnection();
		assertThat(countUsers(connection)).isZero();
		assertThat(countMovies(connection)).isZero();
	}

	@Test
	void shouldLoadDataSetByResolvingConnectionFromAnnotationOnTestClass(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDbUnitConnection.class, "test1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadDataSetWithExplicitJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
		DbUnitRule rule = new DbUnitRule(config);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadDataSetWithConnectionFactory(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadDataSetWithDataSourceConstructor(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule(db);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadClassLevelDataSetWhenDescriptionIsSuiteLevel(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createSuiteDescription(WithDataSet.class);

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	@Test
	void shouldLoadMethodLevelDataSetOverridingClassLevel(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method2");

		applyAndVerifyRule(db, rule, statement, description, 2, 0);
	}

	@Test
	void shouldNotLoadAnyDataSetWhenClassHasNoDataSetAnnotation(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithoutDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 0, 0);
	}

	@Test
	void shouldThrowDbUnitExceptionWhenNoConfigurationAndNoAnnotation(EmbeddedDatabase db) {
		DbUnitRule rule = new DbUnitRule();
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

		assertThatThrownBy(() -> applyAndVerifyRule(db, rule, statement, description, 0, 0))
			.isExactlyInstanceOf(DbUnitException.class)
			.hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");
	}

	@Test
	void shouldReturnNonNullWrappedStatementFromApply(EmbeddedDatabase db) {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull();
		assertThat(result).isNotSameAs(statement);
	}

	@Test
	void shouldNotEvaluateOriginalStatementDuringApply(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		rule.apply(statement, description);

		verifyNoInteractions(statement);
	}

	@Test
	void shouldTearDownDataSetEvenWhenStatementThrowsException(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		RuntimeException error = new RuntimeException("test failure");
		doThrow(error).when(statement).evaluate();

		Statement result = rule.apply(statement, description);

		assertThatThrownBy(result::evaluate)
			.isSameAs(error);

		verify(statement).evaluate();
	}

	@Test
	void shouldInsertDataBeforeStatementEvaluation(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		Statement result = rule.apply(statement, description);

		doAnswer((Answer<Void>) invocation -> {
			Connection connection = db.getConnection();
			assertThat(countUsers(connection)).isEqualTo(2);
			assertThat(countMovies(connection)).isEqualTo(3);
			return null;
		}).when(statement).evaluate();

		result.evaluate();
		verify(statement).evaluate();
	}

	@Test
	void shouldCleanTablesAfterStatementEvaluation(EmbeddedDatabase db) throws Throwable {
		EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		Statement result = rule.apply(statement, description);

		doAnswer((Answer<Void>) invocation -> null).when(statement).evaluate();

		result.evaluate();

		Connection connection = db.getConnection();
		assertThat(countUsers(connection)).isZero();
		assertThat(countMovies(connection)).isZero();
	}

	@Test
	void shouldSupportExplicitJdbcConfigurationWithDriver(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration config = newJdbcConfiguration("org.hsqldb.jdbcDriver", "jdbc:hsqldb:mem:testdb", "sa", "");
		DbUnitRule rule = new DbUnitRule(config);
		Statement statement = mock(Statement.class);
		Description description = createTestDescription(WithDataSet.class, "method1");

		applyAndVerifyRule(db, rule, statement, description, 2, 3);
	}

	private static void applyAndVerifyRule(EmbeddedDatabase db, DbUnitRule rule, Statement statement, Description description, int expectedCountUsers, final int expectedCountMovies) throws Throwable {
		Statement result = rule.apply(statement, description);

		assertThat(result).isNotNull();
		verifyNoInteractions(statement);

		Answer<Void> answer = invocation -> {
			Connection connection = db.getConnection();
			assertThat(countUsers(connection)).isEqualTo(expectedCountUsers);
			assertThat(countMovies(connection)).isEqualTo(expectedCountMovies);
			return null;
		};

		doAnswer(answer).when(statement).evaluate();

		result.evaluate();
		verify(statement).evaluate();
	}
}
