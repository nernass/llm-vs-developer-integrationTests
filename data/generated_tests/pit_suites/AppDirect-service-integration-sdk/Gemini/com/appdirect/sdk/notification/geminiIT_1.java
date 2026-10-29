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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

public class geminiIT_1 {

	private static final String FROM_ADDRESS = "from@example.com";
	private static final String MAIL_SERVER_HOST = "127.0.0.1";

	private GreenMail greenMail;
	private HtmlEmailNotificationService htmlEmailNotificationService;

	@Before
	public void setUp() throws Exception {
		int mailServerPort = findAvailableTcpPort();

		greenMail = new GreenMail(
			new ServerSetup(mailServerPort, MAIL_SERVER_HOST, PROTOCOL_SMTP)
		);
		greenMail.start();

		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(MAIL_SERVER_HOST);
		sender.setPort(mailServerPort);

		htmlEmailNotificationService = new HtmlEmailNotificationService(FROM_ADDRESS, sender);
	}

	@After
	public void tearDown() throws Exception {
		if (greenMail != null) {
			greenMail.stop();
		}
	}

	@Test
	public void shouldSendHtmlEmailSuccessfullyWithExpectedSubjectAndBody() throws Exception {
		String expectedSubject = "Welcome to AppDirect";
		String expectedBody = "<h1>Hello</h1><p>Welcome to our service.</p>";
		String expectedRecipient = "user@example.com";

		htmlEmailNotificationService.sendHtmlEmail(expectedSubject, expectedBody, expectedRecipient);
		
		greenMail.waitForIncomingEmail(1);
		MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
		
		assertThat(receivedMessages).hasSize(1);
		MimeMessage receivedMessage = receivedMessages[0];

		assertThat(getBody(receivedMessage)).isEqualTo(expectedBody);
		Map<String, String> headers = headers(receivedMessage);
		assertThat(headers).contains(entry("Subject", expectedSubject));
		assertThat(headers).contains(entry("To", expectedRecipient));
		assertThat(headers).contains(entry("From", FROM_ADDRESS));
	}

	@Test
	public void shouldSendMultipleEmailsContinuously() throws Exception {
		String subject1 = "Subject 1";
		String body1 = "Body 1";
		String recipient1 = "user1@example.com";

		String subject2 = "Subject 2";
		String body2 = "Body 2";
		String recipient2 = "user2@example.com";

		htmlEmailNotificationService.sendHtmlEmail(subject1, body1, recipient1);
		htmlEmailNotificationService.sendHtmlEmail(subject2, body2, recipient2);
		
		greenMail.waitForIncomingEmail(2);
		MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
		
		assertThat(receivedMessages).hasSize(2);
		
		assertThat(getBody(receivedMessages[0])).isEqualTo(body1);
		assertThat(headers(receivedMessages[0])).contains(entry("Subject", subject1));
		assertThat(headers(receivedMessages[0])).contains(entry("To", recipient1));

		assertThat(getBody(receivedMessages[1])).isEqualTo(body2);
		assertThat(headers(receivedMessages[1])).contains(entry("Subject", subject2));
		assertThat(headers(receivedMessages[1])).contains(entry("To", recipient2));
	}

	@Test
	public void shouldThrowExceptionWhenSmtpServerIsUnreachable() throws Exception {
		greenMail.stop();

		String subject = "Test Failure";
		String body = "This should fail";
		String recipient = "fail@example.com";

		assertThatThrownBy(() -> 
			htmlEmailNotificationService.sendHtmlEmail(subject, body, recipient)
		)
		.isInstanceOf(SendNotificationFailedException.class)
		.hasMessageContaining("Failed sending email notification with from=from@example.com, to=fail@example.com, notification=This should fail");
	}

	private Map<String, String> headers(MimeMessage receivedMessage) {
		String headersAsString = getHeaders(receivedMessage);
		List<String> headers = asList(headersAsString.split(lineSeparator()));

		return headers.stream().collect(
			HashMap::new,
			(hashMap, keyValue) -> {
				String[] split = keyValue.split(":", 2);
				if (split.length == 2) {
					hashMap.put(split[0].trim(), split[1].trim());
				}
			},
			Map::putAll
		);
	}
}
