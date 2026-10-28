package com.trendyol.redisslackbot.slack;

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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest
class SlackCronJobIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", redis::getFirstMappedPort);
    }

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackManager slackManager;

    @SpyBean
    private SlackService slackService;

    @SpyBean
    private SlackCommand slackCommand;

    @Autowired
    private RedisService<String> redisService;

    @BeforeEach
    void setUp() {
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
                .untilAsserted(() -> verify(slackManager, atLeast(1)).process());
    }

    @Test
    void should_execute_set_get_and_delete_commands_against_real_redis() {
        String testKey = "test:key";
        String testValue = "test-value";

        redisService.set(testKey, testValue);

        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isPresent().contains(testValue);

        Boolean deleted = redisService.delete(testKey);
        assertThat(deleted).isTrue();

        Optional<String> afterDelete = redisService.get(testKey);
        assertThat(afterDelete).isNotPresent();
    }

    @Test
    void should_execute_set_with_ttl_against_real_redis() throws InterruptedException {
        String testKey = "test:ttl:key";
        String testValue = "test-ttl-value";
        long ttlInSeconds = 2L;

        redisService.set(testKey, testValue, ttlInSeconds);

        Optional<String> retrievedValue = redisService.get(testKey);
        assertThat(retrievedValue).isPresent().contains(testValue);

        Thread.sleep(ttlInSeconds * 1000L + 500);

        Optional<String> afterTtl = redisService.get(testKey);
        assertThat(afterTtl).isNotPresent();
    }

    @Test
    void should_list_all_keys_against_real_redis() {
        String key1 = "list:key1";
        String key2 = "list:key2";
        String value = "value";

        redisService.set(key1, value);
        redisService.set(key2, value);

        Set<String> keys = redisService.listKeys();

        assertThat(keys)
                .contains(key1, key2)
                .hasSize(2);
    }

    @Test
    void should_return_empty_optional_when_key_not_found() {
        Optional<String> result = redisService.get("non-existent-key");
        assertThat(result).isNotPresent();
    }

    @Test
    void should_return_false_when_deleting_non_existent_key() {
        Boolean result = redisService.delete("non-existent-key");
        assertThat(result).isFalse();
    }
}