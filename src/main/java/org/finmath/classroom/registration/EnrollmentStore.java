package org.finmath.classroom.registration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.ClassroomConfig.Student;
import org.finmath.classroom.registration.GitHubGateway.User;
import org.finmath.classroom.registration.RegistrationService.Identity;

/** SQLite enrollment state. Transactions are short; GitHub calls never hold a database lock. */
public final class EnrollmentStore {

	/** Administrative enrollment view; names and declarations remain private service data. */
	public record Enrollment(String lectureId, String assignmentId, String studentId, String name,
			long githubId, String githubLogin, String identitySource, String acceptedAt,
			Long repositoryId, String repository, String repositoryUrl, String status, String problem) { }

	private final String jdbcUrl;

	/** Opens a writable store; only explicit initialization may create or migrate the database. */
	public EnrollmentStore(Path database, boolean initialize) throws IOException {
		this(database, initialize, false);
	}

	/** Opens an existing database without creating files or modifying its schema. */
	public static EnrollmentStore readOnly(Path database) throws IOException {
		return new EnrollmentStore(database, false, true);
	}

	private EnrollmentStore(Path database, boolean initialize, boolean readOnly) throws IOException {
		Path target = SafeFiles.validateOutput(database, true);
		if(!Files.exists(target) && initialize) {
			if(Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) {
				Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			} else {
				Files.createFile(target);
			}
		}
		if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("Import registration configuration before opening the database.");
		}
		if(Files.getFileStore(target).supportsFileAttributeView("posix")
				&& !PosixFilePermissions.fromString("rw-------").containsAll(Files.getPosixFilePermissions(target))) {
			throw new IllegalArgumentException("Registration database must be accessible only to its owner (chmod 600).");
		}
		jdbcUrl = "jdbc:sqlite:" + target.toUri() + (readOnly ? "?mode=ro" : "?mode=rw");
		try(Connection connection = connect(); Statement statement = connection.createStatement()) {
			boolean empty;
			try(ResultSet result = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
				empty = result.next() && result.getInt(1) == 0;
			}
			if(empty && initialize) {
				try(InputStream input = EnrollmentStore.class.getResourceAsStream("/registration/schema-v1.sql")) {
					String schema = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
					statement.execute("BEGIN IMMEDIATE");
					for(String sql : schema.split(";")) {
						if(!sql.isBlank()) statement.execute(sql);
					}
					statement.execute("COMMIT");
				}
			}
			try(ResultSet version = statement.executeQuery("SELECT version FROM schema_version")) {
				if(!version.next() || version.getInt(1) != 1 || version.next()) {
					throw new IllegalArgumentException("Unsupported registration database version.");
				}
			}
		} catch(SQLException exception) {
			throw new IOException("Cannot open registration database; use a new Java database, not the Python reference database.", exception);
		}
	}

	/** Imports new lectures, assignments and optional roster entries, pinning supplied GitHub accounts. */
	public void importConfiguration(ClassroomConfig config, GitHubGateway github) throws IOException {
		Map<String, User> accounts = new HashMap<>();
		for(Lecture lecture : config.lectures()) {
			for(Student student : lecture.students()) {
				if(student.githubUsername() != null && !accounts.containsKey(student.githubUsername())) {
					accounts.put(student.githubUsername(), github.user(student.githubUsername()));
				}
			}
		}
		transaction(connection -> {
			for(Lecture lecture : config.lectures()) {
				execute(connection, "INSERT INTO lectures VALUES (?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title", lecture.id(), lecture.title());
				for(Assignment assignment : lecture.assignments()) {
					String definition = definition(lecture, assignment);
					execute(connection, "INSERT OR IGNORE INTO assignments VALUES (?,?,?)", lecture.id(), assignment.id(), definition);
					checkDefinition(connection, lecture.id(), assignment.id(), definition);
				}
				for(Student student : lecture.students()) {
					User account = accounts.get(student.githubUsername());
					execute(connection, "INSERT OR IGNORE INTO students (lecture_id,id,name,identity_source) VALUES (?,?,?,'roster')", lecture.id(), student.id(), student.name());
					try(PreparedStatement query = prepare(connection, "SELECT github_id FROM students WHERE lecture_id=? AND id=?", lecture.id(), student.id()); ResultSet row = query.executeQuery()) {
						row.next();
						Long previousId = nullableLong(row, "github_id");
						if(account != null && previousId != null && previousId != account.id()) {
							throw new IllegalArgumentException("Roster account is already pinned; an account correction requires administrator review.");
						}
					}
					if(account != null) {
						execute(connection, "UPDATE students SET github_id=?,github_login=? WHERE lecture_id=? AND id=?", account.id(), account.login(), lecture.id(), student.id());
					}
				}
			}
			return null;
		});
	}

	/** Refuses silently changed repository ownership, template, deadline or Moodle activity mappings. */
	public void verifyConfiguration(ClassroomConfig config) throws IOException {
		try(Connection connection = connect()) {
			for(Lecture lecture : config.lectures()) {
				for(Assignment assignment : lecture.assignments()) {
					checkDefinition(connection, lecture.id(), assignment.id(), definition(lecture, assignment));
				}
			}
		} catch(SQLException exception) {
			throw new IOException("Cannot read imported registration configuration.", exception);
		}
	}

	private static String definition(Lecture lecture, Assignment assignment) {
		return JsonFiles.MAPPER.writeValueAsString(List.of(lecture.organization(), Objects.toString(lecture.staffTeam(), ""),
				assignment.template(), assignment.repositoryPrefix(), assignment.branch(), Objects.toString(assignment.deadline(), ""),
				Objects.toString(assignment.moodleCourseId(), ""), Objects.toString(assignment.moodleResourceId(), "")));
	}

	private static void checkDefinition(Connection connection, String lecture, String assignment, String expected) throws SQLException {
		try(PreparedStatement query = prepare(connection, "SELECT definition FROM assignments WHERE lecture_id=? AND id=?", lecture, assignment); ResultSet row = query.executeQuery()) {
			if(!row.next() || !expected.equals(row.getString(1))) {
				throw new IllegalArgumentException("Assignment is not imported or its settings changed: " + lecture + "/" + assignment);
			}
		}
	}

	/** Reserves one acceptance across threads/processes, retaining the original declaration on retries. */
	Enrollment reserve(Lecture lecture, Assignment assignment, Identity identity, User account, Instant now) throws IOException {
		return transaction(connection -> {
			checkDefinition(connection, lecture.id(), assignment.id(), definition(lecture, assignment));
			String studentId = null;
			try(PreparedStatement query = prepare(connection, "SELECT id,github_id FROM students WHERE lecture_id=? AND identity_source=? AND identity_subject=?", lecture.id(), identity.source(), identity.subject()); ResultSet row = query.executeQuery()) {
				if(row.next()) {
					if(row.getLong("github_id") != account.id()) throw new IllegalArgumentException("This identity is already linked to another GitHub account.");
					studentId = row.getString("id");
				}
			}
			if(studentId == null) {
				try(PreparedStatement query = prepare(connection, "SELECT id,identity_source,identity_subject FROM students WHERE lecture_id=? AND github_id=?", lecture.id(), account.id()); ResultSet row = query.executeQuery()) {
					if(row.next()) {
						String previousSource = row.getString("identity_source");
						if(previousSource.startsWith("moodle:") && !identity.source().equals("self")) {
							throw new IllegalArgumentException("This GitHub account is already linked to another Moodle identity.");
						}
						studentId = row.getString("id");
						// A fallback login never downgrades a verified Moodle association.
						if(!previousSource.startsWith("moodle:")) {
							execute(connection, "UPDATE students SET identity_source=?,identity_subject=? WHERE lecture_id=? AND id=?", identity.source(), identity.subject(), lecture.id(), studentId);
						}
					}
				}
			}
			if(studentId == null) {
				studentId = UUID.randomUUID().toString();
				execute(connection, "INSERT INTO students VALUES (?,?,?,?,?,?,?)", lecture.id(), studentId, identity.name(), account.id(), account.login(), identity.source(), identity.subject());
			}
			execute(connection, "UPDATE students SET github_login=? WHERE lecture_id=? AND id=?", account.login(), lecture.id(), studentId);
			String repository = lecture.organization() + "/" + assignment.repositoryPrefix() + "-" + account.id();
			execute(connection, "INSERT INTO enrollments (lecture_id,assignment_id,student_id,accepted_name,identity_source,identity_subject,accepted_at,declaration_version,declaration,repository,status) VALUES (?,?,?,?,?,?,?,?,?,?,'creating') ON CONFLICT(lecture_id,assignment_id,student_id) DO NOTHING",
					lecture.id(), assignment.id(), studentId, identity.name(), identity.source(), identity.subject(), now.toString(), RegistrationService.DECLARATION_VERSION, RegistrationService.DECLARATION, repository);
			if(execute(connection, "UPDATE enrollments SET busy=1,problem=NULL WHERE lecture_id=? AND assignment_id=? AND student_id=? AND busy=0 AND status!='needs_attention'", lecture.id(), assignment.id(), studentId) != 1) {
				throw new IllegalArgumentException("Repository creation is in progress or needs instructor review. No second repository will be created.");
			}
			return enrollment(connection, lecture.id(), assignment.id(), studentId);
		});
	}

	void recordRepository(Enrollment enrollment, long repositoryId) throws IOException {
		transaction(connection -> {
			execute(connection, "UPDATE enrollments SET repository_id=?,status='retryable' WHERE lecture_id=? AND assignment_id=? AND student_id=?", repositoryId, enrollment.lectureId(), enrollment.assignmentId(), enrollment.studentId());
			return null;
		});
	}

	Enrollment finish(Enrollment enrollment, String status, String problem) throws IOException {
		return transaction(connection -> {
			execute(connection, "UPDATE enrollments SET status=?,problem=?,busy=0 WHERE lecture_id=? AND assignment_id=? AND student_id=?", status, problem, enrollment.lectureId(), enrollment.assignmentId(), enrollment.studentId());
			return enrollment(connection, enrollment.lectureId(), enrollment.assignmentId(), enrollment.studentId());
		});
	}

	/** Lists acceptances, optionally restricted to a lecture and assignment. Does not contact GitHub. */
	public List<Enrollment> list(String lectureId, String assignmentId) throws IOException {
		try(Connection connection = connect(); PreparedStatement query = prepare(connection, SELECT + " WHERE (? IS NULL OR e.lecture_id=?) AND (? IS NULL OR e.assignment_id=?) ORDER BY e.lecture_id,e.assignment_id,s.name,s.id", lectureId, lectureId, assignmentId, assignmentId); ResultSet rows = query.executeQuery()) {
			List<Enrollment> enrollments = new ArrayList<>();
			while(rows.next()) enrollments.add(readEnrollment(rows));
			return List.copyOf(enrollments);
		} catch(SQLException exception) {
			throw new IOException("Cannot list registrations.", exception);
		}
	}

	/** Lists imported and self-registered students, including students without an accepted assignment. */
	public List<Map<String, Object>> students(String lectureId) throws IOException {
		try(Connection connection = connect(); PreparedStatement query = prepare(connection, "SELECT * FROM students WHERE (? IS NULL OR lecture_id=?) ORDER BY lecture_id,name,id", lectureId, lectureId); ResultSet rows = query.executeQuery()) {
			List<Map<String, Object>> students = new ArrayList<>();
			while(rows.next()) {
				Map<String, Object> student = new LinkedHashMap<>();
				for(String column : List.of("lecture_id", "id", "name", "github_id", "github_login", "identity_source")) student.put(column, rows.getObject(column));
				students.add(student);
			}
			return students;
		} catch(SQLException exception) {
			throw new IOException("Cannot list students.", exception);
		}
	}

	private static final String SELECT = "SELECT e.*,s.name,s.github_id,s.github_login FROM enrollments e JOIN students s ON e.lecture_id=s.lecture_id AND e.student_id=s.id";

	private static Enrollment enrollment(Connection connection, String lecture, String assignment, String student) throws SQLException {
		try(PreparedStatement query = prepare(connection, SELECT + " WHERE e.lecture_id=? AND e.assignment_id=? AND e.student_id=?", lecture, assignment, student); ResultSet row = query.executeQuery()) {
			if(!row.next()) throw new IllegalArgumentException("Unknown registration.");
			return readEnrollment(row);
		}
	}

	private static Enrollment readEnrollment(ResultSet row) throws SQLException {
		String status = row.getString("status");
		String problem = row.getString("problem");
		if(row.getInt("busy") != 0) problem = "Operation in progress; if it persists after restart, instructor review is required.";
		return new Enrollment(row.getString("lecture_id"), row.getString("assignment_id"), row.getString("student_id"), row.getString("accepted_name"), row.getLong("github_id"), row.getString("github_login"), row.getString("identity_source"), row.getString("accepted_at"), nullableLong(row, "repository_id"), row.getString("repository"), row.getObject("repository_id") == null ? null : "https://github.com/" + row.getString("repository"), status, problem);
	}

	private static Long nullableLong(ResultSet row, String column) throws SQLException {
		long value = row.getLong(column);
		return row.wasNull() ? null : value;
	}

	private Connection connect() throws SQLException {
		Connection connection = DriverManager.getConnection(jdbcUrl);
		try(Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys=ON");
			statement.execute("PRAGMA busy_timeout=5000");
		}
		return connection;
	}

	@FunctionalInterface
	private interface Transaction<T> {
		T run(Connection connection) throws SQLException;
	}

	private <T> T transaction(Transaction<T> work) throws IOException {
		try(Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.execute("BEGIN IMMEDIATE");
			try {
				T result = work.run(connection);
				statement.execute("COMMIT");
				return result;
			} catch(SQLException | RuntimeException exception) {
				statement.execute("ROLLBACK");
				throw exception;
			}
		} catch(SQLException exception) {
			throw new IOException("Registration database operation failed; check for conflicting roster accounts or repository names.", exception);
		}
	}

	private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
		PreparedStatement statement = connection.prepareStatement(sql);
		for(int index=0; index<values.length; index++) statement.setObject(index + 1, values[index]);
		return statement;
	}

	private static int execute(Connection connection, String sql, Object... values) throws SQLException {
		try(PreparedStatement statement = prepare(connection, sql, values)) {
			return statement.executeUpdate();
		}
	}
}
