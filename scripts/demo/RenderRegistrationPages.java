package org.finmath.classroom.registration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.GitHubGateway.Repository;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.web.RegistrationController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/** Exports the real registration UI with disposable, fictional data and no network adapter. */
public final class RenderRegistrationPages {

	public static void main(String[] args) throws Exception {
		Path output = Path.of(args[0]);
		Files.createDirectories(output);
		String lectureId = "numerical-methods-demo";
		String assignmentId = "quadratic-equation-demo";
		String lectureKey = "public-numerical-methods-demo";
		String assignmentKey = "public-quadratic-equation-demo";
		ClassroomConfig config = new ClassroomConfig(List.of(new Lecture(lectureId, "Numerical methods demo",
				"example-classroom", null, lectureKey, List.of(), List.of(new Assignment(assignmentId,
						"Quadratic Equation Solver", "example-classroom/quadratic-equation-template",
						"numerical-methods-quadraticequation-demo", "main", null, assignmentKey, null, null)))));
		EnrollmentStore store = new EnrollmentStore(output.resolve("fixture.db"), true, () -> 0x1234abcd);
		store.importConfiguration(config, null);
		User alex = new User(42, "alex-example", "Alex Example");
		GitHubGateway github = new GitHubGateway() {
			private Repository created;
			public User user(String login) { return alex; }
			public Repository repository(String name) {
				return created != null && created.fullName().equals(name) ? created
						: new Repository(100, name, false, false, true, "main");
			}
			public Repository create(String template, String organization, String name) {
				if(created != null) throw new IllegalStateException("Fixture attempted a second repository");
				return created = new Repository(101, organization + "/" + name, true, false, false, "main");
			}
			public void grantStaff(String organization, String team, String repository) { }
			public boolean invite(String repository, String login) { return false; }
			public User authenticate(String code, String redirectUri, String verifier) {
				throw new UnsupportedOperationException("Screenshot fixtures do not perform OAuth");
			}
			public String authorizationUrl(String redirectUri, String state, String challenge) {
				throw new UnsupportedOperationException("Screenshot fixtures do not perform OAuth");
			}
		};
		RegistrationService service = new RegistrationService(config, store, github,
				Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC));
		RegistrationController controller = new RegistrationController(service, github, "https://classroom.example", Optional.empty());
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = new MockHttpServletRequest();
		Files.writeString(output.resolve("demo-registration.html"), (String) controller
				.assignment(lectureId, assignmentId, null, null, session, request).getBody());
		session.setAttribute("github", alex);
		request.addParameter("lectureKey", lectureKey);
		request.addParameter("assignmentKey", assignmentKey);
		controller.assignment(lectureId, assignmentId, lectureKey, assignmentKey, session, request);
		Files.writeString(output.resolve("demo-confirmation.html"), (String) controller
				.assignment(lectureId, assignmentId, null, null, session, request).getBody());
		controller.accept(lectureId, assignmentId, (String) session.getAttribute("csrf"), alex.name(),
				lectureKey, assignmentKey, true, session);
		if(store.list(lectureId, assignmentId).size() != 1
				|| !store.list(lectureId, assignmentId).get(0).status().equals("active")) {
			throw new IllegalStateException("Fixture did not reach the expected active enrollment");
		}
		Files.writeString(output.resolve("demo-repository.html"), (String) controller
				.assignment(lectureId, assignmentId, null, null, session, request).getBody());
		System.out.println("Rendered three registration pages with fictional local data.");
	}
}
