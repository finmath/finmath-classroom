package org.finmath.classroom.grading;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.finmath.classroom.feedback.FeedbackListing;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedbackMoodleExportTest {
    @TempDir Path directory;
    private Path mapping;
    private Path output;

    @BeforeEach void prepare() throws IOException {
        mapping = Files.writeString(directory.resolve("moodle-users.json"),
                "{\"s1\":\"00123\",\"s2\":\"moodle.bob\",\"other-assignment\":\"other.student\"}");
        output = directory.resolve("moodle.csv");
    }

    @Test void mapsStableStudentIdsAndScalesEachResultsMaximumWithoutChangingInputs() throws IOException {
        var first = row("s1", "15", "20");
        var second = row("s2", "15", "30");
        first.put("githubLogin", "not-the-moodle-username");
        var original = new LinkedHashMap<>(first);
        byte[] originalMapping = Files.readAllBytes(mapping);

        assertEquals(new MoodleGradeExport.Summary(2, 0), export(List.of(first, second), null));
        assertEquals("username,Interpolation\n00123,7.50\nmoodle.bob,5.00\n", Files.readString(output));
        assertEquals(original, first);
        assertArrayEquals(originalMapping, Files.readAllBytes(mapping));
    }

    @Test void preservesZeroButSkipsMissingAndUnusableFeedbackWithoutRequiringTheirMappings() throws IOException {
        var missing = row("no-mapping", null, null);
        missing.put("collectionStatus", "missing");
        var broken = row("broken", "8", "10");
        broken.put("feedbackStatus", "error");
        var uncollected = row("uncollected", "10", "10");
        uncollected.put("collectionStatus", "not_collected");
        var blank = row("blank", null, "10");

        assertEquals(new MoodleGradeExport.Summary(1, 4), export(List.of(row("s1", "0", "10"),
                missing, broken, uncollected, blank), null));
        assertEquals("username,Interpolation\n00123,0.00\n", Files.readString(output));
    }

    @Test void latestUnavailableRunNeverFallsBackToThePreviousNumericScore() throws IOException {
        Enrollment alice = enrollment("s1", 101L);
        Enrollment bob = enrollment("s2", 102L);
        var old = attempt("s1", 101L, 1L, "2026-10-01T12:00:00Z", "10");
        var current = attempt("s1", 101L, 2L, "2026-10-02T12:00:00Z", null);
        current.put("collectionStatus", "missing");
        var other = attempt("s2", 102L, 3L, "2026-10-02T12:00:00Z", "5");
        var latest = FeedbackListing.latest(List.of(alice, bob), List.of(old, current, other), List.of());

        assertEquals(new MoodleGradeExport.Summary(1, 1), export(latest, null));
        assertEquals("username,Interpolation\nmoodle.bob,5.00\n", Files.readString(output));
    }

    @Test void sourceMaximumOptionChecksReportedMaximumRatherThanReplacingIt() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> export(List.of(row("s1", "5", "10"), row("s2", "5", "20")), "10"));
        assertFalse(Files.exists(output));
        assertEquals(new MoodleGradeExport.Summary(1, 0), export(List.of(row("s1", "5", "10.0")), "10"));
        assertTrue(Files.readString(output).contains("00123,5.00"));
    }

    @Test void appliesTheSharedOffsetAndRoundingRules() throws IOException {
        var summary = FeedbackMoodleExport.export(List.of(row("s1", "5", "20"), row("s2", "20", "20")),
                mapping, output, "Interpolation", null, BigDecimal.TEN, new BigDecimal("0.75"), 1);
        assertEquals(new MoodleGradeExport.Summary(2, 0), summary);
        assertEquals("username,Interpolation\n00123,3.3\nmoodle.bob,10.0\n", Files.readString(output));
    }

    @Test void missingMappingStopsTheWholeExportAndNeverUsesDisplayOrGithubNames() throws IOException {
        var missing = row("unmapped-id", "8", "10");
        missing.put("name", "Moodle User");
        missing.put("githubLogin", "moodle.user");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> export(List.of(row("s1", "5", "10"), missing), null));
        assertTrue(error.getMessage().contains("unmapped-id"));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "{\"s1\":null}", "{\"s1\":123}", "{\"\":\"alice\"}",
            "{\"s1\":\"\"}", "{\"s1\":\"alice\",\"s1\":\"bob\"}",
            "{\"s1\":\"alice\",\"unused\":\"ALICE\"}", "{\"s1\":\"=FORMULA()\"}",
            "{\"s1\":\"alice\",\"unused\":\"@formula\"}", "{\"s1\":\" alice\"}"
    })
    void rejectsInvalidMappingsEvenWhenTheirEntriesWouldNotBeUsed(String json) throws IOException {
        Files.writeString(mapping, json);
        assertThrows(IllegalArgumentException.class, () -> export(List.of(row("s1", "5", "10")), null));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsInvalidNumericFeedbackWithoutWritingEarlierGrades() throws IOException {
        for (Object score : new Object[]{"5", Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("-1"),
                new BigDecimal("11"), new BigDecimal("1e-100")}) {
            var invalid = row("s2", "5", "10");
            invalid.put("score", score);
            assertThrows(IllegalArgumentException.class, () -> export(List.of(row("s1", "5", "10"), invalid), null));
            assertFalse(Files.exists(output));
        }
        for (Object maximum : new Object[]{null, "10", BigDecimal.ZERO, new BigDecimal("-10"), Double.NaN}) {
            var invalid = row("s2", "5", "10");
            invalid.put("maxScore", maximum);
            assertThrows(IllegalArgumentException.class, () -> export(List.of(invalid), null));
            assertFalse(Files.exists(output));
        }
    }

    @Test void rejectsMultipleAssignmentsDuplicateStudentsAndNoAvailableGrades() throws IOException {
        var second = row("s2", "5", "10");
        second.put("assignmentId", "different");
        assertThrows(IllegalArgumentException.class, () -> export(List.of(row("s1", "5", "10"), second), null));
        assertThrows(IllegalArgumentException.class,
                () -> export(List.of(row("s1", "5", "10"), row("s1", "6", "10")), null));
        assertThrows(IllegalArgumentException.class, () -> export(List.of(row("s1", null, null)), null));
        assertThrows(IllegalArgumentException.class, () -> export(List.of(), null));
        assertFalse(Files.exists(output));
    }

    @Test void refusesToOverwriteMappingOrExistingExport() throws IOException {
        Files.writeString(output, "reviewed export");
        assertThrows(IllegalArgumentException.class, () -> export(List.of(row("s1", "5", "10")), null));
        assertEquals("reviewed export", Files.readString(output));
        byte[] originalMapping = Files.readAllBytes(mapping);
        output = mapping;
        assertThrows(IllegalArgumentException.class, () -> export(List.of(row("s1", "5", "10")), null));
        assertArrayEquals(originalMapping, Files.readAllBytes(mapping));
    }

    @Test void automaticExportUsesSavedMoodleUserIdsAndReturnsTheirIssuer() throws IOException {
        var first = row("s1", "15", "20");
        first.put("identitySource", "moodle:https://wrong.example");
        first.put("identitySubject", "999");
        first.put("githubLogin", "123");
        var identities = Map.of("s1", moodleIdentity("123"), "s2", moodleIdentity("456"));

        var result = exportIds(List.of(first, row("s2", "0", "10")), identities);

        assertEquals(new MoodleGradeExport.Summary(2, 0), result.summary());
        assertEquals("https://moodle.example", result.issuer());
        assertEquals("userid,Interpolation\n123,7.50\n456,0.00\n", Files.readString(output));
    }

    @Test void automaticExportSharesLatestSelectionAndNeedsNoIdentityForUnavailableScores() throws IOException {
        var old = attempt("s1", 101L, 1L, "2026-10-01T12:00:00Z", "10");
        var current = attempt("s1", 101L, 2L, "2026-10-02T12:00:00Z", null);
        current.put("collectionStatus", "missing");
        var other = attempt("s2", 102L, 3L, "2026-10-02T12:00:00Z", "5");
        var latest = FeedbackListing.latest(List.of(enrollment("s1", 101L), enrollment("s2", 102L)),
                List.of(old, current, other), List.of());

        var result = exportIds(latest, Map.of("s2", moodleIdentity("456")));

        assertEquals(new MoodleGradeExport.Summary(1, 1), result.summary());
        assertEquals("userid,Interpolation\n456,5.00\n", Files.readString(output));
    }

    @Test void automaticExportFailsForMissingOrSelfIdentityWithoutGuessingFromStudentId() throws IOException {
        var row = row("123", "5", "10");
        assertThrows(IllegalArgumentException.class, () -> exportIds(List.of(row), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> exportIds(List.of(row), Map.of("123", new Identity("self", "123", "Moodle Name"))));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "opaque-subject", "0", "-1", "+12", "00123", "123 ", " 123", "1.0", "1e2",
            "9223372036854775808", "12345678901234567890"})
    void automaticExportRejectsNoncanonicalMoodleUserIds(String subject) throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> exportIds(List.of(row("s1", "5", "10")), Map.of("s1", moodleIdentity(subject))));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"moodle:", "moodle:example.org", "moodle:file:///tmp/moodle", "moodle:https:///missing-host",
            "moodle:https://person@moodle.example", "moodle:https://moodle.example/#fragment", "other:https://moodle.example"})
    void automaticExportRejectsInvalidIssuerSources(String source) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> exportIds(List.of(row("s1", "5", "10")),
                Map.of("s1", new Identity(source, "123", "Alice"))));
        assertFalse(Files.exists(output));
    }

    @Test void automaticExportRejectsDuplicateUserIdsAndMixedMoodleSitesBeforeWriting() throws IOException {
        var rows = List.of(row("s1", "5", "10"), row("s2", "5", "10"));
        assertThrows(IllegalArgumentException.class,
                () -> exportIds(rows, Map.of("s1", moodleIdentity("123"), "s2", moodleIdentity("123"))));
        assertThrows(IllegalArgumentException.class, () -> exportIds(rows, Map.of("s1", moodleIdentity("123"),
                "s2", new Identity("moodle:https://other.example", "456", "Bob"))));
        assertFalse(Files.exists(output));
    }

    @Test void automaticExportSharesScalingExpectedMaximumAndHeaderValidation() throws IOException {
        var rows = List.of(row("s1", "5", "20"));
        var identities = Map.of("s1", moodleIdentity("123"));
        assertThrows(IllegalArgumentException.class, () -> FeedbackMoodleExport.exportByMoodleId(rows, identities,
                output, "Interpolation", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 2));
        assertThrows(IllegalArgumentException.class, () -> FeedbackMoodleExport.exportByMoodleId(rows, identities,
                output, "UserId", null, BigDecimal.TEN, BigDecimal.ZERO, 2));
        assertFalse(Files.exists(output));

        FeedbackMoodleExport.exportByMoodleId(rows, identities, output, "Interpolation", new BigDecimal("20"),
                BigDecimal.TEN, new BigDecimal("0.75"), 1);

        assertEquals("userid,Interpolation\n123,3.3\n", Files.readString(output));
    }

    private FeedbackMoodleExport.MoodleIdExport exportIds(List<Map<String, Object>> rows,
            Map<String, Identity> identities) throws IOException {
        return FeedbackMoodleExport.exportByMoodleId(rows, identities, output, "Interpolation", null,
                BigDecimal.TEN, BigDecimal.ZERO, 2);
    }

    private static Identity moodleIdentity(String subject) {
        return new Identity("moodle:https://moodle.example", subject, "Moodle Name");
    }

    private MoodleGradeExport.Summary export(List<Map<String, Object>> rows, String sourceMax) throws IOException {
        return FeedbackMoodleExport.export(rows, mapping, output, "Interpolation",
                sourceMax == null ? null : new BigDecimal(sourceMax), BigDecimal.TEN, BigDecimal.ZERO, 2);
    }

    private static Map<String, Object> row(String student, String score, String maximum) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lectureId", "lecture");
        row.put("assignmentId", "assignment");
        row.put("studentId", student);
        row.put("collectionStatus", "received");
        row.put("feedbackStatus", "failed");
        row.put("score", score == null ? null : new BigDecimal(score));
        row.put("maxScore", maximum == null ? null : new BigDecimal(maximum));
        return row;
    }

    private static Map<String, Object> attempt(String student, long repository, long run, String created, String score) {
        var row = row(student, score, "10");
        row.put("repositoryId", repository);
        row.put("runId", run);
        row.put("runAttempt", 1);
        row.put("createdAt", created);
        return row;
    }

    private static Enrollment enrollment(String student, long repository) {
        return new Enrollment("lecture", "assignment", student, student, repository, student,
                "self", "2026-10-01T00:00:00Z", repository, "org/task-" + student,
                "https://github.com/org/task-" + student, "active", null);
    }
}
