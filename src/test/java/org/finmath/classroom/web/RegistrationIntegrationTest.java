package org.finmath.classroom.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.EnrollmentStore;
import org.finmath.classroom.registration.GitHubGateway;
import org.finmath.classroom.registration.GitHubGateway.Repository;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Exercises configured Spring wiring, OAuth/session flow, actual SQLite persistence and a fake GitHub boundary. */
@SpringBootTest
class RegistrationIntegrationTest {
	private static Path data;
	@Autowired WebApplicationContext context;
	@Autowired EnrollmentStore store;
	@MockitoBean GitHubGateway github;

	@DynamicPropertySource
	static void configuration(DynamicPropertyRegistry properties) throws IOException {
		data = Files.createTempDirectory("classroom-registration-test-");
		Path lecture = Files.createDirectories(data.resolve("lectures/lecture/assignments/task"));
		Files.writeString(data.resolve("lectures/lecture/lecture.json"), """
				{"title":"Lecture","organization":"org","staffTeam":"staff","registrationKey":"lecture-key"}
				""");
		Files.writeString(lecture.resolve("assignment.json"), """
				{"title":"Task","template":"org/template","repositoryPrefix":"term-task","registrationKey":"assignment-key"}
				""");
		new EnrollmentStore(data.resolve("classroom.db"), true).importConfiguration(ClassroomConfig.load(data), null);
		properties.add("classroom.data", () -> data.toString());
		properties.add("classroom.public-url", () -> "https://class.example");
		properties.add("classroom.moodle.issuer", () -> "");
	}

	@AfterAll
	static void cleanup() throws IOException {
		if(data != null) {
			try(Stream<Path> paths = Files.walk(data)) {
				for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
	}

	@Test
	void testBrowserAcceptanceCreatesAndRemembersExactlyOneRepository() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		User account = new User(42, "alice");
		when(github.authorizationUrl(anyString(), anyString(), anyString())).thenReturn("https://github.com/login/oauth/authorize");
		when(github.authenticate(anyString(), anyString(), anyString())).thenReturn(account);
		when(github.user("alice")).thenReturn(account);
		when(github.repository("org/template")).thenReturn(new Repository(1, "org/template", true, false, true, "main"));
		Repository repository = new Repository(101, "org/term-task-alice-42", true, false, false, "main");
		when(github.create("org/template", "org", "term-task-alice-42")).thenReturn(repository);
		when(github.repository("org/term-task-alice-42")).thenReturn(repository);
		when(github.invite("org/term-task-alice-42", "alice")).thenReturn(true);

		mvc.perform(get("/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("registration"));
		mvc.perform(get("/status")).andExpect(jsonPath("$.enrollmentAvailable").value(true)).andExpect(jsonPath("$.finalGradingAvailable").value(false));
		MvcResult page = mvc.perform(get("/accept/lecture/task").param("lectureKey", "lecture-key").param("assignmentKey", "assignment-key"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location", "/accept/lecture/task")).andReturn();
		MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk());
		String csrf = (String) session.getAttribute("csrf");
		mvc.perform(post("/auth/github/start").session(session).param("csrf", csrf).param("lectureId", "lecture").param("assignmentId", "task"))
				.andExpect(status().isSeeOther());
		ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
		verify(github).authorizationUrl(eq("https://class.example/auth/github/callback"), state.capture(), anyString());
		mvc.perform(get("/auth/github/callback").session(session).param("state", state.getValue()).param("code", "test-code")).andExpect(status().isSeeOther());
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("value='lecture-key'")))
				.andExpect(content().string(containsString("value='assignment-key'")));
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", csrf).param("name", "Alice Example")
				.param("lectureKey", "lecture-key").param("assignmentKey", "assignment-key").param("declaration", "true"))
				.andExpect(status().isSeeOther());
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString(repository.url())))
				.andExpect(content().string(not(containsString("name='lectureKey'"))))
				.andExpect(content().string(not(containsString("name='declaration'"))))
				.andExpect(content().string(containsString("Refresh access status")));
		assertEquals("invitation_pending", store.list(null, null).get(0).status());
		when(github.invite(repository.fullName(), "alice")).thenReturn(false);
		mvc.perform(post("/accept/lecture/task/retry").session(session).param("csrf", csrf))
				.andExpect(status().isSeeOther());
		MockHttpSession returning = new MockHttpSession();
		returning.setAttribute("github", account);
		mvc.perform(get("/accept/lecture/task").session(returning)).andExpect(status().isOk())
				.andExpect(content().string(containsString(repository.url())))
				.andExpect(content().string(not(containsString("name='name'"))))
				.andExpect(content().string(not(containsString("name='lectureKey'"))))
				.andExpect(content().string(not(containsString("name='declaration'"))))
				.andExpect(content().string(not(containsString("Refresh access status"))));
		assertEquals(1, store.list(null, null).size());
		assertEquals("active", store.list(null, null).get(0).status());
		verify(github, times(1)).create(anyString(), anyString(), anyString());
	}
}
