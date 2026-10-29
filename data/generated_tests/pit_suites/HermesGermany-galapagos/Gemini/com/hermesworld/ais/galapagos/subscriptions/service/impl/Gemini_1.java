package com.hermesworld.ais.galapagos.subscriptions.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hermesworld.ais.galapagos.applications.ApplicationMetadata;
import com.hermesworld.ais.galapagos.applications.ApplicationsService;
import com.hermesworld.ais.galapagos.events.GalapagosEventManagerMock;
import com.hermesworld.ais.galapagos.kafka.KafkaCluster;
import com.hermesworld.ais.galapagos.kafka.KafkaClusters;
import com.hermesworld.ais.galapagos.kafka.impl.TopicBasedRepositoryMock;
import com.hermesworld.ais.galapagos.subscriptions.SubscriptionMetadata;
import com.hermesworld.ais.galapagos.subscriptions.SubscriptionState;
import com.hermesworld.ais.galapagos.topics.TopicMetadata;
import com.hermesworld.ais.galapagos.topics.TopicType;
import com.hermesworld.ais.galapagos.topics.service.TopicService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rewritten as a plain unit test (no Spring context), following the same
 * setup pattern used by the other suites in this project (see claudeIT_1).
 *
 * Original Gemini_1 used @SpringBootTest, which required the full
 * application context (including a real PlatformTransactionManager and
 * several other beans not configured for the "test" profile) to load.
 * That infrastructure issue is unrelated to the test logic itself, so the
 * original assertions/scenarios below are preserved unchanged.
 */
public class Gemini_1 {

    private KafkaClusters kafkaClusters;
    private KafkaCluster testCluster;
    private ApplicationsService applicationsService;
    private TopicService topicService;
    private GalapagosEventManagerMock eventManager;
    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;
    private SubscriptionServiceImpl subscriptionService;

    @BeforeEach
    public void setUp() {
        kafkaClusters = mock(KafkaClusters.class);

        testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>();
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment("test")).thenReturn(Optional.of(testCluster));

        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId("app-1");

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata("test", "app-1")).thenReturn(Optional.of(app1));

        // topic-1: regular EVENTS topic, no approval required
        TopicMetadata topic1 = new TopicMetadata();
        topic1.setName("topic-1");
        topic1.setType(TopicType.EVENTS);
        topic1.setOwnerApplicationId("app-2");
        topic1.setSubscriptionApprovalRequired(false);

        // topic-2: INTERNAL topic (subscriptions must be rejected)
        TopicMetadata topic2 = new TopicMetadata();
        topic2.setName("topic-2");
        topic2.setType(TopicType.INTERNAL);
        topic2.setOwnerApplicationId("app-2");

        // topic-3: EVENTS topic requiring approval
        TopicMetadata topic3 = new TopicMetadata();
        topic3.setName("topic-3");
        topic3.setType(TopicType.EVENTS);
        topic3.setOwnerApplicationId("app-2");
        topic3.setSubscriptionApprovalRequired(true);

        topicService = mock(TopicService.class);
        when(topicService.getTopic("test", "topic-1")).thenReturn(Optional.of(topic1));
        when(topicService.getTopic("test", "topic-2")).thenReturn(Optional.of(topic2));
        when(topicService.getTopic("test", "topic-3")).thenReturn(Optional.of(topic3));

        eventManager = new GalapagosEventManagerMock();

        subscriptionService = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
    }

    @Test
    public void shouldSuccessfullyAddSubscriptionWhenTopicAndAppExist() throws Exception {
        SubscriptionMetadata metadata = subscriptionService
                .addSubscription("test", "topic-1", "app-1", "Integration Test Desc").get();

        assertNotNull(metadata.getId());
        assertEquals("topic-1", metadata.getTopicName());
        assertEquals("app-1", metadata.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals("Integration Test Desc", metadata.getDescription());
    }

    @Test
    public void shouldFailToSubscribeToInternalTopic() {
        ExecutionException exception = assertThrows(ExecutionException.class, () -> {
            subscriptionService.addSubscription("test", "topic-2", "app-1", null).get();
        });

        assertTrue(exception.getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void shouldDeleteSubscriptionWhenStateUpdatedToRejected() throws Exception {
        SubscriptionMetadata metadata = subscriptionService
                .addSubscription("test", "topic-1", "app-1", "Integration Test Desc").get();

        subscriptionService.updateSubscriptionState("test", metadata.getId(), SubscriptionState.REJECTED).get();

        List<SubscriptionMetadata> activeSubscriptions = subscriptionService.getSubscriptionsForTopic("test", "topic-1",
                false);
        assertTrue(activeSubscriptions.stream().noneMatch(sub -> sub.getId().equals(metadata.getId())));
    }

    @Test
    public void shouldCreateSubscriptionInPendingStateWhenApprovalIsRequired() throws Exception {
        SubscriptionMetadata metadata = subscriptionService
                .addSubscription("test", "topic-3", "app-1", "Integration Test Desc").get();

        assertNotNull(metadata.getId());
        assertEquals(SubscriptionState.PENDING, metadata.getState());

        List<SubscriptionMetadata> allSubscriptions = subscriptionService.getSubscriptionsForTopic("test", "topic-3",
                true);
        assertTrue(allSubscriptions.stream().anyMatch(sub -> sub.getId().equals(metadata.getId())));
    }
}