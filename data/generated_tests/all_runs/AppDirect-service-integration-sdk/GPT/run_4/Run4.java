
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
import static com.icegreen.greenmail.util.ServerSetup.PROTOCOL_SMTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.util.SocketUtils.findAvailableTcpPort;

import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

public class HtmlEmailNotificationServiceGreenMailIntegrationTest {

	private static final String FROM_ADDRESS = "mockSender@example.com";
	private static final String MAIL_SERVER_HOST = "127.0.0.1";

	private GreenMail greenMail;
	private HtmlEmailNotificationService testedEmailService;

	@Before
	public void setUp() throws Exception {
		int mailServerPort = findAvailableTcpPort();

		greenMail = new GreenMail(
			new ServerSetup(mailServerPort, MAIL_SERVER_HOST, PROTOCOL_SMTP)
		);

		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(MAIL_SERVER_HOST);
		sender.setPort(mailServerPort);
		sender.setProtocol(PROTOCOL_SMTP);

		testedEmailService = new HtmlEmailNotificationService(FROM_ADDRESS, sender);
		greenMail.start();
	}

	@After
	public void tearDown() throws Exception {
		if (greenMail != null) {
			greenMail.stop();
		}
	}

	@Test
	public void sendHtmlEmail_whenEmailNotificationSent_targetSmtpServerReceivesExpectedHeadersAndBody() throws Exception {
		//Given
		String expectedSubject = "expectedEmailSubject";
		String expectedBody = "expectedMessageBody";
		String expectedRecipient = "expectedRecipient@example.com";

		//When
		testedEmailService.sendHtmlEmail(expectedSubject, expectedBody, expectedRecipient);
		MimeMessage receivedMessage = receiveSingleMessage();

		//Then
		assertThat(receivedMessage.getSubject()).isEqualTo(expectedSubject);
		assertThat(((InternetAddress) receivedMessage.getFrom()[0]).getAddress()).isEqualTo(FROM_ADDRESS);
		assertThat(receivedMessage.getAllRecipients()).hasSize(1);
		assertThat(((InternetAddress) receivedMessage.getAllRecipients()[0]).getAddress()).isEqualTo(expectedRecipient);
		assertThat(getBody(receivedMessage)).isEqualTo(expectedBody);
	}

	@Test
	public void sendHtmlEmail_whenHtmlBodyProvided_messageRetainsHtmlMarkupAndHtmlContentType() throws Exception {
		//Given
		String htmlSubject = "htmlSubject";
		String htmlBody = "<html><body><h1>Welcome</h1><p>This is <b>bold</b> text.</p></body></html>";
		String recipient = "html@example.com";

		//When
		testedEmailService.sendHtmlEmail(htmlSubject, htmlBody, recipient);
		MimeMessage receivedMessage = receiveSingleMessage();

		//Then
		assertThat(receivedMessage.getSubject()).isEqualTo(htmlSubject);
		assertThat(getBody(receivedMessage)).contains("<h1>Welcome</h1>");
		assertThat(getBody(receivedMessage)).contains("<b>bold</b>");
		assertThat(receivedMessage.getContentType()).contains("text/html");
	}

	@Test
	public void sendHtmlEmail_whenMultipleNotificationsSent_allMessagesAreDelivered() throws Exception {
		//Given
		String firstRecipient = "first@example.com";
		String secondRecipient = "second@example.com";

		//When
		testedEmailService.sendHtmlEmail("Subject 1", "Body 1", firstRecipient);
		testedEmailService.sendHtmlEmail("Subject 2", "Body 2", secondRecipient);
		MimeMessage[] receivedMessages = receiveMessages(2);

		//Then
		assertThat(receivedMessages[0].getSubject()).isEqualTo("Subject 1");
		assertThat(receivedMessages[1].getSubject()).isEqualTo("Subject 2");
		assertThat(getBody(receivedMessages[0])).isEqualTo("Body 1");
		assertThat(getBody(receivedMessages[1])).isEqualTo("Body 2");
		assertThat(((InternetAddress) receivedMessages[0].getAllRecipients()[0]).getAddress()).isEqualTo(firstRecipient);
		assertThat(((InternetAddress) receivedMessages[1].getAllRecipients()[0]).getAddress()).isEqualTo(secondRecipient);
	}

	@Test
	public void sendHtmlEmail_whenSmtpServerIsUnavailable_itWrapsFailureInSendNotificationFailedException() throws Exception {
		//Given
		String subject = "expectedEmailSubject";
		String body = "expectedMessageBody";
		String recipient = "expectedRecipient@example.com";
		greenMail.stop();
		greenMail = null;

		//When / Then
		assertThatThrownBy(() ->
			testedEmailService.sendHtmlEmail(subject, body, recipient)
		)
			.isInstanceOf(SendNotificationFailedException.class)
			.hasMessageContaining("Failed sending email notification")
			.hasMessageContaining("from=" + FROM_ADDRESS)
			.hasMessageContaining("to=" + recipient)
			.hasMessageContaining("notification=" + body);
	}

	@Test
	public void sendHtmlEmail_whenRecipientAddressIsInvalid_itWrapsValidationFailureInSendNotificationFailedException() throws Exception {
		//Given
		String invalidRecipient = "not-a-valid-email@@example.com";

		//When / Then
		assertThatThrownBy(() ->
			testedEmailService.sendHtmlEmail("Subject", "Body", invalidRecipient)
		)
			.isInstanceOf(SendNotificationFailedException.class)
			.hasMessageContaining("to=" + invalidRecipient);
	}

	private MimeMessage receiveSingleMessage() throws Exception {
		return receiveMessages(1)[0];
	}

	private MimeMessage[] receiveMessages(int expectedCount) throws Exception {
		greenMail.waitForIncomingEmail(expectedCount);
		MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
		assertThat(receivedMessages).hasSize(expectedCount);
		return receivedMessages;
	}
}