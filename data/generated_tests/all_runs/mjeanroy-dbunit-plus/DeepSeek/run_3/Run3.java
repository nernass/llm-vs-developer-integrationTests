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
import com.github.mjeanroy.dbunit.tests.fixtures.MethodLevelDataSet;
import com.github.mjeanroy.dbunit.tests.fixtures.MultipleClassAnnotations;
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

import java.sql.Connection;
import java.sql.SQLException;

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

@EmbeddedDatabaseTest
class DbUnitRuleTest {

    @BeforeEach
    void setup(EmbeddedDatabase db) throws SQLException {
        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isZero();
            assertThat(countMovies(connection)).isZero();
        }
    }

    @Test
    void shouldLoadRuleByScanningTestClass(EmbeddedDatabase db) throws Throwable {
        DbUnitRule rule = new DbUnitRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDbUnitConnection.class, "test");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadRuleWithConfiguration(EmbeddedDatabase db) throws Throwable {
        JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = new DbUnitRule(config);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseForClassTest(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseForClassRule(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createSuiteDescription(WithDataSet.class);

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseForMethodTest(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method2");

        applyAndVerifyRule(db, rule, statement, description, 2, 0);
    }

    @Test
    void shouldLoadMethodLevelDataSet(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(MethodLevelDataSet.class, "methodWithDataset");

        applyAndVerifyRule(db, rule, statement, description, 2, 1);
    }

    @Test
    void shouldLoadMethodLevelDataSetOverridingClassLevel(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(MethodLevelDataSet.class, "methodWithoutDataset");

        applyAndVerifyRule(db, rule, statement, description, 1, 0);
    }

    @Test
    void shouldHandleMultipleClassLevelAnnotations(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(MultipleClassAnnotations.class, "test");

        applyAndVerifyRule(db, rule, statement, description, 3, 2);
    }

    @Test
    void shouldFailWhenRuleBuiltWithoutParameterAndWithoutAnnotation(EmbeddedDatabase db) {
        DbUnitRule rule = new DbUnitRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test");

        assertThatThrownBy(() -> applyAndVerifyRule(db, rule, statement, description, 0, 0))
                .isExactlyInstanceOf(DbUnitException.class)
                .hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");
    }

    @Test
    void shouldPreserveDatabaseStateWhenStatementThrowsException(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        Statement result = rule.apply(statement, description);
        assertThat(result).isNotNull();
        verifyNoInteractions(statement);

        Answer<Void> failingAnswer = invocation -> {
            throw new RuntimeException("Test exception");
        };
        doAnswer(failingAnswer).when(statement).evaluate();

        try {
            result.evaluate();
        } catch (RuntimeException ignored) {
        }

        verify(statement).evaluate();

        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isEqualTo(2);
            assertThat(countMovies(connection)).isEqualTo(3);
        }
    }

    @Test
    void shouldCleanupDatabaseAfterTest(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        Statement result = rule.apply(statement, description);

        doAnswer(invocation -> null).when(statement).evaluate();
        result.evaluate();

        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isZero();
            assertThat(countMovies(connection)).isZero();
        }
    }

    @Test
    void shouldHandleNullMethodNameInDescription(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);

        Description description = createSuiteDescription(WithDataSet.class);

        Statement result = rule.apply(statement, description);
        assertThat(result).isNotNull();

        doAnswer(invocation -> null).when(statement).evaluate();
        result.evaluate();

        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isZero();
            assertThat(countMovies(connection)).isZero();
        }
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