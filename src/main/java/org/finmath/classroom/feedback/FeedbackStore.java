package org.finmath.classroom.feedback;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

/** Private, independent SQLite history of non-authoritative student workflow feedback. */
public final class FeedbackStore {
    private static final Set<String> COLLECTION_STATUSES = Set.of("received", "missing", "expired", "invalid", "unavailable", "pending");
    private static final String FILTER = " WHERE (? IS NULL OR lecture_id=?) AND (? IS NULL OR assignment_id=?)";
    private static final String RESULTS_SCHEMA = """
            CREATE TABLE feedback_results (
                repository_id INTEGER NOT NULL,
                run_id INTEGER NOT NULL,
                run_attempt INTEGER NOT NULL CHECK(run_attempt > 0),
                lecture_id TEXT NOT NULL,
                assignment_id TEXT NOT NULL,
                student_id TEXT NOT NULL,
                name TEXT NOT NULL,
                github_id INTEGER NOT NULL,
                github_login TEXT NOT NULL,
                identity_source TEXT NOT NULL,
                repository TEXT NOT NULL,
                branch TEXT NOT NULL,
                commit_sha TEXT NOT NULL,
                event TEXT NOT NULL,
                run_status TEXT NOT NULL,
                conclusion TEXT,
                workflow_path TEXT NOT NULL,
                workflow_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                collection_status TEXT NOT NULL CHECK(collection_status IN ('received','missing','expired','invalid','unavailable','pending')),
                problem TEXT,
                received_at TEXT,
                last_checked_at TEXT NOT NULL,
                artifact_id INTEGER,
                artifact_name TEXT,
                artifact_digest TEXT,
                archive_sha256 TEXT,
                result_json TEXT,
                PRIMARY KEY(repository_id,run_id,run_attempt),
                CHECK((collection_status='received' AND result_json IS NOT NULL AND received_at IS NOT NULL AND archive_sha256 IS NOT NULL)
                    OR (collection_status<>'received' AND result_json IS NULL AND received_at IS NULL))
            )
            """;
    private static final String RECORD = """
            INSERT INTO feedback_results (
                repository_id,run_id,run_attempt,lecture_id,assignment_id,student_id,name,github_id,github_login,identity_source,
                repository,branch,commit_sha,event,run_status,conclusion,workflow_path,workflow_id,created_at,
                collection_status,problem,received_at,last_checked_at,artifact_id,artifact_name,artifact_digest,archive_sha256,result_json
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(repository_id,run_id,run_attempt) DO UPDATE SET
                lecture_id=excluded.lecture_id,assignment_id=excluded.assignment_id,student_id=excluded.student_id,
                name=excluded.name,github_id=excluded.github_id,github_login=excluded.github_login,identity_source=excluded.identity_source,
                repository=excluded.repository,branch=excluded.branch,commit_sha=excluded.commit_sha,event=excluded.event,
                run_status=excluded.run_status,conclusion=excluded.conclusion,workflow_path=excluded.workflow_path,
                workflow_id=excluded.workflow_id,created_at=excluded.created_at,collection_status=excluded.collection_status,
                problem=excluded.problem,received_at=excluded.received_at,last_checked_at=excluded.last_checked_at,
                artifact_id=excluded.artifact_id,artifact_name=excluded.artifact_name,artifact_digest=excluded.artifact_digest,
                archive_sha256=excluded.archive_sha256,result_json=excluded.result_json
            WHERE feedback_results.result_json IS NULL
            """;

    private final String jdbcUrl;
    private final boolean readOnly;

    /** Only an explicit writable open creates feedback.db; a read-only open never initializes it. */
    public FeedbackStore(Path dataRoot, boolean initialize) throws IOException {
        Path target;
        try {
            target = SafeFiles.validateOutput(dataRoot.resolve("feedback.db"), true);
        } catch(IllegalArgumentException exception) {
            throw new IOException("Feedback database must be a regular, non-symlink file.", exception);
        }
        if(initialize && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if(Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) {
                    Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else {
                    Files.createFile(target);
                }
            } catch(FileAlreadyExistsException exception) {
                // Another initializer won the race; validate its file before opening it.
            }
        }
        if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Feedback database does not exist; collect feedback to initialize it.");
        }
        if(Files.getFileStore(target).supportsFileAttributeView("posix")
                && !PosixFilePermissions.fromString("rw-------").containsAll(Files.getPosixFilePermissions(target))) {
            throw new IOException("Feedback database must be accessible only to its owner (chmod 600).");
        }
        readOnly = !initialize;
        jdbcUrl = "jdbc:sqlite:" + target.toUri() + (readOnly ? "?mode=ro" : "?mode=rw");
        if(initialize) {
            transaction(connection -> {
                try(Statement statement = connection.createStatement()) {
                    boolean empty;
                    try(ResultSet rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
                        empty = rows.next() && rows.getInt(1) == 0;
                    }
                    if(empty) {
                        statement.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)");
                        statement.execute("INSERT INTO schema_version VALUES (1)");
                        statement.execute(RESULTS_SCHEMA);
                        statement.execute("CREATE INDEX feedback_assignment ON feedback_results (lecture_id,assignment_id)");
                        statement.execute("""
                                CREATE TABLE repository_checks (
                                    repository_id INTEGER PRIMARY KEY,
                                    lecture_id TEXT NOT NULL,
                                    assignment_id TEXT NOT NULL,
                                    student_id TEXT NOT NULL,
                                    name TEXT NOT NULL,
                                    github_id INTEGER NOT NULL,
                                    github_login TEXT NOT NULL,
                                    identity_source TEXT NOT NULL,
                                    repository TEXT NOT NULL,
                                    status TEXT NOT NULL,
                                    problem TEXT,
                                    last_checked_at TEXT NOT NULL
                                )
                                """);
                        statement.execute("CREATE TABLE collector_state (id INTEGER PRIMARY KEY CHECK(id=1),deferred_until TEXT NOT NULL)");
                    }
                    verifySchema(statement);
                }
                return null;
            });
        } else {
            try(Connection connection = connect(); Statement statement = connection.createStatement()) {
                verifySchema(statement);
            } catch(SQLException exception) {
                throw new IOException("Cannot open a compatible feedback database; collect feedback to initialize it.", exception);
            }
        }
    }

    /** Whether this exact workflow attempt already has a validated, immutable result. */
    public boolean received(long repositoryId, long runId, int attempt) throws IOException {
        try(Connection connection = connect(); PreparedStatement query = prepare(connection,
                "SELECT 1 FROM feedback_results WHERE repository_id=? AND run_id=? AND run_attempt=? AND result_json IS NOT NULL",
                repositoryId, runId, attempt); ResultSet rows = query.executeQuery()) {
            return rows.next();
        } catch(SQLException exception) {
            throw new IOException("Cannot read feedback collection state.", exception);
        }
    }

    /** Stores one collection outcome atomically; a received result and its provenance are never replaced. */
    public void record(Enrollment enrollment, FeedbackSource.Run run, String collectionStatus, String problem,
            FeedbackSource.Artifact artifact, byte[] archive, JsonNode result, Instant now) throws IOException {
        Objects.requireNonNull(enrollment);
        Objects.requireNonNull(run);
        Objects.requireNonNull(now);
        if(!COLLECTION_STATUSES.contains(collectionStatus)) throw new IllegalArgumentException("Unknown feedback collection status.");
        boolean received = collectionStatus.equals("received");
        if(received != (result != null) || received && (!result.isObject() || archive == null)) {
            throw new IllegalArgumentException("Received feedback requires validated result JSON and archive bytes; other statuses cannot contain results.");
        }
        if(enrollment.repositoryId() == null || enrollment.repositoryId() != run.repositoryId()
                || run.repositoryId() <= 0 || run.id() <= 0 || run.attempt() <= 0) {
            throw new IllegalArgumentException("Feedback run must belong to the enrolled numeric repository and have a positive ID and attempt.");
        }
        String json = result == null ? null : JsonFiles.MAPPER.writeValueAsString(result);
        String archiveSha256 = archive == null ? null : sha256(archive);
        transaction(connection -> {
            execute(connection, RECORD, run.repositoryId(), run.id(), run.attempt(), enrollment.lectureId(), enrollment.assignmentId(),
                    enrollment.studentId(), enrollment.name(), enrollment.githubId(), enrollment.githubLogin(), enrollment.identitySource(),
                    run.repository(), run.branch(), run.commit(), run.event(), run.status(), run.conclusion(), run.workflowPath(), run.workflowId(),
                    run.createdAt().toString(), collectionStatus, problem, received ? now.toString() : null, now.toString(),
                    artifact == null ? null : artifact.id(), artifact == null ? null : artifact.name(), artifact == null ? null : artifact.digest(),
                    archiveSha256, json);
            return null;
        });
    }

    /** Lists all attempts, newest numeric run/attempt first; commits are historical labels, not branch-head assertions. */
    public List<Map<String, Object>> list(String lectureId, String assignmentId) throws IOException {
        try(Connection connection = connect(); PreparedStatement query = prepare(connection,
                "SELECT * FROM feedback_results" + FILTER + " ORDER BY run_id DESC,run_attempt DESC,repository_id",
                lectureId, lectureId, assignmentId, assignmentId); ResultSet rows = query.executeQuery()) {
            List<Map<String, Object>> results = new ArrayList<>();
            while(rows.next()) {
                Map<String, Object> row = identity(rows);
                row.put("runId", rows.getLong("run_id"));
                row.put("runAttempt", rows.getInt("run_attempt"));
                row.put("branch", rows.getString("branch"));
                row.put("commit", rows.getString("commit_sha"));
                row.put("event", rows.getString("event"));
                row.put("runStatus", rows.getString("run_status"));
                row.put("conclusion", rows.getString("conclusion"));
                row.put("workflowPath", rows.getString("workflow_path"));
                row.put("workflowId", rows.getLong("workflow_id"));
                row.put("createdAt", rows.getString("created_at"));
                row.put("collectionStatus", rows.getString("collection_status"));
                row.put("problem", rows.getString("problem"));
                row.put("receivedAt", rows.getString("received_at"));
                row.put("lastCheckedAt", rows.getString("last_checked_at"));
                row.put("artifactId", nullableLong(rows, "artifact_id"));
                row.put("artifactName", rows.getString("artifact_name"));
                row.put("artifactDigest", rows.getString("artifact_digest"));
                row.put("archiveSha256", rows.getString("archive_sha256"));
                JsonNode result = parseResult(rows.getString("result_json"));
                row.put("feedbackStatus", result == null ? null : result.path("status").asString(null));
                row.put("score", result == null ? null : number(result.path("score")));
                row.put("maxScore", result == null ? null : number(result.path("max_score")));
                row.put("authoritative", false);
                row.put("provenance", "student-workflow");
                results.add(row);
            }
            return List.copyOf(results);
        } catch(SQLException exception) {
            throw new IOException("Cannot list collected feedback.", exception);
        }
    }

    /** Records the latest repository check separately from immutable received feedback. */
    public void checked(Enrollment enrollment, String status, String problem, Instant now) throws IOException {
        Objects.requireNonNull(enrollment);
        Objects.requireNonNull(status);
        Objects.requireNonNull(now);
        if(enrollment.repositoryId() == null || enrollment.repositoryId() <= 0) {
            throw new IllegalArgumentException("Feedback checks require an enrolled numeric repository.");
        }
        transaction(connection -> {
            try(PreparedStatement query = prepare(connection, "SELECT last_checked_at FROM repository_checks WHERE repository_id=?",
                    enrollment.repositoryId()); ResultSet rows = query.executeQuery()) {
                if(rows.next() && Instant.parse(rows.getString(1)).isAfter(now)) return null;
            }
            execute(connection, """
                    INSERT INTO repository_checks (repository_id,lecture_id,assignment_id,student_id,name,github_id,github_login,identity_source,repository,status,problem,last_checked_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(repository_id) DO UPDATE SET
                        lecture_id=excluded.lecture_id,assignment_id=excluded.assignment_id,student_id=excluded.student_id,name=excluded.name,
                        github_id=excluded.github_id,github_login=excluded.github_login,identity_source=excluded.identity_source,
                        repository=excluded.repository,status=excluded.status,problem=excluded.problem,last_checked_at=excluded.last_checked_at
                    """, enrollment.repositoryId(), enrollment.lectureId(), enrollment.assignmentId(), enrollment.studentId(), enrollment.name(),
                    enrollment.githubId(), enrollment.githubLogin(), enrollment.identitySource(), enrollment.repository(), status, problem, now.toString());
            return null;
        });
    }

    public List<Map<String, Object>> checks(String lectureId, String assignmentId) throws IOException {
        try(Connection connection = connect(); PreparedStatement query = prepare(connection,
                "SELECT * FROM repository_checks" + FILTER + " ORDER BY lecture_id,assignment_id,name,student_id,repository_id",
                lectureId, lectureId, assignmentId, assignmentId); ResultSet rows = query.executeQuery()) {
            List<Map<String, Object>> checks = new ArrayList<>();
            while(rows.next()) {
                Map<String, Object> row = identity(rows);
                row.put("status", rows.getString("status"));
                row.put("problem", rows.getString("problem"));
                row.put("lastCheckedAt", rows.getString("last_checked_at"));
                checks.add(row);
            }
            return List.copyOf(checks);
        } catch(SQLException exception) {
            throw new IOException("Cannot list feedback repository checks.", exception);
        }
    }

    /** Returns the persisted rate-limit retry time, or null if none has been recorded. */
    public Instant deferredUntil() throws IOException {
        try(Connection connection = connect()) {
            return deferredUntil(connection);
        } catch(SQLException | java.time.DateTimeException exception) {
            throw new IOException("Cannot read feedback collection retry time.", exception);
        }
    }

    /** Concurrent collectors may extend a rate-limit delay, but cannot shorten it. */
    public void defer(Instant until) throws IOException {
        Objects.requireNonNull(until);
        transaction(connection -> {
            Instant previous = deferredUntil(connection);
            if(previous == null || until.isAfter(previous)) {
                execute(connection, "INSERT INTO collector_state (id,deferred_until) VALUES (1,?) ON CONFLICT(id) DO UPDATE SET deferred_until=excluded.deferred_until", until.toString());
            }
            return null;
        });
    }

    /** Returns the stored validated JSON for this attempt, or null if it has not been received. */
    public JsonNode result(long repositoryId, long runId, int attempt) throws IOException {
        try(Connection connection = connect(); PreparedStatement query = prepare(connection,
                "SELECT result_json FROM feedback_results WHERE repository_id=? AND run_id=? AND run_attempt=?",
                repositoryId, runId, attempt); ResultSet rows = query.executeQuery()) {
            return rows.next() ? parseResult(rows.getString(1)) : null;
        } catch(SQLException exception) {
            throw new IOException("Cannot read collected feedback result.", exception);
        }
    }

    private static void verifySchema(Statement statement) throws SQLException, IOException {
        try(ResultSet version = statement.executeQuery("SELECT version FROM schema_version")) {
            if(!version.next() || version.getInt(1) != 1 || version.next()) throw new IOException("Unsupported feedback database version.");
        }
        try(ResultSet ignored = statement.executeQuery("SELECT repository_id,run_id,run_attempt,result_json FROM feedback_results LIMIT 0")) { }
        try(ResultSet ignored = statement.executeQuery("SELECT repository_id,last_checked_at FROM repository_checks LIMIT 0")) { }
        try(ResultSet ignored = statement.executeQuery("SELECT deferred_until FROM collector_state LIMIT 0")) { }
    }

    private static Map<String, Object> identity(ResultSet rows) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lectureId", rows.getString("lecture_id"));
        row.put("assignmentId", rows.getString("assignment_id"));
        row.put("studentId", rows.getString("student_id"));
        row.put("name", rows.getString("name"));
        row.put("githubId", rows.getLong("github_id"));
        row.put("githubLogin", rows.getString("github_login"));
        row.put("identitySource", rows.getString("identity_source"));
        row.put("repositoryId", rows.getLong("repository_id"));
        row.put("repository", rows.getString("repository"));
        return row;
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Number number(JsonNode value) {
        return value.isNumber() ? value.numberValue() : null;
    }

    private static JsonNode parseResult(String json) throws IOException {
        if(json == null) return null;
        try {
            JsonNode result = JsonFiles.MAPPER.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json);
            if(result == null || !result.isObject()) throw new IOException("Stored feedback JSON is invalid.");
            return result;
        } catch(RuntimeException exception) {
            throw new IOException("Cannot read stored feedback JSON.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static Instant deferredUntil(Connection connection) throws SQLException {
        try(PreparedStatement query = prepare(connection, "SELECT deferred_until FROM collector_state WHERE id=1"); ResultSet rows = query.executeQuery()) {
            return rows.next() ? Instant.parse(rows.getString(1)) : null;
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try(Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            if(readOnly) statement.execute("PRAGMA query_only=ON");
        } catch(SQLException exception) {
            try { connection.close(); } catch(SQLException close) { exception.addSuppressed(close); }
            throw exception;
        }
        return connection;
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection connection) throws SQLException, IOException;
    }

    private <T> T transaction(Transaction<T> work) throws IOException {
        if(readOnly) throw new IOException("Feedback database was opened read-only.");
        try(Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                T result = work.run(connection);
                statement.execute("COMMIT");
                return result;
            } catch(SQLException | IOException | RuntimeException exception) {
                try { statement.execute("ROLLBACK"); } catch(SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch(SQLException exception) {
            throw new IOException("Feedback database operation failed.", exception);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for(int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
        } catch(SQLException exception) {
            try { statement.close(); } catch(SQLException close) { exception.addSuppressed(close); }
            throw exception;
        }
        return statement;
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try(PreparedStatement statement = prepare(connection, sql, values)) {
            statement.executeUpdate();
        }
    }
}
