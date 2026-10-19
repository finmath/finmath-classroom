package org.finmath.classroom.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.finmath.classroom.feedback.FeedbackStore;
import org.finmath.classroom.feedback.FeedbackSync;
import org.finmath.classroom.feedback.GitHubFeedbackClient;
import org.finmath.classroom.grading.FeedbackScorer;
import org.finmath.classroom.grading.FeedbackArtifact;
import org.finmath.classroom.grading.GradeCollector;
import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;
import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.EnrollmentStore;
import org.finmath.classroom.registration.EnrollmentStore.Enrollment;
import org.finmath.classroom.registration.GitHubClient;
import org.finmath.classroom.registration.RegistrationService;
import org.finmath.classroom.workspace.WorkspaceGenerator;

/** Thin command-line adapter; only explicit GitHub operations need a server-side credential. */
public final class ClassroomCli {
    private ClassroomCli() { }

    public static int run(String[] args, PrintStream out, PrintStream error) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            help(out);
            return 0;
        }
        try {
            return switch (args[0]) {
                case "score" -> score(options(args, Set.of("--rubric", "--reports", "--output", "--summary"), Set.of()), out);
                case "feedback" -> feedback(Arrays.copyOfRange(args, 1, args.length), out);
                case "collect" -> collect(options(args, Set.of("--manifest", "--results", "--output"), Set.of()), out);
                case "workspace" -> workspace(options(args, Set.of("--modules", "--repositories-root", "--output"),
                        Set.of("--check", "--write")), out);
                case "registration" -> registration(Arrays.copyOfRange(args, 1, args.length), out);
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            };
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            error.println("Error: " + exception.getMessage());
            return 2;
        }
    }

    private static int score(Map<String, String> options, PrintStream out) throws IOException {
        Path rubric = required(options, "--rubric");
        Path reports = required(options, "--reports");
        Path output = SafeFiles.validateOutput(required(options, "--output"), true);
        Path summary = options.containsKey("--summary")
                ? SafeFiles.validateOutput(Path.of(options.get("--summary")), true) : null;
        rejectScoreInputOverwrite(rubric, reports, output);
        if (summary != null) {
            rejectScoreInputOverwrite(rubric, reports, summary);
            if (samePath(summary, output)) {
                throw new IllegalArgumentException("Summary and JSON output must be different files");
            }
        }
        String previousSummary = summary != null && Files.exists(summary)
                ? new String(SafeFiles.read(summary, 1024 * 1024), StandardCharsets.UTF_8) : "";
        var result = FeedbackScorer.score(rubric, reports);
        SafeFiles.writeAtomic(output, (JsonFiles.MAPPER.writeValueAsString(result) + "\n").getBytes(StandardCharsets.UTF_8), true);
        if (summary != null) {
            SafeFiles.writeAtomic(summary, (previousSummary + FeedbackScorer.summary(result)).getBytes(StandardCharsets.UTF_8), true);
        }
        out.println("Feedback only: " + result.path("status").asString() + "; output " + output);
        return FeedbackScorer.exitCode(result);
    }

    private static void rejectScoreInputOverwrite(Path rubric, Path reports, Path output) throws IOException {
        Path canonicalReports = Files.exists(reports) ? reports.toRealPath() : reports.toAbsolutePath().normalize();
        Path canonicalOutput = Files.exists(output) ? output.toRealPath() : output.toAbsolutePath().normalize();
        if (samePath(rubric, output) || canonicalOutput.startsWith(canonicalReports)) {
            throw new IllegalArgumentException("Score output must not overwrite rubric or reports");
        }
    }

    private static boolean samePath(Path first, Path second) throws IOException {
        if (first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())) {
            return true;
        }
        return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
    }

    private static int collect(Map<String, String> options, PrintStream out) throws IOException {
        Path output = required(options, "--output");
        new GradeCollector().collect(required(options, "--manifest"), required(options, "--results"), output);
        out.println("Collected trusted results into " + output);
        return 0;
    }

    private static int feedback(String[] args, PrintStream out) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Choose a feedback command: select, report, sync, list or show");
        }
        if (Set.of("sync", "list", "show").contains(args[0])) return feedbackCollection(args, out);
        if (args[0].equals("select")) {
            var options = options(args, Set.of("--rubric"), Set.of());
            out.println(FeedbackScorer.testSelection(required(options, "--rubric")));
            return 0;
        }
        if (!args[0].equals("report")) {
            throw new IllegalArgumentException("Unknown feedback command: " + args[0]);
        }
        var options = options(args, Set.of("--rubric", "--reports", "--context", "--test-exit-code",
                "--output", "--summary"), Set.of());
        Path rubric = required(options, "--rubric");
        Path reports = required(options, "--reports");
        Path context = required(options, "--context");
        Path output = SafeFiles.validateOutput(required(options, "--output"), false);
        Path summary = options.containsKey("--summary")
                ? SafeFiles.validateOutput(Path.of(options.get("--summary")), false) : null;
        for (Path destination : summary == null ? List.of(output) : List.of(output, summary)) {
            rejectScoreInputOverwrite(rubric, reports, destination);
            if (samePath(context, destination)) {
                throw new IllegalArgumentException("Feedback output must not overwrite context");
            }
        }
        if (summary != null && samePath(summary, output)) {
            throw new IllegalArgumentException("Summary and JSON output must be different files");
        }
        if (!options.containsKey("--test-exit-code")) {
            throw new IllegalArgumentException("Missing required option --test-exit-code");
        }
        int testExitCode = Integer.parseInt(options.get("--test-exit-code"));
        var result = FeedbackArtifact.create(rubric, reports, context, testExitCode);
        SafeFiles.writeAtomic(output, (JsonFiles.MAPPER.writeValueAsString(result) + "\n").getBytes(StandardCharsets.UTF_8), false);
        if (summary != null) {
            SafeFiles.writeAtomic(summary, FeedbackArtifact.summary(result).getBytes(StandardCharsets.UTF_8), false);
        }
        out.println("Feedback only: " + result.path("status").asString() + "; output " + output);
        return FeedbackArtifact.exitCode(result);
    }

    private static int feedbackCollection(String[] args, PrintStream out) throws IOException {
        Set<String> accepted = switch (args[0]) {
            case "sync" -> Set.of("--data", "--lecture", "--assignment", "--workflow");
            case "list" -> Set.of("--data", "--lecture", "--assignment");
            default -> Set.of("--data", "--repository-id", "--run-id", "--attempt");
        };
        var options = options(args, accepted, Set.of());
        Path data = required(options, "--data");
        ClassroomConfig config = ClassroomConfig.load(data);
        String lecture = options.get("--lecture");
        String assignment = options.get("--assignment");
        if (lecture != null) config.lecture(lecture);
        if (assignment != null) {
            if (lecture == null) throw new IllegalArgumentException("--assignment requires --lecture");
            config.assignment(lecture, assignment);
        }
        EnrollmentStore enrollments = EnrollmentStore.readOnly(data.resolve("classroom.db"));
        enrollments.verifyConfiguration(config);
        if (args[0].equals("sync")) {
            String workflow = options.getOrDefault("--workflow", System.getenv().getOrDefault("CLASSROOM_FEEDBACK_WORKFLOW", "classroom.yml"));
            FeedbackSync.workflowFile(workflow);
            String token = System.getenv("CLASSROOM_FEEDBACK_TOKEN");
            if (token == null || token.isBlank()) token = System.getenv("CLASSROOM_GITHUB_TOKEN");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("CLASSROOM_FEEDBACK_TOKEN or CLASSROOM_GITHUB_TOKEN with Actions read access is required.");
            var github = new GitHubFeedbackClient(token);
            var report = new FeedbackSync(config, enrollments, new FeedbackStore(data, true), github, Clock.systemUTC())
                    .sync(lecture, assignment, workflow);
            out.println(JsonFiles.MAPPER.writeValueAsString(report));
            return report.errors() > 0 || report.deferred() ? 2 : 0;
        }
        if (args[0].equals("list") && !Files.exists(data.resolve("feedback.db"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            out.println(JsonFiles.MAPPER.writeValueAsString(Map.of("authoritative", false, "repositories", List.of(), "results", List.of())));
            return 0;
        }
        FeedbackStore store = new FeedbackStore(data, false);
        if (args[0].equals("list")) {
            out.println(JsonFiles.MAPPER.writeValueAsString(Map.of("authoritative", false,
                    "repositories", store.checks(lecture, assignment), "results", store.list(lecture, assignment))));
        } else {
            long repository = positiveOption(options, "--repository-id");
            long run = positiveOption(options, "--run-id");
            long attempt = positiveOption(options, "--attempt");
            if (attempt > 1000) throw new IllegalArgumentException("--attempt must not exceed 1000");
            var result = store.result(repository, run, (int) attempt);
            if (result == null) throw new IllegalArgumentException("No received feedback exists for that repository, run and attempt.");
            out.println(JsonFiles.MAPPER.writeValueAsString(result));
        }
        return 0;
    }

    private static long positiveOption(Map<String, String> options, String key) {
        String text = options.get(key);
        if (text == null || !text.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(key + " must be a positive integer");
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " is too large");
        }
    }

    private static int workspace(Map<String, String> options, PrintStream out) throws IOException {
        if (options.containsKey("--check") == options.containsKey("--write")) {
            throw new IllegalArgumentException("Choose exactly one of --check or --write");
        }
        Path project = Path.of("").toAbsolutePath().normalize();
        Path modules = Path.of(options.getOrDefault("--modules", project.resolve("workspace/modules.json").toString()));
        Path repositories = Path.of(options.getOrDefault("--repositories-root", project.getParent().toString()));
        Path output = Path.of(options.getOrDefault("--output", project.getParent().resolve("finmath-lectures").toString()));
        boolean write = options.containsKey("--write");
        int count = WorkspaceGenerator.generate(modules, repositories, output, write);
        out.println((write ? "Generated" : "Validated") + " workspace with " + count + " modules: " + output.resolve("pom.xml"));
        return 0;
    }

	private static int registration(String[] args, PrintStream out) throws IOException {
		if(args.length == 0) {
			throw new IllegalArgumentException("Choose a registration command: check, import, assignments, students, list, delete or reconcile");
		}
		Set<String> acceptedOptions = switch(args[0]) {
			case "check", "import" -> Set.of("--data");
			case "assignments", "students" -> Set.of("--data", "--lecture");
			case "list" -> Set.of("--data", "--lecture", "--assignment", "--format");
			case "delete" -> Set.of("--data", "--lecture", "--assignment", "--student");
			case "reconcile" -> Set.of("--data", "--lecture", "--assignment", "--student", "--repository-id");
			default -> throw new IllegalArgumentException("Unknown registration command");
		};
		Map<String, String> options = options(args, acceptedOptions, Set.of());
		Path dataRoot = required(options, "--data");
		String format = options.getOrDefault("--format", "json");
		if(!Set.of("json", "table").contains(format)) throw new IllegalArgumentException("--format must be json or table");
		if(args[0].equals("delete") || args[0].equals("reconcile")) {
			for(String required : List.of("--lecture", "--assignment", "--student")) {
				if(!options.containsKey(required) || options.get(required).isBlank()) {
					throw new IllegalArgumentException("Missing required option " + required);
				}
			}
		}
		ClassroomConfig config = ClassroomConfig.load(dataRoot);
		String lectureId = options.get("--lecture");
		String assignmentId = options.get("--assignment");
		if(lectureId != null) {
			config.lecture(lectureId);
		}
		if(assignmentId != null) {
			if(lectureId == null) {
				throw new IllegalArgumentException("--assignment requires --lecture");
			}
			config.assignment(lectureId, assignmentId);
		}
		Object result;
		switch(args[0]) {
			case "check" -> result = configurationSummary(config);
			case "import" -> {
				EnrollmentStore store = new EnrollmentStore(dataRoot.resolve("classroom.db"), true);
				store.importConfiguration(config, githubClient());
				result = configurationSummary(config);
			}
			case "assignments" -> result = assignments(config, lectureId);
			case "students" -> {
				EnrollmentStore store = EnrollmentStore.readOnly(dataRoot.resolve("classroom.db"));
				store.verifyConfiguration(config);
				result = store.students(lectureId);
			}
			case "list" -> {
				EnrollmentStore store = EnrollmentStore.readOnly(dataRoot.resolve("classroom.db"));
				store.verifyConfiguration(config);
				List<Enrollment> enrollments = store.list(lectureId, assignmentId);
				if(format.equals("table")) {
					printRegistrations(enrollments, out);
					return 0;
				}
				result = enrollments;
			}
			case "delete" -> {
				EnrollmentStore store = new EnrollmentStore(dataRoot.resolve("classroom.db"), false);
				store.verifyConfiguration(config);
				result = Map.of("deleted", store.delete(lectureId, assignmentId, options.get("--student")));
			}
			case "reconcile" -> {
				if(!options.containsKey("--repository-id") || options.get("--repository-id").isBlank()) throw new IllegalArgumentException("Missing required option --repository-id");
				long repositoryId;
				try {
					repositoryId = Long.parseLong(options.get("--repository-id"));
				} catch(NumberFormatException exception) {
					throw new IllegalArgumentException("--repository-id must be a positive integer", exception);
				}
				if(repositoryId <= 0) {
					throw new IllegalArgumentException("--repository-id must be a positive integer");
				}
				EnrollmentStore store = new EnrollmentStore(dataRoot.resolve("classroom.db"), false);
				result = new RegistrationService(config, store, githubClient()).reconcile(lectureId, assignmentId, options.get("--student"), repositoryId);
			}
			default -> throw new IllegalArgumentException("Unknown registration command");
		}
		out.println(JsonFiles.MAPPER.writeValueAsString(result));
		return 0;
	}

	private static void printRegistrations(List<Enrollment> enrollments, PrintStream out) {
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] {"LECTURE", "ASSIGNMENT", "STUDENT ID", "NAME", "GITHUB", "STATUS", "REPOSITORY"});
		for(Enrollment enrollment : enrollments) {
			rows.add(new String[] {enrollment.lectureId(), enrollment.assignmentId(), enrollment.studentId(), enrollment.name(), enrollment.githubLogin(), enrollment.status(), enrollment.repository()});
		}
		int[] widths = new int[7];
		for(String[] row : rows) {
			for(int index=0; index<row.length; index++) {
				row[index] = row[index] == null ? "" : row[index].replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
				widths[index] = Math.max(widths[index], row[index].length());
			}
		}
		for(String[] row : rows) {
			for(int index=0; index<row.length; index++) {
				out.print(row[index]);
				if(index + 1 < row.length) out.print(" ".repeat(widths[index] - row[index].length() + 2));
			}
			out.println();
		}
	}

	private static GitHubClient githubClient() {
		return new GitHubClient(System.getenv("CLASSROOM_GITHUB_TOKEN"), System.getenv("CLASSROOM_GITHUB_CLIENT_ID"), System.getenv("CLASSROOM_GITHUB_CLIENT_SECRET"));
	}

	private static Map<String, Object> configurationSummary(ClassroomConfig config) {
		return Map.of("valid", true, "lectures", config.lectures().size(),
				"assignments", config.lectures().stream().mapToInt(lecture -> lecture.assignments().size()).sum(),
				"students", config.lectures().stream().mapToInt(lecture -> lecture.students().size()).sum());
	}

	private static List<Map<String, Object>> assignments(ClassroomConfig config, String lectureId) {
		List<Map<String, Object>> assignments = new ArrayList<>();
		for(Lecture lecture : config.lectures()) {
			if(lectureId != null && !lecture.id().equals(lectureId)) {
				continue;
			}
			for(Assignment assignment : lecture.assignments()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("lecture", lecture.id());
				row.put("assignment", assignment.id());
				row.put("title", assignment.title());
				row.put("organization", lecture.organization());
				row.put("template", assignment.template());
				row.put("repositoryPrefix", assignment.repositoryPrefix());
				row.put("branch", assignment.branch());
				row.put("deadline", assignment.deadline() == null ? null : assignment.deadline().toString());
				row.put("moodleCourseId", assignment.moodleCourseId());
				row.put("moodleResourceId", assignment.moodleResourceId());
				assignments.add(row);
			}
		}
		return assignments;
	}

    private static Path required(Map<String, String> options, String key) {
        if (!options.containsKey(key) || options.get(key).isBlank()) {
            throw new IllegalArgumentException("Missing required option " + key);
        }
        return Path.of(options.get(key));
    }

    private static Map<String, String> options(String[] args, Set<String> values, Set<String> flags) {
        Map<String, String> result = new HashMap<>();
        for (int index = 1; index < args.length; index++) {
            String key = args[index];
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate option " + key);
            }
            if (flags.contains(key)) {
                result.put(key, "true");
            } else if (values.contains(key) && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                result.put(key, args[++index]);
            } else {
                throw new IllegalArgumentException("Unknown option or missing value: " + key);
            }
        }
        return result;
    }

    private static void help(PrintStream out) {
        out.println("""
                finmath-classroom — teaching repository administration

                Commands (run from the finmath-classroom directory):
                  score --rubric FILE --reports DIR --output FILE [--summary FILE]
                  feedback select --rubric FILE
                  feedback report --rubric FILE --reports DIR --context FILE --test-exit-code NUMBER --output FILE [--summary FILE]
                  feedback sync --data DIR [--lecture ID] [--assignment ID] [--workflow classroom.yml]
                  feedback list --data DIR [--lecture ID] [--assignment ID]
                  feedback show --data DIR --repository-id NUMBER --run-id NUMBER --attempt NUMBER
                  collect --manifest FILE --results DIR --output FILE.csv
                  workspace (--check | --write) [--modules FILE] [--repositories-root DIR] [--output DIR]
                  registration check --data DIR
                  registration import --data DIR
                  registration assignments --data DIR [--lecture ID]
                  registration students --data DIR [--lecture ID]
                  registration list --data DIR [--lecture ID] [--assignment ID] [--format json|table]
                  registration delete --data DIR --lecture ID --assignment ID --student ID
                  registration reconcile --data DIR --lecture ID --assignment ID --student ID --repository-id NUMBER
                  serve [Spring Boot options]

                Feedback sync downloads student-workflow artifacts into private DIR/feedback.db; list/show are read-only.
                Scoring is feedback only. The collect command requires trusted final-runner results.
                Registration configuration lives in DIR/lectures; its database is DIR/classroom.db.
                Check and listing commands are read-only. Import pins supplied GitHub usernames using read-only API calls.
                Delete removes one acceptance only. Stop the service and back up first; GitHub repositories are retained.
                Before reconcile, stop the service and inspect the repository's identity and contents. It records the inspected ID for retry.
                Enrollment requires registration configuration and GitHub credentials; the server binds to loopback by default.
                Authoritative final grading is not implemented.
                """);
    }
}
