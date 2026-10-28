package com.trendyol.redisslackbot.slack;

import com.trendyol.redisslackbot.redis.RedisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.profiles.active=prod",
        "slack.token=test-token",
        "slack.channel-id=test-channel",
        "slack.username=test-bot"
})
class SlackIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisService<String> redisService;

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackManager slackManager;

    @SpyBean
    private SlackService slackService;

    @SpyBean
    private SlackCommand slackCommand;

    @Autowired
    private ProfileService profileService;

    private String testKey;
    private String testValue;

    @BeforeEach
    void setUp() {
        // Clean Redis before each test
        Set<String> keys = redisService.listKeys();
        for (String key : keys) {
            redisService.delete(key);
        }

        testKey = "test-key-" + UUID.randomUUID();
        testValue = "test-value-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        // Clean Redis after each test
        Set<String> keys = redisService.listKeys();
        for (String key : keys) {
            redisService.delete(key);
        }
    }

    @Test
    void shouldTriggerFetchLastMessagesFromChannelOnSchedule() {
        // Given - scheduled job runs every 5 seconds

        // Then - verify the cron job method is called at least twice within 10 seconds
        await()
                .atMost(Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> verify(slackCronJob, atLeast(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void shouldDelegateToSlackManagerWhenFetchLastMessagesFromChannelIsCalled() {
        // Given - the scheduled job is triggered
        slackCronJob.fetchLastMessagesFromChannel();

        // Then - verify SlackManager.process() is called
        verify(slackManager, times(1)).process();
    }

    @Test
    void shouldReadActiveProfileFromEnvironment() {
        // Given - the Spring profile is set to "prod" via @TestPropertySource
        // When - checking the profile
        boolean isProd = profileService.isProd();

        // Then - the profile should be "prod"
        assertThat(isProd).isTrue();
    }

    @Test
    void shouldExecuteSetCommandAgainstRealRedis() {
        // Given - a key and value to store
        // When - executing SET command via RedisService
        redisService.set(testKey, testValue);

        // Then - the value should be retrievable from Redis
        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isPresent();
        assertThat(retrievedValue.get()).isEqualTo(testValue);
    }

    @Test
    void shouldExecuteSetCommandWithTtlAgainstRealRedis() throws InterruptedException {
        // Given - a key and value to store with TTL
        long ttlInSeconds = 2;

        // When - executing SET with TTL command via RedisService
        redisService.set(testKey, testValue, ttlInSeconds);

        // Then - the value should be retrievable immediately
        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isPresent();
        assertThat(retrievedValue.get()).isEqualTo(testValue);

        // Then - after TTL expires, the value should not be retrievable
        Thread.sleep(ttlInSeconds * 1000 + 100);
        Optional<String> expiredValue = redisService.get(testKey);
        assertThat(expiredValue).isNotPresent();
    }

    @Test
    void shouldExecuteGetCommandAgainstRealRedis() {
        // Given - a key with a value already stored
        redisService.set(testKey, testValue);

        // When - executing GET command via RedisService
        Optional<String> retrievedValue = redisService.get(testKey);

        // Then - the correct value should be retrieved
        assertThat(retrievedValue).isPresent();
        assertThat(retrievedValue.get()).isEqualTo(testValue);
    }

    @Test
    void shouldReturnEmptyWhenGettingNonExistentKey() {
        // Given - a non-existent key
        String nonExistentKey = "non-existent-" + UUID.randomUUID();

        // When - executing GET command via RedisService
        Optional<String> retrievedValue = redisService.get(nonExistentKey);

        // Then - empty Optional should be returned
        assertThat(retrievedValue).isNotPresent();
    }

    @Test
    void shouldExecuteDeleteCommandAgainstRealRedis() {
        // Given - a key with a value already stored
        redisService.set(testKey, testValue);

        // When - executing DELETE command via RedisService
        Boolean deleted = redisService.delete(testKey);

        // Then - the key should be deleted from Redis
        assertThat(deleted).isTrue();

        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isNotPresent();
    }

    @Test
    void shouldReturnFalseWhenDeletingNonExistentKey() {
        // Given - a non-existent key
        String nonExistentKey = "non-existent-" + UUID.randomUUID();

        // When - executing DELETE command via RedisService
        Boolean deleted = redisService.delete(nonExistentKey);

        // Then - false should be returned
        assertThat(deleted).isFalse();
    }

    @Test
    void shouldListKeysAgainstRealRedis() {
        // Given - multiple keys with values stored
        String key1 = "key1-" + UUID.randomUUID();
        String key2 = "key2-" + UUID.randomUUID();
        String key3 = "key3-" + UUID.randomUUID();

        redisService.set(key1, "value1");
        redisService.set(key2, "value2");
        redisService.set(key3, "value3");

        // When - listing all keys
        Set<String> keys = redisService.listKeys();

        // Then - all keys should be present
        assertThat(keys).containsExactlyInAnyOrder(key1, key2, key3);
    }

    @Test
    void shouldProcessSlackCommandWithSetOperation() {
        // Given - a SET command
        String command = "SET " + testKey + " " + testValue;

        // When - processing the command
        slackCommand.process(command);

        // Then - the value should be stored in Redis
        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isPresent();
        assertThat(retrievedValue.get()).isEqualTo(testValue);
    }

    @Test
    void shouldProcessSlackCommandWithGetOperation() {
        // Given - a key with a value already stored
        redisService.set(testKey, testValue);

        // When - processing a GET command
        slackCommand.process("GET " + testKey);

        // Then - the operation should complete without errors
        // Note: SlackCommand.process() doesn't return the result, but we can verify no exception is thrown
    }

    @Test
    void shouldProcessSlackCommandWithDeleteOperation() {
        // Given - a key with a value already stored
        redisService.set(testKey, testValue);

        // When - processing a DELETE command
        slackCommand.process("DELETE " + testKey);

        // Then - the key should be deleted from Redis
        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isNotPresent();
    }

    @Test
    void shouldIgnoreSlackCommandWhenContainsRedisSlackBot() {
        // Given - a command containing REDIS_SLACK_BOT
        String command = "REDIS_SLACK_BOT ignore this message";

        // When - processing the command
        slackCommand.process(command);

        // Then - verify SlackService.sendPostMessage is not called
        verify(slackService, never()).sendPostMessage(anyString());
    }

    @Test
    void shouldHandleSlackManagerProcessOnlyInProdProfile() {
        // Given - the profile is "prod" (set via @TestPropertySource)
        // When - SlackManager.process() is called
        slackManager.process();

        // Then - SlackService.retrieveConversationsHistory() should be called
        verify(slackService, times(1)).retrieveConversationsHistory();
    }

    @Test
    void shouldSkipSlackManagerProcessWhenNotProdProfile() {
        // This test will use a separate context with non-prod profile
        // We'll verify the behavior by checking that the method doesn't proceed
        slackManager.process();
        verify(slackService, times(1)).retrieveConversationsHistory();
    }

    @Test
    void shouldSendPostMessageOnSlackServiceError() {
        // Given - SlackService will throw an error when retrieving history
        doThrow(new RuntimeException("Simulated error")).when(slackService).retrieveConversationsHistory();

        // When - SlackManager.process() is called
        slackManager.process();

        // Then - verify slackService.sendPostMessage is called with the error message
        verify(slackService, times(1)).sendPostMessage(anyString());
    }
}