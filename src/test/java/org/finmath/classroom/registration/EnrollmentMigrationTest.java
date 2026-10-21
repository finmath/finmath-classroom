package org.finmath.classroom.registration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnrollmentMigrationTest {

	@TempDir Path directory;
	private Path database;
	private ClassroomConfig config;

	@BeforeEach void createPopulatedVersionOneDatabase() throws Exception {
		database = directory.resolve("classroom.db");
		Files.createFile(database);
		if(Files.getFileStore(database).supportsFileAttributeView("posix")) {
			Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-------"));
		}
		Assignment assignment = new Assignment("exercise", "Exercise", "org/template", "term-exercise", "main",
				Instant.parse("2026-10-01T11:00:00Z"), "assignment-secret", "course", "resource");
		config = new ClassroomConfig(List.of(new Lecture("lecture", "Lecture", "org", "staff", "lecture-secret", List.of(), List.of(assignment))));
		try(InputStream input = EnrollmentStore.class.getResourceAsStream("/registration/schema-v1.sql");
				Connection connection = connect(); Statement statement = connection.createStatement()) {
			String schema = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
			for(String sql : schema.split(";")) {
				if(!sql.isBlank()) statement.execute(sql);
			}
			statement.executeUpdate("INSERT INTO lectures VALUES ('lecture','Lecture')");
			try(PreparedStatement insert = connection.prepareStatement("INSERT INTO assignments VALUES ('lecture','exercise',?)")) {
				insert.setString(1, JsonFiles.MAPPER.writeValueAsString(List.of("org", "staff", "org/template", "term-exercise", "main",
						assignment.deadline().toString(), "course", "resource")));
				insert.executeUpdate();
			}
			statement.executeUpdate("INSERT INTO students VALUES ('lecture','student-id','Roster Alice',42,'alice','self','42')");
			try(PreparedStatement insert = connection.prepareStatement("""
					INSERT INTO enrollments
					(lecture_id,assignment_id,student_id,accepted_name,identity_source,identity_subject,
					 accepted_at,declaration_version,declaration,repository,repository_id,status,problem,busy)
					VALUES ('lecture','exercise','student-id','Accepted Alice','self','42',
					 '2026-10-01T10:00:00Z','1',?,'org/term-exercise-legacy-alice',101,'retryable','Original problem',0)
					""")) {
				insert.setString(1, RegistrationService.DECLARATION);
				insert.executeUpdate();
			}
		}
	}

	@Test void testReadOnlyAndServerOpenRejectVersionOneWithoutChangingBytes() throws Exception {
		byte[] original = Files.readAllBytes(database);
		assertThrows(IllegalArgumentException.class, () -> EnrollmentStore.readOnly(database));
		assertArrayEquals(original, Files.readAllBytes(database));
		assertThrows(IllegalArgumentException.class, () -> new EnrollmentStore(database, false));
		assertArrayEquals(original, Files.readAllBytes(database));
		assertVersion(1);
	}

	@Test void testExplicitImportMigrationPreservesLegacyEnrollmentAndIsIdempotent() throws Exception {
		EnrollmentStore migrated = new EnrollmentStore(database, true);
		migrated.importConfiguration(config, noGitHubCalls());
		assertVersion(2);
		Enrollment enrollment = migrated.list("lecture", "exercise").get(0);
		assertEquals("student-id", enrollment.studentId());
		assertEquals("Accepted Alice", enrollment.name());
		assertEquals(42, enrollment.githubId());
		assertEquals("alice", enrollment.githubLogin());
		assertEquals("self", enrollment.identitySource());
		assertEquals("2026-10-01T10:00:00Z", enrollment.acceptedAt());
		assertEquals(101L, enrollment.repositoryId());
		assertEquals("org/term-exercise-legacy-alice", enrollment.repository());
		assertEquals("https://github.com/org/term-exercise-legacy-alice", enrollment.repositoryUrl());
		assertEquals("retryable", enrollment.status());
		assertEquals("Original problem", enrollment.problem());
		assertNull(enrollment.starterCommit());
		assertFalse(enrollment.starterInitialized());
		assertEquals("Roster Alice", migrated.students("lecture").get(0).get("name"));
		try(Connection connection = connect(); Statement statement = connection.createStatement();
				ResultSet row = statement.executeQuery("SELECT identity_subject,declaration_version,declaration,busy FROM enrollments")) {
			assertTrue(row.next());
			assertEquals("42", row.getString("identity_subject"));
			assertEquals("1", row.getString("declaration_version"));
			assertEquals(RegistrationService.DECLARATION, row.getString("declaration"));
			assertEquals(0, row.getInt("busy"));
			assertFalse(row.next());
		}
		assertEquals(List.of(enrollment), EnrollmentStore.readOnly(database).list(null, null));
		EnrollmentStore reimported = new EnrollmentStore(database, true);
		reimported.importConfiguration(config, noGitHubCalls());
		reimported.verifyConfiguration(config);
		assertVersion(2);
		assertEquals(List.of(enrollment), reimported.list(null, null));
		assertEquals(migrated.students(null), reimported.students(null));
	}

	private Connection connect() throws Exception {
		return DriverManager.getConnection("jdbc:sqlite:" + database);
	}

	private void assertVersion(int expected) throws Exception {
		try(Connection connection = connect(); Statement statement = connection.createStatement();
				ResultSet row = statement.executeQuery("SELECT version FROM schema_version")) {
			assertTrue(row.next());
			assertEquals(expected, row.getInt(1));
			assertFalse(row.next());
		}
	}

	private static GitHubGateway noGitHubCalls() {
		return (GitHubGateway) Proxy.newProxyInstance(GitHubGateway.class.getClassLoader(), new Class<?>[]{GitHubGateway.class},
				(proxy, method, arguments) -> { throw new AssertionError("Migration must not contact GitHub: " + method.getName()); });
	}
}
