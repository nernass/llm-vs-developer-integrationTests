package com.trendyol.redisslackbot.slack;

import com.trendyol.redisslackbot.common.service.ProfileService;
import com.trendyol.redisslackbot.redis.RedisService;
import com.trendyol.redisslackbot.slack.strategy.SlackCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
class SlackIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisService<String> redisService;

    @Autowired
    private SlackManager slackManager;

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackCommand slackCommand;

    @MockBean
    private SlackService slackService;

    @BeforeEach
    void setUp() {
        clearRedisKeys();
    }

    @AfterEach
    void tearDown() {
        clearRedisKeys();
    }

    private void clearRedisKeys() {
        Set<String> keys = redisService.listKeys();
        if (keys != null && !keys.isEmpty()) {
            keys.forEach(redisService::delete);
        }
    }

    @Test
    void should_trigger_fetch_last_messages_from_channel_on_schedule() {
        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verify(slackCronJob, atLeast(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void should_delegate_to_slack_manager_when_fetch_last_messages_from_channel_is_called() {
        slackCronJob.fetchLastMessagesFromChannel();

        verify(slackManager, times(1)).process();
    }

    @Test
    void should_not_process_when_profile_is_not_prod() {
        String originalProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        try {
            mockEnvironmentVariable("SPRING_PROFILES_ACTIVE", "dev");

            ProfileService profileService = new ProfileService();
            assertThat(profileService.isProd()).isFalse();

            slackManager.process();

            verify(slackService, never()).retrieveConversationsHistory();
            verify(slackCommand, never()).process(anyString());
        } finally {
            restoreEnvironmentVariable("SPRING_PROFILES_ACTIVE", originalProfile);
        }
    }

    @Test
    void should_process_when_profile_is_prod() {
        String originalProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        try {
            mockEnvironmentVariable("SPRING_PROFILES_ACTIVE", "prod");

            ProfileService profileService = new ProfileService();
            assertThat(profileService.isProd()).isTrue();

            slackManager.process();

            verify(slackService, times(1)).retrieveConversationsHistory();
        } finally {
            restoreEnvironmentVariable("SPRING_PROFILES_ACTIVE", originalProfile);
        }
    }

    @Test
    void should_read_active_profile_from_environment() {
        String originalProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        try {
            mockEnvironmentVariable("SPRING_PROFILES_ACTIVE", "prod");

            ProfileService profileService = new ProfileService();
            assertThat(profileService.isProd()).isTrue();
        } finally {
            restoreEnvironmentVariable("SPRING_PROFILES_ACTIVE", originalProfile);
        }
    }

    @Test
    void should_execute_set_command_against_real_redis() {
        String key = "testKey";
        String value = "testValue";

        redisService.set(key, value);

        Optional<String> retrieved = redisService.get(key);
        assertThat(retrieved).isPresent().contains(value);
    }

    @Test
    void should_execute_get_command_against_real_redis() {
        String key = "testKey";
        String value = "testValue";
        redisService.set(key, value);

        Optional<String> retrieved = redisService.get(key);

        assertThat(retrieved).isPresent().contains(value);
    }

    @Test
    void should_execute_delete_command_against_real_redis() {
        String key = "testKey";
        String value = "testValue";
        redisService.set(key, value);

        Boolean deleted = redisService.delete(key);

        assertThat(deleted).isTrue();
        Optional<String> retrieved = redisService.get(key);
        assertThat(retrieved).isEmpty();
    }

    @Test
    void should_handle_set_with_ttl_against_real_redis() throws InterruptedException {
        String key = "ttlKey";
        String value = "ttlValue";
        long ttlInSeconds = 2;

        redisService.set(key, value, ttlInSeconds);

        Optional<String> retrieved = redisService.get(key);
        assertThat(retrieved).isPresent().contains(value);

        Thread.sleep(ttlInSeconds * 1000L + 100);

        Optional<String> afterTtl = redisService.get(key);
        assertThat(afterTtl).isEmpty();
    }

    @Test
    void should_list_keys_against_real_redis() {
        redisService.set("key1", "value1");
        redisService.set("key2", "value2");
        redisService.set("key3", "value3");

        Set<String> keys = redisService.listKeys();

        assertThat(keys).containsExactlyInAnyOrder("key1", "key2", "key3");
    }

    @Test
    void should_handle_delete_of_nonexistent_key() {
        Boolean deleted = redisService.delete("nonexistentKey");

        assertThat(deleted).isFalse();
    }

    private void mockEnvironmentVariable(String name, String value) {
        try {
            java.lang.reflect.Field field = System.class.getDeclaredField("props");
            field.setAccessible(true);
            java.util.Properties props = (java.util.Properties) field.get(null);
            if (value != null) {
                props.setProperty(name, value);
            } else {
                props.remove(name);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to mock environment variable", e);
        }
    }

    private void restoreEnvironmentVariable(String name, String originalValue) {
        try {
            java.lang.reflect.Field field = System.class.getDeclaredField("props");
            field.setAccessible(true);
            java.util.Properties props = (java.util.Properties) field.get(null);
            if (originalValue != null) {
                props.setProperty(name, originalValue);
            } else {
                props.remove(name);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to restore environment variable", e);
        }
    }
}