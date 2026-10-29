package com.hermesworld.ais.galapagos.subscriptions.service.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

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

public class SubscriptionServiceImplIntegrationTest {

    private KafkaClusters kafkaClusters;

    private ApplicationsService applicationsService;

    private TopicService topicService;

    private GalapagosEventManagerMock eventManager;

    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;

    @Before
    public void setupMocks() {
        kafkaClusters = mock(KafkaClusters.class);

        // Test environment "test" with a repository
        KafkaCluster testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>("subscriptions", SubscriptionMetadata.class);
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment("test")).thenReturn(Optional.of(testCluster));
        when(kafkaClusters.getEnvironment("unknown-env")).thenReturn(Optional.empty());

        // Application metadata
        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId("app-1");
        ApplicationMetadata app2 = new ApplicationMetadata();
        app2.setApplicationId("app-2");

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata("test", "app-1")).thenReturn(Optional.of(app1));
        when(applicationsService.getApplicationMetadata("test", "app-2")).thenReturn(Optional.of(app2));
        when(applicationsService.getApplicationMetadata("test", "unknown-app")).thenReturn(Optional.empty());

        // Topic metadata
        TopicMetadata topic1 = new TopicMetadata();
        topic1.setName("topic-1");
        topic1.setType(TopicType.EVENTS);
        topic1.setOwnerApplicationId("app-2");
        topic1.setSubscriptionApprovalRequired(false);

        TopicMetadata topic2 = new TopicMetadata();
        topic2.setName("topic-2");
        topic2.setType(TopicType.INTERNAL);
        topic2.setOwnerApplicationId("app-2");

        TopicMetadata topic3 = new TopicMetadata();
        topic3.setName("topic-3");
        topic3.setType(TopicType.EVENTS);
        topic3.setOwnerApplicationId("app-2");
        topic3.setSubscriptionApprovalRequired(true);

        topicService = mock(TopicService.class);
        when(topicService.getTopic("test", "topic-1")).thenReturn(Optional.of(topic1));
        when(topicService.getTopic("test", "topic-2")).thenReturn(Optional.of(topic2));
        when(topicService.getTopic("test", "topic-3")).thenReturn(Optional.of(topic3));
        when(topicService.getTopic("test", "unknown-topic")).thenReturn(Optional.empty());

        eventManager = new GalapagosEventManagerMock();
    }

    // --- Existing positive and negative tests from reference ---

    @Test
    public void testAddSubscription_positive() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata metadata = service.addSubscription("test", "topic-1", "app-1", "Some Desc").get();
        assertNotNull(metadata.getId());
        assertEquals("topic-1", metadata.getTopicName());
        assertEquals("app-1", metadata.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals("Some Desc", metadata.getDescription());

        assertNotNull(repository.getObject(metadata.getId()));

        assertEquals(1, eventManager.getSinkInvocations().size());
        InvocationOnMock inv = eventManager.getSinkInvocations().get(0);
        assertEquals("handleSubscriptionCreated", inv.getMethod().getName());
        SubscriptionMetadata eventArg = inv.getArgument(0);
        assertEquals(metadata.getId(), eventArg.getId());
    }

    @Test
    public void testAddSubscription_pending() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata metadata = service.addSubscription("test", "topic-3", "app-1", null).get();
        assertNotNull(metadata.getId());
        assertEquals("topic-3", metadata.getTopicName());
        assertEquals("app-1", metadata.getClientApplicationId());
        assertEquals(SubscriptionState.PENDING, metadata.getState());
        assertNull(metadata.getDescription());

        assertNotNull(repository.getObject(metadata.getId()));

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void testAddSubscription_fail_internalTopic() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription("test", "topic-2", "app-1", null).get();
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
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        SubscriptionMetadata createdMeta = service.addSubscription("test", sub).get();
        assertNotEquals(sub.getId(), createdMeta.getId());
        assertEquals(sub.getState(), createdMeta.getState());
        assertEquals(sub.getClientApplicationId(), createdMeta.getClientApplicationId());
        assertEquals(sub.getTopicName(), createdMeta.getTopicName());
    }

    @Test
    public void testUpdateSubscriptionState_positive() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState("test", "123", SubscriptionState.APPROVED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        SubscriptionMetadata savedSub = repository.getObject("123").orElseThrow();
        assertEquals(SubscriptionState.APPROVED, savedSub.getState());
    }

    @Test
    public void testUpdateSubscriptionState_rejected_deletes() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState("test", "123", SubscriptionState.REJECTED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        assertFalse(repository.getObject("123").isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_canceled_deletes() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState("test", "123", SubscriptionState.CANCELED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());

        assertFalse(repository.getObject("123").isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_rejected_mapsToCanceled() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState("test", "123", SubscriptionState.REJECTED).get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata metadata = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.CANCELED, metadata.getState());

        assertFalse(repository.getObject("123").isPresent());
    }

    @Test
    public void testUpdateSubscriptionState_noop() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.updateSubscriptionState("test", "123", SubscriptionState.APPROVED).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertSame(repository.getObject("123").orElseThrow(), sub);
    }

    @Test
    public void testDeleteSubscription() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        repository.save(sub).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        service.deleteSubscription("test", "123").get();

        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertFalse(repository.getObject("123").isPresent());
    }

    @Test
    public void testGetSubscriptionsForTopic() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("123");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("124");
        sub2.setState(SubscriptionState.PENDING);
        sub2.setClientApplicationId("app-3");
        sub2.setTopicName("topic-3");

        repository.save(sub1).get();
        repository.save(sub2).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", false);
        assertEquals(1, result.size());
        assertEquals("123", result.get(0).getId());

        result = service.getSubscriptionsForTopic("test", "topic-3", true);
        assertEquals(2, result.size());
        // Order is not guaranteed, but we can check ids
        assertTrue(result.stream().anyMatch(s -> "123".equals(s.getId())));
        assertTrue(result.stream().anyMatch(s -> "124".equals(s.getId())));
    }

    @Test
    public void testGetSubscriptionsOfApplication() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("123");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("124");
        sub2.setState(SubscriptionState.PENDING);
        sub2.setClientApplicationId("app-3");
        sub2.setTopicName("topic-3");

        repository.save(sub1).get();
        repository.save(sub2).get();

        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);
        assertEquals(1, result.size());
        assertEquals("123", result.get(0).getId());

        result = service.getSubscriptionsOfApplication("test", "app-3", false);
        assertEquals(0, result.size());

        result = service.getSubscriptionsOfApplication("test", "app-3", true);
        assertEquals(1, result.size());
        assertEquals("124", result.get(0).getId());
    }

    // --- Additional negative tests ---

    @Test
    public void testAddSubscription_fail_environmentNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription("unknown-env", "topic-1", "app-1", "desc").get();
            fail("Expected NoSuchElementException for unknown environment");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertTrue(e.getCause().getMessage().contains("unknown-env"));
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testAddSubscription_fail_topicNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription("test", "unknown-topic", "app-1", "desc").get();
            fail("Expected NoSuchElementException for unknown topic");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertTrue(e.getCause().getMessage().contains("unknown-topic"));
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testAddSubscription_fail_applicationNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.addSubscription("test", "topic-1", "unknown-app", "desc").get();
            fail("Expected NoSuchElementException for unknown application");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertTrue(e.getCause().getMessage().contains("authentication data"));
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void testUpdateSubscriptionState_fail_environmentNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.updateSubscriptionState("unknown-env", "123", SubscriptionState.APPROVED).get();
            fail("Expected NoSuchElementException for unknown environment");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertTrue(e.getCause().getMessage().contains("unknown-env"));
        }
    }

    @Test
    public void testUpdateSubscriptionState_fail_subscriptionNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.updateSubscriptionState("test", "non-existent", SubscriptionState.APPROVED).get();
            fail("Expected NoSuchElementException for non-existent subscription");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }
    }

    @Test
    public void testDeleteSubscription_fail_subscriptionNotFound() throws Exception {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        try {
            service.deleteSubscription("test", "non-existent").get();
            fail("Expected NoSuchElementException for non-existent subscription");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }
    }

    @Test
    public void testGetSubscriptionsForTopic_environmentNotFound() {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("unknown-env", "topic-1", false);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetSubscriptionsOfApplication_environmentNotFound() {
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService,
                eventManager);

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("unknown-env", "app-1", false);
        assertTrue(result.isEmpty());
    }
}