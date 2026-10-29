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

import java.util.Properties;

import javax.mail.internet.MimeMessage;

import org.junit.After;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

public class HtmlEmailNotificationServiceIntegrationTest {

    private GreenMail greenMail;

    @After
    public void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
            greenMail = null;
        }
    }

    @Test
    public void sendHtmlEmail_whenValidEmail_thenEmailReceivedWithCorrectContentAndHeaders() throws Exception {
        // Given
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        greenMail.start();
        int smtpPort = greenMail.getSmtp().getPort();

        JavaMailSenderImpl mailSender = createMailSender(smtpPort);
        HtmlEmailNotificationService service =
                new HtmlEmailNotificationService("from@example.com", mailSender);

        String subject = "Test Subject";
        String body = "<h1>Hello, world!</h1>";
        String to = "to@example.com";

        // When
        service.sendHtmlEmail(subject, body, to);

        // Then
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).hasSize(1);

        MimeMessage message = receivedMessages[0];
        assertThat(message.getSubject()).isEqualTo(subject);
        assertThat(message.getFrom()[0].toString()).isEqualTo("from@example.com");
        assertThat(message.getRecipients(javax.mail.Message.RecipientType.TO)[0].toString())
                .isEqualTo(to);
        assertThat(message.getContent()).isEqualTo(body);
        assertThat(message.getContentType()).startsWith("text/html");
    }

    @Test
    public void sendHtmlEmail_whenSmtpServerUnavailable_thenThrowsSendNotificationFailedException() throws Exception {
        // Given
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        greenMail.start();
        int smtpPort = greenMail.getSmtp().getPort();
        greenMail.stop(); // stop server so the port is free but no one is listening

        JavaMailSenderImpl mailSender = createMailSender(smtpPort);
        HtmlEmailNotificationService service =
                new HtmlEmailNotificationService("from@example.com", mailSender);

        // When / Then
        assertThatThrownBy(() -> service.sendHtmlEmail("Subject", "Body", "to@example.com"))
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining("Failed sending email notification")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    public void sendHtmlEmail_whenInvalidRecipient_thenThrowsSendNotificationFailedException() throws Exception {
        // Given
        greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        greenMail.start();
        int smtpPort = greenMail.getSmtp().getPort();

        JavaMailSenderImpl mailSender = createMailSender(smtpPort);
        HtmlEmailNotificationService service =
                new HtmlEmailNotificationService("from@example.com", mailSender);

        // When / Then
        assertThatThrownBy(() -> service.sendHtmlEmail("Subject", "Body", "invalid-email"))
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining("Failed sending email notification")
                .hasCauseInstanceOf(Exception.class);
    }

    private JavaMailSenderImpl createMailSender(int port) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(port);
        mailSender.setProtocol("smtp");

        Properties props = new Properties();
        props.put("mail.smtp.timeout", 2000);
        props.put("mail.smtp.connectiontimeout", 2000);
        mailSender.setJavaMailProperties(props);

        return mailSender;
    }
}