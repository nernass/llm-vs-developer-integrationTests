
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

import javax.mail.internet.MimeMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.SocketUtils;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

public class HtmlEmailNotificationServiceIntegrationTest {

    private HtmlEmailNotificationService testedEmailService;
    private JavaMailSenderImpl javaMailSender;
    private GreenMail greenMail;

    private static final String SENDER_EMAIL = "mockSender@example.com";
    private static final String RECIPIENT_EMAIL = "expectedRecipient@example.com";
    private static final String EMAIL_SUBJECT = "Integration Test Subject";
    private static final String EMAIL_BODY = "<h1>Integration Test Body</h1>";

    @Before
    public void setUp() throws Exception {
        int smtpPort = SocketUtils.findAvailableTcpPort();
        
        ServerSetup setup = new ServerSetup(smtpPort, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        greenMail = new GreenMail(setup);
        greenMail.start();

        javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("127.0.0.1");
        javaMailSender.setPort(smtpPort);
        javaMailSender.setProtocol(ServerSetup.PROTOCOL_SMTP);

        testedEmailService = new HtmlEmailNotificationService(SENDER_EMAIL, javaMailSender);
    }

    @After
    public void tearDown() throws Exception {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    public void sendHtmlEmail_whenEmailNotificationSent_theTargetSMTPServerReceivesIt() throws Exception {
        // When
        testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL);

        // Then
        boolean messageReceived = greenMail.waitForIncomingEmail(5000, 1);
        assertThat(messageReceived).isTrue();

        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(1);

        MimeMessage receivedMessage = receivedMessages[0];
        assertThat(receivedMessage.getSubject()).isEqualTo(EMAIL_SUBJECT);
        assertThat(receivedMessage.getFrom()[0].toString()).isEqualTo(SENDER_EMAIL);
        assertThat(receivedMessage.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT_EMAIL);
        
        String content = String.valueOf(receivedMessage.getContent());
        assertThat(content).contains(EMAIL_BODY);
        assertThat(receivedMessage.getContentType()).startsWith("text/html");
    }

    @Test
    public void sendHtmlEmail_whenCalledMultipleTimes_allMessagesAreReceivedConcurrently() throws Exception {
        // Given
        int messageCount = 3;

        // When
        for (int i = 0; i < messageCount; i++) {
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT + " " + i, EMAIL_BODY, RECIPIENT_EMAIL);
        }

        // Then
        boolean messagesReceived = greenMail.waitForIncomingEmail(5000, messageCount);
        assertThat(messagesReceived).isTrue();

        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(messageCount);

        for (int i = 0; i < messageCount; i++) {
            assertThat(receivedMessages[i].getSubject()).isEqualTo(EMAIL_SUBJECT + " " + i);
        }
    }

    @Test
    public void sendHtmlEmail_whenSMTPServerIsDown_throwsSendNotificationFailedException() throws Exception {
        // Given
        greenMail.stop(); 

        // When / Then
        assertThatThrownBy(() ->
            testedEmailService.sendHtmlEmail(EMAIL_SUBJECT, EMAIL_BODY, RECIPIENT_EMAIL)
        )
        .isInstanceOf(SendNotificationFailedException.class)
        .hasMessageContaining("Failed sending email notification")
        .hasMessageContaining("from=" + SENDER_EMAIL)
        .hasMessageContaining("to=" + RECIPIENT_EMAIL)
        .hasMessageContaining("notification=" + EMAIL_BODY);
    }
}
