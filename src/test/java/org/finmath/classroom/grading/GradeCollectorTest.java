package org.finmath.classroom.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import org.finmath.classroom.io.JsonFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class GradeCollectorTest {
    @TempDir
    Path temporary;
    private Path manifestPath;
    private Path results;
    private Path output;
    private ObjectNode manifest;
    private ObjectNode result;
    private final GradeCollector collector = new GradeCollector();

    @BeforeEach
    void prepare() throws IOException {
        manifestPath = temporary.resolve("manifest.json");
        results = Files.createDirectory(temporary.resolve("results"));
        output = temporary.resolve("grades.csv");
        manifest = (ObjectNode) JsonFiles.MAPPER.readTree("""
                {"version":1,"assignment":"nm-2026-controlvariate",
                 "deadline":"2026-12-01T12:00:00Z","generated_at":"2026-12-01T12:00:01Z",
                 "submissions":[{"student_id":"00123","github_username":"alice",
                 "repository":"qntlb/nm-2026-controlvariate-alice","commit":"%s",
                 "received_at":"2026-12-01T11:59:59Z","status":"submitted"}]}
                """.formatted("a".repeat(40)));
        result = (ObjectNode) JsonFiles.MAPPER.readTree("""
                {"assignment":"nm-2026-controlvariate","repository":"qntlb/nm-2026-controlvariate-alice",
                 "commit":"%s","status":"failed","score":15,"max_score":22,
                 "graded_at":"2026-12-01T12:03:00Z"}
                """.formatted("a".repeat(40)));
    }

    private ObjectNode submission() {
        return (ObjectNode) manifest.get("submissions").get(0);
    }

    private void saveManifest() throws IOException {
        Files.writeString(manifestPath, JsonFiles.MAPPER.writeValueAsString(manifest));
    }

    private void saveResult() throws IOException {
        Files.writeString(results.resolve("alice.json"), JsonFiles.MAPPER.writeValueAsString(result));
    }

    private List<String> collectRows() throws IOException {
        saveManifest();
        collector.collect(manifestPath, results, output);
        return Files.readAllLines(output);
    }

    @Test
    void partialScorePreservesIdentityAndCsvColumns() throws IOException {
        saveResult();
        List<String> rows = collectRows();
        assertEquals("student_id,github_username,assignment,repository,commit,received_at,status,score,max_score,graded_at", rows.get(0));
        String[] cells = rows.get(1).split(",", -1);
        assertEquals("00123", cells[0]);
        assertEquals("a".repeat(40), cells[4]);
        assertEquals("failed", cells[6]);
        assertEquals("15", cells[7]);
        assertEquals("22", cells[8]);
    }

    @Test
    void missingAndUnsubmittedResultsStayBlank() throws IOException {
        ArrayNode submissions = (ArrayNode) manifest.get("submissions");
        ObjectNode bob = submission().deepCopy();
        bob.put("student_id", "2").put("github_username", "bob").put("repository", "qntlb/bob")
                .putNull("commit").putNull("received_at").put("status", "no_submission");
        submissions.add(bob);
        ObjectNode carol = bob.deepCopy();
        carol.put("student_id", "3").put("github_username", "carol").putNull("repository").put("status", "not_enrolled");
        submissions.add(carol);
        List<String> rows = collectRows();
        for (int index = 1; index <= 3; index++) {
            String[] cells = rows.get(index).split(",", -1);
            assertEquals(List.of("missing_result", "no_submission", "not_enrolled").get(index - 1), cells[6]);
            assertEquals("", cells[7]);
            assertEquals("", cells[8]);
        }
    }

    @Test
    void exactAssignmentRepositoryAndCommitRequired() throws IOException {
        ObjectNode original = result.deepCopy();
        for (String field : List.of("assignment", "repository", "commit")) {
            result = original.deepCopy();
            result.put(field, switch (field) {
                case "assignment" -> "other";
                case "repository" -> "qntlb/other";
                default -> "b".repeat(40);
            });
            saveResult();
            assertThrows(IllegalArgumentException.class, this::collectRows);
        }
        result = original.deepCopy();
        result.put("repository", "QNTLB/nm-2026-controlvariate-alice");
        saveResult();
        assertThrows(IllegalArgumentException.class, this::collectRows);
    }

    @Test
    void duplicateAndConflictingResultsFailWithoutReplacingOutput() throws IOException {
        saveResult();
        Files.writeString(results.resolve("other.JSON"), JsonFiles.MAPPER.writeValueAsString(result));
        Files.writeString(output, "previous marks");
        assertThrows(IllegalArgumentException.class, this::collectRows);
        assertEquals("previous marks", Files.readString(output));
    }

    @Test
    void infrastructureProblemsRemainBlankAndCompileZeroIsExplicit() throws IOException {
        for (String status : List.of("infrastructure_error", "incomplete", "error", "compile_error", "timeout")) {
            result.put("status", status).putNull("score");
            saveResult();
            assertEquals("", collectRows().get(1).split(",", -1)[7]);
        }
        for (String status : List.of("compile_error", "timeout")) {
            result.put("status", status).put("score", 0);
            saveResult();
            assertEquals("0", collectRows().get(1).split(",", -1)[7]);
        }
    }

    @Test
    void invalidNumbersAndInconsistentStatusesAreRejected() throws IOException {
        ObjectNode original = result.deepCopy();
        List<String> changes = List.of("{\"score\":true}", "{\"score\":-1}", "{\"score\":23}",
                "{\"score\":\"15\"}", "{\"score\":null}", "{\"max_score\":0}", "{\"max_score\":1000001}",
                "{\"status\":\"passed\"}", "{\"score\":22}", "{\"status\":\"compile_error\",\"score\":1}",
                "{\"status\":\"infrastructure_error\",\"score\":0}");
        for (String change : changes) {
            result = original.deepCopy();
            ObjectNode patch = (ObjectNode) JsonFiles.MAPPER.readTree(change);
            patch.properties().forEach(entry -> result.set(entry.getKey(), entry.getValue()));
            saveResult();
            assertThrows(IllegalArgumentException.class, this::collectRows, change);
        }
    }

    @Test
    void fullMarksAndFractionalPartialScoresWork() throws IOException {
        result.put("status", "passed").put("score", 22);
        saveResult();
        assertEquals("passed", collectRows().get(1).split(",", -1)[6]);
        result.put("status", "failed").put("score", 15.5);
        saveResult();
        assertEquals("15.5", collectRows().get(1).split(",", -1)[7]);
    }

    @Test
    void explicitTimezoneAndDeadlineAreValidated() throws IOException {
        submission().put("received_at", "2026-12-01T13:00:00+01:00");
        assertEquals("missing_result", collectRows().get(1).split(",", -1)[6]);
        submission().put("received_at", "2026-12-01T12:00:01Z");
        assertThrows(IllegalArgumentException.class, this::collectRows);
        submission().put("received_at", "2026-12-01T11:00:00");
        assertThrows(IllegalArgumentException.class, this::collectRows);
    }

    @Test
    void duplicateManifestAccountIsCaseInsensitive() throws IOException {
        ObjectNode duplicate = submission().deepCopy();
        duplicate.put("student_id", "other").put("github_username", "ALICE");
        ((ArrayNode) manifest.get("submissions")).add(duplicate);
        assertThrows(IllegalArgumentException.class, this::collectRows);
    }

    @Test
    void strictJsonAndUnknownFieldsAreRejected() throws IOException {
        saveManifest();
        for (String invalid : List.of("{\"score\":0,\"score\":22}", "{} {}", "{\"score\":NaN}")) {
            Files.writeString(results.resolve("alice.json"), invalid);
            assertThrows(Exception.class, () -> collector.collect(manifestPath, results, output));
            assertFalse(Files.exists(output));
        }
        result.put("unexpected", "field");
        saveResult();
        assertThrows(IllegalArgumentException.class, this::collectRows);
    }

    @Test
    void studentFeedbackSchemaIsNotAnAuthoritativeResult() throws IOException {
        Files.writeString(results.resolve("feedback.json"), """
                {"schema_version":1,"authoritative":false,"purpose":"student feedback",
                 "status":"passed","score":22,"max_score":22,"cases":[],"errors":[]}
                """);
        assertThrows(IllegalArgumentException.class, this::collectRows);
    }

    @Test
    void oversizedAndSymlinkInputsAreRejected() throws IOException {
        saveManifest();
        Path oversized = results.resolve("large.json");
        Files.write(oversized, new byte[GradeCollector.MAX_RESULT_BYTES + 1]);
        assertThrows(Exception.class, () -> collector.collect(manifestPath, results, output));
        Files.delete(oversized);
        Files.createSymbolicLink(results.resolve("linked.json"), manifestPath);
        assertThrows(Exception.class, () -> collector.collect(manifestPath, results, output));
    }

    @Test
    void resultForUnsubmittedStudentIsRejected() throws IOException {
        saveResult();
        submission().put("status", "no_submission").putNull("commit").putNull("received_at");
        assertThrows(IllegalArgumentException.class, this::collectRows);
    }

    @Test
    void spreadsheetFormulaAndCsvQuotingAreHandled() throws IOException {
        for (String value : List.of("=1+1", "+1", "-1", "@SUM(A1)", "  =1", "\tname", " \rname", "\nname")) {
            assertEquals("'" + value, GradeCollector.spreadsheetSafe(value));
        }
        assertEquals("00123", GradeCollector.spreadsheetSafe("00123"));
        assertEquals("normal,quoted\nname", GradeCollector.spreadsheetSafe("normal,quoted\nname"));
        submission().put("student_id", "  =HYPERLINK(\"bad\")");
        collectRows();
        assertTrue(Files.readString(output).contains("\"'  =HYPERLINK(\"\"bad\"\")\""));
    }

    @Test
    void outputCannotOverwriteInputOrFollowSymlink() throws IOException {
        manifestPath = temporary.resolve("manifest.csv");
        saveManifest();
        byte[] original = Files.readAllBytes(manifestPath);
        assertThrows(IllegalArgumentException.class, () -> collector.collect(manifestPath, results, manifestPath));
        assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(manifestPath));
        Files.createSymbolicLink(output, manifestPath);
        assertThrows(IllegalArgumentException.class, () -> collector.collect(manifestPath, results, output));
    }

    @Test
    void outputCannotBeHardLinkToInput() throws IOException {
        saveManifest();
        Files.createLink(output, manifestPath);
        assertThrows(IllegalArgumentException.class, () -> collector.collect(manifestPath, results, output));
    }

    @Test
    void outputIsPrivateOnPosixFileSystems() throws IOException {
        collectRows();
        if (Files.getFileStore(output).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(output));
        }
    }

    @Test
    void missingFieldsAndInvalidUnicodeFailClosed() throws IOException {
        submission().remove("received_at");
        assertThrows(IllegalArgumentException.class, this::collectRows);
        submission().put("received_at", "2026-12-01T11:59:59Z").put("student_id", "bad\uD800");
        assertThrows(Exception.class, this::collectRows);
    }
}
