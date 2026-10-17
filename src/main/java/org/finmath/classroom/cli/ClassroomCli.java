package org.finmath.classroom.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.finmath.classroom.grading.FeedbackScorer;
import org.finmath.classroom.grading.GradeCollector;
import org.finmath.classroom.io.JsonFiles;
import org.finmath.classroom.io.SafeFiles;
import org.finmath.classroom.registration.ClassroomConfig;
import org.finmath.classroom.registration.ClassroomConfig.Assignment;
import org.finmath.classroom.registration.ClassroomConfig.Lecture;
import org.finmath.classroom.registration.EnrollmentStore;
import org.finmath.classroom.registration.GitHubClient;
import org.finmath.classroom.registration.RegistrationService;
import org.finmath.classroom.workspace.WorkspaceGenerator;

/** Thin command-line adapter. The tools below do not need Spring or server credentials. */
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
			throw new IllegalArgumentException("Choose a registration command: check, import, assignments, students, list or reconcile");
		}
		Set<String> acceptedOptions = switch(args[0]) {
			case "check", "import" -> Set.of("--data");
			case "assignments", "students" -> Set.of("--data", "--lecture");
			case "list" -> Set.of("--data", "--lecture", "--assignment");
			case "reconcile" -> Set.of("--data", "--lecture", "--assignment", "--student", "--repository-id");
			default -> throw new IllegalArgumentException("Unknown registration command");
		};
		Map<String, String> options = options(args, acceptedOptions, Set.of());
		Path dataRoot = required(options, "--data");
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
			case "students", "list" -> {
				EnrollmentStore store = EnrollmentStore.readOnly(dataRoot.resolve("classroom.db"));
				store.verifyConfiguration(config);
				result = args[0].equals("students") ? store.students(lectureId) : store.list(lectureId, assignmentId);
			}
			case "reconcile" -> {
				for(String required : Set.of("--lecture", "--assignment", "--student", "--repository-id")) {
					if(!options.containsKey(required) || options.get(required).isBlank()) {
						throw new IllegalArgumentException("Missing required option " + required);
					}
				}
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
                  collect --manifest FILE --results DIR --output FILE.csv
                  workspace (--check | --write) [--modules FILE] [--repositories-root DIR] [--output DIR]
                  registration check --data DIR
                  registration import --data DIR
                  registration assignments --data DIR [--lecture ID]
                  registration students --data DIR [--lecture ID]
                  registration list --data DIR [--lecture ID] [--assignment ID]
                  registration reconcile --data DIR --lecture ID --assignment ID --student ID --repository-id NUMBER
                  serve [Spring Boot options]

                Scoring is feedback only. Collection requires trusted final-runner results.
                Registration configuration lives in DIR/lectures; its database is DIR/classroom.db.
                Check and listing commands are read-only. Import pins supplied GitHub usernames using read-only API calls.
                Before reconcile, stop the service and inspect the repository's identity and contents. It records the inspected ID for retry.
                Enrollment requires registration configuration and GitHub credentials; the server binds to loopback by default.
                Authoritative final grading is not implemented.
                """);
    }
}
