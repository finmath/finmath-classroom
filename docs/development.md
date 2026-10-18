# Development notes and history

See the [README](../README.md) for the product, [Getting started](../GETTING_STARTED.md)
for operation, and the [roadmap](roadmap.md) for planned work. This document replaces
the former agent instructions and Java migration handover.

## Architecture and development conventions

One Maven application targets Java 17 and uses Spring Boot-managed dependencies,
imperative Spring MVC, SQLite/JDBC and Jackson 3 (`tools.jackson.*`). The POM is the
source of truth for dependency versions. Java is the sole production implementation;
no Python runtime is required.

| Package | Responsibility |
| --- | --- |
| `cli` | Command-line adapters |
| `registration` | Configuration, persistent identities, enrollment and GitHub access |
| `web` | Server-rendered acceptance pages, GitHub OAuth, Moodle launches and status |
| `grading` | Offline JUnit feedback scoring and trusted-result CSV collection |
| `workspace` | Generation of the separate Maven/IntelliJ lecture workspace |
| `io` | Bounded JSON/XML input and private atomic output |

Keep domain and offline logic independent of Spring. Prefer concise, direct code;
extract helpers when they remove duplication or clarify a substantial operation.
Keep validation focused on real input and trust boundaries. Avoid speculative
frameworks, microservices, a second JSON stack, and deployment machinery without a
concrete need.

Assignment repositories keep their own POMs and Git histories. The generated
`finmath-lectures` workspace is an editing convenience, not a parent library or a
published repository for shared workflows. Preserve existing uncommitted work,
especially instructor solutions in sibling repositories. Never copy, stage or
publish those solutions incidentally.

## Contracts to preserve

- Keep secrets, rosters, databases and grade exports outside version control. Use
  fake adapters and fixtures before live operations. GitHub writes, invitations,
  deployment, pushes and credential changes require an explicit user request.
- Registration creates independent private template copies. Students receive
  repository-specific access as outside collaborators; staff access is explicit.
  Avoid a design that requires a paid organization seat per student.
- Pin numeric GitHub account and repository IDs. A matching name is not identity
  evidence. Preserve the acceptance declaration, retry state and deadline checks;
  never silently adopt an unknown repository or replace a recorded repository ID.
- Java SQLite schema version 1 is independent of the retired prototype's schema.
  Only `registration import` initializes it; server startup and read-only commands
  require an existing imported database. Freeze ownership and assignment mapping
  as described in the [registration reference](registration.md).
- Enrollment listings retain the name supplied at acceptance; the student listing
  retains the roster name. Account names and repository URLs can change, but stored
  numeric identities and existing repository names remain stable.
- Feedback from student-controlled workflows, POMs, tests or reports is untrusted.
  Final grading requires trusted tests/build configuration and an isolated runner.
  Never execute student code in the administration process or with its credentials.
- Preserve rubric, feedback, manifest and final-result formats, deterministic
  scoring, duplicate/missing-test handling, bounded inputs, safe XML and formula-safe
  CSV. Numeric JSON formatting need not be byte-identical (`5` and `5.0`). Correlating
  metadata does not prove result provenance. Missing/broken results are not zero marks.
- Keep the [grading contract](grading-contract.md) explicit: server processing time
  determines the proposed submission cutoff, not commit-author timestamps. A SHA
  alone is not a source archive. Change these rules deliberately, with tests.
- `workspace --check` remains read-only. `--write` changes only owned generated
  files and recognizes the old ownership marker; it never edits assignment POMs,
  sources or Git state. Workspace membership does not approve an assignment for use.
- CLI validation errors return 2. Scoring returns 0 for passed, 1 for failed or
  incomplete, and 2 for errors. Invalid rubric input may leave an old output file
  untouched: consumers must inspect exit status and use fresh output locations.

Operational details live in the [registration reference](registration.md) and
[tools reference](tools.md), rather than being duplicated here. `/health` means
liveness; `/ready` covers configured registration, not remote GitHub permissions
or final grading. Moodle launch sessions are in memory in one server instance;
restarts interrupt pending logins. Moodle roster synchronization, deep linking and
grade return are not implemented.

## Development history

| Stage | Outcome |
| --- | --- |
| Prototype | Explored administration, scoring and submission/deadline rules in Python. |
| 2026-10-17: Java foundation | Added the executable JAR, offline scorer, final-result collector and workspace generator. |
| 2026-10-17: registration | Added SQLite enrollment, GitHub OAuth, private template provisioning, recovery and optional Moodle LTI 1.3 launches. |
| 2026-10-17: Java only | Removed the Python prototype at the user's request. Retained the example rubric and grading contract; this did not establish submission/grading feature parity. |
| 2026-10-18: registration usability | Added accepted-assignment pages, explicit retry/refresh, username/ID repository suffixes and assignment-scoped URL key prefilling. |
| 2026-10-18: administration | Added readable enrollment listings and exact local enrollment deletion for re-registration, retaining student identity. |
| 2026-10-18: documentation | Replaced the migration handover and agent file with development notes and a roadmap, including the proposed shared feedback workflow and collection design. |
| 2026-10-18: feedback integration | Added a shared Maven/JUnit action, a versioned result envelope and schema, method-selection/report CLI commands and a migration guide. Publication and the live Actions pilot remain pending. |
| 2026-10-18: full assignment workflow | Restored the example's build, advisory JavaDoc/checkstyle and summary jobs; added ordinary pull-request feedback with explicit merge-commit identity. |
| 2026-10-18: template migration | Added an explicit all-or-nothing Maven suite rubric, preserving existing weighted-method results; prepared pinned action references for assignment templates. |
| 2026-10-18: central feedback updates | Switched assignment templates to the maintained `v1` action reference and added Maven `-U` to refresh SNAPSHOT checks despite dependency caching. |

An instructor-account registration pilot was reported working. A complete learner
pilot, the intended Moodle installation and public hosting still need validation.
No automatic submission capture, source archive or authoritative final runner has
been implemented. The [README capability table](../README.md#what-is-available)
describes current functionality; the roadmap describes future functionality.

## Verification

For Java changes, run from this repository:

```sh
mvn -B -ntp verify
java -jar target/finmath-classroom.jar --help
java -jar target/finmath-classroom.jar registration check --data examples/registration
java -jar target/finmath-classroom.jar registration assignments --data examples/registration
```

Run CLI smoke checks relevant to the change and the read-only workspace check when
changing workspace generation. Keep tests offline with respect to GitHub; Maven
may resolve dependencies. Do not run assignment builds from the aggregate workspace
as part of service verification. The example CI file is not an installed workflow
and does not publish the JAR.

Historical verification before feedback integration:

- On 2026-10-17, the foundation passed 85 Java tests; registration and prototype
  removal passed 162. JDK 17 CLI/server smoke checks also passed. The old Python
  test results are historical and are no longer current verification commands.
- On 2026-10-18, usability changes passed 179 Java tests and administration changes
  passed 185, without failures, errors or skips. Maven ran on JDK 25.0.3 with a Java
  17 target; packaged CLI smoke checks ran on JDK 17.0.12. Tests used temporary
  databases, fake GitHub adapters and local HTTP/JWT fixtures.

Feedback integration verification on 2026-10-18:

- `mvn -B -ntp verify` passed 202 tests without failures, errors or skips and
  produced the executable JAR. The initial sandboxed run blocked local mock-server
  sockets and JVM test instrumentation; the rerun with those permissions passed.
- `scripts/test-autograding.sh` passed 13 offline scenarios with the packaged JAR
  on JDK 17.0.12, fake assignment tools and fixture reports. Checks cover passing
  and partial results, missing/stale/malformed reports, timeouts, abnormal process
  exits, commit/event checks and retaining the assignment's Java environment.
- Bash syntax and YAML parsing passed for the action/scripts and example workflow.
- The JSON Schema and example artifact passed Draft 2020-12 validation, as did
  artifacts emitted on Java 17 for all five effective result states. All 45 local
  documentation links resolved. No student code, live GitHub operations or public
  deployment were involved.

Full workflow follow-up on 2026-10-18: the shell checks passed all 14 scenarios,
including pull-request merge identity and rejection of `pull_request_target`.
The example's YAML, grading independence, summary dependencies and rendering of
success/failure/skipped/cancelled outcomes were checked locally. No Java code
changed in this follow-up; Maven verification above was not rerun.

These checks do not establish live Actions/GitHub/Moodle compatibility or validate
the pending submission and final-grading features. Keep implemented capabilities,
proposals and executed verification distinct.

Template migration verification on 2026-10-18: `mvn -B -ntp verify` passed all
209 tests. The shell integration checks passed 17 scenarios on Java 17, including
whole-suite selection, full/zero points and unavailable reports. The result schema
validated the existing method example and Java-produced suite artifacts for all
five result statuses. YAML and job dependency checks passed for the common example
and 14 prepared template workflows. Assignment builds and live Actions runs were
not executed; publication and the live pilot remain pending.

The central-update follow-up passed all 17 offline shell scenarios on Java 17,
with `-U` required for both method and suite grading. YAML checks confirmed that
all 14 template workflows changed only their action reference, apart from comments.
No Java code changed, so Maven verification above was not repeated. The initial
`v1.0.0` release tag and moving `v1` tag are prepared locally; publication and a live
SNAPSHOT-update pilot remain pending.
