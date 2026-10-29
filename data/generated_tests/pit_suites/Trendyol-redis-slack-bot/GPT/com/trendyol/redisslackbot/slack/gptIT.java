package com.trendyol.redisslackbot.slack;

import com.trendyol.redisslackbot.common.service.ProfileService;
import com.trendyol.redisslackbot.redis.RedisService;
import com.trendyol.redisslackbot.slack.strategy.SlackCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class gptIT {

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

    @MockBean
    private RedisService<String> redisService;

    @BeforeEach
    void setUp() {
        when(redisService.listKeys()).thenReturn(Collections.emptySet());
    }

    @Test
    void should_trigger_fetch_last_messages_from_channel_on_schedule() {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(12))
                .untilAsserted(() -> verify(slackCronJob, times(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void should_delegate_to_slack_manager_when_fetch_last_messages_from_channel_is_called() {
        slackCronJob.fetchLastMessagesFromChannel();
        verify(slackManager, times(1)).process();
    }

    @Test
    void should_read_active_profile_from_environment() {
        boolean expected = "prod".equals(System.getenv("SPRING_PROFILES_ACTIVE"));
        assertThat(profileService.isProd()).isEqualTo(expected);
    }

    @Test
    void should_execute_set_get_and_delete_commands_without_real_redis() {
        when(redisService.get("integration-key"))
                .thenReturn(Optional.of("integration-value"))
                .thenReturn(Optional.empty());

        assertThatCode(() -> {
            slackCommand.process("SET integration-key integration-value");
            slackCommand.process("GET integration-key");
            slackCommand.process("DELETE integration-key");
        }).doesNotThrowAnyException();

        verify(redisService, atLeastOnce()).get("integration-key");
        verify(redisService, atLeastOnce()).delete("integration-key");
    }
}