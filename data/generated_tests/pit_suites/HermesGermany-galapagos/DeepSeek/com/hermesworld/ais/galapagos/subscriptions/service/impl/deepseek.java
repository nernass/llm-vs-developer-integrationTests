package com.hermesworld.ais.galapagos.subscriptions.service.impl;

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
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link SubscriptionServiceImpl} following the reference test pattern.
 * Uses mocks for external dependencies (KafkaClusters, ApplicationsService, TopicService)
 * and in-memory fakes for repository and event manager.
 */
public class deepseek {

    private KafkaClusters kafkaClusters;
    private ApplicationsService applicationsService;
    private TopicService topicService;
    private GalapagosEventManagerMock eventManager;
    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;

    private static final String ENV = "test";
    private static final String APP_1 = "app-1";
    private static final String APP_2 = "app-2";
    private static final String TOPIC_1 = "topic-1";   // EVENTS, no approval
    private static final String TOPIC_2 = "topic-2";   // INTERNAL
    private static final String TOPIC_3 = "topic-3";   // EVENTS, approval required

    @Before
    public void setupMocks() {
        // KafkaClusters
        kafkaClusters = mock(KafkaClusters.class);
        KafkaCluster testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>();
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment(ENV)).thenReturn(Optional.of(testCluster));
        when(kafkaClusters.getEnvironment("non-existent")).thenReturn(Optional.empty());

        // Applications
        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId(APP_1);
        ApplicationMetadata app2 = new ApplicationMetadata();
        app2.setApplicationId(APP_2);

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata(ENV, APP_1)).thenReturn(Optional.of(app1));
        when(applicationsService.getApplicationMetadata(ENV, APP_2)).thenReturn(Optional.of(app2));
        when(applicationsService.getApplicationMetadata(ENV, "unknown-app")).thenReturn(Optional.empty());

        // Topics
        TopicMetadata topic1 = new TopicMetadata();
        topic1.setName(TOPIC_1);
        topic1.setType(TopicType.EVENTS);
        topic1.setOwnerApplicationId(APP_2);
        topic1.setSubscriptionApprovalRequired(false);

        TopicMetadata topic2 = new TopicMetadata();
        topic2.setName(TOPIC_2);
        topic2.setType(TopicType.INTERNAL);
        topic2.setOwnerApplicationId(APP_2);

        TopicMetadata topic3 = new TopicMetadata();
        topic3.setName(TOPIC_3);
        topic3.setType(TopicType.EVENTS);
        topic3.setOwnerApplicationId(APP_2);
        topic3.setSubscriptionApprovalRequired(true);

        topicService = mock(TopicService.class);
        when(topicService.getTopic(ENV, TOPIC_1)).thenReturn(Optional.of(topic1));
        when(topicService.getTopic(ENV, TOPIC_2)).thenReturn(Optional.of(topic2));
        when(topicService.getTopic(ENV, TOPIC_3)).thenReturn(Optional.of(topic3));
        when(topicService.getTopic(ENV, "unknown-topic")).thenReturn(Optional.empty());

        eventManager = new GalapagosEventManagerMock();
    }

    // ====================== Existing test cases (from reference) ======================

    @Test
    public void testAddSubscription_positive() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata metadata = service.addSubscription(ENV, TOPIC_1, APP_1, "Some Desc").get();
        assertNotNull(metadata.getId());
        assertEquals(TOPIC_1, metadata.getTopicName());
        assertEquals(APP_1, metadata.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals("Some Desc", metadata.getDescription());

        assertTrue(repository.getObject(metadata.getId()).isPresent());

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void testAddSubscription_pending() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata metadata = service.addSubscription(ENV, TOPIC_3, APP_1, null).get();
        assertNotNull(metadata.getId());
        assertEquals(TOPIC_3, metadata.getTopicName());
        assertEquals(APP_1, metadata.getClientApplicationId());
        assertEquals(SubscriptionState.PENDING, metadata.getState());
        assertNull(metadata.getDescription());

        assertTrue(repository.getObject(metadata.getId()).isPresent());

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void testAddSubscription_fail_internalTopic() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription(ENV, TOPIC_2, APP_1, null).get();
            fail("Expected exception when trying to subscribe an internal topic");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testAddSubscription_directMetadata() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId(APP_1);
        sub.setTopicName(TOPIC_3);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata createdMeta = service.addSubscription(ENV, sub).get();
        assertNotEquals(sub.getId(), createdMeta.getId());
        assertEquals(sub.getState(), createdMeta.getState());
        assertEquals(sub.getClientApplicationId(), createdMeta.getClientApplicationId());
        assertEquals(sub.getTopicName(), createdMeta.getTopicName());
        assertNull(createdMeta.getDescription()); // default null
    }

    @Test
    public void testUpdateSubscriptionState_positive() throws Exception {
        SubscriptionMetadata sub = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.PENDING);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV, sub.getId(), SubscriptionState.APPROVED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        SubscriptionMetadata savedSub = repository.getObject(sub.getId()).orElseThrow();
        assertEquals(SubscriptionState.APPROVED, savedSub.getState());
    }

    @Test
    public void testUpdateSubscriptionState_rejected_deletes() throws Exception {
        SubscriptionMetadata sub = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.PENDING);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV, sub.getId(), SubscriptionState.REJECTED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertFalse(repository.getObject(sub.getId()).isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_canceled_deletes() throws Exception {
        SubscriptionMetadata sub = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV, sub.getId(), SubscriptionState.CANCELED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertFalse(repository.getObject(sub.getId()).isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_rejected_mapsToCanceled() throws Exception {
        SubscriptionMetadata sub = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV, sub.getId(), SubscriptionState.REJECTED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata metadata = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.CANCELED, metadata.getState());
        assertFalse(repository.getObject(sub.getId()).isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_noop() throws Exception {
        SubscriptionMetadata sub = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV, sub.getId(), SubscriptionState.APPROVED).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertSame(sub, repository.getObject(sub.getId()).orElseThrow()); // object identity preserved
    }

    @Test
    public void testDeleteSubscription() throws Exception {
        SubscriptionMetadata sub = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.deleteSubscription(ENV, sub.getId()).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertFalse(repository.getObject(sub.getId()).isPresent());
    }

    @Test
    public void testGetSubscriptionsForTopic() throws Exception {
        SubscriptionMetadata sub1 = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);
        SubscriptionMetadata sub2 = createAndSaveSubscription(TOPIC_3, "app-3", SubscriptionState.PENDING);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic(ENV, TOPIC_3, false);
        assertEquals(1, result.size());
        assertEquals(sub1.getId(), result.get(0).getId());

        result = service.getSubscriptionsForTopic(ENV, TOPIC_3, true);
        assertEquals(2, result.size());
        assertTrue(result.stream().map(SubscriptionMetadata::getId).anyMatch(id -> id.equals(sub1.getId())));
        assertTrue(result.stream().map(SubscriptionMetadata::getId).anyMatch(id -> id.equals(sub2.getId())));
    }

    @Test
    public void testGetSubscriptionsOfApplication() throws Exception {
        SubscriptionMetadata sub1 = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);
        SubscriptionMetadata sub2 = createAndSaveSubscription(TOPIC_3, "app-3", SubscriptionState.PENDING);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication(ENV, APP_1, false);
        assertEquals(1, result.size());
        assertEquals(sub1.getId(), result.get(0).getId());

        result = service.getSubscriptionsOfApplication(ENV, "app-3", false);
        assertEquals(0, result.size());

        result = service.getSubscriptionsOfApplication(ENV, "app-3", true);
        assertEquals(1, result.size());
        assertEquals(sub2.getId(), result.get(0).getId());
    }

    // ====================== Additional test cases (improvements) ======================

    @Test
    public void testAddSubscription_fail_environmentNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription("non-existent", TOPIC_1, APP_1, null).get();
            fail("Expected NoSuchElementException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof java.util.NoSuchElementException);
            assertEquals("No environment with ID non-existent found.", e.getCause().getMessage());
        }
        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testAddSubscription_fail_topicNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription(ENV, "unknown-topic", APP_1, null).get();
            fail("Expected NoSuchElementException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof java.util.NoSuchElementException);
            assertEquals("No topic with name unknown-topic found on environment test.", e.getCause().getMessage());
        }
        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testAddSubscription_fail_applicationNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription(ENV, TOPIC_1, "unknown-app", null).get();
            fail("Expected NoSuchElementException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof java.util.NoSuchElementException);
            assertEquals("Application not registered on this environment. Please create authentication data first.",
                    e.getCause().getMessage());
        }
        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testAddSubscription_directMetadata_withDescription() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId(APP_1);
        sub.setTopicName(TOPIC_3);
        sub.setDescription("Custom desc");

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata createdMeta = service.addSubscription(ENV, sub).get();
        assertNotEquals(sub.getId(), createdMeta.getId());
        assertEquals(SubscriptionState.PENDING, createdMeta.getState());
        assertEquals("Custom desc", createdMeta.getDescription());
    }

    @Test
    public void testAddSubscription_directMetadata_ignoresApprovalRequirement() throws Exception {
        // Even though topic-1 does not require approval, we can force PENDING via direct metadata.
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId(APP_1);
        sub.setTopicName(TOPIC_1);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata createdMeta = service.addSubscription(ENV, sub).get();
        assertEquals(SubscriptionState.PENDING, createdMeta.getState());
        // Verify repository stores it as PENDING
        assertTrue(repository.getObject(createdMeta.getId()).isPresent());
        assertEquals(SubscriptionState.PENDING, repository.getObject(createdMeta.getId()).get().getState());
    }

    @Test
    public void testUpdateSubscriptionState_fail_subscriptionNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.updateSubscriptionState(ENV, "non-existent-id", SubscriptionState.APPROVED).get();
            fail("Expected NoSuchElementException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof java.util.NoSuchElementException);
        }
        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testDeleteSubscription_fail_subscriptionNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.deleteSubscription(ENV, "non-existent-id").get();
            fail("Expected NoSuchElementException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof java.util.NoSuchElementException);
        }
        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testGetSubscriptionsForTopic_empty() {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic(ENV, TOPIC_3, false);
        assertTrue(result.isEmpty());
        result = service.getSubscriptionsForTopic(ENV, TOPIC_3, true);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetSubscriptionsOfApplication_empty() {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication(ENV, APP_1, false);
        assertTrue(result.isEmpty());
        result = service.getSubscriptionsOfApplication(ENV, APP_1, true);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetSubscriptionsForTopic_returnsOnlyApprovedWhenFlagFalse() throws Exception {
        SubscriptionMetadata approved = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);
        SubscriptionMetadata pending = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.PENDING);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic(ENV, TOPIC_3, false);
        assertEquals(1, result.size());
        assertEquals(approved.getId(), result.get(0).getId());
    }

    @Test
    public void testGetSubscriptionsOfApplication_returnsOnlyApprovedWhenFlagFalse() throws Exception {
        SubscriptionMetadata approved = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.APPROVED);
        SubscriptionMetadata pending = createAndSaveSubscription(TOPIC_3, APP_1, SubscriptionState.PENDING);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication(ENV, APP_1, false);
        assertEquals(1, result.size());
        assertEquals(approved.getId(), result.get(0).getId());
    }

    // ====================== Helper methods ======================

    private SubscriptionMetadata createAndSaveSubscription(String topicName, String clientApp, SubscriptionState state)
            throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId(java.util.UUID.randomUUID().toString());
        sub.setTopicName(topicName);
        sub.setClientApplicationId(clientApp);
        sub.setState(state);
        repository.save(sub).get();
        return sub;
    }
}