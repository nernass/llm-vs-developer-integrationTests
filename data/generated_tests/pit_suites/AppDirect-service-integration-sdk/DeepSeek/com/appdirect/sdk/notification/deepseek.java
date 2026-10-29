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

import static com.icegreen.greenmail.util.GreenMailUtil.getBody;
import static com.icegreen.greenmail.util.GreenMailUtil.getHeaders;
import static com.icegreen.greenmail.util.ServerSetup.PROTOCOL_SMTP;
import static java.lang.System.lineSeparator;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.util.SocketUtils.findAvailableTcpPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.internet.MimeMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

public class deepseek {

    private GreenMail greenMail;
    private HtmlEmailNotificationService service;

    @Before
    public void setUp() {
        String host = "127.0.0.1";
        int port = findAvailableTcpPort();

        greenMail = new GreenMail(new ServerSetup(port, host, PROTOCOL_SMTP));

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);

        service = new HtmlEmailNotificationService("from@example.com", sender);
        greenMail.start();
    }

    @After
    public void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    public void sendHtmlEmail_shouldSendEmailWithCorrectBodyAndSubject() throws Exception {
        String subject = "Test Subject";
        String body = "Test Body";
        String to = "recipient@example.com";

        service.sendHtmlEmail(subject, body, to);
        greenMail.waitForIncomingEmail(1);

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(getBody(received)).isEqualTo(body);
        assertThat(headers(received)).containsEntry("Subject", subject);
    }

    @Test
    public void sendHtmlEmail_shouldSendEmailWithCorrectFromAddress() throws Exception {
        String subject = "Test Subject";
        String body = "Test Body";
        String to = "recipient@example.com";

        service.sendHtmlEmail(subject, body, to);
        greenMail.waitForIncomingEmail(1);

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(headers(received)).containsEntry("From", "from@example.com");
    }

    @Test
    public void sendHtmlEmail_shouldSendEmailWithCorrectToAddress() throws Exception {
        String subject = "Test Subject";
        String body = "Test Body";
        String to = "recipient@example.com";

        service.sendHtmlEmail(subject, body, to);
        greenMail.waitForIncomingEmail(1);

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(headers(received)).containsEntry("To", to);
    }

    @Test
    public void sendHtmlEmail_shouldSendHtmlContentType() throws Exception {
        String subject = "Test Subject";
        String body = "<html><body><h1>Hello</h1></body></html>";
        String to = "recipient@example.com";

        service.sendHtmlEmail(subject, body, to);
        greenMail.waitForIncomingEmail(1);

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(received.getContentType()).contains("text/html");
    }

    @Test
    public void sendHtmlEmail_shouldSupportMultipleRecipients() throws Exception {
        String subject = "Test Subject";
        String body = "Test Body";
        String to1 = "recipient1@example.com";
        String to2 = "recipient2@example.com";

        service.sendHtmlEmail(subject, body, to1);
        service.sendHtmlEmail(subject, body, to2);
        greenMail.waitForIncomingEmail(2);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(2);
        assertThat(headers(received[0])).containsEntry("To", to1);
        assertThat(headers(received[1])).containsEntry("To", to2);
    }

    @Test
    public void sendHtmlEmail_whenSmtpServerDown_shouldThrowSendNotificationFailedException() {
        greenMail.stop();

        assertThatThrownBy(() ->
                service.sendHtmlEmail("Subject", "Body", "to@example.com"))
                .isInstanceOf(SendNotificationFailedException.class)
                .hasMessageContaining("Failed sending email notification");
    }

    @Test
    public void sendHtmlEmail_shouldHandleSpecialCharactersInBodyAndSubject() throws Exception {
        String subject = "Test Subject with ñ and accented characters";
        String body = "Test Body with special characters: ñ, é";
        String to = "recipient@example.com";

        service.sendHtmlEmail(subject, body, to);
        greenMail.waitForIncomingEmail(1);

        MimeMessage received = greenMail.getReceivedMessages()[0];
        String receivedBody = getBody(received);

        assertThat(receivedBody).contains("Test Body with special characters");
        assertThat(receivedBody.contains("ñ") || receivedBody.contains("=F1") || receivedBody.contains("=C3=B1")).isTrue();
        assertThat(receivedBody.contains("é") || receivedBody.contains("=E9") || receivedBody.contains("=C3=A9")).isTrue();

        /*
         * Use MimeMessage#getSubject() instead of the raw "Subject" header.
         * The raw header can be MIME encoded, for example:
         * =?Cp1252?Q?Test_Subject_with_=F1_and_accented_characters?=
         */
        assertThat(received.getSubject()).isEqualTo(subject);
    }

    private Map<String, String> headers(MimeMessage message) {
        String headersAsString = getHeaders(message);
        List<String> headerLines = asList(headersAsString.split(lineSeparator()));

        return headerLines.stream()
                .filter(line -> line.contains(":"))
                .collect(
                        HashMap::new,
                        (map, line) -> {
                            String[] parts = line.split(":", 2);
                            map.put(parts[0].trim(), parts[1].trim());
                        },
                        HashMap::putAll
                );
    }
}
