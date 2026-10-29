package com.trendyol.redisslackbot.slack;

import com.trendyol.redisslackbot.common.service.ProfileService;
import com.trendyol.redisslackbot.slack.strategy.SlackCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.trendyol.redisslackbot.slack.constant.SlackConstant.NULL;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class deepseek {

    private SlackCommand slackCommand;
    private SlackService slackService;
    private ProfileService profileService;
    private SlackManager slackManager;

    @BeforeEach
    void setUp() {
        slackCommand = mock(SlackCommand.class);
        slackService = mock(SlackService.class);
        profileService = mock(ProfileService.class);
        slackManager = new SlackManager(slackCommand, slackService, profileService);
    }

    @Test
    void shouldDelegateCronJobToSlackManager() {
        SlackManager manager = mock(SlackManager.class);
        SlackCronJob cronJob = new SlackCronJob(manager);

        cronJob.fetchLastMessagesFromChannel();

        verify(manager).process();
    }

    @Test
    void shouldProcessSlackMessageWhenProfileIsProd() {
        when(profileService.isProd()).thenReturn(true);
        when(slackService.retrieveConversationsHistory()).thenReturn("GET existingKey");

        slackManager.process();

        verify(slackService).retrieveConversationsHistory();
        verify(slackCommand).process("GET existingKey");
        verify(slackService, never()).sendPostMessage(anyString());
    }

    @Test
    void shouldNotProcessSlackMessageWhenProfileIsNotProd() {
        when(profileService.isProd()).thenReturn(false);

        slackManager.process();

        verify(slackService, never()).retrieveConversationsHistory();
        verifyNoInteractions(slackCommand);
    }

    @Test
    void shouldNotProcessCommandWhenSlackHistoryReturnsNullValue() {
        when(profileService.isProd()).thenReturn(true);
        when(slackService.retrieveConversationsHistory()).thenReturn(NULL);

        slackManager.process();

        verify(slackService).retrieveConversationsHistory();
        verifyNoInteractions(slackCommand);
    }

    @Test
    void shouldSendErrorMessageWhenSlackCommandThrowsException() {
        when(profileService.isProd()).thenReturn(true);
        when(slackService.retrieveConversationsHistory()).thenReturn("GET brokenKey");
        doThrow(new RuntimeException("command failed")).when(slackCommand).process("GET brokenKey");

        slackManager.process();

        verify(slackCommand).process("GET brokenKey");
        verify(slackService).sendPostMessage("command failed");
    }
}
