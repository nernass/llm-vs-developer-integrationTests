
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

@EmbeddedDatabaseTest(lifecycle = EmbeddedDatabaseTest.Lifecycle.BEFORE_EACH)
class DbUnitRuleIntegrationTest {

    @BeforeEach
    void verifyInitialEmptyState(EmbeddedDatabase db) throws Exception {
        assertDatabaseState(db, 0, 0);
    }

    @AfterEach
    void verifyTearDownEmptyState(EmbeddedDatabase db) throws Exception {
        assertDatabaseState(db, 0, 0);
    }

    @Test
    void shouldLoadRuleByScanningTestClassWithDbUnitConnection(EmbeddedDatabase db) throws Throwable {
        DbUnitRule rule = new DbUnitRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDbUnitConnection.class, "test1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadRuleWithExplicitJdbcConfiguration(EmbeddedDatabase db) throws Throwable {
        JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = new DbUnitRule(config);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseUsingConnectionFactoryForClassTest(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseUsingConnectionFactoryForClassRuleSuite(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createSuiteDescription(WithDataSet.class);

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldOverrideClassDatasetWithMethodDataset(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method2");

        applyAndVerifyRule(db, rule, statement, description, 2, 0);
    }

    @Test
    void shouldLeaveDatabaseUntouchedWhenTestLacksDataSetAnnotations(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithoutDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 0, 0);
    }

    @Test
    void shouldThrowExceptionWhenRuleIsBuiltWithoutParameterAndWithoutAnnotation(EmbeddedDatabase db) {
        DbUnitRule rule = new DbUnitRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

        Statement wrappedStatement = rule.apply(statement, description);

        assertThatThrownBy(wrappedStatement::evaluate)
            .isExactlyInstanceOf(DbUnitException.class)
            .hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");

        verifyNoInteractions(statement);
    }

    @Test
    void shouldNotExecuteWrappedStatementDuringRuleApply(EmbeddedDatabase db) {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        Statement wrappedStatement = rule.apply(statement, description);

        assertThat(wrappedStatement)
            .isNotNull()
            .isNotSameAs(statement);

        verifyNoInteractions(statement);
    }

    @Test
    void shouldCleanDatabaseAfterStatementExecutionEvenOnFailure(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = new DbUnitRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        RuntimeException failure = new RuntimeException("Simulated statement failure");

        doAnswer((Answer<Void>) invocation -> {
            assertDatabaseState(db, 2, 3);
            throw failure;
        }).when(statement).evaluate();

        Statement wrappedStatement = rule.apply(statement, description);

        assertThatThrownBy(wrappedStatement::evaluate).isSameAs(failure);

        verify(statement).evaluate();
    }

    private void applyAndVerifyRule(
        EmbeddedDatabase db, 
        DbUnitRule rule, 
        Statement statement, 
        Description description, 
        long expectedCountUsers, 
        long expectedCountMovies
    ) throws Throwable {
        Statement wrappedStatement = rule.apply(statement, description);

        assertThat(wrappedStatement).isNotNull();
        verifyNoInteractions(statement);

        doAnswer((Answer<Void>) invocation -> {
            assertDatabaseState(db, expectedCountUsers, expectedCountMovies);
            return null;
        }).when(statement).evaluate();

        wrappedStatement.evaluate();

        verify(statement).evaluate();
    }

    private void assertDatabaseState(EmbeddedDatabase db, long expectedUsers, long expectedMovies) throws Exception {
        try (Connection connection = db.getConnection()) {
            assertThat(countUsers(connection)).isEqualTo(expectedUsers);
            assertThat(countMovies(connection)).isEqualTo(expectedMovies);
        }
    }
}
