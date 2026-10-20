package org.finmath.classroom.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.ClassroomConfig.Student;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubException;
import org.finmath.classroom.registration.GitHubException.Category;
import org.finmath.classroom.registration.GitHubException.Operation;
import org.finmath.classroom.registration.GitHubGateway;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.registration.RegistrationService;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;

class RegistrationControllerTest {
	private RegistrationService registration;
	private GitHubGateway github;
	private MockMvc mvc;
	private ClassroomConfig config;

	@BeforeEach
	void setup() {
		config = new ClassroomConfig(List.of(new Lecture("lecture", "Lecture <one>", "school", "staff", "lecture-key",
				List.of(new Student("private-id", "Private roster name", null)), List.of(new Assignment("task", "Task", "school/template", "prefix", "main", null,
						"assignment-key", "course-1", "resource-1"), new Assignment("next", "Next task", "school/template", "next", "main", null,
						"next-secret", null, null))), new Lecture("other", "Other lecture", "school", "staff", "other-secret", List.of(),
						List.of(new Assignment("task", "Other task", "school/template", "other", "main", null, "other-task-secret", null, null)))));
		registration = mock(RegistrationService.class);
		github = mock(GitHubGateway.class);
		when(registration.config()).thenReturn(config);
		mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration, github, "https://class.example", Optional.empty())).build();
	}

	@Test
	void testViewingAssignmentIsReadOnlyAndDoesNotExposeRoster() throws Exception {
		mvc.perform(get("/accept/lecture/task")).andExpect(status().isOk())
				.andExpect(content().string(containsString("Lecture &lt;one&gt;")))
				.andExpect(content().string(not(containsString("Private roster name"))))
				.andExpect(header().string("Content-Security-Policy", containsString("form-action 'self' https://github.com;")))
				.andExpect(header().string("Cache-Control", "no-store"));
		verifyNoInteractions(github);
		verify(registration, never()).suggestedName(anyString(), any());
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void testSuggestedNameIsEscapedEditableAndOnlyTheConfirmedNameIsSubmitted() throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student", "GitHub profile name");
		session.setAttribute("github", user);
		when(registration.suggestedName("lecture", user)).thenReturn("Alex O'Connor <example>");
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("name='name' value='Alex O&#39;Connor &lt;example&gt;'")))
				.andExpect(content().string(not(containsString("readonly"))))
				.andExpect(content().string(not(containsString("Private roster name"))));
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
		verifyNoInteractions(github);
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", (String) session.getAttribute("csrf"))
				.param("name", "Alex Corrected").param("lectureKey", "lecture-key").param("assignmentKey", "assignment-key")
				.param("declaration", "true")).andExpect(status().isSeeOther());
		verify(registration).accept("lecture", "task", new Identity("self", "123", "Alex Corrected"), user,
				"lecture-key", "assignment-key", true);
	}

	@Test
	void testNoSuggestedNameLeavesRequiredFieldBlank() throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		when(registration.suggestedName("lecture", user)).thenReturn("");
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("name='name' value='' autocomplete='name' maxlength='200' required")));
	}

	@Test
	void testAcceptanceRequiresSessionCsrfAndGithubLogin() throws Exception {
		MockHttpSession session = session();
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", "forged"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", (String) session.getAttribute("csrf")))
				.andExpect(status().isForbidden());
		verifyNoInteractions(github);
	}

	@Test
	void testGithubCallbackRequiresOneUseStateAndUsesPkce() throws Exception {
		MockHttpSession session = session();
		mvc.perform(get("/accept/lecture/task").session(session).param("lectureKey", "url-lecture-key").param("assignmentKey", "url-assignment-key"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/task"))
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"));
		when(github.authorizationUrl(anyString(), anyString(), anyString())).thenReturn("https://github.com/login/oauth/authorize");
		mvc.perform(post("/auth/github/start").session(session).param("csrf", (String) session.getAttribute("csrf"))
				.param("lectureId", "lecture").param("assignmentId", "task")).andExpect(status().isSeeOther());
		ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
		verify(github).authorizationUrl(eq("https://class.example/auth/github/callback"), state.capture(), challenge.capture());
		mvc.perform(get("/auth/github/callback").session(session).param("state", "wrong").param("code", "code"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/auth/github/callback").param("state", state.getValue()).param("code", "code"))
				.andExpect(status().isForbidden());
		when(github.authenticate(eq("code"), anyString(), anyString())).thenReturn(new User(123, "student"));
		String previousSession = session.getId();
		mvc.perform(get("/auth/github/callback").session(session).param("state", state.getValue()).param("code", "code"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/task"));
		assertNotEquals(previousSession, session.getId());
		ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
		verify(github).authenticate(eq("code"), eq("https://class.example/auth/github/callback"), verifier.capture());
		assertEquals(challenge.getValue(), Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getValue().getBytes(StandardCharsets.US_ASCII))));
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("type='text' name='lectureKey' value='url-lecture-key' autocomplete='off'")))
				.andExpect(content().string(containsString("type='text' name='assignmentKey' value='url-assignment-key' autocomplete='off'")))
				.andExpect(content().string(not(containsString("type='password'"))));
		mvc.perform(get("/auth/github/callback").session(session).param("state", state.getValue()).param("code", "code"))
				.andExpect(status().isForbidden());
	}

	@Test
	void testReturningStudentSeesPersistedRepositoryWithoutProvisioning() throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		when(registration.enrollment("lecture", "task", user)).thenReturn(new Enrollment("lecture", "task", "s1", "Alex", 123,
				"student", "self", Instant.now().toString(), 500L, "school/task-s1", "https://github.com/school/task-s1", "invitation_pending", null));
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("https://github.com/school/task-s1")))
				.andExpect(content().string(containsString("Accept the GitHub repository invitation")))
				.andExpect(content().string(containsString("Refresh access status")))
				.andExpect(content().string(containsString("action='/accept/lecture/task/retry'")))
				.andExpect(content().string(not(containsString("name='name'"))))
				.andExpect(content().string(not(containsString("name='lectureKey'"))))
				.andExpect(content().string(not(containsString("name='declaration'"))));
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
		verify(registration, never()).retry(anyString(), anyString(), any());
		verifyNoInteractions(github);
	}

	@ParameterizedTest
	@CsvSource({ "active,Repository access is active.,false", "retryable,Repository setup is incomplete.,true",
			"creating,Repository setup is in progress.,false", "needs_attention,Repository setup needs instructor review.,false" })
	void testRecordedAcceptanceReplacesFormForEveryProvisioningState(String provisioning, String message, boolean retry) throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		when(registration.enrollment("lecture", "task", user)).thenReturn(new Enrollment("lecture", "task", "s1", "<Alex>", 123,
				"student", "self", "2026-10-17T10:00:00Z", null, "school/task-s1", null, provisioning, null));
		MvcResult result = mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;Alex&gt;"))).andExpect(content().string(containsString("2026-10-17T10:00:00Z")))
				.andExpect(content().string(containsString(message)))
				.andExpect(content().string(containsString("Disconnect GitHub account")))
				.andExpect(content().string(not(containsString("name='name'"))))
				.andExpect(content().string(not(containsString("name='lectureKey'"))))
				.andExpect(content().string(not(containsString("name='assignmentKey'"))))
				.andExpect(content().string(not(containsString("name='declaration'")))).andReturn();
		assertEquals(retry, result.getResponse().getContentAsString().contains("Retry repository setup"));
		mvc.perform(get("/accept/lecture/next").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("name='name'"))).andExpect(content().string(containsString("name='declaration'")))
				.andExpect(content().string(containsString("name='lectureKey' value=''"))).andExpect(content().string(containsString("name='assignmentKey' value=''")));
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
		verify(registration, never()).retry(anyString(), anyString(), any());
		verifyNoInteractions(github);
	}

	@Test
	void testExistingOwnerCanReadMoodleOnlyAssignmentWithoutNewLaunch() throws Exception {
		Lecture lecture = config.lecture("lecture");
		when(registration.config()).thenReturn(new ClassroomConfig(List.of(new Lecture(lecture.id(), lecture.title(), lecture.organization(),
				lecture.staffTeam(), null, lecture.students(), lecture.assignments()))));
		MockHttpSession session = new MockHttpSession();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		when(registration.enrollment("lecture", "task", user)).thenReturn(new Enrollment("lecture", "task", "s1", "Alex", 123,
				"student", "moodle:https://moodle.example", Instant.now().toString(), 500L, "school/task-s1", "https://github.com/school/task-s1", "active", null));
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("https://github.com/school/task-s1")))
				.andExpect(content().string(not(containsString("name='declaration'"))));
		session.setAttribute("github", new User(999, "unregistered"));
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isForbidden());
		verifyNoInteractions(github);
	}

	@Test
	void testRetryRequiresCsrfAndUsesOnlyAuthenticatedAccount() throws Exception {
		MockHttpSession session = session();
		String csrf = (String) session.getAttribute("csrf");
		mvc.perform(post("/accept/lecture/task/retry").session(session).param("csrf", csrf)).andExpect(status().isForbidden());
		User user = new User(123, "student");
		session.setAttribute("github", user);
		mvc.perform(post("/accept/lecture/task/retry").session(session).param("csrf", "forged")).andExpect(status().isForbidden());
		verify(registration, never()).retry(anyString(), anyString(), any());
		mvc.perform(post("/accept/lecture/task/retry").session(session).param("csrf", csrf).param("name", "Someone else")
				.param("githubLogin", "other-user")).andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/task"));
		verify(registration).retry("lecture", "task", user);
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void testIoFailureReferenceCorrelatesWithSafeDiagnosticsOnly(boolean githubFailure) throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		IOException failure = githubFailure
				? new GitHubException(Operation.GRANT_STAFF, Category.HTTP_STATUS, 403)
				: new IOException("private-exception-message");
		failure.initCause(new IOException("private-cause"));
		failure.addSuppressed(new IOException("private-suppressed"));
		when(registration.retry("lecture", "task", user)).thenThrow(failure);
		Logger logger = (Logger) LoggerFactory.getLogger(RegistrationController.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			String previousReference = null;
			for(int attempt = 0; attempt < 2; attempt++) {
				String body = mvc.perform(post("/accept/lecture/task/retry").session(session)
						.param("csrf", (String) session.getAttribute("csrf")).param("code", "private-oauth-code")
						.param("assignmentKey", "private-registration-key"))
						.andExpect(status().isServiceUnavailable())
						.andExpect(content().string(containsString("Registration could not finish")))
						.andReturn().getResponse().getContentAsString();
				Matcher reference = Pattern.compile("Reference: ([0-9a-f-]{36})").matcher(body);
				assertTrue(reference.find());
				String id = UUID.fromString(reference.group(1)).toString();
				assertNotEquals(previousReference, id);
				previousReference = id;
				assertEquals(attempt + 1, appender.list.size());
				ILoggingEvent event = appender.list.get(attempt);
				assertEquals("Registration failed reference=" + id + (githubFailure
						? " operation=GRANT_STAFF category=HTTP_STATUS httpStatus=403" : " category=IO"), event.getFormattedMessage());
				assertNull(event.getThrowableProxy());
				assertFalse(body.contains("private-"));
				assertFalse(body.contains("GRANT_STAFF"));
				assertFalse(body.contains("HTTP_STATUS"));
			}
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void testUrlPrefillIsEscapedAndScopedToAssignmentAndLecture() throws Exception {
		MockHttpSession session = session();
		session.setAttribute("github", new User(123, "student"));
		mvc.perform(get("/accept/lecture/task").session(session).param("lectureKey", "'<script>").param("assignmentKey", "\"&<>"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/task"));
		mvc.perform(get("/accept/lecture/next").session(session).param("assignmentKey", "next-only"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/next"));
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("name='lectureKey' value='&#39;&lt;script&gt;'")))
				.andExpect(content().string(containsString("name='assignmentKey' value='&quot;&amp;&lt;&gt;'")));
		mvc.perform(get("/accept/lecture/next").session(session)).andExpect(content().string(containsString("name='lectureKey' value=''")))
				.andExpect(content().string(containsString("name='assignmentKey' value='next-only'")));
		mvc.perform(get("/accept/other/task").session(session)).andExpect(content().string(containsString("name='lectureKey' value=''")))
				.andExpect(content().string(containsString("name='assignmentKey' value=''")));
		verifyNoInteractions(github);
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void testPartialAndEmptyUrlsReplacePreviousPrefillAndRejectDuplicateOrOversizedKeys() throws Exception {
		MockHttpSession session = session();
		session.setAttribute("github", new User(123, "student"));
		mvc.perform(get("/accept/lecture/task").session(session).param("lectureKey", "old-lecture").param("assignmentKey", "old-assignment"))
				.andExpect(status().isSeeOther());
		mvc.perform(get("/accept/lecture/task").session(session).param("assignmentKey", "new-assignment")).andExpect(status().isSeeOther());
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("name='lectureKey' value=''")))
				.andExpect(content().string(containsString("name='assignmentKey' value='new-assignment'")));
		mvc.perform(get("/accept/lecture/task").session(session).param("lectureKey", "")).andExpect(status().isSeeOther());
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("name='lectureKey' value=''")))
				.andExpect(content().string(containsString("name='assignmentKey' value=''")));
		mvc.perform(get("/accept/lecture/task").session(session).param("lectureKey", "a", "b")).andExpect(status().isBadRequest());
		mvc.perform(get("/accept/lecture/task").session(session).param("assignmentKey", "x".repeat(1025))).andExpect(status().isBadRequest());
		verifyNoInteractions(github);
	}

	@Test
	void testSubmittedKeysAndDeclarationRemainRequiredAndPrefillIsClearedAfterSuccessOrDisconnect() throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		String csrf = (String) session.getAttribute("csrf");
		Identity identity = new Identity("self", "123", "Alex");
		mvc.perform(get("/accept/lecture/task").session(session).param("lectureKey", "from-url").param("assignmentKey", "from-url"))
				.andExpect(status().isSeeOther());
		mvc.perform(get("/accept/lecture/next").session(session).param("lectureKey", "other-url")).andExpect(status().isSeeOther());
		when(registration.accept("lecture", "task", identity, user, "wrong", "wrong", true)).thenThrow(new IllegalArgumentException("Incorrect keys"));
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", csrf).param("name", "Alex")
				.param("lectureKey", "wrong").param("assignmentKey", "wrong").param("declaration", "true")).andExpect(status().isBadRequest());
		verify(registration).accept("lecture", "task", identity, user, "wrong", "wrong", true);
		when(registration.accept("lecture", "task", identity, user, "manual-lecture", "manual-assignment", false))
				.thenThrow(new IllegalArgumentException("Confirm the declaration"));
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", csrf).param("name", "Alex")
				.param("lectureKey", "manual-lecture").param("assignmentKey", "manual-assignment")).andExpect(status().isBadRequest());
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("name='lectureKey' value='from-url'")));
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", csrf).param("name", "Alex").param("lectureKey", "manual-lecture")
				.param("assignmentKey", "manual-assignment").param("declaration", "true")).andExpect(status().isSeeOther());
		verify(registration).accept("lecture", "task", identity, user, "manual-lecture", "manual-assignment", true);
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("name='lectureKey' value=''")))
				.andExpect(content().string(containsString("name='assignmentKey' value=''")));
		mvc.perform(get("/accept/lecture/next").session(session)).andExpect(content().string(containsString("name='lectureKey' value='other-url'")));
		mvc.perform(post("/auth/github/logout").session(session).param("csrf", csrf).param("lectureId", "lecture").param("assignmentId", "task"))
				.andExpect(status().isSeeOther());
		session.setAttribute("github", user);
		mvc.perform(get("/accept/lecture/next").session(session)).andExpect(content().string(containsString("name='lectureKey' value=''")));
	}

	@Test
	void testSelfDeclaredIdentityIsBoundToAuthenticatedGithubAccount() throws Exception {
		MockHttpSession session = session();
		User user = new User(123, "student");
		session.setAttribute("github", user);
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", (String) session.getAttribute("csrf"))
				.param("name", "Alex").param("githubLogin", "someone-else").param("lectureKey", "lecture-key")
				.param("assignmentKey", "assignment-key").param("declaration", "true"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/task"));
		verify(registration).accept("lecture", "task", new Identity("self", "123", "Alex"), user, "lecture-key", "assignment-key", true);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/accept/lecture/task", "/lti/entry" })
	void testMoodleFormPostCompletesOnlyInOriginalBrowserAndIdentityCannotBeOverridden(String targetPath) throws Exception {
		Jwt[] token = new Jwt[1];
		ClassroomConfig unmapped = new ClassroomConfig(List.of(new Lecture("lecture", "Lecture", "school", "staff", "lecture-key", List.of(),
				List.of(new Assignment("task", "Task", "school/template", "prefix", "main", null, "assignment-key", null, null)))));
		MoodleLaunch moodle = new MoodleLaunch(unmapped, "https://class.example", "https://moodle.example", "client", "deployment",
				"https://moodle.example/auth", value -> token[0]);
		mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration, github, "https://class.example", Optional.of(moodle))).build();
		MockHttpSession session = session();
		MvcResult login = mvc.perform(get("/lti/login").session(session).param("iss", "https://moodle.example").param("login_hint", "hint")
				.param("target_link_uri", "https://class.example" + targetPath)
				.param("classroom_lecture", "other").param("classroom_assignment", "forged-task")).andExpect(status().isSeeOther()).andReturn();
		Map<String, List<String>> parameters = UriComponentsBuilder.fromUri(URI.create(login.getResponse().getHeader("Location"))).build().getQueryParams();
		String state = parameters.get("state").get(0);
		token[0] = Jwt.withTokenValue("signed").header("alg", "RS256").issuer("https://moodle.example").subject("moodle-42")
				.audience(List.of("client")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
				.claim("nonce", parameters.get("nonce").get(0)).claim("name", "Moodle Student")
				.claim(MoodleLaunch.CLAIM + "deployment_id", "deployment").claim(MoodleLaunch.CLAIM + "message_type", "LtiResourceLinkRequest")
				.claim(MoodleLaunch.CLAIM + "version", "1.3.0").claim(MoodleLaunch.CLAIM + "target_link_uri", "https://class.example" + targetPath)
				.claim(MoodleLaunch.CLAIM + "roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner"))
				.claim(MoodleLaunch.CLAIM + "custom", Map.of("classroom_lecture", "lecture", "classroom_assignment", "task"))
				.claim(MoodleLaunch.CLAIM + "context", Map.of("id", "new-course")).claim(MoodleLaunch.CLAIM + "resource_link", Map.of("id", "new-activity")).build();
		// The cross-site POST intentionally has no session cookie.
		String encoded = new PlainJWT(new JWTClaimsSet.Builder().issueTime(Date.from(Instant.now())).build()).serialize();
		mvc.perform(post("/lti/launch").param("state", state).param("id_token", encoded)
				.param("classroom_lecture", "other").param("classroom_assignment", "forged-task")).andExpect(status().isSeeOther());
		mvc.perform(get("/lti/complete").param("state", state)).andExpect(status().isForbidden());
		mvc.perform(get("/lti/complete").session(session).param("state", state)).andExpect(status().isSeeOther())
				.andExpect(header().string("Location", "/accept/lecture/task"));
		mvc.perform(get("/lti/complete").session(session).param("state", state)).andExpect(status().isForbidden());
		User account = new User(123, "student", "GitHub profile name");
		session.setAttribute("github", account);
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("Moodle Student")))
				.andExpect(content().string(not(containsString("name='lectureKey'"))))
				.andExpect(content().string(not(containsString("name='name'"))))
				.andExpect(content().string(not(containsString("GitHub profile name"))));
		verify(registration, never()).suggestedName(anyString(), any());
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", (String) session.getAttribute("csrf"))
				.param("name", "Impersonated").param("declaration", "true")).andExpect(status().isSeeOther());
		verify(registration).accept("lecture", "task", new Identity("moodle:https://moodle.example", "moodle-42", "Moodle Student"),
				account, "", "", true);
	}

	@Test
	void testSharedMoodleEntryDoesNotTrustDirectQueryParameters() throws Exception {
		mvc.perform(get("/lti/entry").param("classroom_lecture", "lecture").param("classroom_assignment", "task")
				.param("name", "Unverified name")).andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("Open the External tool activity on your Moodle course page.")))
				.andExpect(content().string(not(containsString("Unverified name"))));
		verifyNoInteractions(registration, github);
	}

	private MockHttpSession session() throws Exception {
		MvcResult response = mvc.perform(get("/accept/lecture/task")).andExpect(status().isOk()).andReturn();
		MockHttpSession session = (MockHttpSession) response.getRequest().getSession(false);
		assertNotNull(session);
		return session;
	}
}
