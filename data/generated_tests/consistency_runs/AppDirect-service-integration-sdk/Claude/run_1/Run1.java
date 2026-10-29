/*
 * Copyright 2017 AppDirect, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.appdirect.sdk.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.assertj.core.api.exception.RuntimeIOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@RunWith(MockitoJUnitRunner.class)
public class HtmlEmailNotificationServiceTest {

    private static final String SENDER_EMAIL = "mockSender@example.com";
    private static final String RECIPIENT_EMAIL = "expectedRecipient@example.com";
    private static final String EMAIL_SUBJECT = "expectedEmailSubject";
    private static final String EMAIL_BODY = "expectedMessageBody";

    private HtmlEmailNotificationService testedEmailService;

    @Mock
    private JavaMailSender mockEmailSender;

    @Before
    public void setUp() throws Exception {
        testedEmailService = new HtmlEmailNotificationService(SENDER_EMAIL, mockEmailSender);
        when(mockEmailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    public void sendHtmlEmail_whenNotificationSent_aCorrespondingEmailSent() throws Exception {
        //Given
        ArgumentCaptor<MimeMessage> argumentCaptor = ArgumentCaptor.forClass(MimeMessage.class);

        //When
        testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL);

        //Then
        verify(mockEmailSender).send(argumentCaptor.capture());
        MimeMessage messageSent = argumentCaptor.getValue();

        assertThat((String) messageSent.getContent()).isEqualTo(EMAIL_BODY);
        assertThat(messageSent.getHeader("Subject")[0]).isEqualTo(EMAIL_SUBJECT);
        assertThat(messageSent.getHeader("To")[0]).isEqualTo(RECIPIENT_EMAIL);
        assertThat(messageSent.getHeader("From")[0]).isEqualTo(SENDER_EMAIL);
    }

    @Test
    public void sendHtmlEmail_whenSent_messageContentTypeIsHtml() throws Exception {
        //Given
        ArgumentCaptor<MimeMessage> argumentCaptor = ArgumentCaptor.forClass(MimeMessage.class);

        //When
        testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, "<h1>Hello</h1>", RECIPIENT_EMAIL);

        //Then
        verify(mockEmailSender).send(argumentCaptor.capture());
        assertThat(argumentCaptor.getValue().getContentType()).startsWith("text/html");
    }

    @Test
    public void sendHtmlEmail_whenSent_fromAddressMatchesConfiguredSender() throws Exception {
        //Given
        String customSender = "customSender@domain.com";
        HtmlEmailNotificationService customService =
                new HtmlEmailNotificationService(customSender, mockEmailSender);
        ArgumentCaptor<MimeMessage> argumentCaptor = ArgumentCaptor.forClass(MimeMessage.class);

        //When
        customService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL);

        //Then
        verify(mockEmailSender).send(argumentCaptor.capture());
        assertThat(argumentCaptor.getValue().getHeader("From")[0]).isEqualTo(customSender);
    }

    @Test
    public void sendHtmlEmail_whenSent_subjectIsPreservedExactly() throws Exception {
        //Given
        String subjectWithSpecialChars = "Alert: [CRITICAL] System failure #42";
        ArgumentCaptor<MimeMessage> argumentCaptor = ArgumentCaptor.forClass(MimeMessage.class);

        //When
        testedEmailService.sendHtmlEmail(subjectWithSpecialChars, EMAIL_BODY, RECIPIENT_EMAIL);

        //Then
        verify(mockEmailSender).send(argumentCaptor.capture());
        assertThat(argumentCaptor.getValue().getHeader("Subject")[0]).isEqualTo(subjectWithSpecialChars);
    }

    @Test
    public void sendHtmlEmail_whenMessageExceptionThrownByJavaApi_itIsWrappedInApplicationLevelException() throws Exception {
        //Given
        doThrow(new RuntimeIOException("test"))
            .when(mockEmailSender).send(any(MimeMessage.class));

        //When / Then
        assertThatThrownBy(() ->
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL)
        )
            .isInstanceOf(SendNotificationFailedException.class)
            .hasMessage(
                "Failed sending email notification with from=mockSender@example.com, to=expectedRecipient@example.com, notification=expectedMessageBody"
            );
    }

    @Test
    public void sendHtmlEmail_whenMailSendExceptionThrown_itIsWrappedInSendNotificationFailedException() throws Exception {
        //Given
        doThrow(new MailSendException("SMTP connection refused"))
            .when(mockEmailSender).send(any(MimeMessage.class));

        //When / Then
        assertThatThrownBy(() ->
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL)
        )
            .isInstanceOf(SendNotificationFailedException.class)
            .hasMessageContaining("from=" + SENDER_EMAIL)
            .hasMessageContaining("to=" + RECIPIENT_EMAIL)
            .hasMessageContaining("notification=" + EMAIL_BODY);
    }

    @Test
    public void sendHtmlEmail_whenExceptionThrown_errorMessageContainsAllContextFields() throws Exception {
        //Given
        String body = "bodyForContextCheck";
        String recipient = "ctx@example.com";
        doThrow(new RuntimeIOException("error"))
            .when(mockEmailSender).send(any(MimeMessage.class));

        //When / Then
        assertThatThrownBy(() ->
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, body, recipient)
        )
            .isInstanceOf(SendNotificationFailedException.class)
            .hasMessageContaining("from=" + SENDER_EMAIL)
            .hasMessageContaining("to=" + recipient)
            .hasMessageContaining("notification=" + body);
    }

    @Test
    public void sendHtmlEmail_whenExceptionThrown_originalCauseIsAttached() throws Exception {
        //Given
        RuntimeIOException rootCause = new RuntimeIOException("root cause");
        doThrow(rootCause).when(mockEmailSender).send(any(MimeMessage.class));

        //When / Then
        assertThatThrownBy(() ->
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL)
        )
            .isInstanceOf(SendNotificationFailedException.class)
            .hasCause(rootCause);
    }

    @Test
    public void sendHtmlEmail_whenCalledMultipleTimes_eachCallSendsExactlyOneMessage() throws Exception {
        //When
        testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL);
        testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL);
        testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL);

        //Then
        verify(mockEmailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    public void sendHtmlEmail_whenCreateMimeMessageFails_sendIsNeverInvoked() throws Exception {
        //Given
        when(mockEmailSender.createMimeMessage()).thenThrow(new RuntimeIOException("mime creation failure"));

        //When / Then
        assertThatThrownBy(() ->
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL)
        ).isInstanceOf(SendNotificationFailedException.class);

        verify(mockEmailSender, never()).send(any(MimeMessage.class));
    }
}
