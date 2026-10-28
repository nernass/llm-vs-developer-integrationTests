package com.trendyol.redisslackbot.slack;

import com.slack.api.methods.MethodsClient;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.trendyol.redisslackbot.slack.constant.SlackConstant.REDIS_SLACK_BOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(SlackCronJobIntegrationTest.SlackApiTestConfiguration.class)
class SlackCronJobIntegrationTest {

    private static final String REDIS_PASSWORD = "password";
    private static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", REDIS_CONTAINER::getHost);
        registry.add("redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
        registry.add("redis.password", () -> REDIS_PASSWORD);
    }

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackManager slackManager;

    @SpyBean
    private SlackCommand slackCommand;

    @Autowired
    private SlackService slackService;

    @Autowired
    private RedisService<String> redisService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private SlackApiProbe slackApiProbe;

    @BeforeEach
    void setUp() {
        clearRedisKeys();
        slackApiProbe.reset();
        clearInvocations(slackManager, slackCommand);
    }

    @AfterEach
    void tearDown() {
        clearRedisKeys();
        slackApiProbe.reset();
    }

    @AfterAll
    static void stopRedisContainer() {
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
    void should_retrieve_last_message_from_slack_using_real_slack_service() {
        String expectedMessage = "integration test message";
        int conversationsHistoryInvocationsBeforeCall = slackApiProbe.getConversationsHistoryInvocations();

        slackApiProbe.setLatestHistoryText(expectedMessage);

        String actualMessage = slackService.retrieveConversationsHistory();

        assertThat(actualMessage).isEqualTo(expectedMessage);
        assertThat(slackApiProbe.getConversationsHistoryInvocations())
                .isGreaterThan(conversationsHistoryInvocationsBeforeCall);
    }

    @Test
    void should_execute_set_get_and_delete_commands_against_real_redis() {
        String key = "integration-key";
        String value = "integration-value";

        slackCommand.process("SET " + key + " " + value);

        assertThat(redisService.get(key)).contains(value);

        int chatPostMessageInvocationsBeforeGet = slackApiProbe.getChatPostMessageInvocations();

        slackCommand.process("GET " + key);

        assertThat(slackApiProbe.getChatPostMessageInvocations())
                .isGreaterThan(chatPostMessageInvocationsBeforeGet);

        slackCommand.process("DELETE " + key);

        assertThat(redisService.get(key)).isEmpty();
    }

    @Test
    void should_read_active_profile_from_environment() throws Exception {
        assertThat(runProfileServiceProbe("prod")).isEqualTo("true");
        assertThat(runProfileServiceProbe("test")).isEqualTo("false");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = "prod")
    void should_delegate_to_slack_command_when_slack_manager_processes_prod_message() {
        String key = "manager-key";
        String value = "manager-value";
        String command = "SET " + key + " " + value;

        clearInvocations(slackCommand);
        slackApiProbe.setLatestHistoryText(command);

        slackManager.process();
        slackApiProbe.setLatestHistoryText(REDIS_SLACK_BOT);

        verify(slackCommand, atLeastOnce()).process(command);
        assertThat(redisService.get(key)).contains(value);
    }

    private void clearRedisKeys() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static String runProfileServiceProbe(String activeProfile) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                ProfileServiceProbe.class.getName()
        );
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("SPRING_PROFILES_ACTIVE", activeProfile);

        Process process = processBuilder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();

        return output;
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static class ProfileServiceProbe {

        public static void main(String[] args) {
            System.out.print(new ProfileService().isProd());
        }
    }

    @TestConfiguration
    static class SlackApiTestConfiguration {

        @Bean
        SlackApiProbe slackApiProbe() {
            return new SlackApiProbe();
        }

        @Bean(name = "getClient")
        MethodsClient getClient(SlackApiProbe slackApiProbe) {
            return slackApiProbe.createClient();
        }
    }

    static class SlackApiProbe {

        private final AtomicReference<String> latestHistoryText = new AtomicReference<>(REDIS_SLACK_BOT);
        private final AtomicInteger conversationsHistoryInvocations = new AtomicInteger();
        private final AtomicInteger chatPostMessageInvocations = new AtomicInteger();

        MethodsClient createClient() {
            InvocationHandler invocationHandler = (proxy, method, args) -> {
                String methodName = method.getName();

                if ("conversationsHistory".equals(methodName)) {
                    conversationsHistoryInvocations.incrementAndGet();
                    return conversationsHistoryResponse(latestHistoryText.get());
                }

                if ("chatPostMessage".equals(methodName)) {
                    chatPostMessageInvocations.incrementAndGet();
                    return new ChatPostMessageResponse();
                }

                if ("toString".equals(methodName)) {
                    return "SlackApiProbeMethodsClient";
                }

                if ("hashCode".equals(methodName)) {
                    return System.identityHashCode(proxy);
                }

                if ("equals".equals(methodName)) {
                    return proxy == (args == null ? null : args[0]);
                }

                return defaultValue(method.getReturnType());
            };

            return (MethodsClient) Proxy.newProxyInstance(
                    MethodsClient.class.getClassLoader(),
                    new Class<?>[]{MethodsClient.class},
                    invocationHandler
            );
        }

        void setLatestHistoryText(String latestHistoryText) {
            this.latestHistoryText.set(latestHistoryText);
        }

        int getConversationsHistoryInvocations() {
            return conversationsHistoryInvocations.get();
        }

        int getChatPostMessageInvocations() {
            return chatPostMessageInvocations.get();
        }

        void reset() {
            latestHistoryText.set(REDIS_SLACK_BOT);
            conversationsHistoryInvocations.set(0);
            chatPostMessageInvocations.set(0);
        }

        private static ConversationsHistoryResponse conversationsHistoryResponse(String text) {
            Message message = new Message();
            message.setText(text);

            ConversationsHistoryResponse response = new ConversationsHistoryResponse();
            response.setMessages(List.of(message));
            return response;
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (boolean.class.equals(returnType)) {
                return false;
            }
            if (byte.class.equals(returnType)) {
                return (byte) 0;
            }
            if (short.class.equals(returnType)) {
                return (short) 0;
            }
            if (int.class.equals(returnType)) {
                return 0;
            }
            if (long.class.equals(returnType)) {
                return 0L;
            }
            if (float.class.equals(returnType)) {
                return 0F;
            }
            if (double.class.equals(returnType)) {
                return 0D;
            }
            if (char.class.equals(returnType)) {
                return '\0';
            }
            return null;
        }
    }
}