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
import javax.mail.internet.MimeUtility;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * Integration test for {@link HtmlEmailNotificationService} using an embedded GreenMail SMTP server.
 * This test verifies that the service correctly sends HTML emails to a real SMTP endpoint.
 */
public class HtmlEmailNotificationServiceIntegrationTest {

    private static final String FROM_ADDRESS = "integration-test@example.com";
    private static final String TO_ADDRESS = "recipient@example.com";
    private static final String SUBJECT = "Test Subject";
    private static final String HTML_BODY = "<html><body><h1>Hello</h1><p>This is a test.</p></body></html>";

    private GreenMail greenMail;
    private HtmlEmailNotificationService notificationService;

    @Before
    public void setUp() {
        // Start embedded SMTP server on a random available port
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        greenMail.start();

        int smtpPort = greenMail.getSmtp().getPort();

        // Configure real JavaMailSenderImpl to point to the embedded SMTP server
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(smtpPort);
        mailSender.setProtocol("smtp");
        mailSender.setDefaultEncoding("UTF-8");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.auth", "false");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "false");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");

        notificationService = new HtmlEmailNotificationService(FROM_ADDRESS, mailSender);
    }

    @After
    public void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    public void sendHtmlEmail_whenValidParameters_expectEmailReceivedBySmtpServer() throws Exception {
        // Given
        String subject = SUBJECT;
        String body = HTML_BODY;
        String to = TO_ADDRESS;

        // When
        notificationService.sendHtmlEmail(subject, body, to);

        // Then
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(1);

        MimeMessage message = receivedMessages[0];
        assertThat(message.getSubject()).isEqualTo(subject);
        assertThat(message.getFrom()[0].toString()).contains(FROM_ADDRESS);
        assertThat(message.getRecipients(javax.mail.Message.RecipientType.TO)[0].toString()).contains(to);

        // Verify HTML content (decode if necessary, but GreenMail returns raw MimeMessage)
        Object content = message.getContent();
        if (content instanceof String) {
            // In some cases, the content may be raw string; but it should be HTML
            String contentString = (String) content;
            assertThat(contentString).contains("<h1>Hello</h1>");
        } else {
            // For MimeMultipart, we can handle it if needed; but for simplicity, we expect String
            // Actually, for HTML with MimeMessageHelper, it's usually a MimeMultipart with text/html
            // Let's handle it robustly:
            if (content instanceof javax.mail.Multipart) {
                javax.mail.Multipart multipart = (javax.mail.Multipart) content;
                boolean foundHtml = false;
                for (int i = 0; i < multipart.getCount(); i++) {
                    javax.mail.BodyPart part = multipart.getBodyPart(i);
                    if (part.isMimeType("text/html")) {
                        String html = (String) part.getContent();
                        assertThat(html).contains("<h1>Hello</h1>");
                        foundHtml = true;
                        break;
                    }
                }
                assertThat(foundHtml).isTrue();
            } else {
                // Fallback: just check content type and body
                assertThat(message.getContentType()).contains("text/html");
            }
        }
    }

    @Test
    public void sendHtmlEmail_whenMultipleEmailsSent_expectAllReceived() throws Exception {
        // Given
        String subject1 = "First";
        String body1 = "Body 1";
        String subject2 = "Second";
        String body2 = "Body 2";

        // When
        notificationService.sendHtmlEmail(subject1, body1, TO_ADDRESS);
        notificationService.sendHtmlEmail(subject2, body2, TO_ADDRESS);

        // Then
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(2);

        assertThat(receivedMessages[0].getSubject()).isEqualTo(subject1);
        assertThat(receivedMessages[1].getSubject()).isEqualTo(subject2);
    }

    @Test
    public void sendHtmlEmail_whenSmtpServerNotRunning_expectSendNotificationFailedException() {
        // Stop the server to simulate failure
        greenMail.stop();

        // Given
        String subject = SUBJECT;
        String body = HTML_BODY;
        String to = TO_ADDRESS;

        // When / Then
        assertThatThrownBy(() -> notificationService.sendHtmlEmail(subject, body, to))
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining("Failed sending email notification")
                .hasCauseInstanceOf(javax.mail.MessagingException.class);
    }
}