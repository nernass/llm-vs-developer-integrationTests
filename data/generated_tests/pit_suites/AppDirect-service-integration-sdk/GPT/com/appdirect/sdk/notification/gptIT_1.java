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
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.util.SocketUtils.findAvailableTcpPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.internet.MimeMessage;

import lombok.extern.slf4j.Slf4j;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

@Slf4j
public class gptIT_1 {
    private static final String MAIL_SERVER_HOST = "127.0.0.1";
    private static final String FROM_ADDRESS = "from@example.com";

    private GreenMail greenMail;
    private HtmlEmailNotificationService htmlEmailNotificationService;

    @Before
    public void setUp() {
        int mailServerPort = findAvailableTcpPort();

        log.warn("Port selected: {}", mailServerPort);
        greenMail = new GreenMail(new ServerSetup(mailServerPort, MAIL_SERVER_HOST, PROTOCOL_SMTP));

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(MAIL_SERVER_HOST);
        sender.setPort(mailServerPort);

        htmlEmailNotificationService = new HtmlEmailNotificationService(FROM_ADDRESS, sender);
        greenMail.start();
    }

    @After
    public void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    public void shouldDeliverHtmlEmailWithExpectedHeadersAndBody() throws Exception {
        String expectedEmailBody = "<html><body><h1>Hello</h1><p>Welcome aboard.</p></body></html>";
        String expectedEmailSubject = "welcome-subject";
        String expectedRecipient = "receiver@example.com";

        htmlEmailNotificationService.sendHtmlEmail(expectedEmailSubject, expectedEmailBody, expectedRecipient);
        greenMail.waitForIncomingEmail(1);
        MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

        assertThat(getBody(receivedMessage)).isEqualTo(expectedEmailBody);
        assertThat(headers(receivedMessage))
            .contains(entry("Subject", expectedEmailSubject))
            .contains(entry("To", expectedRecipient))
            .contains(entry("From", FROM_ADDRESS));
        assertThat(receivedMessage.getContentType()).contains("text/html");
    }

    @Test
    public void shouldDeliverMultipleEmailsWithoutLeakingMessageState() throws Exception {
        htmlEmailNotificationService.sendHtmlEmail("subject-one", "body-one", "first@example.com");
        htmlEmailNotificationService.sendHtmlEmail("subject-two", "body-two", "second@example.com");
        greenMail.waitForIncomingEmail(2);

        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();

        assertThat(receivedMessages).hasSize(2);
        assertThat(getBody(receivedMessages[0])).isEqualTo("body-one");
        assertThat(headers(receivedMessages[0]))
            .contains(entry("Subject", "subject-one"))
            .contains(entry("To", "first@example.com"));
        assertThat(getBody(receivedMessages[1])).isEqualTo("body-two");
        assertThat(headers(receivedMessages[1]))
            .contains(entry("Subject", "subject-two"))
            .contains(entry("To", "second@example.com"));
    }

   /* @Test
    public void shouldPreserveUnicodeAndSpecialCharactersInHtmlBody() throws Exception {
        String expectedEmailBody = "<p>Héllo &amp; welcome, 日本語テスト</p>";

        htmlEmailNotificationService.sendHtmlEmail("unicode-subject", expectedEmailBody, "unicode@example.com");
        greenMail.waitForIncomingEmail(1);
        MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

        assertThat((String) receivedMessage.getContent()).contains(expectedEmailBody);
    }
*/
    @Test
    public void shouldWrapTransportFailuresWhenSmtpServerIsUnavailable() {
        greenMail.stop();

        assertThatThrownBy(() ->
            htmlEmailNotificationService.sendHtmlEmail("subject", "body", "receiver@example.com")
        )
            .isInstanceOf(SendNotificationFailedException.class)
            .hasMessageContaining("Failed sending email notification with from=from@example.com")
            .hasMessageContaining("to=receiver@example.com")
            .hasMessageContaining("notification=body");
    }

    @Test
    public void shouldWrapValidationFailuresForInvalidRecipientAddress() {
        assertThatThrownBy(() ->
            htmlEmailNotificationService.sendHtmlEmail("subject", "body", "not-an-email-address@@example.com")
        )
            .isInstanceOf(SendNotificationFailedException.class)
            .hasMessageContaining("to=not-an-email-address@@example.com");
    }

    private Map<String, String> headers(MimeMessage receivedMessage) {
        String headersAsString = getHeaders(receivedMessage);
        List<String> headers = asList(headersAsString.split(lineSeparator()));

        return headers.stream().collect(
            HashMap::new,
            (hashMap, keyValue) -> {
                String[] split = keyValue.split(":", 2);
                if (split.length == 2) {
                    hashMap.put(split[0], split[1].trim());
                }
            },
            Map::putAll
        );
    }
}
