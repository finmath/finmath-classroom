package org.finmath.classroom.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.ClassroomConfig.Student;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubGateway;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.registration.RegistrationService;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
						"assignment-key", "course-1", "resource-1")))));
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
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
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
				.andExpect(content().string(containsString("Accept the GitHub repository invitation")));
		verify(registration, never()).accept(anyString(), anyString(), any(), any(), anyString(), anyString(), anyBoolean());
		verifyNoInteractions(github);
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

	@Test
	void testMoodleFormPostCompletesOnlyInOriginalBrowserAndIdentityCannotBeOverridden() throws Exception {
		Jwt[] token = new Jwt[1];
		MoodleLaunch moodle = new MoodleLaunch(config, "https://class.example", "https://moodle.example", "client", "deployment",
				"https://moodle.example/auth", value -> token[0]);
		mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration, github, "https://class.example", Optional.of(moodle))).build();
		MockHttpSession session = session();
		MvcResult login = mvc.perform(get("/lti/login").session(session).param("iss", "https://moodle.example").param("login_hint", "hint")
				.param("target_link_uri", "https://class.example/accept/lecture/task")).andExpect(status().isSeeOther()).andReturn();
		Map<String, List<String>> parameters = UriComponentsBuilder.fromUri(URI.create(login.getResponse().getHeader("Location"))).build().getQueryParams();
		String state = parameters.get("state").get(0);
		token[0] = Jwt.withTokenValue("signed").header("alg", "RS256").issuer("https://moodle.example").subject("moodle-42")
				.audience(List.of("client")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
				.claim("nonce", parameters.get("nonce").get(0)).claim("name", "Moodle Student")
				.claim(MoodleLaunch.CLAIM + "deployment_id", "deployment").claim(MoodleLaunch.CLAIM + "message_type", "LtiResourceLinkRequest")
				.claim(MoodleLaunch.CLAIM + "version", "1.3.0").claim(MoodleLaunch.CLAIM + "target_link_uri", "https://class.example/accept/lecture/task")
				.claim(MoodleLaunch.CLAIM + "roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner"))
				.claim(MoodleLaunch.CLAIM + "context", Map.of("id", "course-1")).claim(MoodleLaunch.CLAIM + "resource_link", Map.of("id", "resource-1")).build();
		// The cross-site POST intentionally has no session cookie.
		String encoded = new PlainJWT(new JWTClaimsSet.Builder().issueTime(Date.from(Instant.now())).build()).serialize();
		mvc.perform(post("/lti/launch").param("state", state).param("id_token", encoded)).andExpect(status().isSeeOther());
		mvc.perform(get("/lti/complete").param("state", state)).andExpect(status().isForbidden());
		mvc.perform(get("/lti/complete").session(session).param("state", state)).andExpect(status().isSeeOther());
		mvc.perform(get("/lti/complete").session(session).param("state", state)).andExpect(status().isForbidden());
		session.setAttribute("github", new User(123, "student"));
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(content().string(containsString("Moodle Student")))
				.andExpect(content().string(not(containsString("name='lectureKey'"))));
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", (String) session.getAttribute("csrf"))
				.param("name", "Impersonated").param("declaration", "true")).andExpect(status().isSeeOther());
		verify(registration).accept("lecture", "task", new Identity("moodle:https://moodle.example", "moodle-42", "Moodle Student"),
				new User(123, "student"), "", "", true);
	}

	private MockHttpSession session() throws Exception {
		MvcResult response = mvc.perform(get("/accept/lecture/task")).andExpect(status().isOk()).andReturn();
		MockHttpSession session = (MockHttpSession) response.getRequest().getSession(false);
		assertNotNull(session);
		return session;
	}
}
