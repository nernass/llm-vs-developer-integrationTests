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

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class claudeIT_1 {

    private KafkaClusters kafkaClusters;
    private KafkaCluster testCluster;
    private ApplicationsService applicationsService;
    private TopicService topicService;
    private GalapagosEventManagerMock eventManager;
    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;
    private SubscriptionServiceImpl service;

    @Before
    public void setupMocks() {
        kafkaClusters = mock(KafkaClusters.class);

        testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>();
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment("test")).thenReturn(Optional.of(testCluster));
        when(kafkaClusters.getEnvironment("nonexistent")).thenReturn(Optional.empty());

        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId("app-1");
        ApplicationMetadata app2 = new ApplicationMetadata();
        app2.setApplicationId("app-2");

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata("test", "app-1")).thenReturn(Optional.of(app1));
        when(applicationsService.getApplicationMetadata("test", "app-2")).thenReturn(Optional.of(app2));
        when(applicationsService.getApplicationMetadata("test", "unknown-app")).thenReturn(Optional.empty());

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

        TopicMetadata dataTopic = new TopicMetadata();
        dataTopic.setName("topic-data");
        dataTopic.setType(TopicType.DATA);
        dataTopic.setOwnerApplicationId("app-2");
        dataTopic.setSubscriptionApprovalRequired(false);

        TopicMetadata commandsTopic = new TopicMetadata();
        commandsTopic.setName("topic-commands");
        commandsTopic.setType(TopicType.COMMANDS);
        commandsTopic.setOwnerApplicationId("app-2");
        commandsTopic.setSubscriptionApprovalRequired(false);

        topicService = mock(TopicService.class);
        when(topicService.getTopic("test", "topic-1")).thenReturn(Optional.of(topic1));
        when(topicService.getTopic("test", "topic-2")).thenReturn(Optional.of(topic2));
        when(topicService.getTopic("test", "topic-3")).thenReturn(Optional.of(topic3));
        when(topicService.getTopic("test", "topic-data")).thenReturn(Optional.of(dataTopic));
        when(topicService.getTopic("test", "topic-commands")).thenReturn(Optional.of(commandsTopic));
        when(topicService.getTopic("test", "nonexistent-topic")).thenReturn(Optional.empty());

        eventManager = new GalapagosEventManagerMock();

        service = new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService, eventManager);
    }

    @Test
    public void shouldCreateApprovedSubscriptionWhenApprovalNotRequired() throws Exception {
        SubscriptionMetadata metadata = service.addSubscription("test", "topic-1", "app-1", "Some Desc").get();

        assertNotNull(metadata.getId());
        assertEquals("topic-1", metadata.getTopicName());
        assertEquals("app-1", metadata.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals("Some Desc", metadata.getDescription());
        assertNotNull(repository.getObject(metadata.getId()));
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldCreatePendingSubscriptionWhenApprovalRequired() throws Exception {
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
    public void shouldFailWithIllegalArgumentWhenSubscribingToInternalTopic() {
        try {
            service.addSubscription("test", "topic-2", "app-1", null).get();
            fail("Expected ExecutionException wrapping IllegalArgumentException");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().contains("internal"));
        }
        catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldAllowSubscriptionToDataTopicType() throws Exception {
        SubscriptionMetadata metadata = service.addSubscription("test", "topic-data", "app-1", "data sub").get();

        assertNotNull(metadata.getId());
        assertEquals("topic-data", metadata.getTopicName());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals(1, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldAllowSubscriptionToCommandsTopicType() throws Exception {
        SubscriptionMetadata metadata = service.addSubscription("test", "topic-commands", "app-1", "cmd sub").get();

        assertNotNull(metadata.getId());
        assertEquals("topic-commands", metadata.getTopicName());
        assertEquals(SubscriptionState.APPROVED, metadata.getState());
        assertEquals(1, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWhenEnvironmentDoesNotExist() {
        try {
            service.addSubscription("nonexistent", "topic-1", "app-1", null).get();
            fail("Expected ExecutionException wrapping NoSuchElementException");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("No environment with ID nonexistent found.", e.getCause().getMessage());
        }
        catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
    }

    @Test
    public void shouldFailWhenTopicDoesNotExist() {
        try {
            service.addSubscription("test", "nonexistent-topic", "app-1", null).get();
            fail("Expected ExecutionException wrapping NoSuchElementException");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("No topic with name nonexistent-topic found on environment test.", e.getCause().getMessage());
        }
        catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
    }

    @Test
    public void shouldFailWhenApplicationNotRegistered() {
        try {
            service.addSubscription("test", "topic-1", "unknown-app", null).get();
            fail("Expected ExecutionException wrapping NoSuchElementException");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("Application not registered on this environment. Please create authentication data first.",
                    e.getCause().getMessage());
        }
        catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
    }

    @Test
    public void shouldAssignNewIdWhenAddingDirectMetadata() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("original-id");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        SubscriptionMetadata createdMeta = service.addSubscription("test", sub).get();

        assertNotEquals("original-id", createdMeta.getId());
        assertNotNull(createdMeta.getId());
        assertEquals(SubscriptionState.APPROVED, createdMeta.getState());
        assertEquals("app-1", createdMeta.getClientApplicationId());
        assertEquals("topic-3", createdMeta.getTopicName());
    }

    @Test
    public void shouldPreserveDescriptionWhenAddingDirectMetadata() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("original-id");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-1");
        sub.setDescription("direct metadata description");

        SubscriptionMetadata createdMeta = service.addSubscription("test", sub).get();

        assertEquals("direct metadata description", createdMeta.getDescription());
    }

    @Test
    public void shouldPersistDirectMetadataToRepository() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("original-id");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-1");

        SubscriptionMetadata createdMeta = service.addSubscription("test", sub).get();

        assertTrue(repository.getObject(createdMeta.getId()).isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldFailDirectMetadataWhenApplicationNotRegistered() {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("123");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("unknown-app");
        sub.setTopicName("topic-1");

        try {
            service.addSubscription("test", sub).get();
            fail("Expected ExecutionException wrapping NoSuchElementException");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("Application not registered on this environment. Please create authentication data first.",
                    e.getCause().getMessage());
        }
        catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
    }

    @Test
    public void shouldUpdateStatePendingToApproved() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        SubscriptionMetadata savedSub = repository.getObject("sub-1").orElseThrow();
        assertEquals(SubscriptionState.APPROVED, savedSub.getState());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldDeleteSubscriptionWhenPendingIsRejected() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.REJECTED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata eventMetadata = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.REJECTED, eventMetadata.getState());
    }

    @Test
    public void shouldDeleteSubscriptionWhenApprovedIsCanceled() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.CANCELED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata eventMetadata = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.CANCELED, eventMetadata.getState());
    }

    @Test
    public void shouldMapRejectedToCanceledWhenSubscriptionIsApproved() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.REJECTED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata eventMetadata = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.CANCELED, eventMetadata.getState());
    }

    @Test
    public void shouldNoopWhenUpdatingToSameState() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertSame(sub, repository.getObject("sub-1").orElseThrow());
    }

    @Test
    public void shouldFailUpdateWhenEnvironmentDoesNotExist() {
        try {
            service.updateSubscriptionState("nonexistent", "sub-1", SubscriptionState.APPROVED).get();
            fail("Expected ExecutionException wrapping NoSuchElementException");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
            assertEquals("No environment with ID nonexistent found.", e.getCause().getMessage());
        }
        catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
    }

    @Test
    public void shouldDeleteExistingSubscriptionAndEmitEvent() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.deleteSubscription("test", "sub-1").get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldDeletePendingSubscription() throws Exception {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("sub-1");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        repository.save(sub).get();

        service.deleteSubscription("test", "sub-1").get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldReturnOnlyApprovedSubscriptionsForTopic() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("sub-1");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("sub-2");
        sub2.setState(SubscriptionState.PENDING);
        sub2.setClientApplicationId("app-2");
        sub2.setTopicName("topic-3");

        repository.save(sub1).get();
        repository.save(sub2).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnAllSubscriptionsForTopicWhenIncludingPending() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("sub-1");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("sub-2");
        sub2.setState(SubscriptionState.PENDING);
        sub2.setClientApplicationId("app-2");
        sub2.setTopicName("topic-3");
        SubscriptionMetadata sub3 = new SubscriptionMetadata();
        sub3.setId("sub-3");
        sub3.setState(SubscriptionState.APPROVED);
        sub3.setClientApplicationId("app-1");
        sub3.setTopicName("topic-1");

        repository.save(sub1).get();
        repository.save(sub2).get();
        repository.save(sub3).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", true);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "sub-1".equals(s.getId())));
        assertTrue(result.stream().anyMatch(s -> "sub-2".equals(s.getId())));
        assertFalse(result.stream().anyMatch(s -> "sub-3".equals(s.getId())));
    }

    @Test
    public void shouldReturnEmptyListForTopicWithNoSubscriptions() {
        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-1", false);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnEmptyListForNonexistentEnvironment() {
        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("nonexistent", "topic-1", false);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldNotReturnSubscriptionsOfOtherTopics() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("sub-1");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-1");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("sub-2");
        sub2.setState(SubscriptionState.APPROVED);
        sub2.setClientApplicationId("app-1");
        sub2.setTopicName("topic-3");

        repository.save(sub1).get();
        repository.save(sub2).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-1", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnOnlyApprovedSubscriptionsOfApplication() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("sub-1");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("sub-2");
        sub2.setState(SubscriptionState.PENDING);
        sub2.setClientApplicationId("app-1");
        sub2.setTopicName("topic-1");

        repository.save(sub1).get();
        repository.save(sub2).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnAllSubscriptionsOfApplicationWhenIncludingPending() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("sub-1");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("sub-2");
        sub2.setState(SubscriptionState.PENDING);
        sub2.setClientApplicationId("app-1");
        sub2.setTopicName("topic-1");
        SubscriptionMetadata sub3 = new SubscriptionMetadata();
        sub3.setId("sub-3");
        sub3.setState(SubscriptionState.PENDING);
        sub3.setClientApplicationId("app-2");
        sub3.setTopicName("topic-3");

        repository.save(sub1).get();
        repository.save(sub2).get();
        repository.save(sub3).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", true);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "sub-1".equals(s.getId())));
        assertTrue(result.stream().anyMatch(s -> "sub-2".equals(s.getId())));
        assertFalse(result.stream().anyMatch(s -> "sub-3".equals(s.getId())));
    }

    @Test
    public void shouldReturnEmptyListForApplicationWithNoSubscriptions() {
        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-2", false);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldNotReturnSubscriptionsOfOtherApplications() throws Exception {
        SubscriptionMetadata sub1 = new SubscriptionMetadata();
        sub1.setId("sub-1");
        sub1.setState(SubscriptionState.APPROVED);
        sub1.setClientApplicationId("app-1");
        sub1.setTopicName("topic-3");
        SubscriptionMetadata sub2 = new SubscriptionMetadata();
        sub2.setId("sub-2");
        sub2.setState(SubscriptionState.APPROVED);
        sub2.setClientApplicationId("app-2");
        sub2.setTopicName("topic-3");

        repository.save(sub1).get();
        repository.save(sub2).get();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldSupportFullSubscriptionLifecyclePendingToApprovedToDeleted() throws Exception {
        SubscriptionMetadata created = service.addSubscription("test", "topic-3", "app-1", "lifecycle test").get();
        assertEquals(SubscriptionState.PENDING, created.getState());
        assertTrue(repository.getObject(created.getId()).isPresent());

        service.updateSubscriptionState("test", created.getId(), SubscriptionState.APPROVED).get();
        SubscriptionMetadata approved = repository.getObject(created.getId()).orElseThrow();
        assertEquals(SubscriptionState.APPROVED, approved.getState());

        service.deleteSubscription("test", created.getId()).get();
        assertFalse(repository.getObject(created.getId()).isPresent());

        assertEquals(3, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(1).getMethod().getName());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(2).getMethod().getName());
    }

    @Test
    public void shouldSupportLifecyclePendingToRejectedCausingDeletion() throws Exception {
        SubscriptionMetadata created = service.addSubscription("test", "topic-3", "app-1", "will be rejected").get();
        assertEquals(SubscriptionState.PENDING, created.getState());

        service.updateSubscriptionState("test", created.getId(), SubscriptionState.REJECTED).get();
        assertFalse(repository.getObject(created.getId()).isPresent());

        assertEquals(2, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(1).getMethod().getName());
    }

    @Test
    public void shouldAllowResubscriptionAfterRejection() throws Exception {
        SubscriptionMetadata first = service.addSubscription("test", "topic-3", "app-1", "first attempt").get();
        service.updateSubscriptionState("test", first.getId(), SubscriptionState.REJECTED).get();
        assertFalse(repository.getObject(first.getId()).isPresent());

        SubscriptionMetadata second = service.addSubscription("test", "topic-3", "app-1", "second attempt").get();
        assertNotNull(second.getId());
        assertNotEquals(first.getId(), second.getId());
        assertTrue(repository.getObject(second.getId()).isPresent());
    }

    @Test
    public void shouldAllowResubscriptionAfterCancellation() throws Exception {
        SubscriptionMetadata first = service.addSubscription("test", "topic-1", "app-1", "first").get();
        assertEquals(SubscriptionState.APPROVED, first.getState());

        service.updateSubscriptionState("test", first.getId(), SubscriptionState.CANCELED).get();
        assertFalse(repository.getObject(first.getId()).isPresent());

        SubscriptionMetadata second = service.addSubscription("test", "topic-1", "app-1", "second").get();
        assertNotNull(second.getId());
        assertTrue(repository.getObject(second.getId()).isPresent());
    }

    @Test
    public void shouldHandleMultipleSubscriptionsForDifferentTopicsIndependently() throws Exception {
        SubscriptionMetadata sub1 = service.addSubscription("test", "topic-1", "app-1", "sub events").get();
        SubscriptionMetadata sub2 = service.addSubscription("test", "topic-data", "app-1", "sub data").get();

        assertNotEquals(sub1.getId(), sub2.getId());
        assertTrue(repository.getObject(sub1.getId()).isPresent());
        assertTrue(repository.getObject(sub2.getId()).isPresent());

        service.deleteSubscription("test", sub1.getId()).get();
        assertFalse(repository.getObject(sub1.getId()).isPresent());
        assertTrue(repository.getObject(sub2.getId()).isPresent());
    }
}