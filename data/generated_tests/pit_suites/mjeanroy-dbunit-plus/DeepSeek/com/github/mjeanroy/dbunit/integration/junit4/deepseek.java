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
import com.github.mjeanroy.dbunit.tests.jupiter.EmbeddedDatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import javax.sql.DataSource;
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
@EmbeddedDatabaseTest
class deepseek {

    @BeforeEach
    void setup(EmbeddedDatabase db) throws Exception {
        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isZero();
            assertThat(countMovies(connection)).isZero();
        }
    }

    // ======================== Core functionality ========================

    @Test
    void should_load_rule_by_scanning_test_class(EmbeddedDatabase db) throws Throwable {
        DbUnitRule rule = createRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDbUnitConnection.class, "test1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void should_load_rule_with_jdbc_configuration(EmbeddedDatabase db) throws Throwable {
        JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = createRule(config);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void should_load_rule_with_connection_factory(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void should_load_database_for_class_level_annotation(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createSuiteDescription(WithDataSet.class);

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void should_load_database_for_method_level_annotation(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method2");

        applyAndVerifyRule(db, rule, statement, description, 2, 0);
    }

    @Test
    void should_fail_if_rule_has_no_configuration_and_no_annotation(EmbeddedDatabase db) {
        DbUnitRule rule = createRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

        assertThatThrownBy(() -> applyAndVerifyRule(db, rule, statement, description, 0, 0))
                .isExactlyInstanceOf(DbUnitException.class)
                .hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");
    }

    // ======================== Additional edge cases ========================

    @Test
    void should_load_rule_with_data_source(EmbeddedDatabase db) throws Throwable {
        DataSource dataSource = db;
        DbUnitRule rule = new DbUnitRule(dataSource);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void should_handle_multiple_dataset_files(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);

        Statement methodLevelStatement = mock(Statement.class);
        Description methodLevelDescription = createTestDescription(WithDataSet.class, "method2");
        applyAndVerifyRule(db, rule, methodLevelStatement, methodLevelDescription, 2, 0);

        Statement classLevelStatement = mock(Statement.class);
        Description classLevelDescription = createTestDescription(WithDataSet.class, "method1");
        applyAndVerifyRule(db, rule, classLevelStatement, classLevelDescription, 2, 3);
    }

    @Test
    void should_evaluate_statement_when_connection_annotation_is_declared(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDbUnitConnection.class, "test1");

        Answer<Void> checkDatabaseIsLoaded = invocation -> {
            try (Connection connection = db.getConnection()) {
                assertThat(countUsers(connection)).isEqualTo(2);
                assertThat(countMovies(connection)).isEqualTo(3);
            }
            return null;
        };

        doAnswer(checkDatabaseIsLoaded).when(statement).evaluate();

        Statement result = rule.apply(statement, description);
        assertThat(result).isNotNull();

        result.evaluate();

        verify(statement).evaluate();
    }

    @Test
    void should_handle_empty_database_state(EmbeddedDatabase db) throws Exception {
        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isZero();
            assertThat(countMovies(connection)).isZero();
        }
    }

    @Test
    void should_handle_database_loaded_from_embedded_connection_factory(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void should_create_rule_without_immediate_dataset_lookup(EmbeddedDatabase db) {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);

        assertThat(rule).isNotNull();
    }

    // ======================== Utility methods ========================

    private static DbUnitRule createRule() {
        return new DbUnitRule();
    }

    private static DbUnitRule createRule(JdbcConfiguration configuration) {
        return new DbUnitRule(configuration);
    }

    private static DbUnitRule createRule(JdbcConnectionFactory factory) {
        return new DbUnitRule(factory);
    }

    private static void applyAndVerifyRule(EmbeddedDatabase db, DbUnitRule rule, Statement statement,
                                           Description description, int expectedCountUsers,
                                           int expectedCountMovies) throws Throwable {
        Statement result = rule.apply(statement, description);

        assertThat(result).isNotNull();
        verifyNoInteractions(statement);

        Answer<Void> answer = invocation -> {
            try (Connection connection = db.getConnection()) {
                assertThat(countUsers(connection)).isEqualTo(expectedCountUsers);
                assertThat(countMovies(connection)).isEqualTo(expectedCountMovies);
            }
            return null;
        };

        doAnswer(answer).when(statement).evaluate();

        result.evaluate();

        verify(statement).evaluate();
    }
}
