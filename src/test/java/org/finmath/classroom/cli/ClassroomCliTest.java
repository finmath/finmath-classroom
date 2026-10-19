package org.finmath.classroom.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.EnrollmentStore;
import org.finmath.classroom.feedback.FeedbackSource;
import org.finmath.classroom.feedback.FeedbackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

class ClassroomCliTest {
    @TempDir Path directory;

    private int run(String... args) {
        return ClassroomCli.run(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test void helpDoesNotStartAService() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, ClassroomCli.run(new String[0], new PrintStream(output), System.err));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("registration check"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Authoritative final grading is not implemented"));
    }

    @Test void invalidOptionsFailWithoutWrites() {
        assertEquals(2, run("enroll"));
        assertEquals(2, run("score", "--rubric", "missing"));
        assertEquals(2, run("workspace"));
        assertEquals(2, run("workspace", "--check", "--write"));
        assertEquals(2, run("workspace", "--check", "--check"));
    }

    @Test void feedbackCommandProducesJsonAndSummary() throws Exception {
        Path rubric = directory.resolve("rubric.json");
        Files.writeString(rubric, """
                {"tests":[{"class_name":"ExampleTest","method":"testExample","points":5}]}
                """);
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Files.writeString(reports.resolve("TEST-example.xml"), """
                <testsuite><testcase classname="ExampleTest" name="testExample"/></testsuite>
                """);
        Path output = directory.resolve("feedback.json");
        Path summary = directory.resolve("summary.md");
        assertEquals(0, run("score", "--rubric", rubric.toString(), "--reports", reports.toString(),
                "--output", output.toString(), "--summary", summary.toString()));
        var result = JsonFiles.read(output, 10000);
        assertEquals(5, result.get("score").intValue());
        assertFalse(result.get("authoritative").booleanValue());
        assertTrue(Files.readString(summary).contains("Feedback"));
        assertEquals(2, run("score", "--rubric", rubric.toString(), "--reports", reports.toString(), "--output", rubric.toString()));
    }

    @Test void trustedResultManifestToCsvEndToEnd() throws Exception {
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, """
                {"version":1,"assignment":"nm-2026-example","deadline":"2026-12-15T23:00:00Z",
                 "generated_at":"2026-12-16T00:00:00Z","submissions":[
                  {"student_id":"s1","github_username":"alice","repository":"qntlb/example-alice",
                   "commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","received_at":"2026-12-15T22:00:00Z","status":"submitted"},
                  {"student_id":"s2","github_username":"bob","repository":null,"commit":null,"received_at":null,"status":"not_enrolled"}]}
                """);
        Path results = Files.createDirectory(directory.resolve("results"));
        Files.writeString(results.resolve("alice.json"), """
                {"assignment":"nm-2026-example","repository":"qntlb/example-alice",
                 "commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"failed","score":15,"max_score":22}
                """);
        Path csv = directory.resolve("grades.csv");
        assertEquals(0, run("collect", "--manifest", manifest.toString(), "--results", results.toString(), "--output", csv.toString()));
        String text = Files.readString(csv);
        assertTrue(text.contains("failed,15,22"));
        assertTrue(text.contains("not_enrolled,,"));
        assertTrue(text.contains("received_at,status,score,max_score"));
    }

    @Test void feedbackArtifactCommandPreservesRunIdentityAndPartialScore() throws Exception {
        Path rubric = Files.writeString(directory.resolve("rubric.json"), """
                {"tests":[{"class_name":"ExampleTest","method":"basic","points":1},
                          {"class_name":"ExampleTest","method":"stability","points":3}]}
                """);
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Files.writeString(reports.resolve("TEST-example.xml"), """
                <testsuite><testcase classname="ExampleTest" name="basic"/>
                <testcase classname="ExampleTest" name="stability"><failure/></testcase></testsuite>
                """);
        Path context = Files.writeString(directory.resolve("context.json"), """
                {"repository_id":123,"repository":"example-org/assignment-student",
                 "commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ref":"refs/heads/main",
                 "run_id":456,"run_attempt":2,"event":"push",
                 "workflow_ref":"example-org/assignment-student/.github/workflows/classroom.yml@refs/heads/main",
                 "workflow_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
        Path output = directory.resolve("result.json");
        Path summary = directory.resolve("summary.md");
        assertEquals(1, run("feedback", "report", "--rubric", rubric.toString(), "--reports", reports.toString(),
                "--context", context.toString(), "--test-exit-code", "1", "--output", output.toString(),
                "--summary", summary.toString()));
        var result = JsonFiles.read(output, 10000);
        assertEquals("finmath-classroom-feedback", result.path("kind").asString());
        assertEquals(2, result.path("context").path("run_attempt").intValue());
        assertEquals(1, result.path("score").intValue());
        assertEquals(4, result.path("max_score").intValue());
        assertEquals(1, result.path("execution").path("exit_code").intValue());
        assertFalse(result.path("authoritative").booleanValue());
        assertTrue(Files.readString(summary).contains("1/4"));
        byte[] saved = Files.readAllBytes(output);
        assertEquals(2, run("feedback", "report", "--rubric", rubric.toString(), "--reports", reports.toString(),
                "--context", context.toString(), "--test-exit-code", "0", "--output", output.toString()));
        assertArrayEquals(saved, Files.readAllBytes(output));

        var selected = new ByteArrayOutputStream();
        assertEquals(0, ClassroomCli.run(new String[]{"feedback", "select", "--rubric", rubric.toString()},
                new PrintStream(selected), System.err));
        assertEquals("ExampleTest#basic,ExampleTest#stability", selected.toString(StandardCharsets.UTF_8).trim());
    }

    @Test void feedbackCommandsRejectUnknownCommandsAndMissingRequiredOptions() {
        assertEquals(2, run("feedback"));
        assertEquals(2, run("feedback", "sync"));
        assertEquals(2, run("feedback", "select"));
        assertEquals(2, run("feedback", "select", "--run", "echo ignored"));
        assertEquals(2, run("feedback", "report", "--rubric", "missing"));
    }

    @Test void feedbackListingDoesNotCreateACollectionDatabaseOrReadCredentials() throws Exception {
        createAcceptedRegistration("Alice");
        byte[] enrollmentBytes = Files.readAllBytes(directory.resolve("classroom.db"));
        var output = new ByteArrayOutputStream();
        assertEquals(0, ClassroomCli.run(new String[]{"feedback", "list", "--data", directory.toString(),
                "--lecture", "lecture", "--assignment", "exercise"}, new PrintStream(output), System.err));
        var listing = JsonFiles.MAPPER.readTree(output.toString(StandardCharsets.UTF_8));
        assertFalse(listing.path("authoritative").asBoolean());
        assertTrue(listing.path("results").isEmpty());
        assertFalse(Files.exists(directory.resolve("feedback.db")));
        assertArrayEquals(enrollmentBytes, Files.readAllBytes(directory.resolve("classroom.db")));
        assertEquals(2, run("feedback", "sync", "--data", directory.toString(), "--workflow", "../wrong.yml"));
        assertFalse(Files.exists(directory.resolve("feedback.db")));
        assertEquals(2, run("feedback", "list", "--data", directory.toString(), "--assignment", "exercise"));
    }

    @Test void feedbackListAndShowReadStoredAttemptsWithoutNetwork() throws Exception {
        createAcceptedRegistration("Alice");
        var enrollment = EnrollmentStore.readOnly(directory.resolve("classroom.db")).list(null, null).get(0);
        var time = java.time.Instant.parse("2026-10-19T12:00:00Z");
        var result = JsonFiles.read(Path.of("examples/grading/result.json"), 1024 * 1024);
        var storedRun = new FeedbackSource.Run(456, 2, 101, enrollment.repository(), "main", "a".repeat(40),
                "push", "completed", "failure", ".github/workflows/classroom.yml", 77, time);
        var artifact = new FeedbackSource.Artifact(999, "classroom-feedback-456-2", 456, 101, "a".repeat(40), false, 1, null);
        new FeedbackStore(directory, true).record(enrollment, storedRun, "received", null, artifact, new byte[]{1}, result, time);
        byte[] feedbackBytes = Files.readAllBytes(directory.resolve("feedback.db"));
        var output = new ByteArrayOutputStream();
        assertEquals(0, ClassroomCli.run(new String[]{"feedback", "show", "--data", directory.toString(),
                "--repository-id", "101", "--run-id", "456", "--attempt", "2"}, new PrintStream(output), System.err));
        assertEquals(result, JsonFiles.MAPPER.readTree(output.toString(StandardCharsets.UTF_8)));
        assertEquals(0, run("feedback", "list", "--data", directory.toString()));
        assertEquals(2, run("feedback", "show", "--data", directory.toString(), "--repository-id", "101", "--run-id", "456", "--attempt", "1"));
        assertEquals(2, run("feedback", "show", "--data", directory.toString(), "--repository-id", "-1", "--run-id", "456", "--attempt", "2"));
        assertArrayEquals(feedbackBytes, Files.readAllBytes(directory.resolve("feedback.db")));
    }

    @Test void scoreRejectsParentAliasesIntoReports() throws Exception {
        Path rubric = directory.resolve("rubric.json");
        Files.writeString(rubric, "{\"tests\":[{\"class_name\":\"Test\",\"method\":\"test\",\"points\":1}]}");
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), reports);
        assertEquals(2, run("score", "--rubric", rubric.toString(), "--reports", reports.toString(),
                "--output", alias.resolve("new.xml").toString()));
        assertFalse(Files.exists(reports.resolve("new.xml")));
    }

    @Test void jsonAndSummaryCannotReferToOneNewFileThroughAliases() throws Exception {
        Path rubric = directory.resolve("rubric.json");
        Files.writeString(rubric, "{\"tests\":[{\"class_name\":\"Test\",\"method\":\"test\",\"points\":1}]}");
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Path outputDirectory = Files.createDirectory(directory.resolve("output"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), outputDirectory);
        assertEquals(2, run("score", "--rubric", rubric.toString(), "--reports", reports.toString(),
                "--output", outputDirectory.resolve("new.json").toString(), "--summary", alias.resolve("new.json").toString()));
        assertFalse(Files.exists(outputDirectory.resolve("new.json")));
    }

    @Test void invalidSummaryDoesNotReplaceJsonOutput() throws Exception {
        Path rubric = directory.resolve("rubric.json");
        Files.writeString(rubric, "{\"tests\":[{\"class_name\":\"Test\",\"method\":\"test\",\"points\":1}]}");
        Path reports = Files.createDirectory(directory.resolve("reports"));
        Path output = directory.resolve("feedback.json");
        Files.writeString(output, "previous output");
        Path summary = Files.createDirectory(directory.resolve("summary"));
        assertEquals(2, run("score", "--rubric", rubric.toString(), "--reports", reports.toString(),
                "--output", output.toString(), "--summary", summary.toString()));
        assertEquals("previous output", Files.readString(output));
    }

    @Test void collectorRejectsRootOutputCleanly() {
        assertEquals(2, run("collect", "--manifest", "unused.json", "--results", "unused", "--output", "/"));
    }

	@Test void testRegistrationCheckAndAssignmentListingAreReadOnly() throws Exception {
		createRegistrationConfiguration();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "check", "--data", directory.toString()}, new PrintStream(output), System.err));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"valid\" : true"));
		output.reset();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "assignments", "--data", directory.toString()}, new PrintStream(output), System.err));
		String assignments = output.toString(StandardCharsets.UTF_8);
		assertTrue(assignments.contains("org/template"));
		assertFalse(assignments.contains("PRIVATE-LECTURE-KEY"));
		assertFalse(assignments.contains("PRIVATE-ASSIGNMENT-KEY"));
		assertFalse(assignments.contains("Private Student"));
		assertFalse(Files.exists(directory.resolve("classroom.db")));
	}

	@Test void testRegistrationListsDoNotInitializeMissingDatabase() throws Exception {
		createRegistrationConfiguration();
		assertEquals(2, run("registration", "students", "--data", directory.toString()));
		assertEquals(2, run("registration", "list", "--data", directory.toString()));
		assertEquals(2, run("registration", "list", "--data", directory.toString(), "--format", "table"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "s1"));
		assertFalse(Files.exists(directory.resolve("classroom.db")));
		assertEquals(2, run("registration", "list", "--data", directory.toString(), "--assignment", "exercise"));
		assertEquals(2, run("registration", "assignments", "--data", directory.toString(), "--lecture", "unknown"));
		assertEquals(2, run("registration", "check", "--data", directory.toString(), "--lecture", "lecture"));
	}

	@Test void testRegistrationImportsAnUnassignedRosterOffline() throws Exception {
		createRegistrationConfiguration();
		assertEquals(0, run("registration", "import", "--data", directory.toString()));
		assertEquals(0, run("registration", "import", "--data", directory.toString()));
		byte[] database = Files.readAllBytes(directory.resolve("classroom.db"));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "students", "--data", directory.toString(), "--lecture", "lecture"},
				new PrintStream(output), System.err));
		assertTrue(output.toString(StandardCharsets.UTF_8).contains("Private Student"));
		assertFalse(output.toString(StandardCharsets.UTF_8).contains("PRIVATE-LECTURE-KEY"));
		assertEquals(0, run("registration", "list", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise"));
		assertArrayEquals(database, Files.readAllBytes(directory.resolve("classroom.db")));
	}

	@Test void testReconciliationRequiresExplicitInspectionIdentifiers() throws Exception {
		createRegistrationConfiguration();
		assertEquals(2, run("registration", "reconcile", "--data", directory.toString()));
		assertEquals(2, run("registration", "reconcile", "--data", directory.toString(), "--lecture", "lecture",
				"--assignment", "exercise", "--student", "s1", "--repository-id", "0"));
		assertEquals(2, run("registration", "reconcile", "--data", directory.toString(), "--lecture", "lecture",
				"--assignment", "exercise", "--student", "s1", "--repository-id", "invalid"));
		assertFalse(Files.exists(directory.resolve("classroom.db")));
	}

	@Test void testRegistrationTableAndDefaultJsonAreReadOnly() throws Exception {
		String name = "Alice\nExample\t" + (char)27 + "[31m" + Character.toString(0x202E) + Character.toString(0x2028) + "!";
		createAcceptedRegistration(name);
		byte[] database = Files.readAllBytes(directory.resolve("classroom.db"));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "list", "--data", directory.toString()}, new PrintStream(output), System.err));
		String defaultJson = output.toString(StandardCharsets.UTF_8);
		JsonNode registrations = JsonFiles.MAPPER.readTree(defaultJson);
		assertTrue(registrations.isArray());
		assertEquals(1, registrations.size());
		assertEquals(name, registrations.get(0).path("name").asString());
		assertEquals("s1", registrations.get(0).path("studentId").asString());
		output.reset();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "list", "--data", directory.toString(), "--format", "json"}, new PrintStream(output), System.err));
		assertEquals(defaultJson, output.toString(StandardCharsets.UTF_8));
		output.reset();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "list", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--format", "table"}, new PrintStream(output), System.err));
		String table = output.toString(StandardCharsets.UTF_8);
		assertEquals(2, table.lines().count());
		assertTrue(table.startsWith("LECTURE"));
		assertTrue(table.contains("STUDENT ID"));
		assertTrue(table.contains("Alice Example  [31m  !"));
		assertTrue(table.contains("org/term-exercise-alice-42"));
		assertFalse(table.contains("\t"));
		assertFalse(table.contains(Character.toString(27)));
		assertFalse(table.contains(Character.toString(0x202E)));
		assertFalse(table.contains(Character.toString(0x2028)));
		assertEquals(2, run("registration", "list", "--data", directory.toString(), "--format", "csv"));
		assertArrayEquals(database, Files.readAllBytes(directory.resolve("classroom.db")));
	}

	@Test void testRegistrationDeleteRequiresExactSelectors() throws Exception {
		createAcceptedRegistration("Alice");
		byte[] database = Files.readAllBytes(directory.resolve("classroom.db"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString()));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--student", "s1"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--assignment", "exercise", "--student", "s1"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", ""));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "unknown"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "s1", "--force"));
		assertArrayEquals(database, Files.readAllBytes(directory.resolve("classroom.db")));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0, ClassroomCli.run(new String[] {"registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "s1"}, new PrintStream(output), System.err));
		JsonNode deleted = JsonFiles.MAPPER.readTree(output.toString(StandardCharsets.UTF_8)).path("deleted");
		assertEquals("s1", deleted.path("studentId").asString());
		assertEquals("org/term-exercise-alice-42", deleted.path("repository").asString());
		EnrollmentStore store = EnrollmentStore.readOnly(directory.resolve("classroom.db"));
		assertTrue(store.list(null, null).isEmpty());
		assertEquals(1, store.students(null).size());
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "s1"));
	}

	@Test void testRegistrationDeleteRefusesBusyRowsAndChangedConfiguration() throws Exception {
		createAcceptedRegistration("Alice");
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); Statement statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE enrollments SET busy=1");
		}
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "s1"));
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); Statement statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE enrollments SET busy=0");
		}
		Path assignment = directory.resolve("lectures/lecture/assignments/exercise/assignment.json");
		Files.writeString(assignment, Files.readString(assignment).replace("org/template", "org/different-template"));
		assertEquals(2, run("registration", "delete", "--data", directory.toString(), "--lecture", "lecture", "--assignment", "exercise", "--student", "s1"));
		assertEquals(1, EnrollmentStore.readOnly(directory.resolve("classroom.db")).list(null, null).size());
	}

	private void createAcceptedRegistration(String name) throws Exception {
		createRegistrationConfiguration();
		assertEquals(0, run("registration", "import", "--data", directory.toString()));
		try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); Statement statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE students SET github_id=42,github_login='alice' WHERE id='s1'");
			try(PreparedStatement insert = connection.prepareStatement("INSERT INTO enrollments (lecture_id,assignment_id,student_id,accepted_name,identity_source,identity_subject,accepted_at,declaration_version,declaration,repository,repository_id,status) VALUES ('lecture','exercise','s1',?,'self','42','2026-10-01T10:00:00Z','1','Original declaration','org/term-exercise-alice-42',101,'active')")) {
				insert.setString(1, name);
				insert.executeUpdate();
			}
		}
	}

	private void createRegistrationConfiguration() throws Exception {
		Path lecture = Files.createDirectories(directory.resolve("lectures/lecture"));
		Files.writeString(lecture.resolve("lecture.json"), """
				{"title":"Lecture","organization":"org","registrationKey":"PRIVATE-LECTURE-KEY",
				 "students":[{"id":"s1","name":"Private Student"}]}
				""");
		Path assignment = Files.createDirectories(lecture.resolve("assignments/exercise"));
		Files.writeString(assignment.resolve("assignment.json"), """
				{"title":"Exercise","template":"org/template","repositoryPrefix":"term-exercise",
				 "registrationKey":"PRIVATE-ASSIGNMENT-KEY"}
				""");
	}
}
