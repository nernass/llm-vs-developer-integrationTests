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
public class claudeIT_1 {
	private GreenMail greenMail;
	private HtmlEmailNotificationService htmlEmailNotificationService;
	private JavaMailSenderImpl sender;
	private static final String FROM_ADDRESS = "from@example.com";
	private static final String MAIL_SERVER_HOST = "127.0.0.1";

	@Before
	public void setUp() throws Exception {
		int mailServerPort = findAvailableTcpPort();
		log.warn("Port selected: {}", mailServerPort);

		greenMail = new GreenMail(
			new ServerSetup(mailServerPort, MAIL_SERVER_HOST, PROTOCOL_SMTP)
		);

		sender = new JavaMailSenderImpl();
		sender.setHost(MAIL_SERVER_HOST);
		sender.setPort(mailServerPort);

		htmlEmailNotificationService = new HtmlEmailNotificationService(FROM_ADDRESS, sender);
		greenMail.start();
	}

	@After
	public void tearDown() throws Exception {
		greenMail.stop();
	}

	@Test
	public void shouldDeliverEmailWithCorrectSubjectAndBody() throws Exception {
		//Given
		String expectedBody = "Hello, this is a test email body.";
		String expectedSubject = "Test Subject";
		String recipient = "recipient@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail(expectedSubject, expectedBody, recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(getBody(receivedMessage)).isEqualTo(expectedBody);
		assertThat(headers(receivedMessage)).contains(entry("Subject", expectedSubject));
	}

	@Test
	public void shouldSetCorrectFromAddress() throws Exception {
		//Given
		String recipient = "someone@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("subject", "body", recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(headers(receivedMessage)).contains(entry("From", FROM_ADDRESS));
	}

	@Test
	public void shouldSetCorrectToAddress() throws Exception {
		//Given
		String expectedRecipient = "target-user@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("subject", "body", expectedRecipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(headers(receivedMessage)).contains(entry("To", expectedRecipient));
	}

	@Test
	public void shouldDeliverHtmlContentInBody() throws Exception {
		//Given
		String htmlBody = "<html><body><h1>Welcome</h1><p>This is <b>bold</b> text.</p></body></html>";
		String recipient = "htmluser@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("HTML Email", htmlBody, recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(getBody(receivedMessage)).contains("<h1>Welcome</h1>");
		assertThat(getBody(receivedMessage)).contains("<b>bold</b>");
	}

	@Test
	public void shouldDeliverMultipleEmailsToSameRecipient() throws Exception {
		//Given
		String recipient = "multi@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("Subject 1", "Body 1", recipient);
		htmlEmailNotificationService.sendHtmlEmail("Subject 2", "Body 2", recipient);
		greenMail.waitForIncomingEmail(2);
		MimeMessage[] receivedMessages = greenMail.getReceivedMessages();

		//Then
		assertThat(receivedMessages).hasSize(2);
		assertThat(getBody(receivedMessages[0])).isEqualTo("Body 1");
		assertThat(getBody(receivedMessages[1])).isEqualTo("Body 2");
		assertThat(headers(receivedMessages[0])).contains(entry("Subject", "Subject 1"));
		assertThat(headers(receivedMessages[1])).contains(entry("Subject", "Subject 2"));
	}

	@Test
	public void shouldDeliverEmailsToDifferentRecipients() throws Exception {
		//Given
		String recipient1 = "first@example.com";
		String recipient2 = "second@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("Subj", "Body", recipient1);
		htmlEmailNotificationService.sendHtmlEmail("Subj", "Body", recipient2);
		greenMail.waitForIncomingEmail(2);
		MimeMessage[] receivedMessages = greenMail.getReceivedMessages();

		//Then
		assertThat(receivedMessages).hasSize(2);
		assertThat(headers(receivedMessages[0])).contains(entry("To", recipient1));
		assertThat(headers(receivedMessages[1])).contains(entry("To", recipient2));
	}

	@Test
	public void shouldHandleEmptySubject() throws Exception {
		//Given
		String recipient = "emptysubject@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("", "body content", recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(getBody(receivedMessage)).isEqualTo("body content");
	}

	@Test
	public void shouldHandleEmptyBody() throws Exception {
		//Given
		String recipient = "emptybody@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("Subject Here", "", recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(getBody(receivedMessage)).isEmpty();
		assertThat(headers(receivedMessage)).contains(entry("Subject", "Subject Here"));
	}

	@Test
	public void shouldHandleSpecialCharactersInBody() throws Exception {
		//Given
		String bodyWithSpecialChars = "Price: $100 & tax < 10% > 5%";
		String recipient = "special@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("Special Chars", bodyWithSpecialChars, recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(getBody(receivedMessage)).contains("$100");
		assertThat(getBody(receivedMessage)).contains("&");
	}

	@Test
	public void shouldThrowSendNotificationFailedExceptionWhenSmtpServerIsDown() throws Exception {
		//Given
		greenMail.stop();
		String recipient = "fail@example.com";

		//When / Then
		assertThatThrownBy(() ->
			htmlEmailNotificationService.sendHtmlEmail("Subject", "Body", recipient)
		).isInstanceOf(SendNotificationFailedException.class)
			.hasMessageContaining("Failed sending email notification")
			.hasMessageContaining(FROM_ADDRESS)
			.hasMessageContaining(recipient);
	}

	@Test
	public void shouldThrowSendNotificationFailedExceptionForInvalidRecipientAddress() throws Exception {
		//Given
		String invalidRecipient = "not-a-valid-email@@example.com";

		//When / Then
		assertThatThrownBy(() ->
			htmlEmailNotificationService.sendHtmlEmail("Subject", "Body", invalidRecipient)
		).isInstanceOf(SendNotificationFailedException.class);
	}

	@Test
	public void shouldSetContentTypeToHtml() throws Exception {
		//Given
		String recipient = "contenttype@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("Subject", "<p>html</p>", recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(receivedMessage.getContentType()).contains("text/html");
	}

/*	@Test
	public void shouldHandleLongSubjectLine() throws Exception {
		//Given
		StringBuilder longSubject = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			longSubject.append("LongSubject ");
		}
		String recipient = "longsubject@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail(longSubject.toString(), "body", recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		assertThat(receivedMessage.getSubject()).isEqualTo(longSubject.toString());
	}

	@Test
	public void shouldHandleUnicodeCharactersInBody() throws Exception {
		//Given
		String unicodeBody = "<p>Héllo Wörld! 日本語テスト</p>";
		String recipient = "unicode@example.com";

		//When
		htmlEmailNotificationService.sendHtmlEmail("Unicode Test", unicodeBody, recipient);
		greenMail.waitForIncomingEmail(1);
		MimeMessage receivedMessage = greenMail.getReceivedMessages()[0];

		//Then
		String body = (String) receivedMessage.getContent();
		assertThat(body).contains("Héllo Wörld!");
		assertThat(body).contains("日本語テスト");
	}*/

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
