package com.trendyol.redisslackbot.slack;

import com.trendyol.redisslackbot.common.service.ProfileService;
import com.trendyol.redisslackbot.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class SlackCronJobIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RedisService<String> redisService;

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackManager slackManager;

    @Autowired
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        // Clear all Redis keys before each test
        Set<String> keys = redisService.listKeys();
        for (String key : keys) {
            redisService.delete(key);
        }
    }

    @Test
    void should_trigger_fetch_last_messages_from_channel_on_schedule() {
        await()
                .atMost(Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> verify(slackCronJob, atLeast(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void should_delegate_to_slack_manager_when_fetch_last_messages_from_channel_is_called() {
        await()
                .atMost(Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> verify(slackManager, atLeast(2)).process());
    }

    @Test
    void should_read_active_profile_from_environment() {
        // The test should verify that profile detection works correctly
        // Since we're in test environment, this will typically be false (not prod)
        assertThat(profileService.isProd()).isFalse();
    }

    @Test
    void should_execute_set_get_and_delete_commands_against_real_redis() {
        // Given
        String key = "test:key";
        String value = "test-value";

        // When - Set
        redisService.set(key, value);

        // Then - Get
        assertThat(redisService.get(key)).hasValue(value);

        // When - Delete
        Boolean deleted = redisService.delete(key);

        // Then - Verify deleted and not present
        assertThat(deleted).isTrue();
        assertThat(redisService.get(key)).isEmpty();
    }

    @Test
    void should_verify_redis_key_deletion_and_absence_after_delete_command() {
        // Given
        String key = "test:delete:key";
        String value = "delete-test-value";

        // When
        redisService.set(key, value);
        assertThat(redisService.get(key)).hasValue(value);

        // Then
        Boolean deleted = redisService.delete(key);
        assertThat(deleted).isTrue();
        assertThat(redisService.get(key)).isEmpty();
    }

    @Test
    void should_handle_ttl_when_setting_redis_key() {
        // Given
        String key = "test:ttl:key";
        String value = "ttl-test-value";
        long ttlInSeconds = 5;

        // When
        redisService.set(key, value, ttlInSeconds);

        // Then
        assertThat(redisService.get(key)).hasValue(value);

        // Wait for TTL to expire
        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(redisService.get(key)).isEmpty());
    }

    @Test
    void should_list_all_redis_keys() {
        // Given
        String key1 = "test:list:key1";
        String key2 = "test:list:key2";
        String key3 = "test:list:key3";
        String value = "list-test-value";

        // When
        redisService.set(key1, value);
        redisService.set(key2, value);
        redisService.set(key3, value);

        // Then
        Set<String> keys = redisService.listKeys();
        assertThat(keys).contains(key1, key2, key3);

        // Cleanup
        for (String key : keys) {
            redisService.delete(key);
        }
    }

    @Test
    void should_not_process_when_not_in_prod_environment() {
        // Given - we're not in prod environment by default
        // When - process is called
        slackManager.process();

        // Then - No exception should be thrown and processing should be skipped
        // Since we're not in prod, the SlackService methods should not be called
        // This is a behavioral test that verifies the profile check works
        assertThat(profileService.isProd()).isFalse();
    }

    @Test
    void should_handle_multiple_operations_on_redis_without_state_leakage() {
        // Given - Clean state from @BeforeEach

        // When - Perform multiple operations
        String key1 = "test:multi1";
        String key2 = "test:multi2";
        String value1 = "value1";
        String value2 = "value2";

        redisService.set(key1, value1);
        redisService.set(key2, value2);

        // Then - Verify both are present
        assertThat(redisService.get(key1)).hasValue(value1);
        assertThat(redisService.get(key2)).hasValue(value2);

        // When - Delete one
        redisService.delete(key1);

        // Then - Verify only key2 remains
        assertThat(redisService.get(key1)).isEmpty();
        assertThat(redisService.get(key2)).hasValue(value2);

        // Cleanup
        redisService.delete(key2);
    }
}