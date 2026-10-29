

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

public class HtmlEmailNotificationServiceIntegrationTest {

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
	public void sendHtmlEmail_whenEmailNotificationSent_theTargetSmtpServerReceivesExpectedHeadersAndBody() throws Exception {
		//Given
		String expectedEmailSubject = "expectedEmailSubject";
		String expectedEmailBody = "expectedMessageBody";
		String expectedRecipientAddress = "expectedRecipient@example.com";

		//When
		testedEmailService.sendHtmlEmail(expectedEmailSubject, expectedEmailBody, expectedRecipientAddress);
		MimeMessage receivedMessage = receiveSingleMessage();

		//Then
		assertThat(receivedMessage.getSubject()).isEqualTo(expectedEmailSubject);
		assertThat(((InternetAddress) receivedMessage.getFrom()[0]).getAddress()).isEqualTo(FROM_ADDRESS);
		assertThat(receivedMessage.getAllRecipients()).hasSize(1);
		assertThat(((InternetAddress) receivedMessage.getAllRecipients()[0]).getAddress())
			.isEqualTo(expectedRecipientAddress);
		assertThat(getBody(receivedMessage)).isEqualTo(expectedEmailBody);
	}

	@Test
	public void sendHtmlEmail_whenHtmlBodyProvided_theReceivedMessageRetainsHtmlMarkupAndContentType() throws Exception {
		//Given
		String expectedEmailSubject = "htmlSubject";
		String expectedHtmlBody = "<html><body><h1>Welcome</h1><p>This is <b>bold</b> text.</p></body></html>";
		String expectedRecipientAddress = "html@example.com";

		//When
		testedEmailService.sendHtmlEmail(expectedEmailSubject, expectedHtmlBody, expectedRecipientAddress);
		MimeMessage receivedMessage = receiveSingleMessage();

		//Then
		assertThat(receivedMessage.getSubject()).isEqualTo(expectedEmailSubject);
		assertThat(getBody(receivedMessage)).contains("<h1>Welcome</h1>");
		assertThat(getBody(receivedMessage)).contains("<b>bold</b>");
		assertThat(receivedMessage.getContentType()).contains("text/html");
	}

	@Test
	public void sendHtmlEmail_whenMultipleNotificationsAreSent_allMessagesAreDelivered() throws Exception {
		//Given
		String firstRecipientAddress = "first@example.com";
		String secondRecipientAddress = "second@example.com";

		//When
		testedEmailService.sendHtmlEmail("Subject 1", "Body 1", firstRecipientAddress);
		testedEmailService.sendHtmlEmail("Subject 2", "Body 2", secondRecipientAddress);
		MimeMessage[] receivedMessages = receiveMessages(2);

		//Then
		assertThat(receivedMessages[0].getSubject()).isEqualTo("Subject 1");
		assertThat(receivedMessages[1].getSubject()).isEqualTo("Subject 2");
		assertThat(getBody(receivedMessages[0])).isEqualTo("Body 1");
		assertThat(getBody(receivedMessages[1])).isEqualTo("Body 2");
		assertThat(((InternetAddress) receivedMessages[0].getAllRecipients()[0]).getAddress())
			.isEqualTo(firstRecipientAddress);
		assertThat(((InternetAddress) receivedMessages[1].getAllRecipients()[0]).getAddress())
			.isEqualTo(secondRecipientAddress);
	}

	@Test
	public void sendHtmlEmail_whenSmtpServerIsUnavailable_itIsWrappedInApplicationLevelException() throws Exception {
		//Given
		String expectedEmailSubject = "expectedEmailSubject";
		String expectedEmailBody = "expectedMessageBody";
		String expectedRecipientAddress = "expectedRecipient@example.com";
		greenMail.stop();
		greenMail = null;

		//When / Then
		assertThatThrownBy(() ->
			testedEmailService.sendHtmlEmail(expectedEmailSubject, expectedEmailBody, expectedRecipientAddress)
		)
			.isInstanceOf(SendNotificationFailedException.class)
			.hasMessageContaining("Failed sending email notification")
			.hasMessageContaining("from=" + FROM_ADDRESS)
			.hasMessageContaining("to=" + expectedRecipientAddress)
			.hasMessageContaining("notification=" + expectedEmailBody);
	}

	@Test
	public void sendHtmlEmail_whenRecipientAddressIsInvalid_itIsWrappedInApplicationLevelException() throws Exception {
		//Given
		String invalidRecipientAddress = "not-a-valid-email@@example.com";

		//When / Then
		assertThatThrownBy(() ->
			testedEmailService.sendHtmlEmail("Subject", "Body", invalidRecipientAddress)
		)
			.isInstanceOf(SendNotificationFailedException.class)
			.hasMessageContaining("to=" + invalidRecipientAddress);
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