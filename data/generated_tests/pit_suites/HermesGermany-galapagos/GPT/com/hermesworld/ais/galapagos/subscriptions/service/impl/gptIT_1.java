package com.hermesworld.ais.galapagos.subscriptions.service.impl;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class gptIT_1 {

    private KafkaClusters kafkaClusters;
    private ApplicationsService applicationsService;
    private TopicService topicService;
    private GalapagosEventManagerMock eventManager;
    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;
    private SubscriptionServiceImpl service;

    @Before
    public void setup() {
        kafkaClusters = mock(KafkaClusters.class);

        KafkaCluster testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>();
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment("test")).thenReturn(Optional.of(testCluster));
        when(kafkaClusters.getEnvironment("missing-env")).thenReturn(Optional.empty());

        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId("app-1");
        ApplicationMetadata app2 = new ApplicationMetadata();
        app2.setApplicationId("app-2");

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata("test", "app-1")).thenReturn(Optional.of(app1));
        when(applicationsService.getApplicationMetadata("test", "app-2")).thenReturn(Optional.of(app2));
        when(applicationsService.getApplicationMetadata("test", "missing-app")).thenReturn(Optional.empty());

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
        when(topicService.getTopic("test", "missing-topic")).thenReturn(Optional.empty());

        eventManager = new GalapagosEventManagerMock();
        service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService, eventManager);
    }

    private SubscriptionMetadata subscription(String id, SubscriptionState state, String appId, String topicName) {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId(id);
        sub.setState(state);
        sub.setClientApplicationId(appId);
        sub.setTopicName(topicName);
        return sub;
    }

    private void assertSingleEvent(String methodName) {
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals(methodName, eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    private SubscriptionMetadata emittedSubscription() {
        return (SubscriptionMetadata) eventManager.getSinkInvocations().get(0).getArgument(0);
    }

    private void assertContainsIds(List<SubscriptionMetadata> result, String... ids) {
        assertEquals(ids.length, result.size());
        for (String id : ids) {
            assertTrue(result.stream().anyMatch(sub -> id.equals(sub.getId())));
        }
    }

    @Test
    public void shouldCreateApprovedSubscriptionForTopicWithoutApprovalRequirement() throws Exception {
        SubscriptionMetadata metadata = service.addSubscription("test", "topic-1", "app-1", "Some Desc").get();

        assertNotNull(metadata.getId());
        assertEquals("topic-1", metadata.getTopicName());
        assertEquals("app-1", metadata.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals("Some Desc", metadata.getDescription());
        assertTrue(repository.getObject(metadata.getId()).isPresent());
        assertSingleEvent("handleSubscriptionCreated");
    }

    @Test
    public void shouldCreatePendingSubscriptionForApprovalRequiredTopic() throws Exception {
        SubscriptionMetadata metadata = service.addSubscription("test", "topic-3", "app-1", null).get();

        assertNotNull(metadata.getId());
        assertEquals("topic-3", metadata.getTopicName());
        assertEquals("app-1", metadata.getClientApplicationId());
        assertEquals(SubscriptionState.PENDING, metadata.getState());
        assertNull(metadata.getDescription());
        assertTrue(repository.getObject(metadata.getId()).isPresent());
        assertSingleEvent("handleSubscriptionCreated");
    }

    @Test
    public void shouldFailWhenSubscribingToInternalTopic() throws Exception {
        try {
            service.addSubscription("test", "topic-2", "app-1", null).get();
            fail("Expected exception when subscribing to internal topic");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().contains("internal"));
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWhenEnvironmentDoesNotExist() throws Exception {
        try {
            service.addSubscription("missing-env", "topic-1", "app-1", null).get();
            fail("Expected missing environment exception");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("No environment with ID missing-env found.", e.getCause().getMessage());
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWhenTopicDoesNotExist() throws Exception {
        try {
            service.addSubscription("test", "missing-topic", "app-1", null).get();
            fail("Expected missing topic exception");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("No topic with name missing-topic found on environment test.", e.getCause().getMessage());
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWhenApplicationDoesNotExist() throws Exception {
        try {
            service.addSubscription("test", "topic-1", "missing-app", null).get();
            fail("Expected missing application exception");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("Application not registered on this environment. Please create authentication data first.",
                    e.getCause().getMessage());
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldGenerateNewIdWhenAddingDirectMetadata() throws Exception {
        SubscriptionMetadata sub = subscription("123", SubscriptionState.APPROVED, "app-1", "topic-3");
        sub.setDescription("desc");

        SubscriptionMetadata createdMeta = service.addSubscription("test", sub).get();

        assertNotEquals("123", createdMeta.getId());
        assertEquals(SubscriptionState.APPROVED, createdMeta.getState());
        assertEquals("app-1", createdMeta.getClientApplicationId());
        assertEquals("topic-3", createdMeta.getTopicName());
        assertEquals("desc", createdMeta.getDescription());
        assertTrue(repository.getObject(createdMeta.getId()).isPresent());
        assertSingleEvent("handleSubscriptionCreated");
    }

    @Test
    public void shouldUpdatePendingSubscriptionToApproved() throws Exception {
        SubscriptionMetadata sub = subscription("sub-1", SubscriptionState.PENDING, "app-1", "topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        assertEquals(SubscriptionState.APPROVED, repository.getObject("sub-1").orElseThrow().getState());
        assertSingleEvent("handleSubscriptionUpdated");
        assertEquals(SubscriptionState.APPROVED, emittedSubscription().getState());
    }

    @Test
    public void shouldDeleteSubscriptionWhenPendingIsRejected() throws Exception {
        SubscriptionMetadata sub = subscription("sub-1", SubscriptionState.PENDING, "app-1", "topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.REJECTED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertSingleEvent("handleSubscriptionUpdated");
        assertEquals(SubscriptionState.REJECTED, emittedSubscription().getState());
    }

    @Test
    public void shouldMapRejectedApprovedSubscriptionToCanceled() throws Exception {
        SubscriptionMetadata sub = subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.REJECTED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertSingleEvent("handleSubscriptionUpdated");
        assertEquals(SubscriptionState.CANCELED, emittedSubscription().getState());
    }

    @Test
    public void shouldNotEmitEventWhenUpdatingToSameState() throws Exception {
        SubscriptionMetadata sub = subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertSame(sub, repository.getObject("sub-1").orElseThrow());
    }

    @Test
    public void shouldDeleteSubscriptionAndEmitDeleteEvent() throws Exception {
        SubscriptionMetadata sub = subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-3");
        repository.save(sub).get();

        service.deleteSubscription("test", "sub-1").get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertSingleEvent("handleSubscriptionDeleted");
    }

    @Test
    public void shouldReturnOnlyApprovedSubscriptionsForTopicByDefault() throws Exception {
        repository.save(subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-3")).get();
        repository.save(subscription("sub-2", SubscriptionState.PENDING, "app-2", "topic-3")).get();
        repository.save(subscription("sub-3", SubscriptionState.APPROVED, "app-1", "topic-1")).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", false);

        assertContainsIds(result, "sub-1");
        assertEquals("topic-3", result.get(0).getTopicName());
        assertFalse(result.stream().anyMatch(sub -> "sub-3".equals(sub.getId())));
    }

    @Test
    public void shouldReturnPendingSubscriptionsForTopicWhenRequested() throws Exception {
        repository.save(subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-3")).get();
        repository.save(subscription("sub-2", SubscriptionState.PENDING, "app-2", "topic-3")).get();
        repository.save(subscription("sub-3", SubscriptionState.APPROVED, "app-1", "topic-1")).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", true);

        assertContainsIds(result, "sub-1", "sub-2");
        assertFalse(result.stream().anyMatch(sub -> "sub-3".equals(sub.getId())));
    }

    @Test
    public void shouldReturnOnlyApprovedSubscriptionsForApplicationByDefault() throws Exception {
        repository.save(subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-1")).get();
        repository.save(subscription("sub-2", SubscriptionState.PENDING, "app-1", "topic-3")).get();
        repository.save(subscription("sub-3", SubscriptionState.APPROVED, "app-2", "topic-1")).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);

        assertContainsIds(result, "sub-1");
        assertEquals("app-1", result.get(0).getClientApplicationId());
        assertFalse(result.stream().anyMatch(sub -> "sub-3".equals(sub.getId())));
    }

    @Test
    public void shouldReturnPendingSubscriptionsForApplicationWhenRequested() throws Exception {
        repository.save(subscription("sub-1", SubscriptionState.APPROVED, "app-1", "topic-1")).get();
        repository.save(subscription("sub-2", SubscriptionState.PENDING, "app-1", "topic-3")).get();
        repository.save(subscription("sub-3", SubscriptionState.PENDING, "app-2", "topic-3")).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", true);

        assertContainsIds(result, "sub-1", "sub-2");
        assertFalse(result.stream().anyMatch(sub -> "sub-3".equals(sub.getId())));
    }
}