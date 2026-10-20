package org.finmath.classroom.registration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.finmath.classroom.io.JsonFiles;

import tools.jackson.databind.JsonNode;

/** Private lecture directories and their assignment configurations. */
public record ClassroomConfig(List<Lecture> lectures) {

	/** Keep the loaded configuration immutable. */
	public ClassroomConfig {
		lectures = List.copyOf(lectures);
	}

	/** A lecture roster and defaults shared by its assignments; a null key disables shared-key registration. */
	public record Lecture(String id, String title, String organization, String staffTeam, String registrationKey,
			List<Student> students, List<Assignment> assignments) {
		/** Keep roster and assignment lists immutable. */
		public Lecture {
			students = List.copyOf(students);
			assignments = List.copyOf(assignments);
		}
	}

	/** A stable local student identifier, display name, and optional preassigned GitHub username. */
	public record Student(String id, String name, String githubUsername) { }

	/** An assignment template, repository naming policy, optional cutoff and optional explicit Moodle activity mapping. */
	public record Assignment(String id, String title, String template, String repositoryPrefix, String branch,
			Instant deadline, String registrationKey, String moodleCourseId, String moodleResourceId) { }

	/**
	 * Read and validate configuration without creating files or contacting GitHub.
	 *
	 * @param dataRoot private directory containing {@code lectures/<id>/lecture.json}
	 * @return the immutable configuration
	 * @throws IOException if configuration cannot be read
	 */
	public static ClassroomConfig load(Path dataRoot) throws IOException {
		List<Lecture> lectures = new ArrayList<>();
		Set<String> repositoryPrefixes = new HashSet<>();
		Set<String> moodleActivities = new HashSet<>();
		for(Path lectureDirectory : directories(dataRoot.resolve("lectures"))) {
			String lectureId = slug(lectureDirectory.getFileName().toString(), "lecture directory", 80);
			JsonNode lecture = JsonFiles.read(lectureDirectory.resolve("lecture.json"), 2 * 1024 * 1024);
			fields(lecture, Set.of("title", "organization", "staffTeam", "registrationKey", "students"));
			String organization = login(required(lecture, "organization", 39), "organization");
			String staffTeam = optional(lecture, "staffTeam", 100);
			if(staffTeam != null && !staffTeam.matches("[a-z0-9][a-z0-9-]*")) {
				throw new IllegalArgumentException("Invalid staffTeam; use a GitHub team slug with letters, digits and hyphens");
			}
			List<Student> students = students(lecture);
			List<Assignment> assignments = new ArrayList<>();
			Path assignmentsDirectory = lectureDirectory.resolve("assignments");
			if(Files.exists(assignmentsDirectory, LinkOption.NOFOLLOW_LINKS)) {
				for(Path assignmentDirectory : directories(assignmentsDirectory)) {
					Assignment assignment = assignment(assignmentDirectory);
					if(!repositoryPrefixes.add(organization.toLowerCase(Locale.ROOT) + "/" + assignment.repositoryPrefix().toLowerCase(Locale.ROOT))) {
						throw new IllegalArgumentException("Repository prefixes must be unique within an organization");
					}
					if(assignment.moodleCourseId() != null && !moodleActivities.add(assignment.moodleCourseId() + "\n" + assignment.moodleResourceId())) {
						throw new IllegalArgumentException("A Moodle activity can map to only one assignment");
					}
					assignments.add(assignment);
				}
			}
			lectures.add(new Lecture(lectureId, required(lecture, "title", 300), organization, staffTeam,
					optional(lecture, "registrationKey", 1024), students, assignments));
		}
		if(lectures.isEmpty()) {
			throw new IllegalArgumentException("At least one lecture directory is required");
		}
		return new ClassroomConfig(lectures);
	}

	/** Return a configured lecture, rejecting unknown identifiers. */
	public Lecture lecture(String id) {
		return lectures.stream().filter(lecture -> lecture.id().equals(id)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown lecture"));
	}

	/** Return a configured assignment within its lecture, rejecting unknown identifiers. */
	public Assignment assignment(String lectureId, String assignmentId) {
		return lecture(lectureId).assignments().stream().filter(assignment -> assignment.id().equals(assignmentId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown assignment"));
	}

	private static List<Student> students(JsonNode lecture) {
		JsonNode roster = lecture.get("students");
		if(roster == null) {
			return List.of();
		}
		if(!roster.isArray() || roster.size() > 10000) {
			throw new IllegalArgumentException("students must be an array with at most 10,000 entries");
		}
		List<Student> students = new ArrayList<>();
		Set<String> identifiers = new HashSet<>();
		Set<String> usernames = new HashSet<>();
		for(JsonNode student : roster) {
			fields(student, Set.of("id", "name", "githubUsername"));
			String id = required(student, "id", 100);
			String username = optional(student, "githubUsername", 39);
			if(!identifiers.add(id)) {
				throw new IllegalArgumentException("Duplicate student identifier within lecture");
			}
			if(username != null && !usernames.add(login(username, "githubUsername").toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("Duplicate GitHub account within lecture");
			}
			students.add(new Student(id, required(student, "name", 300), username));
		}
		return List.copyOf(students);
	}

	private static Assignment assignment(Path directory) throws IOException {
		String id = slug(directory.getFileName().toString(), "assignment directory", 80);
		JsonNode assignment = JsonFiles.read(directory.resolve("assignment.json"), 64 * 1024);
		fields(assignment, Set.of("title", "template", "repositoryPrefix", "branch", "deadline", "registrationKey", "moodleCourseId", "moodleResourceId"));
		String template = required(assignment, "template", 300);
		if(template.startsWith("https://github.com/")) {
			template = template.substring("https://github.com/".length());
			if(template.endsWith("/")) {
				template = template.substring(0, template.length() - 1);
			}
		}
		String[] repository = template.split("/", -1);
		if(repository.length != 2 || !repository[1].matches("[A-Za-z0-9_.-]{1,100}") || repository[1].equals(".") || repository[1].equals("..")) {
			throw new IllegalArgumentException("template must be owner/repository or its https://github.com URL");
		}
		login(repository[0], "template owner");
		String branch = optional(assignment, "branch", 200);
		String deadline = optional(assignment, "deadline", 100);
		Instant cutoff = null;
		if(deadline != null) {
			try {
				cutoff = Instant.parse(deadline);
			} catch(DateTimeParseException exception) {
				throw new IllegalArgumentException("deadline must be an ISO-8601 timestamp with a UTC offset", exception);
			}
		}
		String courseId = optional(assignment, "moodleCourseId", 200);
		String resourceId = optional(assignment, "moodleResourceId", 200);
		if((courseId == null) != (resourceId == null)) {
			throw new IllegalArgumentException("moodleCourseId and moodleResourceId must be supplied together");
		}
		return new Assignment(id, required(assignment, "title", 300), template,
				slug(required(assignment, "repositoryPrefix", 80), "repositoryPrefix", 80), branch == null ? "main" : branch,
				cutoff, optional(assignment, "registrationKey", 1024), courseId, resourceId);
	}

	private static List<Path> directories(Path path) throws IOException {
		if(!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("Expected a non-symlink directory: " + path);
		}
		try(Stream<Path> entries = Files.list(path)) {
			List<Path> children = entries.sorted().limit(1001).toList();
			if(children.size() > 1000) {
				throw new IllegalArgumentException("Too many configuration directory entries");
			}
			for(Path child : children) {
				if(Files.isSymbolicLink(child)) {
					throw new IllegalArgumentException("Configuration directories must not contain symbolic links");
				}
			}
			return children.stream().filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)).toList();
		}
	}

	private static void fields(JsonNode object, Set<String> allowed) {
		if(!object.isObject()) {
			throw new IllegalArgumentException("Configuration entries must be JSON objects");
		}
		for(String field : object.propertyNames()) {
			if(!allowed.contains(field)) {
				throw new IllegalArgumentException("Unknown configuration field");
			}
		}
	}

	private static String required(JsonNode object, String field, int limit) {
		String value = optional(object, field, limit);
		if(value == null) {
			throw new IllegalArgumentException("Missing configuration field " + field);
		}
		return value;
	}

	private static String optional(JsonNode object, String field, int limit) {
		JsonNode value = object.get(field);
		if(value == null || value.isNull()) {
			return null;
		}
		if(!value.isString() || value.asString().isBlank() || value.asString().length() > limit
				|| value.asString().chars().anyMatch(character -> Character.isISOControl(character))) {
			throw new IllegalArgumentException("Invalid configuration field " + field);
		}
		return value.asString();
	}

	private static String slug(String value, String label, int limit) {
		if(value.length() > limit || !value.matches("[a-z0-9][a-z0-9_-]*")) {
			throw new IllegalArgumentException("Invalid " + label + "; use lowercase letters, digits, hyphens or underscores");
		}
		return value;
	}

	private static String login(String value, String label) {
		if(!value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) {
			throw new IllegalArgumentException("Invalid " + label);
		}
		return value;
	}
}
