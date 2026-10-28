
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
import static com.github.mjeanroy.dbunit.tests.utils.TestDatasets.XML_DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.runner.Description.createSuiteDescription;
import static org.junit.runner.Description.createTestDescription;

@EmbeddedDatabaseTest(
	type = EmbeddedDatabaseTest.Type.H2,
	db = "testdb",
	lifecycle = EmbeddedDatabaseTest.Lifecycle.BEFORE_EACH
)
class DbUnitRuleH2IntegrationTest {

	@BeforeEach
	void shouldStartWithEmptyDatabase(EmbeddedDatabase db) throws Exception {
		assertDatabaseCounts(db, 0, 0);
	}

	@AfterEach
	void shouldLeaveDatabaseEmptyAfterEachTest(EmbeddedDatabase db) throws Exception {
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldLoadDataSetByScanningAnnotatedTestClass(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule();
		Description description = createTestDescription(WithH2DbUnitConnection.class, "test1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadDataSetByScanningAnnotatedTestClassWithExplicitDriver(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule();
		Description description = createTestDescription(WithH2DbUnitConnectionAndDriver.class, "test1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadClassLevelDataSetWithJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("jdbc:h2:mem:testdb", "sa", "");
		DbUnitRule rule = new DbUnitRule(configuration);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadClassLevelDataSetWithJdbcConfigurationAndDriver(EmbeddedDatabase db) throws Throwable {
		JdbcConfiguration configuration = newJdbcConfiguration("org.h2.Driver", "jdbc:h2:mem:testdb", "sa", "");
		DbUnitRule rule = new DbUnitRule(configuration);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadClassLevelDataSetWithJdbcConnectionFactory(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadClassLevelDataSetWithDataSource(EmbeddedDatabase db) throws Throwable {
		DbUnitRule rule = new DbUnitRule(db);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldLoadClassLevelDataSetForSuiteDescription(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createSuiteDescription(WithDataSet.class);
		InspectableStatement statement = new InspectableStatement(db, 2, 3);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldOverrideClassLevelDataSetWithMethodLevelDataSet(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createTestDescription(WithDataSet.class, "method2");
		InspectableStatement statement = new InspectableStatement(db, 2, 0);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldSkipLoadingWhenNoDataSetIsConfigured(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createTestDescription(WithoutDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 0, 0);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldRespectMethodLevelNoOpSetupAndTearDown(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createTestDescription(WithDataSet.class, "method3");
		InspectableStatement statement = new InspectableStatement(db, 0, 0);

		applyAndVerifyRule(rule, statement, description);
	}

	@Test
	void shouldWrapStatementWithoutExecutingItDuringApply(EmbeddedDatabase db) throws Exception {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createTestDescription(WithDataSet.class, "method1");
		InspectableStatement statement = new InspectableStatement(db, 0, 0);

		Statement wrapped = rule.apply(statement, description);

		assertThat(wrapped).isNotNull();
		assertThat(wrapped).isNotSameAs(statement);
		assertThat(statement.evaluationCount()).isZero();
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldCleanDatabaseWhenWrappedStatementFails(EmbeddedDatabase db) throws Throwable {
		JdbcConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
		DbUnitRule rule = new DbUnitRule(factory);
		Description description = createTestDescription(WithDataSet.class, "method1");
		RuntimeException failure = new RuntimeException("statement failure");
		InspectableStatement statement = new InspectableStatement(db, 2, 3, failure);

		Statement wrapped = rule.apply(statement, description);

		assertThatThrownBy(wrapped::evaluate).isSameAs(failure);
		assertThat(statement.evaluationCount()).isEqualTo(1);
		assertDatabaseCounts(db, 0, 0);
	}

	@Test
	void shouldFailWhenNoConnectionConfigurationCanBeResolved(EmbeddedDatabase db) {
		DbUnitRule rule = new DbUnitRule();
		Description description = createTestDescription(WithoutConnectionConfiguration.class, "test1");
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

	public static class WithoutConnectionConfiguration {

		public void test1() {
		}
	}
}

