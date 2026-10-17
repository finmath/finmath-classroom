package org.finmath.classroom.registration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;

import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubGateway.Repository;
import org.finmath.classroom.registration.GitHubGateway.User;

/** Assignment acceptance and retryable repository provisioning, independent of HTTP and Spring. */
public final class RegistrationService {

	public static final String DECLARATION_VERSION = "1";
	public static final String DECLARATION = "I confirm that my name is correct and that the connected GitHub account belongs to me. I will submit my own work in accordance with the course's collaboration and attribution rules.";

	/** Trusted caller context: self-declared name or identity from a validated Moodle launch. */
	public record Identity(String source, String subject, String name) { }

	private final ClassroomConfig config;
	private final EnrollmentStore store;
	private final GitHubGateway github;
	private final Clock clock;

	/** Uses UTC processing time for acceptance deadlines. */
	public RegistrationService(ClassroomConfig config, EnrollmentStore store, GitHubGateway github) {
		this(config, store, github, Clock.systemUTC());
	}

	RegistrationService(ClassroomConfig config, EnrollmentStore store, GitHubGateway github, Clock clock) {
		this.config = config;
		this.store = store;
		this.github = github;
		this.clock = clock;
	}

	/** Returns lecture/assignment settings for the student-facing adapter. */
	public ClassroomConfig config() {
		return config;
	}

	/** Returns this authenticated account's existing repository even after the acceptance deadline. */
	public Enrollment enrollment(String lectureId, String assignmentId, User account) throws IOException {
		config.assignment(lectureId, assignmentId);
		return store.list(lectureId, assignmentId).stream().filter(entry -> entry.githubId() == account.id()).findFirst().orElse(null);
	}

	/**
	 * Reconciles an instructor-inspected repository after a lost creation response or process crash.
	 * Stop the service before running this administrative operation; it never creates a repository.
	 */
	public Enrollment reconcile(String lectureId, String assignmentId, String studentId, long repositoryId) throws IOException {
		store.verifyConfiguration(config);
		Enrollment enrollment = store.list(lectureId, assignmentId).stream().filter(entry -> entry.studentId().equals(studentId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown registration."));
		if(enrollment.repositoryId() != null && enrollment.repositoryId() != repositoryId) {
			throw new IllegalArgumentException("The recorded repository ID cannot be replaced.");
		}
		Repository repository = github.repository(enrollment.repository());
		checkRepository(repository, enrollment.repository(), repositoryId);
		store.recordRepository(enrollment, repository.id());
		return store.finish(enrollment, "retryable", "Repository reconciled by instructor; accept again to finish access grants.");
	}

	/** Accepts once, creates a private template copy and resumes recorded staff/invitation failures. */
	public Enrollment accept(String lectureId, String assignmentId, Identity identity, User account,
			String lectureKey, String assignmentKey, boolean declaration) throws IOException {
		Lecture lecture = config.lecture(lectureId);
		Assignment assignment = config.assignment(lectureId, assignmentId);
		if(!declaration) throw new IllegalArgumentException("Please confirm the declaration before accepting.");
		if(identity == null || identity.name() == null || identity.name().isBlank() || identity.name().length() > 200
				|| identity.subject() == null || identity.subject().isBlank() || identity.subject().length() > 255
				|| identity.source() == null || !(identity.source().equals("self") || identity.source().startsWith("moodle:"))) {
			throw new IllegalArgumentException("A name and authenticated identity are required.");
		}
		if(account == null || account.id() <= 0) throw new IllegalArgumentException("Connect your GitHub account first.");
		if(identity.source().equals("self") && (!identity.subject().equals(Long.toString(account.id()))
				|| !matches(lecture.registrationKey(), lectureKey) || !matches(assignment.registrationKey(), assignmentKey))) {
			throw new IllegalArgumentException("The registration keys are incorrect or standalone registration is disabled.");
		}
		if(assignment.deadline() != null && !clock.instant().isBefore(assignment.deadline())) {
			throw new IllegalArgumentException("The assignment deadline has passed.");
		}
		User currentAccount = github.user(account.login());
		if(currentAccount.id() != account.id() || !currentAccount.login().equalsIgnoreCase(account.login())) {
			throw new IllegalArgumentException("The GitHub account has changed. Sign in again.");
		}
		Enrollment enrollment = store.reserve(lecture, assignment, identity, currentAccount, clock.instant());
		boolean creationAttempted = false;
		boolean repositoryRecorded = enrollment.repositoryId() != null;
		boolean identityMismatch = false;
		try {
			if(!repositoryRecorded) {
				Repository template = github.repository(assignment.template());
				if(!template.template() || !template.fullName().equalsIgnoreCase(assignment.template()) || !template.defaultBranch().equals(assignment.branch())) {
					throw new IllegalArgumentException("The instructor must enable the template and check its default branch.");
				}
				// Recheck after the read-only lookup, immediately before the irreversible API call.
				if(assignment.deadline() != null && !clock.instant().isBefore(assignment.deadline())) {
					throw new IllegalArgumentException("The assignment deadline has passed.");
				}
				creationAttempted = true;
				Repository repository = github.create(assignment.template(), lecture.organization(), enrollment.repository().substring(lecture.organization().length() + 1));
				checkRepository(repository, enrollment.repository(), null);
				store.recordRepository(enrollment, repository.id());
				repositoryRecorded = true;
			} else {
				Repository repository = github.repository(enrollment.repository());
				identityMismatch = true;
				checkRepository(repository, enrollment.repository(), enrollment.repositoryId());
				identityMismatch = false;
			}
			if(lecture.staffTeam() != null) github.grantStaff(lecture.organization(), lecture.staffTeam(), enrollment.repository());
			boolean pending = github.invite(enrollment.repository(), currentAccount.login());
			return store.finish(enrollment, pending ? "invitation_pending" : "active", null);
		} catch(IOException | RuntimeException exception) {
			boolean needsReview = identityMismatch || creationAttempted && !repositoryRecorded;
			try {
				store.finish(enrollment, needsReview ? "needs_attention" : "retryable", needsReview
						? "Repository creation or identity is uncertain. Instructor review is required before retrying."
						: "Provisioning did not finish. Accept again to retry safely.");
			} catch(IOException failure) {
				exception.addSuppressed(failure);
			}
			throw exception;
		}
	}

	private static boolean matches(String expected, String supplied) {
		return expected != null && supplied != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
	}

	static void checkRepository(Repository repository, String expectedName, Long expectedId) {
		if(repository.id() <= 0 || !repository.fullName().equalsIgnoreCase(expectedName) || !repository.privateRepository()
				|| repository.fork() || expectedId != null && expectedId != repository.id()) {
			throw new IllegalArgumentException("Repository identity or privacy changed. Instructor review is required.");
		}
	}
}
