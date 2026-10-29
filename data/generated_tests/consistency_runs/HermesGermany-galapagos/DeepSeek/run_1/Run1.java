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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SubscriptionServiceImplIntegrationTest {

    private KafkaClusters kafkaClusters;
    private ApplicationsService applicationsService;
    private TopicService topicService;
    private GalapagosEventManagerMock eventManager;
    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;

    private static final String ENV_ID = "test";
    private static final String APP_1 = "app-1";
    private static final String APP_2 = "app-2";
    private static final String APP_3 = "app-3";
    private static final String TOPIC_EVENTS = "topic-1";
    private static final String TOPIC_INTERNAL = "topic-2";
    private static final String TOPIC_APPROVAL = "topic-3";
    private static final String SUB_ID = "123";

    @Before
    public void setupMocks() {
        kafkaClusters = mock(KafkaClusters.class);
        KafkaCluster testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>("subscriptions", SubscriptionMetadata.class);
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment(ENV_ID)).thenReturn(Optional.of(testCluster));
        // non-existing environment
        when(kafkaClusters.getEnvironment("unknown")).thenReturn(Optional.empty());

        // Application metadata
        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId(APP_1);
        ApplicationMetadata app2 = new ApplicationMetadata();
        app2.setApplicationId(APP_2);
        ApplicationMetadata app3 = new ApplicationMetadata();
        app3.setApplicationId(APP_3);

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata(ENV_ID, APP_1)).thenReturn(Optional.of(app1));
        when(applicationsService.getApplicationMetadata(ENV_ID, APP_2)).thenReturn(Optional.of(app2));
        when(applicationsService.getApplicationMetadata(ENV_ID, APP_3)).thenReturn(Optional.of(app3));
        when(applicationsService.getApplicationMetadata(ENV_ID, "unknown-app")).thenReturn(Optional.empty());

        // Topic metadata
        TopicMetadata topic1 = new TopicMetadata();
        topic1.setName(TOPIC_EVENTS);
        topic1.setType(TopicType.EVENTS);
        topic1.setOwnerApplicationId(APP_2);
        topic1.setSubscriptionApprovalRequired(false);

        TopicMetadata topic2 = new TopicMetadata();
        topic2.setName(TOPIC_INTERNAL);
        topic2.setType(TopicType.INTERNAL);
        topic2.setOwnerApplicationId(APP_2);

        TopicMetadata topic3 = new TopicMetadata();
        topic3.setName(TOPIC_APPROVAL);
        topic3.setType(TopicType.EVENTS);
        topic3.setOwnerApplicationId(APP_2);
        topic3.setSubscriptionApprovalRequired(true);

        topicService = mock(TopicService.class);
        when(topicService.getTopic(ENV_ID, TOPIC_EVENTS)).thenReturn(Optional.of(topic1));
        when(topicService.getTopic(ENV_ID, TOPIC_INTERNAL)).thenReturn(Optional.of(topic2));
        when(topicService.getTopic(ENV_ID, TOPIC_APPROVAL)).thenReturn(Optional.of(topic3));
        when(topicService.getTopic(ENV_ID, "unknown-topic")).thenReturn(Optional.empty());

        eventManager = new GalapagosEventManagerMock();
    }

    // ---------- ADD SUBSCRIPTION ----------

    @Test
    public void testAddSubscription_positive() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata metadata = service.addSubscription(ENV_ID, TOPIC_EVENTS, APP_1, "Some Desc").get();
        assertNotNull(metadata.getId());
        assertEquals(TOPIC_EVENTS, metadata.getTopicName());
        assertEquals(APP_1, metadata.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals("Some Desc", metadata.getDescription());

        SubscriptionMetadata saved = repository.getObject(metadata.getId()).orElseThrow();
        assertEquals(metadata.getId(), saved.getId());

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void testAddSubscription_pending() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata metadata = service.addSubscription(ENV_ID, TOPIC_APPROVAL, APP_1, null).get();
        assertNotNull(metadata.getId());
        assertEquals(TOPIC_APPROVAL, metadata.getTopicName());
        assertEquals(APP_1, metadata.getClientApplicationId());
        assertEquals(SubscriptionState.PENDING, metadata.getState());
        assertNull(metadata.getDescription());

        assertNotNull(repository.getObject(metadata.getId()));

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test(expected = ExecutionException.class)
    public void testAddSubscription_fail_internalTopic() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.addSubscription(ENV_ID, TOPIC_INTERNAL, APP_1, null).get();
    }

    @Test(expected = ExecutionException.class)
    public void testAddSubscription_fail_unknownTopic() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.addSubscription(ENV_ID, "unknown-topic", APP_1, null).get();
    }

    @Test(expected = ExecutionException.class)
    public void testAddSubscription_fail_unknownApplication() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.addSubscription(ENV_ID, TOPIC_EVENTS, "unknown-app", null).get();
    }

    @Test(expected = ExecutionException.class)
    public void testAddSubscription_fail_unknownEnvironment() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.addSubscription("unknown", TOPIC_EVENTS, APP_1, null).get();
    }

    @Test
    public void testAddSubscription_directMetadata() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId(APP_1);
        sub.setTopicName(TOPIC_APPROVAL);

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata createdMeta = service.addSubscription(ENV_ID, sub).get();
        assertNotEquals(sub.getId(), createdMeta.getId());
        assertEquals(sub.getState(), createdMeta.getState());
        assertEquals(sub.getClientApplicationId(), createdMeta.getClientApplicationId());
        assertEquals(sub.getTopicName(), createdMeta.getTopicName());

        // Event should be fired
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    // ---------- UPDATE STATE ----------

    @Test
    public void testUpdateSubscriptionState_positive() throws Exception {
        // Insert pending subscription
        SubscriptionMetadata sub = createSubscription(SUB_ID, SubscriptionState.PENDING, APP_1, TOPIC_APPROVAL);
        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV_ID, SUB_ID, SubscriptionState.APPROVED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        SubscriptionMetadata saved = repository.getObject(SUB_ID).orElseThrow();
        assertEquals(SubscriptionState.APPROVED, saved.getState());
    }

    @Test
    public void testUpdateSubscriptionState_rejected_deletes() throws Exception {
        SubscriptionMetadata sub = createSubscription(SUB_ID, SubscriptionState.PENDING, APP_1, TOPIC_APPROVAL);
        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV_ID, SUB_ID, SubscriptionState.REJECTED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        assertFalse(repository.getObject(SUB_ID).isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_canceled_deletes() throws Exception {
        SubscriptionMetadata sub = createSubscription(SUB_ID, SubscriptionState.APPROVED, APP_1, TOPIC_APPROVAL);
        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV_ID, SUB_ID, SubscriptionState.CANCELED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        assertFalse(repository.getObject(SUB_ID).isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_rejected_mapsToCanceled() throws Exception {
        SubscriptionMetadata sub = createSubscription(SUB_ID, SubscriptionState.APPROVED, APP_1, TOPIC_APPROVAL);
        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV_ID, SUB_ID, SubscriptionState.REJECTED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata eventSub = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.CANCELED, eventSub.getState());

        assertFalse(repository.getObject(SUB_ID).isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_noop() throws Exception {
        SubscriptionMetadata sub = createSubscription(SUB_ID, SubscriptionState.APPROVED, APP_1, TOPIC_APPROVAL);
        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState(ENV_ID, SUB_ID, SubscriptionState.APPROVED).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        // object identity preserved
        assertSame(sub, repository.getObject(SUB_ID).orElseThrow());
    }

    @Test(expected = ExecutionException.class)
    public void testUpdateSubscriptionState_subscriptionNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.updateSubscriptionState(ENV_ID, "non-existent", SubscriptionState.APPROVED).get();
    }

    @Test(expected = ExecutionException.class)
    public void testUpdateSubscriptionState_environmentNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.updateSubscriptionState("unknown", SUB_ID, SubscriptionState.APPROVED).get();
    }

    // ---------- DELETE SUBSCRIPTION ----------

    @Test
    public void testDeleteSubscription() throws Exception {
        SubscriptionMetadata sub = createSubscription(SUB_ID, SubscriptionState.APPROVED, APP_1, TOPIC_APPROVAL);
        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.deleteSubscription(ENV_ID, SUB_ID).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertFalse(repository.getObject(SUB_ID).isPresent());
    }

    @Test(expected = ExecutionException.class)
    public void testDeleteSubscription_notFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);
        service.deleteSubscription(ENV_ID, "non-existent").get();
    }

    // ---------- QUERIES ----------

    @Test
    public void testGetSubscriptionsForTopic() throws Exception {
        SubscriptionMetadata sub1 = createSubscription("123", SubscriptionState.APPROVED, APP_1, TOPIC_APPROVAL);
        SubscriptionMetadata sub2 = createSubscription("124", SubscriptionState.PENDING, APP_3, TOPIC_APPROVAL);
        // Another topic
        SubscriptionMetadata sub3 = createSubscription("125", SubscriptionState.APPROVED, APP_1, TOPIC_EVENTS);
        repository.save(sub1).get();
        repository.save(sub2).get();
        repository.save(sub3).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        // Without pending
        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic(ENV_ID, TOPIC_APPROVAL, false);
        assertEquals(1, result.size());
        assertEquals("123", result.get(0).getId());

        // With pending
        result = service.getSubscriptionsForTopic(ENV_ID, TOPIC_APPROVAL, true);
        assertEquals(2, result.size());
        // order not guaranteed, but both present
        assertTrue(result.stream().anyMatch(s -> "123".equals(s.getId())));
        assertTrue(result.stream().anyMatch(s -> "124".equals(s.getId())));

        // Non-existing topic -> empty
        result = service.getSubscriptionsForTopic(ENV_ID, "unknown", false);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetSubscriptionsOfApplication() throws Exception {
        SubscriptionMetadata sub1 = createSubscription("123", SubscriptionState.APPROVED, APP_1, TOPIC_APPROVAL);
        SubscriptionMetadata sub2 = createSubscription("124", SubscriptionState.PENDING, APP_3, TOPIC_APPROVAL);
        SubscriptionMetadata sub3 = createSubscription("125", SubscriptionState.APPROVED, APP_1, TOPIC_EVENTS);
        repository.save(sub1).get();
        repository.save(sub2).get();
        repository.save(sub3).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        // APP-1: two approved, one pending (APP-3) excluded by default
        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication(ENV_ID, APP_1, false);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(s -> s.getState() == SubscriptionState.APPROVED));

        // APP-3: pending only, excluded
        result = service.getSubscriptionsOfApplication(ENV_ID, APP_3, false);
        assertTrue(result.isEmpty());

        // APP-3 with pending included
        result = service.getSubscriptionsOfApplication(ENV_ID, APP_3, true);
        assertEquals(1, result.size());
        assertEquals("124", result.get(0).getId());

        // Unknown app
        result = service.getSubscriptionsOfApplication(ENV_ID, "unknown", false);
        assertTrue(result.isEmpty());
    }

    // ---------- HELPER ----------
    private SubscriptionMetadata createSubscription(String id, SubscriptionState state, String appId, String topic) {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId(id);
        sub.setState(state);
        sub.setClientApplicationId(appId);
        sub.setTopicName(topic);
        return sub;
    }
}