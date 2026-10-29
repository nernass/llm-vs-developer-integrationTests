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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.sql.Connection;

import static com.github.mjeanroy.dbunit.core.jdbc.JdbcConfiguration.newJdbcConfiguration;
import static com.github.mjeanroy.dbunit.tests.db.TestDbUtils.countMovies;
import static com.github.mjeanroy.dbunit.tests.db.TestDbUtils.countUsers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.runner.Description.createSuiteDescription;
import static org.junit.runner.Description.createTestDescription;

@EmbeddedDatabaseTest(lifecycle = EmbeddedDatabaseTest.Lifecycle.BEFORE_EACH)
class gptIT {

	@BeforeEach
	void shouldStartWithEmptyDatabase(EmbeddedDatabase db) throws Exception {
		assertDatabaseCounts(db, 0, 0);
	}

	@AfterEach
	void shouldEndWithEmptyDatabase(EmbeddedDatabase db) throws Exception {
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldLoadDataSetFromDbUnitConnectionAnnotation(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule();
		Description description = createTestDescription(WithDbUnitConnection.class, "test1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadDataSetWithExplicitJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "SA", "");
		DbUnitRule rule = new DbUnitRule(configuration);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadDataSetWithExplicitJdbcConfigurationAndDriver(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("org.hsqldb.jdbcDriver", "jdbc:hsqldb:mem:testdb", "SA", "");
		DbUnitRule rule = new DbUnitRule(configuration);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadDataSetWithJdbcConnectionFactory(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory connectionFactory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(connectionFactory);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadClassLevelDataSetForSuiteDescription(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory connectionFactory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(connectionFactory);
		Description description = createSuiteDescription(WithDataSet.class);
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldApplyMethodLevelDataSetOverride(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory connectionFactory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(connectionFactory);
		Description description = createTestDescription(WithDataSet.class, "method2");
		InspectableStatement statement = new InspectableStatement(db, 2, 0);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLeaveDatabaseUntouchedWhenNoDataSetIsConfigured(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory connectionFactory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(connectionFactory);
		Description description = createTestDescription(WithoutDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 0, 0);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldWrapStatementWithoutExecutingItDuringApply(EmbeddedDatabase db) {
		JdbcConnectionFactory connectionFactory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(connectionFactory);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		Statement wrapped = rule.apply(statement, description);

		assertThat(wrapped).isNotNull();
		assertThat(wrapped).isNotSameAs(statement);
		assertThat(statement.evaluationCount()).isZero();
	}

	@Test
	void shouldCleanDatabaseAfterStatementFailure(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory connectionFactory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(connectionFactory);
		Description description = createTestDescription(WithDataSet.class, "method1");
		RuntimeException failure = new RuntimeException("statement failure");
		InspectableStatement statement = new InspectableStatement(db, 2, 3, failure);

		Statement wrapped = rule.apply(statement, description);

		assertThatThrownBy(wrapped::evaluate)
			.isSameAs(failure);
		assertThat(statement.evaluationCount()).isEqualTo(1);
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldFailWhenNoConnectionConfigurationCanBeResolved(EmbeddedDatabase db) {
		DbUnitRule rule = new DbUnitRule();
		Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");
		InspectableStatement statement = new InspectableStatement(db, 0, 0);
		Statement wrapped = rule.apply(statement, description);

		assertThatThrownBy(wrapped::evaluate)
			.isExactlyInstanceOf(DbUnitException.class)
			.hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");
		assertThat(statement.evaluationCount()).isZero();
	}

	private static void applyAndVerifyRule(DbUnitRule rule, InspectableStatement statement, Description description) throws Throwable {
		Statement wrapped = rule.apply(statement, description);

		assertThat(wrapped).isNotNull();
		assertThat(wrapped).isNotSameAs(statement);
		assertThat(statement.evaluationCount()).isZero();

		wrapped.evaluate();

		assertThat(statement.evaluationCount()).isEqualTo(1);
	}

	private static void assertDatabaseCounts(EmbeddedDatabase db, long expectedUsers, long expectedMovies) throws Exception {
		try (Connection connection = db.getConnection()) {
			assertThat(countUsers(connection)).isEqualTo(expectedUsers);
			assertThat(countMovies(connection)).isEqualTo(expectedMovies);
		}
	}

	private static final class InspectableStatement extends Statement {
		private final EmbeddedDatabase db;
		private final long expectedUsers;
		private final long expectedMovies;
		private final RuntimeException failure;
		private int evaluationCount;

		private InspectableStatement(EmbeddedDatabase db, long expectedUsers, long expectedMovies) {
			this(db, expectedUsers, expectedMovies, null);
		}

		private InspectableStatement(EmbeddedDatabase db, long expectedUsers, long expectedMovies, RuntimeException failure) {
			this.db = db;
			this.expectedUsers = expectedUsers;
			this.expectedMovies = expectedMovies;
			this.failure = failure;
		}

		@Override
		public void evaluate() throws Throwable {
			evaluationCount++;
			assertDatabaseCounts(db, expectedUsers, expectedMovies);

			if (failure != null) {
				throw failure;
			}
		}

		private int evaluationCount() {
			return evaluationCount;
		}
	}
}