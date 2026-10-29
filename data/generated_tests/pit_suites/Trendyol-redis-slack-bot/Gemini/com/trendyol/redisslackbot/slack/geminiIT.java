package com.trendyol.redisslackbot.slack;

import com.trendyol.redisslackbot.common.service.ProfileService;
import com.trendyol.redisslackbot.redis.RedisService;
import com.trendyol.redisslackbot.slack.strategy.SlackCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest
class geminiIT {

    @SpyBean
    private SlackCronJob slackCronJob;

    @SpyBean
    private SlackManager slackManager;

    @Autowired
    private SlackService slackService;

    @Autowired
    private SlackCommand slackCommand;

    @MockBean
    private RedisService<String> redisService;

    @Autowired
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        when(redisService.listKeys()).thenReturn(Collections.emptySet());
        clearInvocations(slackCronJob, slackManager);
    }

    @Test
    void should_trigger_fetch_last_messages_from_channel_on_schedule() {
        await()
                .atMost(Duration.ofSeconds(12))
                .untilAsserted(() ->
                        verify(slackCronJob, atLeast(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void should_delegate_to_slack_manager_when_fetch_last_messages_from_channel_is_called() {
        slackCronJob.fetchLastMessagesFromChannel();
        verify(slackManager, atLeast(1)).process();
    }

    @Test
    void should_read_active_profile_from_environment() {
        boolean expected = "prod".equals(System.getenv("SPRING_PROFILES_ACTIVE"));
        assertThat(profileService.isProd()).isEqualTo(expected);
    }

    @Test
    void should_execute_commands_without_real_redis() {
        when(redisService.get("integration-key"))
                .thenReturn(Optional.of("integration-value"))
                .thenReturn(Optional.empty());

        slackCommand.process("SET integration-key integration-value");
        slackCommand.process("GET integration-key");
        slackCommand.process("DELETE integration-key");

        verify(redisService, atLeastOnce()).get("integration-key");
        verify(redisService, atLeastOnce()).delete("integration-key");
    }

    @Test
    void should_list_keys_and_send_help_and_ping_messages() {
        when(redisService.listKeys()).thenReturn(Set.of("first-key", "second-key"));

        slackCommand.process("LIST ALL");
        slackCommand.process("HELP");
        slackCommand.process("PING");

        verify(redisService, atLeastOnce()).listKeys();
    }
}