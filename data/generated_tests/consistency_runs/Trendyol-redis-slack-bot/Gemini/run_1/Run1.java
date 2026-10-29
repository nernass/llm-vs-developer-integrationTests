package com.trendyol.redisslackbot.slack;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.RequestConfigurator;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.model.Message;
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
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class claudeIT {

    private static final String REDIS_PASSWORD = "password";

    private static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    private static final WireMockServer WIRE_MOCK_SERVER = new WireMockServer(options().dynamicPort());

    static {
        REDIS_CONTAINER.start();
        WIRE_MOCK_SERVER.start();
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

    @BeforeEach
    void setUp() throws Exception {
        clearRedisKeys();
        WIRE_MOCK_SERVER.resetAll();
        clearInvocations(slackCronJob, slackManager);

        Message message = new Message();
        message.setText("Redis Slack Bot");
        ConversationsHistoryResponse historyResponse = new ConversationsHistoryResponse();
        historyResponse.setMessages(List.of(message));

        doReturn(historyResponse).when(methodsClient).conversationsHistory(any((Class<RequestConfigurator<com.slack.api.methods.request.conversations.ConversationsHistoryRequest.ConversationsHistoryRequestBuilder, com.slack.api.methods.request.conversations.ConversationsHistoryRequest>>) (Class<?>) RequestConfigurator.class));
        doReturn(new ChatPostMessageResponse()).when(methodsClient).chatPostMessage(any((Class<RequestConfigurator<com.slack.api.methods.request.chat.ChatPostMessageRequest.ChatPostMessageRequestBuilder, com.slack.api.methods.request.chat.ChatPostMessageRequest>>) (Class<?>) RequestConfigurator.class));
    }

    @AfterEach
    void tearDown() {
        WIRE_MOCK_SERVER.resetAll();
        clearRedisKeys();
    }

    @AfterAll
    static void cleanUpSuite() {
        WIRE_MOCK_SERVER.stop();
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
        String key = "integration-key";
        String value = "integration-value";

        slackCommand.process("SET " + key + " " + value);
        assertThat(redisService.get(key)).contains(value);

        slackCommand.process("GET " + key);
        slackCommand.process("DELETE " + key);

        assertThat(redisService.get(key)).isEmpty();
    }

    @Test
    void should_clean_redis_keys_in_cleanup_flow() {
        String key = "cleanup-key";

        redisService.set(key, "cleanup-value");
        assertThat(redisService.get(key)).contains("cleanup-value");

        clearRedisKeys();

        assertThat(redisService.get(key)).isEmpty();
    }

    private void clearRedisKeys() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}