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

@EmbeddedDatabaseTest
class GeminiIT {

    @BeforeEach
    void setUpInitialState(EmbeddedDatabase db) throws Exception {
        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isZero();
            assertThat(countMovies(connection)).isZero();
        }
    }

    @Test
    void shouldExecuteRuleAndInitializeDatabaseWithDefaultConfiguration(EmbeddedDatabase db) throws Throwable {
        DbUnitRule rule = new DbUnitRule();
        Statement mockStatement = mock(Statement.class);
        Description description = createTestDescription(WithDbUnitConnection.class, "test1");

        applyAndVerifyMockStatement(db, rule, mockStatement, description, 2, 3);
    }

    @Test
    void shouldExecuteRuleWithExplicitJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
        JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = new DbUnitRule(config);
        Statement mockStatement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyMockStatement(db, rule, mockStatement, description, 2, 3);
    }

    @Test
    void shouldExecuteRuleForMethodLevelDescription(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement mockStatement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method2");

        applyAndVerifyMockStatement(db, rule, mockStatement, description, 2, 0);
    }

    @Test
    void shouldExecuteRuleForClassLevelDescription(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement mockStatement = mock(Statement.class);
        Description description = createSuiteDescription(WithDataSet.class);

        applyAndVerifyMockStatement(db, rule, mockStatement, description, 2, 3);
    }

    @Test
    void shouldThrowExceptionWhenDbUnitConnectionAnnotationIsMissing(EmbeddedDatabase db) {
        DbUnitRule rule = new DbUnitRule();
        Statement mockStatement = mock(Statement.class);
        Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

        assertThatThrownBy(() -> {
            Statement result = rule.apply(mockStatement, description);
            result.evaluate();
        })
        .isInstanceOf(DbUnitException.class)
        .hasMessageContaining("Cannot find database configuration");
    }

    private void applyAndVerifyMockStatement(EmbeddedDatabase db, DbUnitRule rule, Statement statement, Description description, int expectedUsers, int expectedMovies) throws Throwable {
        Statement result = rule.apply(statement, description);
        
        assertThat(result).isNotNull();
        verifyNoInteractions(statement);

        doAnswer((Answer<Void>) invocation -> {
            try (Connection connection = db.getConnection()) {
                assertThat(countUsers(connection)).isEqualTo(expectedUsers);
                assertThat(countMovies(connection)).isEqualTo(expectedMovies);
            }
            return null;
        }).when(statement).evaluate();

        result.evaluate();
        verify(statement).evaluate();
    }
}