package org.finmath.classroom.feedback;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

class FeedbackStoreTest {
    @TempDir Path directory;
    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");
    private final byte[] archive = "downloaded feedback archive".getBytes(StandardCharsets.UTF_8);
    private final Enrollment enrollment = enrollment(12, "lecture", "exercise", "student-1");

    @Test void persistsAtomicResultWithIdentityProvenanceAndArchiveHash() throws Exception {
        Path registration = Files.writeString(directory.resolve("classroom.db"), "existing registration database");
        byte[] originalRegistration = Files.readAllBytes(registration);
        FeedbackStore store = new FeedbackStore(directory, true);
        JsonNode result = result("passed", "7");
        FeedbackSource.Run run = run(123, 2, "a".repeat(40));
        store.record(enrollment, run, "received", null, artifact(run), archive, result, now);
        store.checked(enrollment, "received", null, now);

        FeedbackStore reopened = new FeedbackStore(directory, false);
        assertTrue(reopened.received(12, 123, 2));
        assertEquals(result, reopened.result(12, 123, 2));
        Map<String, Object> row = reopened.list(null, null).get(0);
        assertEquals("lecture", row.get("lectureId"));
        assertEquals("exercise", row.get("assignmentId"));
        assertEquals("student-1", row.get("studentId"));
        assertEquals("Alice", row.get("name"));
        assertEquals(42L, row.get("githubId"));
        assertEquals("alice", row.get("githubLogin"));
        assertEquals("self", row.get("identitySource"));
        assertEquals(12L, row.get("repositoryId"));
        assertEquals("org/repository-12", row.get("repository"));
        assertEquals(123L, row.get("runId"));
        assertEquals(2, row.get("runAttempt"));
        assertEquals("a".repeat(40), row.get("commit"));
        assertEquals("main", row.get("branch"));
        assertEquals("push", row.get("event"));
        assertEquals("completed", row.get("runStatus"));
        assertEquals("success", row.get("conclusion"));
        assertEquals(".github/workflows/feedback.yml", row.get("workflowPath"));
        assertEquals(9L, row.get("workflowId"));
        assertEquals(now.minusSeconds(60).toString(), row.get("createdAt"));
        assertEquals(now.toString(), row.get("receivedAt"));
        assertEquals(now.toString(), row.get("lastCheckedAt"));
        assertEquals(1002L, row.get("artifactId"));
        assertEquals("sha256:upstream-digest", row.get("artifactDigest"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)), row.get("archiveSha256"));
        assertEquals("received", row.get("collectionStatus"));
        assertEquals("passed", row.get("feedbackStatus"));
        assertEquals(7, ((Number) row.get("score")).intValue());
        assertEquals(7, ((Number) row.get("maxScore")).intValue());
        assertEquals(false, row.get("authoritative"));
        assertEquals("student-workflow", row.get("provenance"));
        assertFalse(row.containsKey("result"));
        assertFalse(row.containsKey("result_json"));
        assertFalse(row.containsKey("feedback"));
        assertEquals(1, reopened.checks("lecture", "exercise").size());
        assertArrayEquals(originalRegistration, Files.readAllBytes(registration));
    }

    @Test void receivedResultAndMetadataSurviveDuplicateAndLateErrorNotifications() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        FeedbackSource.Run original = run(123, 1, "a".repeat(40));
        JsonNode accepted = result("failed", "3");
        store.record(enrollment, original, "received", null, artifact(original), archive, accepted, now);
        List<Map<String, Object>> before = store.list(null, null);

        FeedbackSource.Run changed = run(123, 1, "b".repeat(40));
        store.record(enrollment, changed, "received", null, artifact(changed), new byte[] {1, 2, 3}, result("passed", "7"), now.plusSeconds(10));
        for(String status : List.of("missing", "expired", "invalid", "unavailable", "pending")) {
            store.record(enrollment, changed, status, "Collection failed.", null, null, null, now.plusSeconds(20));
        }
        store.checked(enrollment, "expired", "Artifact expired upstream.", now.plusSeconds(20));

        assertEquals(before, store.list(null, null));
        assertEquals(accepted, store.result(12, 123, 1));
        assertEquals("expired", store.checks(null, null).get(0).get("status"));
        assertEquals(now.plusSeconds(20).toString(), store.checks(null, null).get(0).get("lastCheckedAt"));
    }

    @Test void preciseDecimalScoresSurvivePersistenceInRawAndSummaryResults() throws Exception {
        BigDecimal score = new BigDecimal("0.12345678901234567890123456789");
        BigDecimal maximum = new BigDecimal("7.98765432109876543210987654321");
        var result = JsonFiles.MAPPER.createObjectNode();
        result.put("status", "failed");
        result.put("score", score);
        result.put("max_score", maximum);
        result.putObject("feedback").putArray("cases").addObject().put("points", score).put("max_points", maximum);
        FeedbackSource.Run run = run(123, 1, "a".repeat(40));
        new FeedbackStore(directory, true).record(enrollment, run, "received", null, artifact(run), archive, result, now);

        FeedbackStore reopened = new FeedbackStore(directory, false);
        JsonNode restored = reopened.result(12, 123, 1);
        assertEquals(result, restored);
        assertEquals(score, restored.path("score").decimalValue());
        assertEquals(maximum, restored.path("max_score").decimalValue());
        assertEquals(score, restored.path("feedback").path("cases").get(0).path("points").decimalValue());
        Map<String, Object> summary = reopened.list(null, null).get(0);
        assertEquals(score, summary.get("score"));
        assertEquals(maximum, summary.get("maxScore"));
    }

    @Test void retryUpgradesMissingResultAndKeepsAllAttemptsInNumericOrder() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        for(long runId : List.of(9L, 100L, 20L)) {
            for(int attempt : List.of(1, 12, 2)) {
                store.record(enrollment, run(runId, attempt, "a".repeat(40)), "missing", "No artifact.", null, null, null, now);
            }
        }
        FeedbackSource.Run successful = run(20, 2, "a".repeat(40));
        store.record(enrollment, successful, "received", null, artifact(successful), archive, result("passed", "7"), now.plusSeconds(60));
        List<Map<String, Object>> rows = store.list("lecture", "exercise");
        assertEquals(List.of("100/12", "100/2", "100/1", "20/12", "20/2", "20/1", "9/12", "9/2", "9/1"),
                rows.stream().map(row -> row.get("runId") + "/" + row.get("runAttempt")).toList());
        assertTrue(store.received(12, 20, 2));
        assertFalse(store.received(12, 20, 1));
        assertFalse(store.received(99, 20, 2));
        assertNull(store.result(12, 999, 1));
        assertNull(store.result(12, 20, 1));
        assertTrue(store.list("other-lecture", null).isEmpty());
        assertTrue(store.list(null, "other-assignment").isEmpty());
    }

    @Test void unavailableCollectionAndIncompleteFeedbackKeepNullScores() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        int attempt = 1;
        for(String status : List.of("missing", "expired", "invalid", "unavailable", "pending")) {
            store.record(enrollment, run(123, attempt++, "a".repeat(40)), status, "Feedback unavailable.", null, null, null, now);
        }
        for(Map<String, Object> row : store.list(null, null)) {
            assertNull(row.get("score"));
            assertNull(row.get("maxScore"));
            assertNull(row.get("feedbackStatus"));
            assertNull(row.get("receivedAt"));
            assertNull(row.get("artifactId"));
        }
        FeedbackSource.Run incomplete = run(124, 1, "b".repeat(40));
        store.record(enrollment, incomplete, "received", null, artifact(incomplete), archive, result("incomplete", "null"), now);
        Map<String, Object> row = store.list(null, null).get(0);
        assertNull(row.get("score"));
        assertEquals(7, ((Number) row.get("maxScore")).intValue());
        assertEquals("incomplete", row.get("feedbackStatus"));
        assertTrue(store.received(12, 124, 1));
    }

    @Test void readonlyOpenAndAllReadsLeaveFilesUnchangedAndRejectWrites() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        FeedbackSource.Run run = run(123, 1, "a".repeat(40));
        store.record(enrollment, run, "received", null, artifact(run), archive, result("passed", "7"), now);
        store.checked(enrollment, "received", null, now);
        store.defer(now.plusSeconds(60));
        Path database = directory.resolve("feedback.db");
        byte[] bytes = Files.readAllBytes(database);
        var modified = Files.getLastModifiedTime(database);
        Set<Path> files;
        try(var paths = Files.list(directory)) { files = Set.copyOf(paths.toList()); }

        FeedbackStore readonly = new FeedbackStore(directory, false);
        assertEquals(1, readonly.list(null, null).size());
        assertEquals(1, readonly.checks(null, null).size());
        assertTrue(readonly.received(12, 123, 1));
        assertNotNull(readonly.result(12, 123, 1));
        assertEquals(now.plusSeconds(60), readonly.deferredUntil());
        assertThrows(IOException.class, () -> readonly.record(enrollment, run, "missing", null, null, null, null, now));
        assertThrows(IOException.class, () -> readonly.checked(enrollment, "unavailable", null, now));
        assertThrows(IOException.class, () -> readonly.defer(now.plusSeconds(120)));
        assertArrayEquals(bytes, Files.readAllBytes(database));
        assertEquals(modified, Files.getLastModifiedTime(database));
        try(var paths = Files.list(directory)) { assertEquals(files, Set.copyOf(paths.toList())); }
    }

    @Test void readonlyOpenNeverCreatesMissingDatabaseOrInitializesEmptyFile() throws Exception {
        assertThrows(IOException.class, () -> new FeedbackStore(directory, false));
        assertFalse(Files.exists(directory.resolve("feedback.db")));
        Path database = privateFile(directory.resolve("feedback.db"));
        var modified = Files.getLastModifiedTime(database);
        assertThrows(IOException.class, () -> new FeedbackStore(directory, false));
        assertEquals(0, Files.size(database));
        assertEquals(modified, Files.getLastModifiedTime(database));
        try(var paths = Files.list(directory)) { assertEquals(List.of(database), paths.toList()); }
    }

    @Test void initializationRequiresExistingDataRootAndPrivateRegularFile() throws Exception {
        Path missingRoot = directory.resolve("missing");
        assertThrows(IOException.class, () -> new FeedbackStore(missingRoot, true));
        assertFalse(Files.exists(missingRoot));
        Path database = directory.resolve("feedback.db");
        Files.createDirectory(database);
        assertThrows(IOException.class, () -> new FeedbackStore(directory, true));
        Files.delete(database);
        Path other = Files.writeString(directory.resolve("other.db"), "untouched");
        Files.createSymbolicLink(database, other);
        assertThrows(IOException.class, () -> new FeedbackStore(directory, true));
        assertThrows(IOException.class, () -> new FeedbackStore(directory, false));
        assertEquals("untouched", Files.readString(other));
    }

    @Test void databaseIsCreatedOwnerOnlyAndBroadPermissionsAreRejected() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        new FeedbackStore(directory, true);
        Path database = directory.resolve("feedback.db");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(database));
        Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IOException.class, () -> new FeedbackStore(directory, false));
        assertThrows(IOException.class, () -> new FeedbackStore(directory, true));
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(database));
    }

    @Test void unrelatedSchemaAndUnsupportedVersionAreNeverMigrated() throws Exception {
        Path database = privateFile(directory.resolve("feedback.db"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE registration_only (value TEXT)");
            statement.execute("INSERT INTO registration_only VALUES ('keep')");
        }
        byte[] bytes = Files.readAllBytes(database);
        assertThrows(IOException.class, () -> new FeedbackStore(directory, true));
        assertArrayEquals(bytes, Files.readAllBytes(database));
        Files.delete(database);
        new FeedbackStore(directory, true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE schema_version SET version=2");
        }
        bytes = Files.readAllBytes(database);
        assertThrows(IOException.class, () -> new FeedbackStore(directory, true));
        assertThrows(IOException.class, () -> new FeedbackStore(directory, false));
        assertArrayEquals(bytes, Files.readAllBytes(database));
    }

    @Test void failedRecordRollsBackAndASecondConnectionCanRetry() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        FeedbackSource.Run run = run(123, 1, "a".repeat(40));
        store.record(enrollment, run, "missing", "No artifact.", null, null, null, now);
        List<Map<String, Object>> original = store.list(null, null);
        Path database = directory.resolve("feedback.db");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_received BEFORE UPDATE ON feedback_results WHEN NEW.result_json IS NOT NULL BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        }
        assertThrows(IOException.class, () -> store.record(enrollment, run, "received", null, artifact(run), archive, result("passed", "7"), now));
        assertEquals(original, store.list(null, null));
        assertFalse(store.received(12, 123, 1));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER reject_received");
        }
        new FeedbackStore(directory, true).record(enrollment, run, "received", null, artifact(run), archive, result("passed", "7"), now);
        assertTrue(store.received(12, 123, 1));
    }

    @Test void latestRepositoryCheckAndLongestRateLimitSurviveRestartAndOutOfOrderCalls() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        assertNull(store.deferredUntil());
        Instant later = now.plusNanos(500_000_000);
        store.defer(later);
        store.defer(now);
        store.checked(enrollment, "unavailable", "Rate limited.", later);
        store.checked(enrollment, "missing", "Older check.", now);
        Enrollment other = enrollment(13, "other-lecture", "exercise", "student-2");
        store.checked(other, "pending", null, now);
        FeedbackStore reopened = new FeedbackStore(directory, false);
        assertEquals(later, reopened.deferredUntil());
        assertEquals(2, reopened.checks(null, null).size());
        Map<String, Object> check = reopened.checks("lecture", "exercise").get(0);
        assertEquals("unavailable", check.get("status"));
        assertEquals("Rate limited.", check.get("problem"));
        assertEquals(later.toString(), check.get("lastCheckedAt"));
        assertTrue(reopened.checks("lecture", "other").isEmpty());
    }

    @Test void simultaneousInitializersAndCollectorsKeepOneResultAndMaximumDelay() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        FeedbackSource.Run run = run(123, 1, "a".repeat(40));
        try {
            Future<Void> first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                FeedbackStore store = new FeedbackStore(directory, true);
                store.record(enrollment, run, "received", null, artifact(run), archive, result("passed", "7"), now);
                store.defer(now.plusSeconds(100));
                return null;
            });
            Future<Void> second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                FeedbackStore store = new FeedbackStore(directory, true);
                store.record(enrollment, run, "missing", "No artifact.", null, null, null, now.plusSeconds(10));
                store.defer(now.plusSeconds(10));
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        FeedbackStore store = new FeedbackStore(directory, false);
        assertEquals(1, store.list(null, null).size());
        assertEquals(result("passed", "7"), store.result(12, 123, 1));
        assertEquals(now.plusSeconds(100), store.deferredUntil());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("feedback.db")); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM schema_version")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test void inconsistentStatusAndResultAreRejectedBeforeWriting() throws Exception {
        FeedbackStore store = new FeedbackStore(directory, true);
        FeedbackSource.Run run = run(123, 1, "a".repeat(40));
        assertThrows(IllegalArgumentException.class, () -> store.record(enrollment, run, "missing", null, artifact(run), archive, result("passed", "7"), now));
        assertThrows(IllegalArgumentException.class, () -> store.record(enrollment, run, "received", null, artifact(run), archive, null, now));
        assertThrows(IllegalArgumentException.class, () -> store.record(enrollment, run, "received", null, artifact(run), null, result("passed", "7"), now));
        assertThrows(IllegalArgumentException.class, () -> store.record(enrollment(99, "lecture", "exercise", "other"), run, "missing", null, null, null, null, now));
        assertTrue(store.list(null, null).isEmpty());
    }

    private static Enrollment enrollment(long repositoryId, String lecture, String assignment, String student) {
        return new Enrollment(lecture, assignment, student, "Alice", 42, "alice", "self", "2026-09-01T12:00:00Z",
                repositoryId, "org/repository-" + repositoryId, "https://github.com/org/repository-" + repositoryId, "active", null);
    }

    private FeedbackSource.Run run(long id, int attempt, String commit) {
        return new FeedbackSource.Run(id, attempt, 12, "org/repository-12", "main", commit, "push", "completed", "success",
                ".github/workflows/feedback.yml", 9, now.minusSeconds(60));
    }

    private static FeedbackSource.Artifact artifact(FeedbackSource.Run run) {
        return new FeedbackSource.Artifact(1000 + run.attempt(), "feedback-" + run.id() + "-" + run.attempt(), run.id(),
                run.repositoryId(), run.commit(), false, 500, "sha256:upstream-digest");
    }

    private static JsonNode result(String status, String score) {
        return JsonFiles.MAPPER.readTree("""
                {"schema_version":1,"kind":"finmath-classroom-feedback","authoritative":false,
                "status":"%s","score":%s,"max_score":7,"feedback":{"cases":[{"method":"example","status":"passed"}]}}
                """.formatted(status, score));
    }

    private static Path privateFile(Path path) throws IOException {
        return Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")
                ? Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createFile(path);
    }
}
