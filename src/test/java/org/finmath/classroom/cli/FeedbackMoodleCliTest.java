package org.finmath.classroom.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.finmath.classroom.feedback.FeedbackSource;
import org.finmath.classroom.feedback.FeedbackStore;
import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.EnrollmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackMoodleCliTest {
    @TempDir Path directory;
    private Path mapping;
    private Path output;

    @BeforeEach void prepare() throws Exception {
        Path lecture = Files.createDirectories(directory.resolve("lectures/lecture"));
        Files.writeString(lecture.resolve("lecture.json"), """
                {"title":"Lecture","organization":"org","registrationKey":"lecture-key",
                 "students":[{"id":"s1","name":"Alice"},{"id":"s2","name":"Bob"}]}
                """);
        Path assignment = Files.createDirectories(lecture.resolve("assignments/exercise"));
        Files.writeString(assignment.resolve("assignment.json"), """
                {"title":"Exercise","template":"org/template","repositoryPrefix":"term-exercise",
                 "registrationKey":"assignment-key"}
                """);
        assertEquals(0, run("registration", "import", "--data", directory.toString()).code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db"));
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE students SET github_id=42,github_login='alice' WHERE id='s1'");
            statement.executeUpdate("UPDATE students SET github_id=43,github_login='bob' WHERE id='s2'");
            statement.executeUpdate("""
                    INSERT INTO enrollments (lecture_id,assignment_id,student_id,accepted_name,identity_source,
                        identity_subject,accepted_at,declaration_version,declaration,repository,repository_id,status)
                    VALUES ('lecture','exercise','s1','Alice','self','42','2026-10-01T10:00:00Z','1','Declaration','org/task-alice',101,'active'),
                           ('lecture','exercise','s2','Bob','self','43','2026-10-01T10:00:00Z','1','Declaration','org/task-bob',102,'active')
                    """);
        }
        mapping = Files.writeString(directory.resolve("users.json"), "{\"s1\":\"moodle.alice\",\"s2\":\"moodle.bob\"}");
        output = directory.resolve("moodle.csv");
    }

    @Test void exportsLatestScoresAlongsideTableWithoutChangingDatabases() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        record("s2", 457, 1, "received", "failed", "0");
        byte[] registration = Files.readAllBytes(directory.resolve("classroom.db"));
        byte[] feedback = Files.readAllBytes(directory.resolve("feedback.db"));

        Result result = run(command());

        assertEquals(0, result.code(), result.error());
        assertEquals("username,Exercise\nmoodle.alice,6.82\nmoodle.bob,0.00\n", Files.readString(output));
        assertTrue(result.output().contains("POINTS"));
        assertTrue(result.output().contains("15/22"));
        assertTrue(result.output().contains("0/22"));
        assertArrayEquals(registration, Files.readAllBytes(directory.resolve("classroom.db")));
        assertArrayEquals(feedback, Files.readAllBytes(directory.resolve("feedback.db")));

        Result listing = run("feedback", "list", "--data", directory.toString(), "--latest");
        assertEquals(0, listing.code(), listing.error());
        var json = JsonFiles.MAPPER.readTree(listing.output());
        assertFalse(json.path("authoritative").asBoolean());
        assertFalse(json.path("results").get(0).path("authoritative").asBoolean());
    }

    @Test void exportsSavedMoodleIdsWithoutMappingEvenAfterRegistrationIdentityUpgrade() throws Exception {
        identity("s1", "moodle:https://moodle.example", "123");
        identity("s2", "moodle:https://moodle.example", "456");
        record("s1", 456, 1, "received", "failed", "15");
        record("s2", 457, 1, "received", "failed", "0");
        Files.delete(mapping);
        byte[] registration = Files.readAllBytes(directory.resolve("classroom.db"));
        byte[] feedback = Files.readAllBytes(directory.resolve("feedback.db"));

        Result result = run(without(command(), "--moodle-users"));

        assertEquals(0, result.code(), result.error());
        assertEquals("userid,Exercise\n123,6.82\n456,0.00\n", Files.readString(output));
        assertTrue(result.output().contains("User ID"), result.output());
        assertTrue(result.output().contains("https://moodle.example"), result.output());
        assertTrue(result.output().contains("15/22"));
        assertArrayEquals(registration, Files.readAllBytes(directory.resolve("classroom.db")));
        assertArrayEquals(feedback, Files.readAllBytes(directory.resolve("feedback.db")));
        assertEquals("self", EnrollmentStore.readOnly(directory.resolve("classroom.db"))
                .list("lecture", "exercise").get(0).identitySource(), "An old enrollment snapshot must not be rewritten");
    }

    @Test void automaticIdExportWithJsonReportsIdentifierAndMoodleSite() throws Exception {
        identity("s1", "moodle:https://moodle.example", "123");
        record("s1", 456, 1, "received", "passed", "22");
        String[] arguments = without(command(), "--moodle-users");
        arguments[Arrays.asList(arguments).indexOf("--format") + 1] = "json";

        Result result = run(arguments);

        assertEquals(0, result.code(), result.error());
        var json = JsonFiles.MAPPER.readTree(result.output());
        assertFalse(json.path("authoritative").asBoolean());
        assertEquals("userid", json.path("moodleExport").path("identifier").asString());
        assertEquals("https://moodle.example", json.path("moodleExport").path("issuer").asString());
        assertEquals(1, json.path("moodleExport").path("exported").asInt());
        assertEquals(1, json.path("moodleExport").path("skipped").asInt());
        assertEquals("userid,Exercise\n123,10.00\n", Files.readString(output));
    }

    @Test void automaticIdExportDoesNotRequireIdentityForLatestUnavailableScore() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        record("s1", 456, 2, "missing", null, null);
        identity("s2", "moodle:https://moodle.example", "456");
        record("s2", 457, 1, "received", "passed", "22");

        Result result = run(without(command(), "--moodle-users"));

        assertEquals(0, result.code(), result.error());
        assertEquals("userid,Exercise\n456,10.00\n", Files.readString(output));
        assertTrue(result.output().contains("missing"));
        assertFalse(result.output().contains("15/22"));
    }

    @Test void automaticIdExportRejectsMissingSelfAndOpaqueIdentitiesWithMappingGuidance() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        for (String[] values : new String[][]{{"roster", null}, {"self", "123"},
                {"moodle:https://moodle.example", "opaque-subject"},
                {"moodle:https://moodle.example", "0"}, {"moodle:https://moodle.example", null}}) {
            identity("s1", values[0], values[1]);
            byte[] registration = Files.readAllBytes(directory.resolve("classroom.db"));

            Result result = run(without(command(), "--moodle-users"));

            assertEquals(2, result.code());
            assertTrue(result.error().contains("--moodle-users"), result.error());
            assertFalse(Files.exists(output));
            assertArrayEquals(registration, Files.readAllBytes(directory.resolve("classroom.db")));
        }
    }

    @Test void automaticIdExportRejectsGradesFromDifferentMoodleSites() throws Exception {
        identity("s1", "moodle:https://moodle.example", "123");
        identity("s2", "moodle:https://other-moodle.example", "123");
        record("s1", 456, 1, "received", "failed", "15");
        record("s2", 457, 1, "received", "passed", "22");

        assertEquals(2, run(without(command(), "--moodle-users")).code());

        assertFalse(Files.exists(output));
    }

    @Test void automaticIdExportNeverOverwritesExistingOutput() throws Exception {
        identity("s1", "moodle:https://moodle.example", "123");
        record("s1", 456, 1, "received", "failed", "15");
        Files.writeString(output, "Previously reviewed grades\n");

        assertEquals(2, run(without(command(), "--moodle-users")).code());

        assertEquals("Previously reviewed grades\n", Files.readString(output));
    }

    @Test void explicitUsernameMappingSelectsUsernameModeEvenForMoodleRegistrations() throws Exception {
        identity("s1", "moodle:https://moodle.example", "123");
        record("s1", 456, 1, "received", "failed", "15");

        Result result = run(command());

        assertEquals(0, result.code(), result.error());
        assertEquals("username,Exercise\nmoodle.alice,6.82\n", Files.readString(output));
    }

    @Test void appliesCustomTitleOffsetAndPrecisionAndChecksReportedMaximum() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");

        Result result = run(append(command(), "--grade-item", "Interpolation", "--offset", "0.5",
                "--decimals", "1", "--source-max", "22"));

        assertEquals(0, result.code(), result.error());
        assertEquals("username,Interpolation\nmoodle.alice,7.3\n", Files.readString(output));
        Files.delete(output);
        assertEquals(2, run(append(command(), "--source-max", "100")).code());
        assertFalse(Files.exists(output));
    }

    @Test void exportWithJsonListingPreservesMachineReadableFeedbackAndSummary() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        String[] arguments = command();
        arguments[Arrays.asList(arguments).indexOf("--format") + 1] = "json";

        Result result = run(arguments);

        assertEquals(0, result.code(), result.error());
        var json = JsonFiles.MAPPER.readTree(result.output());
        assertFalse(json.path("authoritative").asBoolean());
        assertEquals(2, json.path("results").size());
        assertEquals(1, json.path("moodleExport").path("exported").asInt());
        assertEquals(1, json.path("moodleExport").path("skipped").asInt());
        assertEquals("gradebook", json.path("moodleExport").path("target").asString());
        assertEquals("username", json.path("moodleExport").path("identifier").asString());
        assertEquals("username,Exercise\nmoodle.alice,6.82\n", Files.readString(output));
    }

    @Test void latestMissingAttemptIsOmittedInsteadOfFallingBackToEarlierScore() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        record("s1", 456, 2, "missing", null, null);
        record("s2", 457, 1, "received", "passed", "22");

        Result result = run(command());

        assertEquals(0, result.code(), result.error());
        assertEquals("username,Exercise\nmoodle.bob,10.00\n", Files.readString(output));
        assertTrue(result.output().contains("missing"));
        assertFalse(result.output().contains("15/22"));
    }

    @Test void noCollectedScoresDoesNotCreateFeedbackDatabaseOrCsv() throws Exception {
        byte[] registration = Files.readAllBytes(directory.resolve("classroom.db"));

        assertEquals(2, run(command()).code());
        assertEquals(2, run(without(command(), "--moodle-users")).code());

        assertFalse(Files.exists(output));
        assertFalse(Files.exists(directory.resolve("feedback.db")));
        assertArrayEquals(registration, Files.readAllBytes(directory.resolve("classroom.db")));
    }

    @Test void receivedInfrastructureResultIsNotExportedAsNumericZero() throws Exception {
        record("s1", 456, 1, "received", "error", "0");
        record("s2", 457, 1, "received", "failed", "0");

        Result result = run(command());

        assertEquals(0, result.code(), result.error());
        assertEquals("username,Exercise\nmoodle.bob,0.00\n", Files.readString(output));
    }

    @Test void requiresLatestSingleAssignmentAndExportConfiguration() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        for (String option : List.of("--latest", "--lecture", "--assignment", "--moodle-max")) {
            assertEquals(2, run(without(command(), option)).code(), option);
            assertFalse(Files.exists(output));
        }
        for (String[] option : List.of(new String[]{"--moodle-users", mapping.toString()},
                new String[]{"--moodle-max", "10"}, new String[]{"--source-max", "22"},
                new String[]{"--offset", "1"}, new String[]{"--decimals", "1"},
                new String[]{"--grade-item", "Exercise"})) {
            assertEquals(2, run(append(new String[]{"feedback", "list", "--data", directory.toString()}, option)).code());
        }
        assertEquals(2, run("feedback", "sync", "--data", directory.toString(), "--moodle-output", output.toString()).code());
        assertFalse(Files.exists(output));
    }

    @Test void missingIdentityMappingAndExistingExportFailWithoutMutation() throws Exception {
        record("s1", 456, 1, "received", "failed", "15");
        byte[] registration = Files.readAllBytes(directory.resolve("classroom.db"));
        byte[] feedback = Files.readAllBytes(directory.resolve("feedback.db"));
        Files.writeString(mapping, "{\"different-student\":\"moodle.alice\"}");
        assertEquals(2, run(command()).code());
        assertFalse(Files.exists(output));

        Files.writeString(mapping, "{\"s1\":\"moodle.alice\"}");
        Files.writeString(output, "Previously reviewed grades\n");
        assertEquals(2, run(command()).code());
        assertEquals("Previously reviewed grades\n", Files.readString(output));
        assertArrayEquals(registration, Files.readAllBytes(directory.resolve("classroom.db")));
        assertArrayEquals(feedback, Files.readAllBytes(directory.resolve("feedback.db")));
    }

    private void identity(String student, String source, String subject) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db"));
                var statement = connection.prepareStatement(
                        "UPDATE students SET identity_source=?,identity_subject=? WHERE lecture_id='lecture' AND id=?")) {
            statement.setString(1, source);
            statement.setString(2, subject);
            statement.setString(3, student);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void record(String student, long runId, int attempt, String collectionStatus,
            String feedbackStatus, String score) throws Exception {
        var enrollment = EnrollmentStore.readOnly(directory.resolve("classroom.db")).list("lecture", "exercise")
                .stream().filter(row -> row.studentId().equals(student)).findFirst().orElseThrow();
        Instant time = Instant.parse("2026-10-19T12:00:00Z").plusSeconds(runId);
        var run = new FeedbackSource.Run(runId, attempt, enrollment.repositoryId(), enrollment.repository(),
                "main", "a".repeat(40), "push", "completed", "failure", ".github/workflows/classroom.yml", 77, time);
        var artifact = new FeedbackSource.Artifact(runId + 1000, "classroom-feedback-" + runId + "-" + attempt,
                runId, enrollment.repositoryId(), "a".repeat(40), false, 1, null);
        var result = feedbackStatus == null ? null : JsonFiles.MAPPER.readTree(
                "{\"authoritative\":false,\"status\":\"" + feedbackStatus + "\",\"score\":" + score + ",\"max_score\":22}");
        new FeedbackStore(directory, true).record(enrollment, run, collectionStatus, null,
                result == null ? null : artifact, result == null ? null : new byte[]{1}, result, time);
    }

    private String[] command() {
        return new String[]{"feedback", "list", "--data", directory.toString(), "--latest", "--format", "table",
                "--lecture", "lecture", "--assignment", "exercise", "--moodle-output", output.toString(),
                "--moodle-users", mapping.toString(), "--moodle-max", "10"};
    }

    private static String[] without(String[] arguments, String option) {
        List<String> result = new ArrayList<>(Arrays.asList(arguments));
        int index = result.indexOf(option);
        if (!option.equals("--latest")) result.remove(index + 1);
        result.remove(index);
        return result.toArray(String[]::new);
    }

    private static String[] append(String[] arguments, String... extra) {
        String[] result = Arrays.copyOf(arguments, arguments.length + extra.length);
        System.arraycopy(extra, 0, result, arguments.length, extra.length);
        return result;
    }

    private static Result run(String... args) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code = ClassroomCli.run(args, new PrintStream(output), new PrintStream(error));
        return new Result(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String output, String error) { }
}
