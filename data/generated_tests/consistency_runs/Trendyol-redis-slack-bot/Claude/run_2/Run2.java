package com.trendyol.redisslackbot.slack;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.slack.api.methods.MethodsClient;
import com.trendyol.redisslackbot.common.service.ProfileService;
import com.trendyol.redisslackbot.redis.RedisService;
import com.trendyol.redisslackbot.slack.strategy.SlackCommand;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class claudeIT {

    private static final String REDIS_PASSWORD = "password";

    private static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", REDIS_CONTAINER::getHost);
        registry.add("redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
        registry.add("redis.password", () -> REDIS_PASSWORD);
    }

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackManager slackManager;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private SlackService slackService;

    @Autowired
    private SlackCommand slackCommand;

    @Autowired
    private RedisService<String> redisService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private MethodsClient methodsClient;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        clearRedisKeys();
        clearInvocations(slackCronJob, slackManager);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.resetAll();
            wireMockServer.stop();
        }
        clearRedisKeys();
    }

    @AfterAll
    static void stopRedis() {
        REDIS_CONTAINER.stop();
    }

    @Test
    void should_trigger_fetch_last_messages_from_channel_on_schedule() {
        clearInvocations(slackCronJob);

        await()
                .atMost(Duration.of(10, ChronoUnit.SECONDS))
                .untilAsserted(() -> verify(slackCronJob, atLeast(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void should_delegate_to_slack_manager_when_fetch_last_messages_from_channel_is_called() {
        clearInvocations(slackManager);

        slackCronJob.fetchLastMessagesFromChannel();

        verify(slackManager, atLeastOnce()).process();
    }

    @Test
    void should_read_active_profile_from_environment() {
        boolean expected = "prod".equals(System.getenv("SPRING_PROFILES_ACTIVE"));

        assertThat(profileService.isProd()).isEqualTo(expected);
    }

    @Test
    void should_execute_set_get_and_delete_commands_against_real_redis() {
        String key = "it-key";
        String value = "it-value";

        slackCommand.process("SET " + key + " " + value);
        assertThat(redisService.get(key)).contains(value);

        slackCommand.process("GET " + key);

        slackCommand.process("DELETE " + key);
        assertThat(redisService.get(key)).isEmpty();
    }

    @Test
    void should_initialize_real_slack_service() {
        assertThat(slackService).isNotNull();
    }

    private void clearRedisKeys() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
