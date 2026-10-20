package org.finmath.classroom.registration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.registration.RegistrationService.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EnrollmentAccessTest {
    @TempDir Path directory;
    private final Instant acceptedAt = Instant.parse("2026-10-19T10:00:00Z");
    private final Assignment assignment = new Assignment("task", "Task", "example-org/template", "task", "main", null,
            "assignment-key", null, null);
    private final Lecture lecture = new Lecture("lecture", "Lecture", "example-org", "staff", "lecture-key", List.of(), List.of(assignment));
    private final ClassroomConfig config = new ClassroomConfig(List.of(lecture));
    private final User account = new User(42, "alice");
    private final Identity identity = new Identity("moodle:https://moodle.example", "student-42", "Alice Example");
    private EnrollmentStore store;
    private Enrollment pending;

    @BeforeEach void prepare() throws Exception {
        store = new EnrollmentStore(directory.resolve("classroom.db"), true, () -> 0x1234abcd);
        store.importConfiguration(config, null);
        pending = accept(acceptedAt);
    }

    private Enrollment accept(Instant time) throws IOException {
        Enrollment reserved = store.reserve(lecture, assignment, identity, account, time);
        store.recordRepository(reserved, 101);
        return store.finish(reserved, "invitation_pending", "Waiting for the student to accept the invitation");
    }

    @Test void activationChangesOnlyPendingStatusAndProblemAndIsIdempotent() throws Exception {
        Map<String, Object> enrollment = row("enrollments");
        Map<String, Object> student = row("students");
        Map<String, Object> definition = row("assignments");

        assertTrue(store.activateInvitation(pending));

        Enrollment active = store.list(null, null).get(0);
        assertEquals("active", active.status());
        assertNull(active.problem());
        enrollment.put("status", "active");
        enrollment.put("problem", null);
        assertEquals(enrollment, row("enrollments"));
        assertEquals(student, row("students"));
        assertEquals(definition, row("assignments"));
        store.verifyConfiguration(config);
        assertFalse(store.activateInvitation(pending));
        assertEquals(active, store.list(null, null).get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "retryable", "needs_attention", "creating"})
    void activationNeverChangesAnotherEnrollmentStatus(String status) throws Exception {
        Enrollment current = store.finish(pending, status, "Preserve this status and problem");
        Map<String, Object> before = row("enrollments");

        assertFalse(store.activateInvitation(pending));
        assertFalse(store.activateInvitation(current));

        assertEquals(before, row("enrollments"));
    }

    @Test void retryStartedDuringAnAccessRequestKeepsItsOperationReservation() throws Exception {
        Enrollment retrying = store.retry(lecture, assignment, account);
        assertEquals("invitation_pending", retrying.status());
        Map<String, Object> before = row("enrollments");
        assertEquals(1, ((Number) before.get("busy")).intValue());

        assertFalse(store.activateInvitation(pending));
        assertFalse(store.activateInvitation(retrying));

        assertEquals(before, row("enrollments"));
        store.finish(retrying, "retryable", "The retry owns the outcome");
        assertEquals("retryable", store.list(null, null).get(0).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "repository_id", "accepted_at", "github_id"})
    void accessResponseForAStaleRepositoryAcceptanceOrAccountCannotActivate(String changedField) throws Exception {
        String update = switch (changedField) {
            case "repository" -> "UPDATE enrollments SET repository='example-org/replaced-repository'";
            case "repository_id" -> "UPDATE enrollments SET repository_id=999";
            case "accepted_at" -> "UPDATE enrollments SET accepted_at='2026-10-19T11:00:00Z'";
            case "github_id" -> "UPDATE students SET github_id=77";
            default -> throw new AssertionError(changedField);
        };
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db"));
                var statement = connection.createStatement()) {
            statement.executeUpdate(update);
        }
        Map<String, Object> before = row("enrollments");

        assertFalse(store.activateInvitation(pending));

        assertEquals(before, row("enrollments"));
    }

    @Test void deletionWhileAnAccessRequestIsRunningDoesNotRecreateTheEnrollment() throws Exception {
        store.delete("lecture", "task", pending.studentId());

        assertFalse(store.activateInvitation(pending));

        assertTrue(store.list(null, null).isEmpty());
        assertEquals(1, store.students(null).size());
    }

    @Test void deletingAndReacceptingTheSameStudentAndRepositoryDoesNotUseAnOldAccessResponse() throws Exception {
        store.delete("lecture", "task", pending.studentId());
        Enrollment reaccepted = accept(acceptedAt.plusSeconds(1));
        assertEquals(pending.studentId(), reaccepted.studentId());
        assertEquals(pending.repository(), reaccepted.repository());
        assertEquals(pending.repositoryId(), reaccepted.repositoryId());
        assertNotEquals(pending.acceptedAt(), reaccepted.acceptedAt());

        assertFalse(store.activateInvitation(pending));
        assertEquals(reaccepted, store.list(null, null).get(0));
        assertTrue(store.activateInvitation(reaccepted));
    }

    @Test void concurrentCollectorsActivateTheSameAcceptanceOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return store.activateInvitation(pending); });
            var second = executor.submit(() -> { start.await(); return store.activateInvitation(pending); });
            start.countDown();
            int activated = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, activated);
            assertEquals("active", store.list(null, null).get(0).status());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void readOnlyListingNeverActivatesPendingInvitationsAndRejectsWrites() throws Exception {
        EnrollmentStore readOnly = EnrollmentStore.readOnly(directory.resolve("classroom.db"));
        Map<String, Object> before = row("enrollments");

        assertEquals(List.of(pending), readOnly.list(null, null));
        assertEquals(List.of(pending), readOnly.list("lecture", "task"));
        assertThrows(IOException.class, () -> readOnly.activateInvitation(pending));

        assertEquals(before, row("enrollments"));
    }

    private Map<String, Object> row(String table) throws Exception {
        assertTrue(List.of("enrollments", "students", "assignments").contains(table));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("classroom.db"));
                var statement = connection.createStatement(); var result = statement.executeQuery("SELECT * FROM " + table)) {
            assertTrue(result.next());
            Map<String, Object> values = new LinkedHashMap<>();
            var metadata = result.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) values.put(metadata.getColumnName(column), result.getObject(column));
            assertFalse(result.next());
            return values;
        }
    }
}
