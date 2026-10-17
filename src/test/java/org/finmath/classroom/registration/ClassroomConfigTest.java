package org.finmath.classroom.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassroomConfigTest {
	@TempDir Path directory;

	@Test void testLectureRosterAndAssignmentDefaults() throws Exception {
		writeLecture("course", """
				{"title":"Numerical Methods","organization":"qntlb","staffTeam":"teaching",
				 "students":[{"id":"s1","name":"First Student"},{"id":"s2","name":"Second Student","githubUsername":"Example-User"}]}
				""");
		writeAssignment("course", "exercise", """
				{"title":"Exercise","template":"https://github.com/qntlb/template/","repositoryPrefix":"term-exercise",
				 "deadline":"2027-02-01T12:00:00+01:00","moodleCourseId":"10","moodleResourceId":"20"}
				""");
		ClassroomConfig config = ClassroomConfig.load(directory);
		assertEquals(2, config.lecture("course").students().size());
		assertNull(config.lecture("course").students().get(0).githubUsername());
		assertNull(config.lecture("course").registrationKey());
		assertEquals("qntlb/template", config.assignment("course", "exercise").template());
		assertEquals("main", config.assignment("course", "exercise").branch());
		assertEquals(Instant.parse("2027-02-01T11:00:00Z"), config.assignment("course", "exercise").deadline());
		assertEquals("20", config.assignment("course", "exercise").moodleResourceId());
		assertThrows(IllegalArgumentException.class, () -> config.lecture("unknown"));
		assertThrows(IllegalArgumentException.class, () -> config.assignment("course", "unknown"));
		assertThrows(UnsupportedOperationException.class, () -> config.lectures().clear());
	}

	@Test void testEmptyLectureCanBeConfiguredBeforeAssignments() throws Exception {
		writeLecture("course", "{\"title\":\"Lecture\",\"organization\":\"qntlb\"}");
		ClassroomConfig.Lecture lecture = ClassroomConfig.load(directory).lecture("course");
		assertEquals(0, lecture.students().size());
		assertEquals(0, lecture.assignments().size());
	}

	@Test void testRosterIdentityCollisionsAreRejected() throws Exception {
		writeLecture("course", """
				{"title":"Lecture","organization":"qntlb","students":[
				 {"id":"s1","name":"First","githubUsername":"Alice"},
				 {"id":"s2","name":"Second","githubUsername":"alice"}]}
				""");
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
		writeLecture("course", """
				{"title":"Lecture","organization":"qntlb","students":[
				 {"id":"s1","name":"First"},{"id":"s1","name":"Second"}]}
				""");
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
	}

	@Test void testStaffTeamMustBeAGitHubTeamSlug() throws Exception {
		writeLecture("course", "{\"title\":\"Lecture\",\"organization\":\"qntlb\",\"staffTeam\":\"teaching_staff\"}");
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
	}

	@Test void testRepositoryPrefixesAreUniqueWithinOrganization() throws Exception {
		writeLecture("first", "{\"title\":\"First\",\"organization\":\"qntlb\"}");
		writeLecture("second", "{\"title\":\"Second\",\"organization\":\"QNTLB\"}");
		String assignment = "{\"title\":\"Exercise\",\"template\":\"qntlb/template\",\"repositoryPrefix\":\"same-prefix\"}";
		writeAssignment("first", "exercise", assignment);
		writeAssignment("second", "exercise", assignment);
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
		writeLecture("second", "{\"title\":\"Second\",\"organization\":\"another-org\"}");
		assertEquals(2, ClassroomConfig.load(directory).lectures().size());
	}

	@Test void testInvalidAssignmentMetadataIsRejected() throws Exception {
		writeLecture("course", "{\"title\":\"Lecture\",\"organization\":\"qntlb\"}");
		String prefix = "\"title\":\"Exercise\",\"repositoryPrefix\":\"exercise\",";
		for(String fields : new String[] {
				"\"template\":\"https://example.com/qntlb/template\"",
				"\"template\":\"https://github.com/qntlb/template?x=y\"",
				"\"template\":\"qntlb/../template\"",
				"\"template\":\"qntlb/template\",\"moodleCourseId\":\"10\"",
				"\"template\":\"qntlb/template\",\"deadline\":\"2027-01-01T10:00:00\"",
				"\"template\":\"qntlb/template\",\"registratonKey\":\"typo\"",
				"\"template\":\"qntlb/template\",\"branch\":\"main\\nother\"",
				"\"template\":\"qntlb/template\",\"registrationKey\":\"\""
		}) {
			writeAssignment("course", "exercise", "{" + prefix + fields + "}");
			assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
		}
	}

	@Test void testMoodleActivityCannotSelectMultipleAssignments() throws Exception {
		writeLecture("course", "{\"title\":\"Lecture\",\"organization\":\"qntlb\"}");
		writeAssignment("course", "first", """
				{"title":"First","template":"qntlb/template","repositoryPrefix":"first",
				 "moodleCourseId":"10","moodleResourceId":"20"}
				""");
		writeAssignment("course", "second", """
				{"title":"Second","template":"qntlb/template","repositoryPrefix":"second",
				 "moodleCourseId":"10","moodleResourceId":"20"}
				""");
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
	}

	@Test void testConfigurationFilesAreBoundedAndCannotBeSymlinks() throws Exception {
		writeLecture("course", "{\"title\":\"Lecture\",\"organization\":\"qntlb\"}");
		writeAssignment("course", "exercise", " ".repeat(64 * 1024 + 1));
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
		Path file = directory.resolve("lectures/course/assignments/exercise/assignment.json");
		Files.delete(file);
		Files.createSymbolicLink(file, directory.resolve("lectures/course/lecture.json"));
		assertThrows(IllegalArgumentException.class, () -> ClassroomConfig.load(directory));
	}

	private void writeLecture(String id, String content) throws Exception {
		Path lecture = Files.createDirectories(directory.resolve("lectures").resolve(id));
		Files.writeString(lecture.resolve("lecture.json"), content);
	}

	private void writeAssignment(String lecture, String assignment, String content) throws Exception {
		Path path = Files.createDirectories(directory.resolve("lectures").resolve(lecture).resolve("assignments").resolve(assignment));
		Files.writeString(path.resolve("assignment.json"), content);
	}
}
