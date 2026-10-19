package org.finmath.classroom.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.junit.jupiter.api.Test;

class FeedbackListingTest {
    private final Enrollment alice = enrollment("lecture", "task", "alice", 101L);

    @Test void newerRunWinsOverOldRerunOrLaterReceiptAndUsesHighestAttempt() {
        var oldRerun = result(alice, 900, 20, "2026-10-01T12:00:00Z", "received");
        oldRerun.put("receivedAt", "2026-10-20T12:00:00Z");
        var firstAttempt = result(alice, 100, 1, "2026-10-02T12:00:00Z", "received");
        var latestAttempt = result(alice, 100, 2, "2026-10-02T12:00:00Z", "received");
        var rows = FeedbackListing.latest(List.of(alice), List.of(latestAttempt, oldRerun, firstAttempt), List.of());

        assertEquals(1, rows.size());
        assertEquals(100L, rows.get(0).get("runId"));
        assertEquals(2, rows.get(0).get("runAttempt"));
    }

    @Test void timestampsAreComparedAsInstantsAndRunIdBreaksCreationTimeTies() {
        var previous = result(alice, 999, 1, "2026-10-02T12:00:00Z", "received");
        var newer = result(alice, 100, 8, "2026-10-02T12:00:00.100Z", "received");
        var sameTime = result(alice, 101, 1, "2026-10-02T12:00:00.100Z", "received");

        var row = FeedbackListing.latest(List.of(alice), List.of(sameTime, previous, newer), List.of()).get(0);

        assertEquals(101L, row.get("runId"));
        assertEquals(1, row.get("runAttempt"));
    }

    @Test void newestUnavailableAttemptNeverFallsBackToAnOlderScore() {
        for (String status : List.of("missing", "pending", "invalid", "expired", "unavailable")) {
            var older = result(alice, 100, 1, "2026-10-01T12:00:00Z", "received");
            var current = result(alice, 101, 1, "2026-10-02T12:00:00Z", status);
            var row = FeedbackListing.latest(List.of(alice), List.of(older, current), List.of()).get(0);

            assertEquals(101L, row.get("runId"), status);
            assertEquals(status, row.get("collectionStatus"));
            assertNull(row.get("score"));
            assertNull(row.get("feedbackStatus"));
        }
        var completed = result(alice, 100, 1, "2026-10-01T12:00:00Z", "received");
        var pending = result(alice, 100, 2, "2026-10-01T12:00:00Z", "pending");
        var row = FeedbackListing.latest(List.of(alice), List.of(completed, pending), List.of()).get(0);
        assertEquals(2, row.get("runAttempt"));
        assertEquals("pending", row.get("collectionStatus"));
        assertNull(row.get("score"));
    }

    @Test void associatesCurrentRepositoryAndCompleteEnrollmentIdentityInEnrollmentOrder() {
        Enrollment bob = enrollment("lecture", "task", "bob", 102L);
        Enrollment otherTask = enrollment("lecture", "other", "alice", 103L);
        Enrollment otherLecture = enrollment("other", "task", "alice", 104L);
        List<Enrollment> enrollments = List.of(bob, otherTask, alice, otherLecture);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Enrollment enrollment : enrollments) {
            results.add(result(enrollment, enrollment.repositoryId(), 1, "2026-10-01T12:00:00Z", "received"));
        }
        results.add(result(enrollment("lecture", "task", "alice", 99L), 900, 1,
                "2026-10-20T12:00:00Z", "received"));
        for (String field : List.of("lectureId", "assignmentId", "studentId")) {
            var unrelated = result(alice, 901, 1, "2026-10-20T12:00:00Z", "received");
            unrelated.put(field, "unrelated");
            results.add(unrelated);
        }
        results.add(result(enrollment("lecture", "task", "deleted", 105L), 902, 1,
                "2026-10-20T12:00:00Z", "received"));

        var rows = FeedbackListing.latest(enrollments, results, List.of());

        assertEquals(List.of(102L, 103L, 101L, 104L), rows.stream().map(row -> row.get("runId")).toList());
        assertEquals(List.of("bob", "alice", "alice", "alice"), rows.stream().map(row -> row.get("studentId")).toList());
    }

    @Test void includesUnseenAndUnprovisionedEnrollmentsWithNullScores() {
        Enrollment unprovisioned = enrollment("lecture", "task", "bob", null);
        var rows = FeedbackListing.latest(List.of(alice, unprovisioned), List.of(), List.of());

        assertEquals(2, rows.size());
        for (var row : rows) {
            assertEquals("not_collected", row.get("collectionStatus"));
            for (String field : List.of("runId", "runAttempt", "commit", "score", "maxScore", "feedbackStatus")) {
                assertTrue(row.containsKey(field));
                assertNull(row.get(field));
            }
            assertEquals(false, row.get("authoritative"));
            assertEquals("student-workflow", row.get("provenance"));
            assertFalse(((String) row.get("problem")).isBlank());
        }
        assertEquals("alice", rows.get(0).get("name"));
        assertEquals(101L, rows.get(0).get("repositoryId"));
        assertNull(rows.get(1).get("repositoryId"));
        assertTrue(((String) rows.get(1).get("problem")).contains("not been provisioned"));
    }

    @Test void usesOnlyChecksForTheCurrentRepositoryAndEnrollment() {
        var check = check(alice, "unavailable", "GitHub feedback request failed (HTTP 403).");
        var row = FeedbackListing.latest(List.of(alice), List.of(), List.of(check)).get(0);
        assertEquals("unavailable", row.get("collectionStatus"));
        assertEquals(check.get("problem"), row.get("problem"));
        assertEquals(check.get("lastCheckedAt"), row.get("lastCheckedAt"));
        assertNull(row.get("score"));

        var deferred = check(alice, "deferred", "GitHub feedback request failed (HTTP 429).");
        row = FeedbackListing.latest(List.of(alice), List.of(), List.of(deferred)).get(0);
        assertEquals("unavailable", row.get("collectionStatus"));
        assertEquals(deferred.get("problem"), row.get("problem"));
        assertNull(row.get("score"));

        for (String field : List.of("repositoryId", "lectureId", "assignmentId", "studentId")) {
            var stale = new LinkedHashMap<>(check);
            stale.put(field, field.equals("repositoryId") ? 99L : "unrelated");
            row = FeedbackListing.latest(List.of(alice), List.of(), List.of(stale)).get(0);
            assertEquals("not_collected", row.get("collectionStatus"), field);
            assertNull(row.get("lastCheckedAt"));
            assertNotEquals(check.get("problem"), row.get("problem"));
        }
        row = FeedbackListing.latest(List.of(alice), List.of(), List.of(check(alice, "ok", null))).get(0);
        assertEquals("not_collected", row.get("collectionStatus"));
    }

    @Test void keepsStoredRowsUnchangedAndShowsCurrentDisplayIdentity() {
        var result = result(alice, 100, 1, "2026-10-01T12:00:00Z", "received");
        result.put("name", "Old name");
        result.put("githubLogin", "old-login");
        var check = check(alice, "unavailable", "Later collection failed.");
        var originalResult = new LinkedHashMap<>(result);
        var originalCheck = new LinkedHashMap<>(check);

        var row = FeedbackListing.latest(List.of(alice), List.of(result), List.of(check)).get(0);

        assertEquals("alice", row.get("name"));
        assertEquals("alice", row.get("githubLogin"));
        assertEquals("received", row.get("collectionStatus"));
        assertEquals(5, row.get("score"));
        assertEquals(originalResult, result);
        assertEquals(originalCheck, check);
        row.put("score", 99);
        assertEquals(5, result.get("score"));
    }

    private static Enrollment enrollment(String lecture, String assignment, String student, Long repositoryId) {
        return new Enrollment(lecture, assignment, student, student, 42, student, "self", "2026-09-01T12:00:00Z",
                repositoryId, "org/" + student + "-" + assignment, null, repositoryId == null ? "pending" : "active", null);
    }

    private static Map<String, Object> identity(Enrollment enrollment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lectureId", enrollment.lectureId());
        row.put("assignmentId", enrollment.assignmentId());
        row.put("studentId", enrollment.studentId());
        row.put("repositoryId", enrollment.repositoryId());
        return row;
    }

    private static Map<String, Object> result(Enrollment enrollment, long runId, int attempt, String createdAt, String status) {
        Map<String, Object> row = identity(enrollment);
        row.put("runId", runId);
        row.put("runAttempt", attempt);
        row.put("createdAt", createdAt);
        row.put("collectionStatus", status);
        row.put("feedbackStatus", status.equals("received") ? "passed" : null);
        row.put("score", status.equals("received") ? 5 : null);
        row.put("maxScore", status.equals("received") ? 5 : null);
        return row;
    }

    private static Map<String, Object> check(Enrollment enrollment, String status, String problem) {
        Map<String, Object> row = identity(enrollment);
        row.put("status", status);
        row.put("problem", problem);
        row.put("lastCheckedAt", "2026-10-02T12:00:00Z");
        return row;
    }
}
