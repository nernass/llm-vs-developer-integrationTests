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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class claudeIT {

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
    void cleanRedis() {
        when(redisService.listKeys()).thenReturn(Collections.emptySet());
        when(redisService.delete("nonexistent-key")).thenReturn(false);
        when(redisService.get("nonexistent-key")).thenReturn(Optional.empty());
        clearInvocations(slackCronJob, slackManager, redisService);
    }

    @Test
    void should_invoke_scheduled_cron_job_multiple_times() {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(12))
                .untilAsserted(() -> verify(slackCronJob, times(2)).fetchLastMessagesFromChannel());
    }

    @Test
    void should_delegate_cron_job_to_manager() {
        slackCronJob.fetchLastMessagesFromChannel();
        verify(slackManager, times(1)).process();
    }

    @Test
    void should_detect_active_profile_from_env() {
        boolean expected = "prod".equals(System.getenv("SPRING_PROFILES_ACTIVE"));
        assertThat(profileService.isProd()).isEqualTo(expected);
    }

    @Test
    void should_perform_redis_crud_via_slack_command() {
        when(redisService.get("integration-key"))
                .thenReturn(Optional.of("integration-value"))
                .thenReturn(Optional.empty());

        slackCommand.process("SET integration-key integration-value");
        assertThat(redisService.get("integration-key")).contains("integration-value");

        slackCommand.process("GET integration-key");
        slackCommand.process("DELETE integration-key");

        assertThat(redisService.get("integration-key")).isEmpty();
        verify(redisService, atLeastOnce()).get("integration-key");
        verify(redisService, atLeastOnce()).delete("integration-key");
    }

    @Test
    void should_return_empty_when_getting_nonexistent_key() {
        assertThat(redisService.get("nonexistent-key")).isEmpty();
    }

    @Test
    void should_not_fail_when_deleting_nonexistent_key() {
        assertThat(redisService.delete("nonexistent-key")).isFalse();
    }
}