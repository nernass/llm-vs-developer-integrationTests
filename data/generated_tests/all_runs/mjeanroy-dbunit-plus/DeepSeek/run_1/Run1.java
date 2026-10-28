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
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class DbUnitRuleIT {

    @BeforeEach
    void setup(EmbeddedDatabase db) throws Exception {
        Connection connection = db.getConnection();
        assertThat(countUsers(connection)).isZero();
        assertThat(countMovies(connection)).isZero();
    }

    @Test
    void shouldLoadRuleByScanningTestClass(EmbeddedDatabase db) throws Throwable {
        DbUnitRule rule = createRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDbUnitConnection.class, "test1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadRuleWithConfiguration(EmbeddedDatabase db) throws Throwable {
        JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = createRule(config);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseForClassTest(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseForClassRule(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createSuiteDescription(WithDataSet.class);

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
    }

    @Test
    void shouldLoadDatabaseForMethodTest(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method2");

        applyAndVerifyRule(db, rule, statement, description, 2, 0);
    }

    @Test
    void shouldFailIfRuleIsBuiltWithoutParameterAndWithoutAnnotation(EmbeddedDatabase db) {
        DbUnitRule rule = createRule();
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithRunnerWithoutConfiguration.class, "test1");

        assertThatThrownBy(() -> applyAndVerifyRule(db, rule, statement, description, 0, 0))
            .isExactlyInstanceOf(DbUnitException.class)
            .hasMessage("Cannot find database configuration, please annotate your class with @DbUnitConnection");
    }

    @Test
    void shouldLoadDatabaseWithJdbcConfigurationAndMethodLevelDataSet(EmbeddedDatabase db) throws Throwable {
        JdbcConfiguration config = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = createRule(config);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method2");

        applyAndVerifyRule(db, rule, statement, description, 2, 0);
    }

    @Test
    void shouldLoadDatabaseWithConnectionFactoryAndClassLevelDataSet(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);
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

    @Test
    void shouldHandleMultipleRulesAndDescriptions(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement1 = mock(Statement.class);
        Statement statement2 = mock(Statement.class);
        
        Description description1 = createTestDescription(WithDataSet.class, "method1");
        Description description2 = createTestDescription(WithDataSet.class, "method2");

        // Apply first rule
        Statement result1 = rule.apply(statement1, description1);
        assertThat(result1).isNotNull();
        verifyNoInteractions(statement1);

        // Apply second rule
        Statement result2 = rule.apply(statement2, description2);
        assertThat(result2).isNotNull();
        verifyNoInteractions(statement2);

        // Setup assertions for first dataset (full dataset)
        Answer<Void> answer1 = invocation -> {
            Connection connection = db.getConnection();
            assertThat(countUsers(connection)).isEqualTo(2);
            assertThat(countMovies(connection)).isEqualTo(3);
            return null;
        };
        doAnswer(answer1).when(statement1).evaluate();

        // Setup assertions for second dataset (only users)
        Answer<Void> answer2 = invocation -> {
            Connection connection = db.getConnection();
            assertThat(countUsers(connection)).isEqualTo(2);
            assertThat(countMovies(connection)).isZero();
            return null;
        };
        doAnswer(answer2).when(statement2).evaluate();

        // Execute both statements
        result1.evaluate();
        result2.evaluate();
        
        verify(statement1).evaluate();
        verify(statement2).evaluate();
    }

    @Test
    void shouldHandleDatabaseConnectionWithDifferentConfigurations(EmbeddedDatabase db) throws Throwable {
        // Test with driver specification
        JdbcConfiguration config = newJdbcConfiguration("org.hsqldb.jdbcDriver", "jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule rule = createRule(config);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        applyAndVerifyRule(db, rule, statement, description, 2, 3);

        // Test without driver specification
        JdbcConfiguration configWithoutDriver = newJdbcConfiguration("jdbc:hsqldb:mem:testdb", "sa", "");
        DbUnitRule ruleWithoutDriver = createRule(configWithoutDriver);
        Statement statementWithoutDriver = mock(Statement.class);
        
        applyAndVerifyRule(db, ruleWithoutDriver, statementWithoutDriver, description, 2, 3);
    }

    @Test
    void shouldThrowExceptionWhenStatementEvaluationFails(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        Statement result = rule.apply(statement, description);
        assertThat(result).isNotNull();
        verifyNoInteractions(statement);

        // Simulate statement failure
        RuntimeException expectedException = new RuntimeException("Statement execution failed");
        doAnswer(invocation -> { throw expectedException; }).when(statement).evaluate();

        // The rule should still execute the statement and propagate the exception
        assertThatThrownBy(result::evaluate)
            .isSameAs(expectedException);
        
        verify(statement).evaluate();
    }

    @Test
    void shouldPreserveOriginalStatementBehavior(EmbeddedDatabase db) throws Throwable {
        EmbeddedDatabaseConnectionFactory factory = new EmbeddedDatabaseConnectionFactory(db);
        DbUnitRule rule = createRule(factory);
        Statement statement = mock(Statement.class);
        Description description = createTestDescription(WithDataSet.class, "method1");

        // Configure statement to return a value
        Statement result = rule.apply(statement, description);
        assertThat(result).isNotNull();
        verifyNoInteractions(statement);

        // Verify statement executes with correct behavior
        doAnswer(invocation -> {
            // Simulate some business logic
            return null;
        }).when(statement).evaluate();

        result.evaluate();
        verify(statement).evaluate();
    }
}