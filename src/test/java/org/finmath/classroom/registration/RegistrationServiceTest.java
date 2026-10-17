package org.finmath.classroom.registration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.ClassroomConfig.Student;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubGateway.Repository;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistrationServiceTest {

	@TempDir Path directory;
	private final Instant now = Instant.parse("2026-10-01T10:00:00Z");
	private final User alice = new User(42, "alice");
	private final Identity self = new Identity("self", "42", "Alice Example");
	private final FakeGitHub github = new FakeGitHub();
	private ClassroomConfig config;
	private EnrollmentStore store;
	private RegistrationService service;

	@BeforeEach void setUp() throws IOException {
		config = configuration(List.of(), "template", now.plusSeconds(3600));
		store = new EnrollmentStore(directory.resolve("classroom.db"), true);
		store.importConfiguration(config, github);
		service = new RegistrationService(config, store, github, Clock.fixed(now, ZoneOffset.UTC));
	}

	private ClassroomConfig configuration(List<Student> students, String template, Instant deadline) {
		Assignment assignment = new Assignment("exercise", "Exercise", "org/" + template, "term-exercise", "main", deadline, "assignment-secret", "course", "resource");
		return new ClassroomConfig(List.of(new Lecture("lecture", "Lecture", "org", "staff", "lecture-secret", students, List.of(assignment))));
	}

	private Enrollment accept() throws IOException {
		return service.accept("lecture", "exercise", self, alice, "lecture-secret", "assignment-secret", true);
	}

	@Test void testAcceptancePersistsDeclarationAndResumesAfterRestart() throws Exception {
		Enrollment enrollment = accept();
		assertEquals("invitation_pending", enrollment.status());
		assertEquals("https://github.com/org/term-exercise-42", enrollment.repositoryUrl());
		assertEquals(101L, enrollment.repositoryId());
		assertEquals(List.of("create", "staff", "invite"), github.operations);
		assertEquals("self", enrollment.identitySource());
		github.pending = false;
		EnrollmentStore reopened = new EnrollmentStore(directory.resolve("classroom.db"), false);
		service = new RegistrationService(config, reopened, github, Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC));
		Enrollment repeated = accept();
		assertEquals(enrollment.studentId(), repeated.studentId());
		assertEquals(enrollment.acceptedAt(), repeated.acceptedAt());
		assertEquals("active", repeated.status());
		assertEquals(1, github.creations);
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT * FROM enrollments")) {
			assertTrue(row.next());
			assertEquals(RegistrationService.DECLARATION, row.getString("declaration"));
			assertEquals("1", row.getString("declaration_version"));
			assertEquals(self.name(), row.getString("accepted_name"));
			assertFalse(row.next());
		}
	}

	@Test void testIncorrectKeysAndMissingDeclarationNeverProvision() {
		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", self, alice, "wrong", "assignment-secret", true));
		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", self, alice, "lecture-secret", "wrong", true));
		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", self, alice, "lecture-secret", "assignment-secret", false));
		assertEquals(0, github.creations);
	}

	@Test void testPartialInvitationFailureUsesRecordedRepository() throws Exception {
		github.failInvitation = true;
		assertThrows(IOException.class, this::accept);
		Enrollment failed = store.list(null, null).get(0);
		assertEquals("retryable", failed.status());
		assertEquals(101L, failed.repositoryId());
		github.failInvitation = false;
		assertEquals("invitation_pending", accept().status());
		assertEquals(1, github.creations);
	}

	@Test void testLostCreationResponseRequiresExplicitReconciliation() throws Exception {
		github.loseCreationResponse = true;
		assertThrows(IOException.class, this::accept);
		Enrollment uncertain = store.list(null, null).get(0);
		assertEquals("needs_attention", uncertain.status());
		assertNull(uncertain.repositoryId());
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals(1, github.creations);
		assertThrows(IllegalArgumentException.class, () -> service.reconcile("lecture", "exercise", uncertain.studentId(), 999));
		service.reconcile("lecture", "exercise", uncertain.studentId(), 101);
		assertEquals("invitation_pending", accept().status());
		assertEquals(1, github.creations);
	}

	@Test void testExistingSameNameRepositoryIsNeverAdopted() throws Exception {
		github.existingCollision = true;
		assertThrows(IOException.class, this::accept);
		assertThrows(IllegalArgumentException.class, this::accept);
		assertNull(store.list(null, null).get(0).repositoryId());
		assertFalse(github.operations.contains("invite"));
	}

	@Test void testChangedRepositoryIdAndPrivacyBlockFurtherGrants() throws Exception {
		Enrollment enrollment = accept();
		github.operations.clear();
		github.created = new Repository(999, enrollment.repository(), false, false, false, "main");
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals("needs_attention", store.list(null, null).get(0).status());
		assertTrue(github.operations.isEmpty());
		assertThrows(IllegalArgumentException.class, () -> service.reconcile("lecture", "exercise", enrollment.studentId(), 999));
	}

	@Test void testRecycledGithubLoginCannotClaimPinnedAccount() throws Exception {
		accept();
		github.current = new User(77, "alice");
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals(1, github.creations);
	}

	@Test void testMoodleIdentityCannotSwitchGithubAccounts() throws Exception {
		Identity moodle = new Identity("moodle:https://moodle.example", "student-1", "Alice Example");
		Enrollment enrollment = service.accept("lecture", "exercise", moodle, alice, null, null, true);
		assertEquals("moodle:https://moodle.example", enrollment.identitySource());
		github.current = new User(77, "bob");
		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", moodle, github.current, null, null, true));
		github.current = alice;
		Identity other = new Identity("moodle:https://moodle.example", "student-2", "Another Student");
		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", other, alice, null, null, true));
	}

	@Test void testFallbackCanBeUpgradedToMoodleWithoutDuplicateEnrollment() throws Exception {
		Enrollment original = accept();
		Identity moodle = new Identity("moodle:https://moodle.example", "student-1", "Alice Example");
		Enrollment upgraded = service.accept("lecture", "exercise", moodle, alice, null, null, true);
		assertEquals(original.studentId(), upgraded.studentId());
		assertEquals("moodle:https://moodle.example", store.students(null).get(0).get("identity_source"));
		// The original acceptance remains explicitly self-declared even after identity linking.
		assertEquals("self", upgraded.identitySource());
		accept();
		assertEquals("moodle:https://moodle.example", store.students(null).get(0).get("identity_source"));
		assertEquals(1, github.creations);
	}

	@Test void testSelfDeclaredNameDoesNotClaimUnassignedRosterEntry() throws Exception {
		ClassroomConfig roster = configuration(List.of(new Student("official-student", self.name(), null)), "template", now.plusSeconds(3600));
		store.importConfiguration(roster, github);
		Enrollment enrollment = accept();
		assertNotEquals("official-student", enrollment.studentId());
		assertEquals(2, store.students(null).size());
	}

	@Test void testPreassignedRosterAccountUsesPinnedIdentity() throws Exception {
		ClassroomConfig roster = configuration(List.of(new Student("official-student", "Alice", "alice")), "template", now.plusSeconds(3600));
		store.importConfiguration(roster, github);
		assertEquals("official-student", accept().studentId());
		github.current = new User(77, "alice");
		assertThrows(IllegalArgumentException.class, () -> store.importConfiguration(roster, github));
		assertEquals(42L, ((Number)store.students(null).get(0).get("github_id")).longValue());
	}

	@Test void testDuplicateNamesCreateSeparateStudents() throws Exception {
		Enrollment first = accept();
		github.current = new User(77, "bob");
		Enrollment second = service.accept("lecture", "exercise", new Identity("self", "77", self.name()), github.current, "lecture-secret", "assignment-secret", true);
		assertNotEquals(first.studentId(), second.studentId());
		assertNotEquals(first.repository(), second.repository());
	}

	@Test void testDeadlineAndTemplateChecksPrecedeCreation() throws Exception {
		service = new RegistrationService(config, store, github, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals(0, github.creations);
		service = new RegistrationService(config, store, github, Clock.fixed(now, ZoneOffset.UTC));
		github.validTemplate = false;
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals("retryable", store.list(null, null).get(0).status());
		assertEquals(0, github.creations);
	}

	@Test void testChangedAssignmentSettingsRequireReview() throws Exception {
		ClassroomConfig changed = configuration(List.of(), "different-template", now.plusSeconds(3600));
		assertThrows(IllegalArgumentException.class, () -> store.importConfiguration(changed, github));
		assertThrows(IllegalArgumentException.class, () -> store.verifyConfiguration(changed));
		store.verifyConfiguration(config);
		service = new RegistrationService(changed, store, github);
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals(0, github.creations);
	}

	@Test void testConcurrentAcceptanceHasSingleCreationAndShortTransactions() throws Exception {
		github.creationStarted = new CountDownLatch(1);
		github.continueCreation = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Enrollment> first = executor.submit(this::accept);
			assertTrue(github.creationStarted.await(5, TimeUnit.SECONDS));
			assertEquals(1, EnrollmentStore.readOnly(directory.resolve("classroom.db")).list(null, null).size());
			RegistrationService second = new RegistrationService(config, new EnrollmentStore(directory.resolve("classroom.db"), false), github, Clock.fixed(now, ZoneOffset.UTC));
			assertThrows(IllegalArgumentException.class, () -> second.accept("lecture", "exercise", self, alice, "lecture-secret", "assignment-secret", true));
			github.continueCreation.countDown();
			assertEquals("invitation_pending", first.get(5, TimeUnit.SECONDS).status());
			assertEquals(1, github.creations);
		} finally {
			github.continueCreation.countDown();
			executor.shutdownNow();
		}
	}

	@Test void testCrashReservationDoesNotAutoRecreateOnRestart() throws Exception {
		store.reserve(config.lecture("lecture"), config.assignment("lecture", "exercise"), self, alice, now);
		service = new RegistrationService(config, new EnrollmentStore(directory.resolve("classroom.db"), false), github, Clock.fixed(now, ZoneOffset.UTC));
		assertThrows(IllegalArgumentException.class, this::accept);
		assertEquals(0, github.creations);
		assertTrue(store.list(null, null).get(0).problem().contains("instructor review"));
	}

	@Test void testReadOnlyOpenCannotCreateOrWriteAndRejectsPythonSchema() throws Exception {
		Path absent = directory.resolve("missing.db");
		assertThrows(IllegalArgumentException.class, () -> EnrollmentStore.readOnly(absent));
		assertFalse(Files.exists(absent));
		assertThrows(IOException.class, () -> EnrollmentStore.readOnly(directory.resolve("classroom.db")).importConfiguration(config, github));
		Path python = directory.resolve("python.db");
		Files.createFile(python, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + python); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE roster (id TEXT)");
		}
		assertThrows(IOException.class, () -> new EnrollmentStore(python, true));
	}

	private static class FakeGitHub implements GitHubGateway {
		User current = new User(42, "alice");
		Repository created;
		boolean validTemplate = true;
		boolean pending = true;
		boolean failInvitation;
		boolean loseCreationResponse;
		boolean existingCollision;
		int creations;
		CountDownLatch creationStarted;
		CountDownLatch continueCreation;
		final List<String> operations = new java.util.ArrayList<>();

		@Override public User user(String login) { return current; }
		@Override public Repository repository(String fullName) {
			return fullName.equals("org/template") ? new Repository(1, fullName, true, false, validTemplate, "main") : created;
		}
		@Override public Repository create(String template, String organization, String name) throws IOException {
			creations++;
			operations.add("create");
			if(existingCollision) throw new IOException("HTTP 422");
			created = new Repository(100 + creations, organization + "/" + name, true, false, false, "main");
			if(creationStarted != null) {
				creationStarted.countDown();
				try {
					if(!continueCreation.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout");
				} catch(InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IOException(exception);
				}
			}
			if(loseCreationResponse) throw new IOException("Lost response after GitHub created repository");
			return created;
		}
		@Override public void grantStaff(String organization, String team, String repository) { operations.add("staff"); }
		@Override public boolean invite(String repository, String login) throws IOException {
			operations.add("invite");
			if(failInvitation) throw new IOException("Invitation failed");
			return pending;
		}
		@Override public User authenticate(String code, String redirectUri, String verifier) { return current; }
		@Override public String authorizationUrl(String redirectUri, String state, String challenge) { return "https://github.com/login/oauth/authorize"; }
	}
}
