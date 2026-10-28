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

import com.icegreen.greenmail.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

public class HtmlEmailNotificationServiceIntegrationTest {

    private static final String FROM_ADDRESS = "sender@example.com";
    private static final String TO_ADDRESS = "recipient@example.com";
    private static final String SUBJECT = "Test Subject";
    private static final String BODY = "<html><body><h1>Test</h1><p>Hello World</p></body></html>";

    private GreenMail greenMail;
    private JavaMailSenderImpl mailSender;
    private HtmlEmailNotificationService service;

    @Before
    public void setUp() {
        // GreenMail will be started in each test individually
        greenMail = null;
        mailSender = new JavaMailSenderImpl();
        mailSender.setProtocol("smtp");
        mailSender.setHost("127.0.0.1");
        // Port will be set after GreenMail is started
        mailSender.setUsername(null);
        mailSender.setPassword(null);
        // Disable TLS and authentication
        mailSender.getJavaMailProperties().setProperty("mail.smtp.auth", "false");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "false");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
        mailSender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "5000");
    }

    @After
    public void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    public void shouldSendEmailAndReceiveItOnGreenMailServer() throws Exception {
        // Start GreenMail on an ephemeral port
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        greenMail.start();

        // Configure JavaMailSender with the actual bound port
        int smtpPort = greenMail.getSmtp().getPort();
        mailSender.setPort(smtpPort);

        service = new HtmlEmailNotificationService(FROM_ADDRESS, mailSender);

        // When
        service.sendHtmlEmail(SUBJECT, BODY, TO_ADDRESS);

        // Then
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(1);

        MimeMessage message = receivedMessages[0];
        assertThat(message.getHeader("Subject", null)).isEqualTo(SUBJECT);
        assertThat(message.getHeader("To", null)).isEqualTo(TO_ADDRESS);
        assertThat(message.getHeader("From", null)).isEqualTo(FROM_ADDRESS);
        // The body is HTML, getContent() returns a String
        assertThat(message.getContent()).isInstanceOf(String.class);
        String content = (String) message.getContent();
        assertThat(content).contains("<h1>Test</h1>");
        assertThat(content).contains("Hello World");
    }

    @Test
    public void shouldThrowSendNotificationFailedExceptionWhenSmtpServerUnavailable() throws Exception {
        // Start GreenMail on ephemeral port, get the port, then stop it to make it unavailable
        GreenMail tempMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        tempMail.start();
        int smtpPort = tempMail.getSmtp().getPort();
        tempMail.stop();

        // Assign to the field so @After can try to stop (it's already stopped)
        greenMail = tempMail; // but we don't want to stop again in tearDown? It's safe.

        // Configure JavaMailSender with the port that is now free (no server listening)
        mailSender.setPort(smtpPort);
        service = new HtmlEmailNotificationService(FROM_ADDRESS, mailSender);

        // When / Then
        assertThatThrownBy(() -> service.sendHtmlEmail(SUBJECT, BODY, TO_ADDRESS))
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining(FROM_ADDRESS)
                .hasMessageContaining(TO_ADDRESS)
                .hasMessageContaining(BODY)
                .hasCauseInstanceOf(Exception.class); // the cause is a MailSendException or similar
    }

    @Test
    public void shouldHandleMalformedRecipientAddressGracefully() {
        // Start GreenMail for this test
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        greenMail.start();
        int smtpPort = greenMail.getSmtp().getPort();
        mailSender.setPort(smtpPort);
        service = new HtmlEmailNotificationService(FROM_ADDRESS, mailSender);

        // Invalid recipient (missing @domain)
        String invalidRecipient = "invalid-email";

        assertThatThrownBy(() -> service.sendHtmlEmail(SUBJECT, BODY, invalidRecipient))
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining(FROM_ADDRESS)
                .hasMessageContaining(invalidRecipient)
                .hasMessageContaining(BODY)
                .hasCauseInstanceOf(Exception.class);
    }
}