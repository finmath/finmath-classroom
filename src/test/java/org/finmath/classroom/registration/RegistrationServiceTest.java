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
import java.util.Map;
import java.util.Set;
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

	private Enrollment retry() throws IOException {
		return service.retry("lecture", "exercise", github.current);
	}

	@Test void testAcceptancePersistsDeclarationAndResumesAfterRestart() throws Exception {
		Enrollment enrollment = accept();
		assertEquals("invitation_pending", enrollment.status());
		assertEquals("https://github.com/org/term-exercise-alice-42", enrollment.repositoryUrl());
		assertEquals(101L, enrollment.repositoryId());
		assertEquals(List.of("create", "staff", "invite"), github.operations);
		assertEquals("self", enrollment.identitySource());
		github.pending = false;
		EnrollmentStore reopened = new EnrollmentStore(directory.resolve("classroom.db"), false);
		service = new RegistrationService(config, reopened, github, Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC));
		Enrollment repeated = retry();
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
		Enrollment retried = retry();
		assertEquals("invitation_pending", retried.status());
		assertEquals(failed.acceptedAt(), retried.acceptedAt());
		assertEquals(failed.name(), retried.name());
		assertEquals(failed.identitySource(), retried.identitySource());
		assertEquals(1, github.creations);
	}

	@Test void testNewRepositoryNameUsesLowercaseLoginAndNumericId() throws Exception {
		github.current = new User(42, "Alice");
		assertEquals("org/term-exercise-alice-42", accept().repository());
	}

	@Test void testLegacyRepositoryNameSurvivesRetryAndUsernameChange() throws Exception {
		Enrollment original = accept();
		String legacyRepository = "org/term-exercise-42";
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); Statement statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE enrollments SET repository='" + legacyRepository + "'");
		}
		github.created = new Repository(101, legacyRepository, true, false, false, "main");
		github.current = new User(42, "renamed-alice");
		Enrollment retried = retry();
		assertEquals(legacyRepository, retried.repository());
		assertEquals(original.repositoryId(), retried.repositoryId());
		assertEquals(original.acceptedAt(), retried.acceptedAt());
		assertEquals("renamed-alice", retried.githubLogin());
		assertEquals("renamed-alice", github.invitedLogin);
		assertEquals(1, github.creations);
	}

	@Test void testRepositoryNameLengthCheckedOnlyForNewAcceptance() throws Exception {
		Assignment assignment = new Assignment("long", "Long prefix", "org/template", "p".repeat(80), "main", now.plusSeconds(3600), "assignment-secret", null, null);
		ClassroomConfig longPrefix = new ClassroomConfig(List.of(new Lecture("lecture", "Lecture", "org", "staff", "lecture-secret", List.of(), List.of(assignment))));
		store.importConfiguration(longPrefix, github);
		service = new RegistrationService(longPrefix, store, github, Clock.fixed(now, ZoneOffset.UTC));
		github.current = new User(42, "a".repeat(16));
		Enrollment original = service.accept("lecture", "long", self, github.current, "lecture-secret", "assignment-secret", true);
		assertEquals(100, original.repository().substring("org/".length()).length());
		github.current = new User(42, "a".repeat(17));
		assertEquals(original.repository(), service.retry("lecture", "long", github.current).repository());
		assertEquals(original.repository(), service.accept("lecture", "long", self, github.current, "lecture-secret", "assignment-secret", true).repository());
		github.current = new User(77, "b".repeat(17));
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "long", new Identity("self", "77", "Bob"), github.current, "lecture-secret", "assignment-secret", true));
		assertTrue(failure.getMessage().contains("100 characters"));
		assertEquals(1, store.list(null, null).size());
		assertEquals(1, store.students(null).size());
		assertEquals(1, github.creations);
	}

	@Test void testRetryCannotCreateAcceptanceOrUseAnotherAccount() throws Exception {
		assertThrows(IllegalArgumentException.class, this::retry);
		assertTrue(store.list(null, null).isEmpty());
		assertTrue(store.students(null).isEmpty());
		store.importConfiguration(configuration(List.of(new Student("official-student", "Alice", "alice")), "template", now.plusSeconds(3600)), github);
		assertThrows(IllegalArgumentException.class, this::retry);
		assertTrue(store.list(null, null).isEmpty());
		Enrollment original = accept();
		github.operations.clear();
		github.current = new User(77, "bob");
		assertThrows(IllegalArgumentException.class, this::retry);
		assertEquals(List.of(original), store.list(null, null));
		assertTrue(github.operations.isEmpty());
		github.current = new User(77, "alice");
		assertThrows(IllegalArgumentException.class, () -> service.retry("lecture", "exercise", alice));
		assertThrows(IllegalArgumentException.class, () -> service.retry("lecture", "exercise", null));
		assertTrue(github.operations.isEmpty());
	}

	@Test void testRetryPreservesMoodleIdentityAndOriginalDeclaration() throws Exception {
		Identity moodle = new Identity("moodle:https://moodle.example", "student-1", "Alice Example");
		github.failInvitation = true;
		assertThrows(IOException.class, () -> service.accept("lecture", "exercise", moodle, alice, null, null, true));
		github.failInvitation = false;
		github.current = new User(42, "renamed-alice");
		Enrollment retried = retry();
		assertEquals(moodle.source(), retried.identitySource());
		assertEquals(moodle.name(), retried.name());
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT e.identity_source,e.identity_subject,e.accepted_at,e.declaration_version,e.declaration,s.identity_source AS student_source,s.identity_subject AS student_subject FROM enrollments e JOIN students s ON e.student_id=s.id")) {
			assertTrue(row.next());
			assertEquals(moodle.source(), row.getString("identity_source"));
			assertEquals(moodle.subject(), row.getString("identity_subject"));
			assertEquals(moodle.source(), row.getString("student_source"));
			assertEquals(moodle.subject(), row.getString("student_subject"));
			assertEquals(now.toString(), row.getString("accepted_at"));
			assertEquals(RegistrationService.DECLARATION_VERSION, row.getString("declaration_version"));
			assertEquals(RegistrationService.DECLARATION, row.getString("declaration"));
		}
	}

	@Test void testActiveRetryHasNoProvisioningSideEffects() throws Exception {
		github.pending = false;
		Enrollment original = accept();
		github.operations.clear();
		assertEquals(original, retry());
		assertTrue(github.operations.isEmpty());
		assertNull(store.list(null, null).get(0).problem());
	}

	@Test void testRetryChecksDeadlineAndFrozenAssignmentSettings() throws Exception {
		accept();
		github.operations.clear();
		service = new RegistrationService(config, store, github, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
		assertThrows(IllegalArgumentException.class, this::retry);
		ClassroomConfig changed = configuration(List.of(), "different-template", now.plusSeconds(3600));
		service = new RegistrationService(changed, store, github, Clock.fixed(now, ZoneOffset.UTC));
		assertThrows(IllegalArgumentException.class, this::retry);
		assertEquals("invitation_pending", store.list(null, null).get(0).status());
		assertTrue(github.operations.isEmpty());
	}

	@Test void testLostCreationResponseRequiresExplicitReconciliation() throws Exception {
		github.loseCreationResponse = true;
		assertThrows(IOException.class, this::accept);
		Enrollment uncertain = store.list(null, null).get(0);
		assertEquals("needs_attention", uncertain.status());
		assertNull(uncertain.repositoryId());
		assertThrows(IllegalArgumentException.class, this::accept);
		assertThrows(IllegalArgumentException.class, this::retry);
		assertEquals(1, github.creations);
		assertThrows(IllegalArgumentException.class, () -> service.reconcile("lecture", "exercise", uncertain.studentId(), 999));
		service.reconcile("lecture", "exercise", uncertain.studentId(), 101);
		assertEquals("invitation_pending", retry().status());
		assertEquals(1, github.creations);
	}

	@Test void testExistingSameNameRepositoryIsNeverAdopted() throws Exception {
		github.existingCollision = true;
		assertThrows(IOException.class, this::accept);
		assertThrows(IllegalArgumentException.class, this::retry);
		assertNull(store.list(null, null).get(0).repositoryId());
		assertFalse(github.operations.contains("invite"));
	}

	@Test void testChangedRepositoryIdAndPrivacyBlockFurtherGrants() throws Exception {
		Enrollment enrollment = accept();
		github.operations.clear();
		github.created = new Repository(999, enrollment.repository(), false, false, false, "main");
		assertThrows(IllegalArgumentException.class, this::retry);
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

	@Test void testPreassignedRosterWaitsForAcceptanceAndSurvivesGithubRename() throws Exception {
		String rosterName = "Alice Instructor Roster";
		ClassroomConfig roster = configuration(List.of(new Student("official-student", rosterName, "alice")), "template", now.plusSeconds(3600));
		store.importConfiguration(roster, github);
		service = new RegistrationService(roster, store, github, Clock.fixed(now, ZoneOffset.UTC));
		assertTrue(store.list("lecture", "exercise").isEmpty());
		assertNull(service.enrollment("lecture", "exercise", alice));
		assertEquals(1, store.students("lecture").size());
		Map<String, Object> imported = store.students("lecture").get(0);
		assertEquals("official-student", imported.get("id"));
		assertEquals(rosterName, imported.get("name"));
		assertEquals(42L, ((Number)imported.get("github_id")).longValue());
		assertEquals("roster", imported.get("identity_source"));
		assertEquals(0, github.creations);
		assertTrue(github.operations.isEmpty());

		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", self, alice, "wrong", "assignment-secret", true));
		assertThrows(IllegalArgumentException.class, () -> service.accept("lecture", "exercise", self, alice, "lecture-secret", "assignment-secret", false));
		assertTrue(store.list("lecture", "exercise").isEmpty());
		assertTrue(github.operations.isEmpty());

		github.current = new User(42, "renamed-alice");
		Enrollment enrollment = service.accept("lecture", "exercise", self, github.current, "lecture-secret", "assignment-secret", true);
		assertEquals("official-student", enrollment.studentId());
		assertEquals(self.name(), enrollment.name());
		assertEquals("invitation_pending", enrollment.status());
		assertEquals("org/term-exercise-renamed-alice-42", enrollment.repository());
		assertEquals(1, store.students("lecture").size());
		Map<String, Object> accepted = store.students("lecture").get(0);
		assertEquals(rosterName, accepted.get("name"));
		assertEquals(42L, ((Number)accepted.get("github_id")).longValue());
		assertEquals("renamed-alice", accepted.get("github_login"));
		assertEquals("self", accepted.get("identity_source"));
		assertEquals("renamed-alice", github.invitedLogin);
		assertEquals(1, github.creations);
		assertEquals(List.of("create", "staff", "invite"), github.operations);
	}

	@Test void testDifferentAccountCannotClaimPreassignedRosterByName() throws Exception {
		ClassroomConfig roster = configuration(List.of(new Student("official-student", self.name(), "alice")), "template", now.plusSeconds(3600));
		store.importConfiguration(roster, github);
		service = new RegistrationService(roster, store, github, Clock.fixed(now, ZoneOffset.UTC));
		github.current = new User(77, "bob");
		Enrollment enrollment = service.accept("lecture", "exercise", new Identity("self", "77", self.name()), github.current, "lecture-secret", "assignment-secret", true);
		assertNotEquals("official-student", enrollment.studentId());
		assertEquals(77L, enrollment.githubId());
		assertEquals(2, store.students("lecture").size());
		Map<String, Object> imported = store.students("lecture").stream().filter(student -> student.get("id").equals("official-student")).findFirst().orElseThrow();
		assertEquals(42L, ((Number)imported.get("github_id")).longValue());
		assertEquals("alice", imported.get("github_login"));
		assertEquals("roster", imported.get("identity_source"));
		assertNull(service.enrollment("lecture", "exercise", alice));
	}

	@Test void testNameSuggestionUsesPinnedRosterWithoutAccepting() throws Exception {
		ClassroomConfig roster = configuration(List.of(new Student("official-student", "Official Alice", "alice")), "template", now.plusSeconds(3600));
		store.importConfiguration(roster, github);
		service = new RegistrationService(roster, store, github, Clock.fixed(now, ZoneOffset.UTC));
		byte[] database = Files.readAllBytes(directory.resolve("classroom.db"));
		assertEquals("Official Alice", service.suggestedName("lecture", new User(42, "renamed-alice", "Public Alice")));
		assertTrue(store.list("lecture", "exercise").isEmpty());
		assertEquals(1, store.students("lecture").size());
		assertTrue(github.operations.isEmpty());
		assertArrayEquals(database, Files.readAllBytes(directory.resolve("classroom.db")));
	}

	@Test void testNameSuggestionsAreScopedToLectureAndNumericAccount() throws Exception {
		ClassroomConfig roster = configuration(List.of(new Student("official-student", "Official Alice", "alice")), "template", now.plusSeconds(3600));
		Lecture other = new Lecture("other-lecture", "Other lecture", "org", "staff", "lecture-secret", List.of(), List.of(config.assignment("lecture", "exercise")));
		ClassroomConfig lectures = new ClassroomConfig(List.of(roster.lecture("lecture"), other));
		store.importConfiguration(lectures, github);
		service = new RegistrationService(lectures, store, github, Clock.fixed(now, ZoneOffset.UTC));
		assertEquals("Public Alice", service.suggestedName("other-lecture", new User(42, "alice", "Public Alice")));
		assertEquals("Public Bob", service.suggestedName("lecture", new User(77, "alice", "Public Bob")));
		assertEquals("", service.suggestedName("lecture", new User(77, "alice")));
		assertTrue(store.list(null, null).isEmpty());
		assertEquals(1, store.students(null).size());
	}

	@Test void testNameSuggestionRetainsExistingSelfDeclaredName() throws Exception {
		accept();
		List<Map<String, Object>> students = store.students("lecture");
		List<Enrollment> enrollments = store.list("lecture", "exercise");
		assertEquals(self.name(), service.suggestedName("lecture", new User(42, "renamed-alice", "Changed profile name")));
		assertEquals(students, store.students("lecture"));
		assertEquals(enrollments, store.list("lecture", "exercise"));
		assertEquals(1, github.creations);
	}

	@Test void testNameSuggestionFallsBackToUsableProfileOrEmptyInput() throws Exception {
		assertEquals("Public Alice", service.suggestedName("lecture", new User(42, "alice", "Public Alice")));
		assertEquals("", service.suggestedName("lecture", alice));
		assertEquals("", service.suggestedName("lecture", new User(42, "alice", "   ")));
		assertEquals("", service.suggestedName("lecture", new User(42, "alice", "a".repeat(201))));
		ClassroomConfig roster = configuration(List.of(new Student("official-student", "r".repeat(201), "alice")), "template", now.plusSeconds(3600));
		store.importConfiguration(roster, github);
		service = new RegistrationService(roster, store, github, Clock.fixed(now, ZoneOffset.UTC));
		assertEquals("Public Alice", service.suggestedName("lecture", new User(42, "alice", "Public Alice")));
		assertEquals("", service.suggestedName("lecture", alice));
		assertThrows(IllegalArgumentException.class, () -> service.suggestedName("lecture", null));
		assertThrows(IllegalArgumentException.class, () -> service.suggestedName("lecture", new User(0, "alice", "Public Alice")));
		assertTrue(store.list(null, null).isEmpty());
		assertTrue(github.operations.isEmpty());
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
			assertThrows(IllegalArgumentException.class, () -> second.retry("lecture", "exercise", alice));
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
		assertThrows(IllegalArgumentException.class, this::retry);
		assertEquals(0, github.creations);
		assertTrue(store.list(null, null).get(0).problem().contains("instructor review"));
	}

	@Test void testConcurrentRetriesAfterStaffFailureGrantAccessOnce() throws Exception {
		github.failStaff = true;
		assertThrows(IOException.class, this::accept);
		Enrollment failed = store.list(null, null).get(0);
		assertEquals("retryable", failed.status());
		github.failStaff = false;
		github.operations.clear();
		github.staffStarted = new CountDownLatch(1);
		github.continueStaff = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Enrollment> first = executor.submit(this::retry);
			assertTrue(github.staffStarted.await(5, TimeUnit.SECONDS));
			RegistrationService second = new RegistrationService(config, new EnrollmentStore(directory.resolve("classroom.db"), false), github, Clock.fixed(now, ZoneOffset.UTC));
			assertThrows(IllegalArgumentException.class, () -> second.retry("lecture", "exercise", alice));
			github.continueStaff.countDown();
			Enrollment retried = first.get(5, TimeUnit.SECONDS);
			assertEquals("invitation_pending", retried.status());
			assertEquals(failed.repository(), retried.repository());
			assertEquals(failed.acceptedAt(), retried.acceptedAt());
			assertEquals(List.of("staff", "invite"), github.operations);
			assertEquals(1, github.creations);
		} finally {
			github.continueStaff.countDown();
			executor.shutdownNow();
		}
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

	@Test void testDeleteRetainsStudentAndOtherAssignmentsAndPermitsFreshAcceptance() throws Exception {
		Enrollment selected = accept();
		Assignment exercise = config.assignment("lecture", "exercise");
		Assignment otherExercise = new Assignment("other-exercise", "Other exercise", "org/template", "term-other", "main", exercise.deadline(), "assignment-secret", null, null);
		Lecture lecture = new Lecture("lecture", "Lecture", "org", "staff", "lecture-secret", List.of(), List.of(exercise, otherExercise));
		Lecture otherLecture = new Lecture("other-lecture", "Other lecture", "other-org", "staff", "lecture-secret", List.of(new Student(selected.studentId(), "Alice", "alice")), List.of(exercise));
		store.importConfiguration(new ClassroomConfig(List.of(lecture, otherLecture)), github);
		Enrollment second = store.finish(store.reserve(lecture, otherExercise, self, alice, now), "retryable", null);
		Enrollment third = store.finish(store.reserve(otherLecture, exercise, self, alice, now), "retryable", null);
		List<Map<String, Object>> students = store.students(null);
		assertEquals(selected, store.delete("lecture", "exercise", selected.studentId()));
		assertEquals(Set.of(second, third), Set.copyOf(store.list(null, null)));
		assertEquals(students, store.students(null));
		assertNull(service.enrollment("lecture", "exercise", alice));
		service = new RegistrationService(config, store, github, Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC));
		Enrollment acceptedAgain = accept();
		assertEquals(selected.studentId(), acceptedAgain.studentId());
		assertEquals(now.plusSeconds(10).toString(), acceptedAgain.acceptedAt());
		assertEquals(2, github.creations);
	}

	@Test void testDeleteRequiresExactExistingSelectorsAndWritableStore() throws Exception {
		Enrollment enrollment = accept();
		byte[] database = Files.readAllBytes(directory.resolve("classroom.db"));
		assertThrows(IllegalArgumentException.class, () -> store.delete(null, "exercise", enrollment.studentId()));
		assertThrows(IllegalArgumentException.class, () -> store.delete("lecture", "", enrollment.studentId()));
		assertThrows(IllegalArgumentException.class, () -> store.delete("lecture", "exercise", " "));
		assertThrows(IllegalArgumentException.class, () -> store.delete("lecture", "exercise", "unknown"));
		assertThrows(IllegalArgumentException.class, () -> store.delete("other", "exercise", enrollment.studentId()));
		assertThrows(IOException.class, () -> EnrollmentStore.readOnly(directory.resolve("classroom.db")).delete("lecture", "exercise", enrollment.studentId()));
		assertEquals(List.of(enrollment), store.list(null, null));
		assertArrayEquals(database, Files.readAllBytes(directory.resolve("classroom.db")));
	}

	@Test void testDeleteRefusesBusyAndUnfinishedCreation() throws Exception {
		Enrollment reserved = store.reserve(config.lecture("lecture"), config.assignment("lecture", "exercise"), self, alice, now);
		assertThrows(IllegalArgumentException.class, () -> store.delete("lecture", "exercise", reserved.studentId()));
		assertEquals(1, store.list(null, null).size());
		store.finish(reserved, "creating", null);
		assertThrows(IllegalArgumentException.class, () -> store.delete("lecture", "exercise", reserved.studentId()));
		assertEquals("creating", store.list(null, null).get(0).status());
	}

	private static class FakeGitHub implements GitHubGateway {
		User current = new User(42, "alice");
		Repository created;
		boolean validTemplate = true;
		boolean pending = true;
		boolean failStaff;
		boolean failInvitation;
		boolean loseCreationResponse;
		boolean existingCollision;
		String invitedLogin;
		int creations;
		CountDownLatch creationStarted;
		CountDownLatch continueCreation;
		CountDownLatch staffStarted;
		CountDownLatch continueStaff;
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
		@Override public void grantStaff(String organization, String team, String repository) throws IOException {
			operations.add("staff");
			if(failStaff) throw new IOException("Staff grant failed");
			if(staffStarted != null) {
				staffStarted.countDown();
				try {
					if(!continueStaff.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout");
				} catch(InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IOException(exception);
				}
			}
		}
		@Override public boolean invite(String repository, String login) throws IOException {
			operations.add("invite");
			invitedLogin = login;
			if(failInvitation) throw new IOException("Invitation failed");
			return pending;
		}
		@Override public User authenticate(String code, String redirectUri, String verifier) { return current; }
		@Override public String authorizationUrl(String redirectUri, String state, String challenge) { return "https://github.com/login/oauth/authorize"; }
	}
}
