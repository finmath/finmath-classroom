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
| `feedback` | Read-only GitHub artifact collection, validation and private feedback history |
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
- Registration creates independent private repositories from published starter
  snapshots. The first snapshot is parentless; new template revisions append to
  the same assignment's publication history for late starters. Original template
  history is excluded from pushes, and the template is never modified. JGit handles
  Git transport without native Git. Keep publication repositories under private
  `DATA/snapshots/` and back them up with the registration database. Students receive
  repository-specific access as outside collaborators; staff access is explicit.
  Avoid a design that requires a paid organization seat per student.
- Pin numeric GitHub account and repository IDs. A matching name is not identity
  evidence. Preserve the acceptance declaration, retry state and deadline checks;
  never silently adopt an unknown repository or replace a recorded repository ID.
- Java SQLite schema version 2 is independent of the retired prototype's schema.
  Only `registration import` initializes it or migrates Java schema 1; server
  startup and read-only commands require an existing compatible database. The
  nullable enrollment starter commit preserves legacy repositories without
  rewriting them. Pin a new enrollment's starter before remote creation, record
  the numeric repository ID before initialization, and initialize before granting
  access. Retries preserve the pinned snapshot and existing branch contents.
  Freeze ownership and assignment mapping as described in the
  [registration reference](registration.md). Prefix rollover and instructor update
  PRs remain separate [TODOs](../todo.md).
- Enrollment listings retain the name supplied at acceptance; the student listing
  retains the roster name. Account names and repository URLs can change, but stored
  numeric identities and existing repository names remain stable.
- Moodle routing is additive: one registered `/lti/entry` Tool URL serves activities
  selected by `classroom_lecture` and `classroom_assignment` in the verified LTI
  custom claim. Keep `/lti/login` for OIDC initiation and `/lti/launch` for its
  callback, and preserve assignment-specific `/accept/...` launches and standalone
  key registration. Custom selectors require nonblank string values and an existing
  configured assignment. Require signed course context and resource-link IDs, but
  do not match them to configuration when routing by custom selectors. JWT, state,
  nonce and learner-role validation and the single trusted issuer/client/deployment
  boundary still apply. Activity editors authorized for that trusted tool can select
  any configured assignment; standalone registration keys do not constrain these
  signed launches. Preserve paired, frozen `moodleCourseId` and
  `moodleResourceId` for optional explicit activity mapping of assignment-specific
  Moodle launches without either custom selector. Standalone assignment URLs with
  registration-key parameters require no Moodle mapping and are supported
  alongside Moodle launches. The shared route needs no configuration migration.
  A Moodle launch establishes identity and any released name; students must still
  sign in to GitHub and confirm acceptance before provisioning.
- Feedback from student-controlled workflows, POMs, tests or reports is untrusted.
  Final grading requires trusted tests/build configuration and an isolated runner.
  Never execute student code in the administration process or with its credentials.
- Feedback collection uses a separate `feedback.db` schema version 1. It may update
  confirmed invitation status in the existing registration database without
  migrating it. Listings remain read-only. Received results are immutable
  per numeric repository ID, run ID and attempt; failures cannot erase a result.
  Parse bounded archives without extraction, verify run correlation, and keep missing
  results distinct from a failed test with a numeric score. HTTP operations must not
  hold SQLite write transactions or forward credentials to artifact storage.
  Repository checks can have status `deferred` after a rate limit. This existing
  version-1 table stores the resume marker without a schema migration; a later
  scoped sync prioritizes matching interrupted enrollments. Latest listings show
  a deferred check without any result as unavailable. Resume is per repository,
  not per page/run within its history.
- Latest feedback listings use the newest known run by GitHub creation time and
  run ID, then its highest known attempt, per current enrollment. Preserve missing
  or invalid latest results and enrollments without attempts; never replace them
  with older scores. Listing remains local and read-only; it does not establish
  the current branch head or a final grade.
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

| Date | Development |
| --- | --- |
| Prototype | Explored administration, scoring and submission/deadline rules in Python. |
| 2026-10-17 | Introduced the Java executable JAR, offline scorer, result collector, workspace generator, SQLite registration, GitHub OAuth and optional Moodle launches. Retired the prototype; submission capture and authoritative grading remain future work. |
| 2026-10-18 | Added accepted-assignment pages, provisioning recovery, enrollment administration and assignment-scoped key prefilling. Added shared Maven/JUnit feedback, versioned artifacts, weighted-method and whole-suite rubrics, and advisory build/JavaDoc/checkstyle jobs. |
| 2026-10-18 | Adopted a maintained `v1` action reference and Maven `-U` for updated SNAPSHOT test artifacts. Local rubric/workflow changes still require changes in student repositories. |
| 2026-10-19 | Improved sanitized diagnostics, account-linked name suggestions, roster preassignment and bounded repository names. Existing numeric identities and repository names remain stable. |
| 2026-10-19 | Added read-only GitHub feedback collection, a separate private history database, a systemd timer, and latest-result tables. Added rate-limit deferral and repository-level resumption. |
| 2026-10-19 | Added Ubuntu/Plesk deployment examples, CLI and release-update helpers, verified JAR publication, and clearer registration actions. |
| 2026-10-19 | Separated reusable documentation from private operator notes and added a Maven project site with GitHub Pages publication. |
| 2026-10-20 | Added shared Moodle tool entry routing through signed per-activity custom parameters, without per-activity server mappings. Standalone assignment links and assignment-specific Moodle launches with explicit course/resource mappings are also supported. |
| 2026-10-21 | Added shared starter snapshot ancestry with JGit, lazy publication of updated template revisions for late starters, and explicit registration schema-1-to-2 migration. Existing student repositories retain their history; update PRs and year rollover remain planned. Verification: 556 Java tests on Java 17 and 25, packaged configuration check, and all four offline shell checks passed. |

The [README capability table](../README.md#what-is-available) describes the current
implementation. The [roadmap](roadmap.md) covers submission source snapshots, authoritative
final grading, Moodle grade return and remaining operational checks. Validate
registration, invitations and feedback on each deployment using a separate learner
account; local tests do not establish the permissions or policies of a particular
GitHub organization or Moodle installation.

## Verification

Run from this repository with JDK 17 or newer and Maven 3.6.3 or newer:

```sh
mvn -B -ntp verify
java -jar target/finmath-classroom.jar --help
java -jar target/finmath-classroom.jar registration check --data examples/registration
java -jar target/finmath-classroom.jar registration assignments --data examples/registration
bash scripts/test-autograding.sh
bash scripts/test-classroom-wrapper.sh
bash scripts/test-classroom-update.sh
bash scripts/test-server-release.sh
mvn -B -ntp site
bash scripts/check-site.sh
```

Java tests use temporary databases, fake adapters and local HTTP/JWT fixtures.
They do not require GitHub credentials. Maven may resolve dependencies. Some
sandboxed environments need permission for local sockets and JVM instrumentation.
Run relevant packaged CLI smoke checks and the read-only workspace check when
changing those tools. Do not run assignment builds from the aggregate workspace
as part of service verification.

The autograding shell checks run the packaged scorer against fake assignment tools
and fixture reports. Update/publication checks use fake service and GitHub commands;
they do not deploy or publish. The site checker validates generated local links
and excludes private artifacts. These checks complement a separate browser and
server pilot.

The application baseline on 2026-10-19 passed 352 Java tests with no failures,
errors or skips, 17 autograding scenarios, 15 updater scenarios, 16 publication
executions and the CLI-helper checks. Packaged CLI smoke checks passed on Java 17,
including read-only listing without creating a feedback database. Historical
prototype tests are not verification of pending Java features.

## Publication boundaries

The installed [server-release workflow](../.github/workflows/release.yml) verifies
the project and publishes a JAR/checksum for successful `main` builds. Production
updates use the manual helper; publishing a binary does not deploy a server or
move the feedback action's `v1` reference. The separate
[Pages workflow](../.github/workflows/pages.yml) builds the public Maven site and
publishes only `target/site`. Pull requests receive validation without publication.
See the [site guide](site.md) for local preview and initial Pages setup.
