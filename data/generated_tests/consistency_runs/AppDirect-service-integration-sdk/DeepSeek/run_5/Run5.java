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

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Properties;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Integration tests for {@link HtmlEmailNotificationService} using an embedded GreenMail SMTP server.
 * These tests verify the actual SMTP interaction and error handling.
 */
public class HtmlEmailNotificationServiceIntegrationTest {

    private static final String FROM_ADDRESS = "integration@example.com";
    private static final String TO_ADDRESS = "recipient@example.com";
    private static final String SUBJECT = "Test Subject";
    private static final String BODY = "<html><body><h1>Test</h1><p>Hello</p></body></html>";

    private GreenMail greenMail;
    private HtmlEmailNotificationService notificationService;

    @Before
    public void setUp() {
        // Start GreenMail on a random available port
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();

        // Configure JavaMailSender to send to the embedded server
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(greenMail.getSmtp().getPort());
        mailSender.setProtocol("smtp");

        // Disable authentication and TLS for local testing
        Properties props = new Properties();
        props.setProperty("mail.smtp.auth", "false");
        props.setProperty("mail.smtp.starttls.enable", "false");
        mailSender.setJavaMailProperties(props);

        notificationService = new HtmlEmailNotificationService(FROM_ADDRESS, mailSender);
    }

    @After
    public void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    public void sendHtmlEmail_whenValidInput_emailIsReceivedByGreenMail() throws MessagingException, IOException {
        // When
        notificationService.sendHtmlEmail(SUBJECT, BODY, TO_ADDRESS);

        // Then
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(1);

        MimeMessage message = receivedMessages[0];
        assertThat(message.getSubject()).isEqualTo(SUBJECT);
        assertThat(message.getFrom()[0].toString()).isEqualTo(FROM_ADDRESS);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(TO_ADDRESS);
        assertThat(message.getContent()).isEqualTo(BODY);
        // Additional check: content type is HTML
        assertThat(message.getContentType()).contains("text/html");
    }

    @Test
    public void sendHtmlEmail_whenSMTPServerUnavailable_throwsSendNotificationFailedException() {
        // Stop the GreenMail server to simulate SMTP unavailability
        greenMail.stop();

        // Expect the exception to be wrapped
        assertThatThrownBy(() ->
                notificationService.sendHtmlEmail(SUBJECT, BODY, TO_ADDRESS)
        )
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining("Failed sending email notification with from=" + FROM_ADDRESS)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    public void sendHtmlEmail_whenInvalidRecipientAddress_throwsSendNotificationFailedException() {
        // GreenMail is running, but the recipient address is malformed
        String invalidTo = "invalid-address";

        assertThatThrownBy(() ->
                notificationService.sendHtmlEmail(SUBJECT, BODY, invalidTo)
        )
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining("Failed sending email notification with from=" + FROM_ADDRESS)
                .hasCauseInstanceOf(Exception.class);
    }
}