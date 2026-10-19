package org.finmath.classroom.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.ClassroomConfig.Student;
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
	void testPreassignedStudentCanAcceptThroughGithubAndRemembersExactlyOneRepository() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		User account = new User(42, "alice", "Alice GitHub profile");
		when(github.authorizationUrl(anyString(), anyString(), anyString())).thenReturn("https://github.com/login/oauth/authorize");
		when(github.authenticate(anyString(), anyString(), anyString())).thenReturn(account);
		when(github.user("alice")).thenReturn(account);
		AtomicReference<Repository> createdRepository = new AtomicReference<>();
		when(github.create(eq("org/template"), eq("org"), anyString())).thenAnswer(invocation -> {
			String name = invocation.getArgument(2);
			assertTrue(name.matches("term-task-[0-9a-f]{8}-alice"));
			Repository repository = new Repository(101, "org/" + name, true, false, false, "main");
			createdRepository.set(repository);
			return repository;
		});
		when(github.repository(anyString())).thenAnswer(invocation -> {
			String fullName = invocation.getArgument(0);
			if(fullName.equals("org/template")) return new Repository(1, fullName, true, false, true, "main");
			Repository repository = createdRepository.get();
			assertNotNull(repository);
			assertEquals(repository.fullName(), fullName);
			return repository;
		});
		when(github.invite(anyString(), eq("alice"))).thenReturn(true);
		Lecture lecture = ClassroomConfig.load(data).lecture("lecture");
		store.importConfiguration(new ClassroomConfig(List.of(new Lecture(lecture.id(), lecture.title(), lecture.organization(),
				lecture.staffTeam(), lecture.registrationKey(), List.of(new Student("roster-alice", "Alice Roster", "alice"),
						new Student("roster-other", "Other private roster name", null)), lecture.assignments()))), github);
		assertEquals(0, store.list(null, null).size());

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
				.andExpect(content().string(containsString("value='assignment-key'")))
				.andExpect(content().string(containsString("name='name' value='Alice Roster'")))
				.andExpect(content().string(not(containsString("Alice GitHub profile"))))
				.andExpect(content().string(not(containsString("Other private roster name"))));
		assertEquals(0, store.list(null, null).size());
		mvc.perform(post("/accept/lecture/task").session(session).param("csrf", csrf).param("name", "Alice Example")
				.param("lectureKey", "lecture-key").param("assignmentKey", "assignment-key").param("declaration", "true"))
				.andExpect(status().isSeeOther());
		Repository repository = createdRepository.get();
		assertNotNull(repository);
		mvc.perform(get("/accept/lecture/task").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString(repository.url())))
				.andExpect(content().string(not(containsString("name='lectureKey'"))))
				.andExpect(content().string(not(containsString("name='declaration'"))))
				.andExpect(content().string(containsString("Refresh access status")));
		assertEquals("invitation_pending", store.list(null, null).get(0).status());
		assertEquals("roster-alice", store.list(null, null).get(0).studentId());
		assertEquals("Alice Example", store.list(null, null).get(0).name());
		assertEquals(2, store.students("lecture").size());
		assertEquals("Alice Roster", store.students("lecture").stream().filter(student -> student.get("id").equals("roster-alice"))
				.findFirst().orElseThrow().get("name"));
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
		EnrollmentStore reopened = new EnrollmentStore(data.resolve("classroom.db"), false);
		assertEquals(repository.fullName(), reopened.list(null, null).get(0).repository());
		assertEquals(repository.id(), reopened.list(null, null).get(0).repositoryId());
		verify(github, times(2)).grantStaff("org", "staff", repository.fullName());
		verify(github, times(2)).invite(repository.fullName(), "alice");
		verify(github, times(1)).create(anyString(), anyString(), anyString());
	}
}
