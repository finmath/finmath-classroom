package org.finmath.classroom.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.finmath.classroom.io.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
