package org.finmath.classroom.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.finmath.classroom.feedback.FeedbackSource.Artifact;
import org.finmath.classroom.feedback.FeedbackSource.Run;
import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.EnrollmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.node.ObjectNode;

class FeedbackSyncTest {
    @TempDir Path directory;
    private final Instant now = Instant.parse("2026-10-19T12:00:00Z");
    private final String sha = "0123456789abcdef0123456789abcdef01234567";
    private final String repository = "example-org/assignment-student";
    private final FakeSource source = new FakeSource();
    private ClassroomConfig config;
    private EnrollmentStore enrollments;
    private FeedbackStore store;

    @BeforeEach void prepare() throws Exception {
        var assignment = new ClassroomConfig.Assignment("task", "Task", "example-org/template", "task", "main", null, "assignment-key", null, null);
        var otherAssignment = new ClassroomConfig.Assignment("other", "Other", "example-org/template", "other", "main", null, "assignment-key", null, null);
        config = new ClassroomConfig(List.of(new ClassroomConfig.Lecture("lecture", "Lecture", "example-org", null,
                "lecture-key", List.of(), List.of(assignment, otherAssignment))));
        enrollments = new EnrollmentStore(directory.resolve("classroom.db"), true);
        enrollments.importConfiguration(config, null);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO students VALUES ('lecture','alice','Alice',42,'alice','self','42')");
            statement.executeUpdate("INSERT INTO enrollments (lecture_id,assignment_id,student_id,accepted_name,identity_source,identity_subject,accepted_at,declaration_version,declaration,repository,repository_id,status) VALUES ('lecture','task','alice','Alice','self','42','2026-10-19T10:00:00Z','1','accepted','example-org/assignment-student',123456,'active')");
        }
        store = new FeedbackStore(directory, true);
    }

    private FeedbackSync syncer() { return new FeedbackSync(config, enrollments, store, source, Clock.fixed(now, ZoneOffset.UTC)); }
    private FeedbackSync.Report sync() throws IOException { return syncer().sync(null, null, "classroom.yml"); }
    private Run run(int attempt, String state) {
        return new Run(987654, attempt, 123456, repository, "main", sha, "push", state,
                state.equals("completed") ? "failure" : null, ".github/workflows/classroom.yml", 55, now.minusSeconds(86400 * 60));
    }

    private byte[] archive(int attempt, boolean wrongCommit) throws Exception {
        ObjectNode result = (ObjectNode) JsonFiles.read(Path.of("examples/grading/result.json"), 1024 * 1024);
        ((ObjectNode) result.path("context")).put("run_attempt", attempt);
        if (wrongCommit) ((ObjectNode) result.path("context")).put("commit", "a".repeat(40));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("result.json"));
            zip.write(JsonFiles.MAPPER.writeValueAsBytes(result));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private Artifact addArtifact(int attempt, boolean expired, boolean wrongCommit) throws Exception {
        byte[] bytes = archive(attempt, wrongCommit);
        Artifact artifact = new Artifact(attempt, "classroom-feedback-987654-" + attempt, 987654, 123456, sha, expired, bytes.length, null);
        source.artifacts.add(artifact);
        source.archives.put((long) attempt, bytes);
        return artifact;
    }

    @Test void collectsFailureAndSurvivesRepeatedCollectionAndUpstreamDeletion() throws Exception {
        source.runs = List.of(run(1, "completed"));
        addArtifact(1, false, false);
        var report = sync();
        assertEquals(1, report.received());
        assertEquals(0, report.errors());
        assertEquals(15, store.result(123456, 987654, 1).path("score").asInt());
        assertEquals("failed", store.list(null, null).get(0).get("feedbackStatus"));
        source.artifacts.clear();
        store = new FeedbackStore(directory, true);
        assertEquals(1, sync().alreadyStored());
        assertEquals(1, source.downloads);
        assertEquals(1, source.artifactRequests);
        assertEquals(15, new FeedbackStore(directory, false).result(123456, 987654, 1).path("score").asInt());
    }

    @Test void oldRunRerunInProgressStillRecoversPreviousAttemptThenNewAttempt() throws Exception {
        source.runs = List.of(run(2, "in_progress"));
        source.attempts.put(1, run(1, "completed"));
        addArtifact(1, false, false);
        assertEquals(1, sync().received());
        assertEquals("pending", store.list(null, null).get(0).get("collectionStatus"));
        source.runs = List.of(run(2, "completed"));
        addArtifact(2, false, false);
        var report = sync();
        assertEquals(1, report.received());
        assertEquals(1, report.alreadyStored());
        assertEquals(2, store.list(null, null).size());
        assertTrue(store.received(123456, 987654, 1));
        assertTrue(store.received(123456, 987654, 2));
        assertEquals(1, source.attemptRequests);
    }

    @Test void missingFeedbackIsNullAndRetriedWhenItArrives() throws Exception {
        source.runs = List.of(run(1, "completed"));
        assertEquals(1, sync().unavailable());
        var row = store.list(null, null).get(0);
        assertEquals("missing", row.get("collectionStatus"));
        assertNull(row.get("score"));
        addArtifact(1, false, false);
        assertEquals(1, sync().received());
        assertEquals(15, store.result(123456, 987654, 1).path("score").asInt());
    }

    @Test void unavailableOldAttemptDoesNotBlockNewerResultAndIsRetriedLater() throws Exception {
        source.runs = List.of(run(2, "completed"));
        source.attemptFailure = new FeedbackSource.Failure("private response", 404, null);
        addArtifact(2, false, false);
        var report = sync();
        assertEquals(1, report.errors());
        assertEquals(1, report.received());
        assertTrue(store.received(123456, 987654, 2));
        var older = store.list(null, null).get(1);
        assertEquals("unavailable", older.get("collectionStatus"));
        assertEquals("unknown", older.get("runStatus"));
        assertNull(older.get("conclusion"));
        assertNull(older.get("score"));
        assertFalse(older.toString().contains("private response"));
        source.attemptFailure = null;
        source.attempts.put(1, run(1, "completed"));
        addArtifact(1, false, false);
        assertEquals(1, sync().received());
        assertTrue(store.received(123456, 987654, 1));
    }

    @Test void replacedRepositoryIsNeverReadForArtifacts() throws Exception {
        source.repositoryId = 999;
        source.runs = List.of(run(1, "completed"));
        assertEquals(1, sync().errors());
        assertEquals(0, source.runRequests);
        assertTrue(store.list(null, null).isEmpty());
        assertEquals("unavailable", store.checks(null, null).get(0).get("status"));
    }

    @Test void onlyConfiguredBranchPushesAreCollected() throws Exception {
        Run base = run(1, "completed");
        source.runs = List.of(new Run(base.id(), 1, base.repositoryId(), repository, "main", sha, "pull_request", "completed", "failure", base.workflowPath(), 55, now),
                new Run(base.id(), 1, base.repositoryId(), repository, "other", sha, "push", "completed", "failure", base.workflowPath(), 55, now));
        assertEquals(0, sync().received());
        assertEquals(0, source.artifactRequests);
        assertTrue(store.list(null, null).isEmpty());
    }

    @Test void mismatchedResultIdentityIsInvalidNotZero() throws Exception {
        source.runs = List.of(run(1, "completed"));
        addArtifact(1, false, true);
        assertEquals(1, sync().errors());
        var row = store.list(null, null).get(0);
        assertEquals("invalid", row.get("collectionStatus"));
        assertNull(row.get("score"));
        assertFalse(store.received(123456, 987654, 1));
    }

    @Test void expiredArtifactsAreVisibleWithoutDownload() throws Exception {
        source.runs = List.of(run(1, "completed"));
        addArtifact(1, true, false);
        assertEquals(1, sync().unavailable());
        assertEquals("expired", store.list(null, null).get(0).get("collectionStatus"));
        assertEquals(0, source.downloads);
    }

    @Test void duplicateArtifactNamesAreRejected() throws Exception {
        source.runs = List.of(run(1, "completed"));
        Artifact artifact = addArtifact(1, false, false);
        source.artifacts.add(artifact);
        assertEquals(1, sync().errors());
        assertEquals("invalid", store.list(null, null).get(0).get("collectionStatus"));
        assertEquals(0, source.downloads);
    }

    @Test void rateLimitDelaySurvivesRestartAndSuppressesRequestsUntilDue() throws Exception {
        source.failure = new FeedbackSource.Failure("secret response body", 429, now.plusSeconds(900));
        var report = sync();
        assertTrue(report.deferred());
        assertEquals(1, report.errors());
        store = new FeedbackStore(directory, true);
        assertTrue(sync().deferred());
        assertEquals(1, source.runRequests);
        assertFalse(store.checks(null, null).toString().contains("secret"));
        source.failure = null;
        var later = new FeedbackSync(config, enrollments, store, source, Clock.fixed(now.plusSeconds(901), ZoneOffset.UTC));
        assertFalse(later.sync(null, null, "classroom.yml").deferred());
        assertEquals(2, source.runRequests);
    }

    @Test void invalidWorkflowRejectedBeforeNetworkOrWrites() {
        assertThrows(IllegalArgumentException.class, () -> syncer().sync(null, null, "../private.yml"));
        assertEquals(0, source.repositoryRequests);
    }

    @Test void permissionFailureDoesNotCreateADeferredResumeMarker() throws Exception {
        source.failure = new FeedbackSource.Failure("private permission response", 403, null);
        assertFalse(sync().deferred());
        assertEquals("unavailable", store.checks(null, null).get(0).get("status"));
        assertNull(store.deferredUntil());
    }

    @Test void rateLimitedScanResumesSecondStudentAfterRestart() throws Exception {
        addEnrollment("bob", "task", 123457);
        BudgetSource budget = new BudgetSource();
        budget.reset(3, now.plusSeconds(60));
        assertTrue(sync(budget, now, null, null).deferred());
        assertEquals(List.of(repository), budget.scanned);
        assertEquals("deferred", store.checks(null, null).get(1).get("status"));

        store = new FeedbackStore(directory, true);
        budget.reset(3, now.plusSeconds(121));
        assertTrue(sync(budget, now.plusSeconds(61), null, null).deferred());
        assertEquals(List.of(repository, "example-org/bob-task"), budget.scanned);
        assertEquals("example-org/bob-task", budget.requested.get(0));
        assertEquals(2, store.list(null, null).size());
    }

    @Test void repeatedRateLimitsAdvanceAcrossThreeStudentsBeforeWrapping() throws Exception {
        addEnrollment("bob", "task", 123457);
        addEnrollment("carol", "task", 123458);
        BudgetSource budget = new BudgetSource();
        for (int cycle = 0; cycle < 4; cycle++) {
            Instant time = now.plusSeconds(cycle * 61L);
            budget.reset(3, time.plusSeconds(60));
            store = new FeedbackStore(directory, true);
            assertTrue(sync(budget, time, null, null).deferred());
        }
        assertEquals(List.of(repository, "example-org/bob-task", "example-org/carol-task", repository), budget.scanned);
        assertEquals(3, store.list(null, null).size());
    }

    @Test void scopedScanPreservesOtherAssignmentsResumeMarkers() throws Exception {
        addEnrollment("bob", "task", 123457);
        addEnrollment("carol", "other", 123458);
        store.checked(enrollment("bob"), "deferred", "Rate limited.", now.minusSeconds(60));
        store.checked(enrollment("carol"), "deferred", "Rate limited.", now.minusSeconds(120));
        BudgetSource budget = new BudgetSource();
        budget.reset(100, now.plusSeconds(60));

        assertFalse(sync(budget, now, "lecture", "task").deferred());
        assertEquals(List.of("example-org/bob-task", repository), budget.requested);
        assertEquals("deferred", store.checks("lecture", "other").get(0).get("status"));

        budget.reset(100, now.plusSeconds(61));
        assertFalse(sync(budget, now.plusSeconds(1), null, null).deferred());
        assertEquals("example-org/carol-other", budget.requested.get(0));
    }

    @Test void oldestDeferredScopeIsResumedBeforeNewerMarkers() throws Exception {
        addEnrollment("bob", "task", 123457);
        addEnrollment("carol", "other", 123458);
        store.checked(enrollment("bob"), "deferred", "Rate limited.", now.minusSeconds(120));
        store.checked(enrollment("carol"), "deferred", "Rate limited.", now.minusSeconds(60));
        BudgetSource budget = new BudgetSource();
        budget.reset(100, now.plusSeconds(60));

        assertFalse(sync(budget, now, null, null).deferred());

        assertEquals(List.of("example-org/bob-task", "example-org/carol-other", repository), budget.requested);
    }

    @Test void ignoresResumeMarkersForOldRepositoriesOrDifferentStudents() throws Exception {
        addEnrollment("bob", "task", 123457);
        var bob = enrollment("bob");
        for (boolean oldRepository : List.of(true, false)) {
            Instant time = now.plusSeconds(oldRepository ? 0 : 2);
            var stale = new EnrollmentStore.Enrollment(bob.lectureId(), bob.assignmentId(), oldRepository ? bob.studentId() : "former-student",
                    bob.name(), bob.githubId(), bob.githubLogin(), bob.identitySource(), bob.acceptedAt(),
                    oldRepository ? 999L : bob.repositoryId(), bob.repository(), bob.repositoryUrl(), bob.status(), bob.problem());
            store.checked(stale, "deferred", "Rate limited.", time);
            BudgetSource budget = new BudgetSource();
            budget.reset(100, now.plusSeconds(60));

            assertFalse(sync(budget, time.plusSeconds(1), null, null).deferred());
            assertEquals(List.of(repository, "example-org/bob-task"), budget.requested);
        }
    }

    @Test void emptyEnrollmentScopeDoesNotPollOrRemoveResumeMarkers() throws Exception {
        store.checked(enrollment("alice"), "deferred", "Rate limited.", now.minusSeconds(60));
        BudgetSource budget = new BudgetSource();
        budget.reset(100, now.plusSeconds(60));
        assertEquals(0, sync(budget, now, "lecture", "other").repositories());
        assertTrue(budget.requested.isEmpty());
        assertEquals("deferred", store.checks(null, null).get(0).get("status"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db")); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM enrollments");
        }
        assertEquals(0, sync(budget, now, null, null).repositories());
        assertTrue(budget.requested.isEmpty());
    }

    private FeedbackSync.Report sync(FeedbackSource github, Instant time, String lecture, String assignment) throws IOException {
        return new FeedbackSync(config, enrollments, store, github, Clock.fixed(time, ZoneOffset.UTC)).sync(lecture, assignment, "classroom.yml");
    }

    private EnrollmentStore.Enrollment enrollment(String student) throws IOException {
        return enrollments.list(null, null).stream().filter(enrollment -> student.equals(enrollment.studentId())).findFirst().orElseThrow();
    }

    private void addEnrollment(String student, String assignment, long repositoryId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db"));
                var insertStudent = connection.prepareStatement("INSERT INTO students VALUES ('lecture',?,?,?,?,'self',?)");
                var insertEnrollment = connection.prepareStatement("INSERT INTO enrollments (lecture_id,assignment_id,student_id,accepted_name,identity_source,identity_subject,accepted_at,declaration_version,declaration,repository,repository_id,status) VALUES ('lecture',?,?,?,'self',?,'2026-10-19T10:00:00Z','1','accepted',?,?,'active')")) {
            String name = Character.toUpperCase(student.charAt(0)) + student.substring(1);
            insertStudent.setString(1, student);
            insertStudent.setString(2, name);
            insertStudent.setLong(3, repositoryId);
            insertStudent.setString(4, student);
            insertStudent.setString(5, Long.toString(repositoryId));
            insertStudent.executeUpdate();
            insertEnrollment.setString(1, assignment);
            insertEnrollment.setString(2, student);
            insertEnrollment.setString(3, name);
            insertEnrollment.setString(4, Long.toString(repositoryId));
            insertEnrollment.setString(5, "example-org/" + student + "-" + assignment);
            insertEnrollment.setLong(6, repositoryId);
            insertEnrollment.executeUpdate();
        }
    }

    private final class BudgetSource implements FeedbackSource {
        private final Map<String, Long> repositoryIds = new HashMap<>();
        private final List<String> requested = new ArrayList<>();
        private final List<String> scanned = new ArrayList<>();
        private int remaining;
        private Instant retryAt;

        private BudgetSource() throws IOException {
            for (var enrollment : enrollments.list(null, null)) repositoryIds.put(enrollment.repository(), enrollment.repositoryId());
        }

        private void reset(int requests, Instant retry) {
            remaining = requests;
            retryAt = retry;
            requested.clear();
        }

        private void request() throws Failure {
            if (remaining-- <= 0) throw new Failure("private rate-limit response", 429, retryAt);
        }

        public Repository repository(String name) throws IOException {
            requested.add(name);
            request();
            return new Repository(repositoryIds.get(name), name, true, false);
        }

        public List<Run> runs(String name, String workflow) throws IOException {
            request();
            scanned.add(name);
            return List.of(new Run(repositoryIds.get(name), 1, repositoryIds.get(name), name, "main", sha,
                    "push", "completed", "success", ".github/workflows/" + workflow, 55, now.minusSeconds(86400)));
        }

        public List<Artifact> artifacts(String name, long run) throws IOException { request(); return List.of(); }
        public Run attempt(String name, long run, int attempt) { throw new AssertionError("No older attempt expected"); }
        public byte[] download(String name, long artifact) { throw new AssertionError("Missing artifacts cannot be downloaded"); }
    }

    private final class FakeSource implements FeedbackSource {
        long repositoryId = 123456;
        int repositoryRequests, runRequests, artifactRequests, attemptRequests, downloads;
        List<Run> runs = List.of();
        List<Artifact> artifacts = new ArrayList<>();
        Map<Integer, Run> attempts = new HashMap<>();
        Map<Long, byte[]> archives = new HashMap<>();
        Failure failure, attemptFailure;

        public Repository repository(String name) { repositoryRequests++; return new Repository(repositoryId, name, true, false); }
        public List<Run> runs(String name, String workflow) throws IOException {
            assertEquals("classroom.yml", workflow);
            runRequests++;
            if (failure != null) throw failure;
            return runs;
        }
        public Run attempt(String name, long run, int attempt) throws IOException {
            attemptRequests++;
            if (attemptFailure != null) throw attemptFailure;
            return attempts.get(attempt);
        }
        public List<Artifact> artifacts(String name, long run) { artifactRequests++; return List.copyOf(artifacts); }
        public byte[] download(String name, long artifact) { downloads++; return archives.get(artifact); }
    }
}
