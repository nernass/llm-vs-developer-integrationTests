

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
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SubscriptionServiceImplIntegrationTest {

    private KafkaClusters kafkaClusters;
    private ApplicationsService applicationsService;
    private TopicService topicService;
    private GalapagosEventManagerMock eventManager;
    private TopicBasedRepositoryMock<SubscriptionMetadata> repository;

    @Before
    public void setupMocks() {
        kafkaClusters = mock(KafkaClusters.class);

        KafkaCluster testCluster = mock(KafkaCluster.class);
        repository = new TopicBasedRepositoryMock<>();
        when(testCluster.getRepository("subscriptions", SubscriptionMetadata.class)).thenReturn(repository);
        when(kafkaClusters.getEnvironment("test")).thenReturn(Optional.of(testCluster));
        when(kafkaClusters.getEnvironment("unknown-env")).thenReturn(Optional.empty());

        ApplicationMetadata app1 = new ApplicationMetadata();
        app1.setApplicationId("app-1");
        ApplicationMetadata app2 = new ApplicationMetadata();
        app2.setApplicationId("app-2");

        applicationsService = mock(ApplicationsService.class);
        when(applicationsService.getApplicationMetadata("test", "app-1")).thenReturn(Optional.of(app1));
        when(applicationsService.getApplicationMetadata("test", "app-2")).thenReturn(Optional.of(app2));
        when(applicationsService.getApplicationMetadata("test", "app-unknown")).thenReturn(Optional.empty());

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

        TopicMetadata topic4 = new TopicMetadata();
        topic4.setName("topic-4");
        topic4.setType(TopicType.DATA);
        topic4.setOwnerApplicationId("app-2");
        topic4.setSubscriptionApprovalRequired(false);

        TopicMetadata topic5 = new TopicMetadata();
        topic5.setName("topic-5");
        topic5.setType(TopicType.COMMANDS);
        topic5.setOwnerApplicationId("app-2");
        topic5.setSubscriptionApprovalRequired(false);

        topicService = mock(TopicService.class);
        when(topicService.getTopic("test", "topic-1")).thenReturn(Optional.of(topic1));
        when(topicService.getTopic("test", "topic-2")).thenReturn(Optional.of(topic2));
        when(topicService.getTopic("test", "topic-3")).thenReturn(Optional.of(topic3));
        when(topicService.getTopic("test", "topic-4")).thenReturn(Optional.of(topic4));
        when(topicService.getTopic("test", "topic-5")).thenReturn(Optional.of(topic5));
        when(topicService.getTopic("test", "topic-unknown")).thenReturn(Optional.empty());

        eventManager = new GalapagosEventManagerMock();
    }

    @Test
    public void shouldCreateApprovedSubscriptionForEventsTopicWithNoApprovalRequired() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata result = service.addSubscription("test", "topic-1", "app-1", "consumer reason").get();

        assertNotNull(result.getId());
        assertEquals("topic-1", result.getTopicName());
        assertEquals("app-1", result.getClientApplicationId());
        assertEquals(SubscriptionState.APPROVED, result.getState());
        assertEquals("consumer reason", result.getDescription());
        assertTrue(repository.getObject(result.getId()).isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldCreatePendingSubscriptionForEventsTopicWithApprovalRequired() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata result = service.addSubscription("test", "topic-3", "app-1", null).get();

        assertNotNull(result.getId());
        assertEquals("topic-3", result.getTopicName());
        assertEquals("app-1", result.getClientApplicationId());
        assertEquals(SubscriptionState.PENDING, result.getState());
        assertNull(result.getDescription());
        assertTrue(repository.getObject(result.getId()).isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldCreateApprovedSubscriptionForDataTopic() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata result = service.addSubscription("test", "topic-4", "app-1", "data access").get();

        assertNotNull(result.getId());
        assertEquals("topic-4", result.getTopicName());
        assertEquals(SubscriptionState.APPROVED, result.getState());
        assertTrue(repository.getObject(result.getId()).isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldCreateApprovedSubscriptionForCommandsTopic() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata result = service.addSubscription("test", "topic-5", "app-1", null).get();

        assertNotNull(result.getId());
        assertEquals("topic-5", result.getTopicName());
        assertEquals(SubscriptionState.APPROVED, result.getState());
        assertTrue(repository.getObject(result.getId()).isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldPersistDescriptionInRepositoryWhenCreatingSubscription() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata result = service.addSubscription("test", "topic-1", "app-1", "audit note").get();

        SubscriptionMetadata stored = repository.getObject(result.getId()).orElseThrow();
        assertEquals("audit note", stored.getDescription());
    }

    @Test
    public void shouldPersistNullDescriptionWhenNoneProvided() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata result = service.addSubscription("test", "topic-1", "app-1", null).get();

        assertNull(result.getDescription());
        assertNull(repository.getObject(result.getId()).orElseThrow().getDescription());
    }

    @Test
    public void shouldFailWithIllegalArgumentWhenSubscribingToInternalTopic() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.addSubscription("test", "topic-2", "app-1", null).get();
            fail("Expected ExecutionException wrapping IllegalArgumentException for internal topic");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertEquals(0, repository.getObjects().size());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenEnvironmentNotFound_addSubscription() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.addSubscription("unknown-env", "topic-1", "app-1", null).get();
            fail("Expected ExecutionException for unknown environment");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenTopicNotFound_addSubscription() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.addSubscription("test", "topic-unknown", "app-1", null).get();
            fail("Expected ExecutionException for unknown topic");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertEquals(0, repository.getObjects().size());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenApplicationNotRegisteredOnEnvironment() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.addSubscription("test", "topic-1", "app-unknown", null).get();
            fail("Expected ExecutionException for unregistered application");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertEquals(0, repository.getObjects().size());
    }

    @Test
    public void shouldAssignNewIdAndPreserveAllFieldsWhenAddingSubscriptionViaDirectMetadata() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("fixed-id");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");
        sub.setDescription("migrated subscription");

        SubscriptionMetadata result = service.addSubscription("test", sub).get();

        assertNotEquals("fixed-id", result.getId());
        assertNotNull(result.getId());
        assertEquals(SubscriptionState.APPROVED, result.getState());
        assertEquals("app-1", result.getClientApplicationId());
        assertEquals("topic-3", result.getTopicName());
        assertEquals("migrated subscription", result.getDescription());
        assertTrue(repository.getObject(result.getId()).isPresent());
        assertFalse(repository.getObject("fixed-id").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionCreated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldAssignNewIdAndPreservePendingStateWhenAddingDirectMetadata() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("original-id");
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-3");

        SubscriptionMetadata result = service.addSubscription("test", sub).get();

        assertNotEquals("original-id", result.getId());
        assertNotNull(result.getId());
        assertEquals(SubscriptionState.PENDING, result.getState());
        assertTrue(repository.getObject(result.getId()).isPresent());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenEnvironmentNotFound_addSubscriptionDirectMetadata() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("id-1");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-1");

        try {
            service.addSubscription("unknown-env", sub).get();
            fail("Expected ExecutionException for unknown environment");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWithIllegalArgumentWhenDirectMetadataSubscribesToInternalTopic() throws Exception {
        SubscriptionServiceImpl service = buildService();

        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId("id-1");
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId("app-1");
        sub.setTopicName("topic-2");

        try {
            service.addSubscription("test", sub).get();
            fail("Expected ExecutionException for internal topic");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertEquals(0, repository.getObjects().size());
    }

    @Test
    public void shouldUpdateSubscriptionFromPendingToApprovedAndPersistNewState() throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        SubscriptionMetadata saved = repository.getObject("sub-1").orElseThrow();
        assertEquals(SubscriptionState.APPROVED, saved.getState());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldDeleteSubscriptionImmediatelyWhenRejectingPendingSubscription() throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.REJECTED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldDeleteSubscriptionImmediatelyWhenCancelingApprovedSubscription() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.CANCELED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldMapRejectedStateToCanceledInEventWhenRejectingAlreadyApprovedSubscription() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.REJECTED).get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionUpdated", eventManager.getSinkInvocations().get(0).getMethod().getName());
        SubscriptionMetadata eventArg = eventManager.getSinkInvocations().get(0).getArgument(0);
        assertEquals(SubscriptionState.CANCELED, eventArg.getState());
    }

    @Test
    public void shouldBeNoopWithNoEventWhenUpdatingApprovedSubscriptionToApproved() throws Exception {
        SubscriptionMetadata sub = approvedSub("sub-1", "app-1", "topic-3");
        repository.save(sub).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertSame(sub, repository.getObject("sub-1").orElseThrow());
    }

    @Test
    public void shouldBeNoopWithNoEventWhenUpdatingPendingSubscriptionToPending() throws Exception {
        SubscriptionMetadata sub = pendingSub("sub-1", "app-1", "topic-3");
        repository.save(sub).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.PENDING).get();

        assertEquals(0, eventManager.getSinkInvocations().size());
        assertSame(sub, repository.getObject("sub-1").orElseThrow());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenSubscriptionNotFoundForUpdateState() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.updateSubscriptionState("test", "nonexistent-id", SubscriptionState.APPROVED).get();
            fail("Expected ExecutionException for missing subscription");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenEnvironmentNotFoundForUpdateState() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.updateSubscriptionState("unknown-env", "sub-1", SubscriptionState.APPROVED).get();
            fail("Expected ExecutionException for unknown environment");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldNotAffectSiblingSubscriptionsWhenUpdatingOneSubscriptionState() throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();
        repository.save(approvedSub("sub-2", "app-2", "topic-1")).get();

        SubscriptionServiceImpl service = buildService();
        service.updateSubscriptionState("test", "sub-1", SubscriptionState.APPROVED).get();

        assertEquals(SubscriptionState.APPROVED, repository.getObject("sub-1").orElseThrow().getState());
        assertEquals(SubscriptionState.APPROVED, repository.getObject("sub-2").orElseThrow().getState());
        assertEquals(1, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldDeleteApprovedSubscriptionAndEmitDeletedEvent() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.deleteSubscription("test", "sub-1").get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldDeletePendingSubscriptionAndEmitDeletedEvent() throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.deleteSubscription("test", "sub-1").get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
        assertEquals("handleSubscriptionDeleted", eventManager.getSinkInvocations().get(0).getMethod().getName());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenSubscriptionNotFoundForDelete() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.deleteSubscription("test", "nonexistent-id").get();
            fail("Expected ExecutionException for missing subscription");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldFailWithNoSuchElementWhenEnvironmentNotFoundForDelete() throws Exception {
        SubscriptionServiceImpl service = buildService();

        try {
            service.deleteSubscription("unknown-env", "sub-1").get();
            fail("Expected ExecutionException for unknown environment");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NoSuchElementException);
        }

        assertEquals(0, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldNotAffectOtherSubscriptionsWhenDeletingOne() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-1")).get();
        repository.save(approvedSub("sub-2", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();
        service.deleteSubscription("test", "sub-1").get();

        assertFalse(repository.getObject("sub-1").isPresent());
        assertTrue(repository.getObject("sub-2").isPresent());
        assertEquals(1, eventManager.getSinkInvocations().size());
    }

    @Test
    public void shouldReturnOnlyApprovedSubscriptionsForTopicWhenIncludePendingFalse() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-3")).get();
        repository.save(pendingSub("sub-2", "app-2", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnAllSubscriptionsForTopicWhenIncludePendingTrue() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-3")).get();
        repository.save(pendingSub("sub-2", "app-2", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", true);

        assertEquals(2, result.size());
    }

    @Test
    public void shouldReturnEmptyListForTopicWithNoSubscriptions() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-1")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", true);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnEmptyListWhenEnvironmentNotFoundForGetSubscriptionsForTopic() {
        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("unknown-env", "topic-3", true);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldNotReturnSubscriptionsOfOtherTopicsWhenQueryingByTopic() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-1")).get();
        repository.save(approvedSub("sub-2", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-1", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnEmptyListWhenAllTopicSubscriptionsArePendingAndIncludePendingFalse() throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();
        repository.save(pendingSub("sub-2", "app-2", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsForTopic("test", "topic-3", false);

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnOnlyApprovedSubscriptionsForApplicationWhenIncludePendingFalse() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-1")).get();
        repository.save(pendingSub("sub-2", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnAllSubscriptionsForApplicationWhenIncludePendingTrue() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-1")).get();
        repository.save(pendingSub("sub-2", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", true);

        assertEquals(2, result.size());
    }

    @Test
    public void shouldReturnEmptyListForApplicationWithNoSubscriptions() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-2", false);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnEmptyListWhenEnvironmentNotFoundForGetSubscriptionsOfApplication() {
        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("unknown-env", "app-1", true);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldNotReturnSubscriptionsOfOtherApplicationsWhenQueryingByApplication() throws Exception {
        repository.save(approvedSub("sub-1", "app-1", "topic-1")).get();
        repository.save(approvedSub("sub-2", "app-2", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnEmptyListWhenApplicationHasOnlyPendingSubscriptionsAndIncludePendingFalse()
            throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", false);

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnPendingSubscriptionForApplicationWhenIncludePendingTrue() throws Exception {
        repository.save(pendingSub("sub-1", "app-1", "topic-3")).get();

        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", true);

        assertEquals(1, result.size());
        assertEquals("sub-1", result.get(0).getId());
    }

    @Test
    public void shouldReturnEmptyListForApplicationWithNoEntriesInRepository() {
        SubscriptionServiceImpl service = buildService();

        List<SubscriptionMetadata> result = service.getSubscriptionsOfApplication("test", "app-1", true);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    private SubscriptionServiceImpl buildService() {
        return new SubscriptionServiceImpl(kafkaClusters, applicationsService, topicService, eventManager);
    }

    private static SubscriptionMetadata approvedSub(String id, String appId, String topicName) {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId(id);
        sub.setState(SubscriptionState.APPROVED);
        sub.setClientApplicationId(appId);
        sub.setTopicName(topicName);
        return sub;
    }

    private static SubscriptionMetadata pendingSub(String id, String appId, String topicName) {
        SubscriptionMetadata sub = new SubscriptionMetadata();
        sub.setId(id);
        sub.setState(SubscriptionState.PENDING);
        sub.setClientApplicationId(appId);
        sub.setTopicName(topicName);
        return sub;
    }
}