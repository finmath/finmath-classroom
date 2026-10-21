# finmath-classroom

A general-purpose, self-hosted Java service for running programming assignments
on GitHub. Instructors define their own assignments through template repositories,
instructions and tests. Students accept an assignment through a web page, connect
their GitHub account, and receive a private repository created from the template.
Instructors manage courses, assignment settings and registrations through files
and command-line tools.

The project provides the registration and repository-provisioning part of a
GitHub Classroom alternative, with shared test feedback and automatic downloading
of feedback artifacts. Submission capture and authoritative final grading are
still pending.

**Start here:** [Getting started](GETTING_STARTED.md) walks through an offline
trial, a local browser preview, and a first live GitHub assignment.
The [project site](https://www.finmath.net/finmath-classroom/) introduces the
project motivation and an example assignment with test feedback.

## How it works

Students can enter through a direct assignment link with `lectureKey` and
`assignmentKey` URL parameters, or through a Moodle External tool activity with
signed `classroom_lecture` and `classroom_assignment` custom parameters. Direct
links use registration keys and a confirmed name; Moodle launches supply the
verified Moodle identity and released name. Both routes include GitHub sign-in
and an acceptance declaration. Direct links require no Moodle configuration.

1. An instructor configures a course and its assignments, including the GitHub
   organization, staff team, template repository and registration keys.
2. A student opens the assignment URL. They identify themselves either through
   a configured Moodle LTI 1.3 launch or by confirming a self-declared name and
   the shared course and assignment keys. After GitHub sign-in, the name field
   suggests their account-linked lecture name or, if absent, their GitHub profile name.
3. The student signs in with GitHub and confirms their account and the declaration
   about submitting their own work under the course rules.
4. The service publishes the template's current files into a shared snapshot
   history and initializes an independent private repository in the teaching
   organization. It then grants staff access and invites the student as an
   outside collaborator with write access.
5. SQLite records the acceptance, declaration, numeric account/repository IDs and
   provisioning progress. Students can return to their repository link; instructors
   can list registrations, recover interrupted provisioning, or delete an
   acceptance to let a student register again.

Opening an assignment page does not create a repository. After acceptance, it
shows the saved repository and status, with retry controls when needed. Ambiguous
creation failures require instructor review.
Self-declared names do not prove university identity or claim a roster entry by name.

The first published snapshot is a new root commit. Later template changes become
descendant snapshots, so late starters receive the updated files and share ancestry
with earlier students. The template's original history is excluded from student
repositories. Existing student repositories keep their work; instructor update PRs
and year rollover remain [planned work](todo.md).

## What is available

| Capability | Status |
| --- | --- |
| Course and assignment configuration; optional rosters | Implemented |
| GitHub sign-in, shared-key registration and declaration | Implemented |
| Private repositories with shared starter snapshot ancestry, staff access and invitations | Implemented |
| Instructor update PRs and year rollover under the same acceptance URL | Planned |
| SQLite persistence and administrative CLI | Implemented |
| Optional Moodle LTI 1.3 resource launches | Implemented; pilot with your Moodle installation |
| Local JUnit feedback scoring and trusted-result CSV collection | Implemented; operate on supplied files |
| Shared Maven/JUnit feedback action and versioned artifacts | Implemented |
| Automatic feedback artifact collection | Implemented as a CLI and optional systemd timer |
| Verified server releases and manual server updater | Implemented |
| Submission fetching, push/deadline tracking and submission source snapshots | Pending in Java |
| Isolated final grader and Moodle grade return | Pending |

Student-controlled workflows and reports provide feedback. Authoritative grading
requires an isolated instructor-controlled runner, which this service does not
implement. The administration process never executes student code.

## Try it locally

Requirements: **JDK 17 or newer** and **Maven 3.6.3 or newer**. Run from the
repository root:

```sh
mvn -B -ntp verify
java -jar target/finmath-classroom.jar --help
java -jar target/finmath-classroom.jar registration check --data examples/registration
```

Tests use temporary databases, fake GitHub adapters and local HTTP/JWT fixtures;
they do not require GitHub credentials. Maven may download dependencies. The
configuration check is read-only. Continue with [Getting started](GETTING_STARTED.md)
to import a disposable database and preview the registration page.

A live assignment requires a teaching organization and enabled GitHub template,
a GitHub OAuth application for student sign-in, a separate provisioning credential,
and a running service. Student access from other machines requires an HTTPS host.
Moodle is optional. The supplied adapter targets GitHub.com.

## Configuration and operation

One Spring Boot application serves the registration pages and provides CLI tools.
It uses Java 17, Spring MVC, SQLite/JDBC, Jackson 3 and JGit. The registration logic is
independent of Spring, and the interface uses server-rendered HTML.

A private data directory holds course configuration, the registration database,
published starter histories in `snapshots/` and the separate feedback database. Back
up the entire directory together. Configuration
uses the term `lecture` for a course and groups its assignments in subdirectories.
Secrets, rosters and runtime data stay outside version control. Student repositories
keep their own Git histories and builds.

- [Registration reference](docs/registration.md): configuration fields, environment
  variables, GitHub permissions, Moodle setup and recovery.
- [Ubuntu/Plesk deployment](docs/deployment.md): private service setup, HTTPS,
  initial import, verification, release updates and backups.
- [Other tools](docs/tools.md): local scoring, CSV collection and optional
  Maven/IntelliJ workspace generation.
- [Autograding migration](docs/autograding.md): replacement GitHub action, rubric
  conversion, standardized uploaded results and automatic feedback collection.
- [Development notes and history](docs/development.md): architecture, conventions
  and recorded verification.
- [Roadmap](docs/roadmap.md): launch preparation, shared feedback workflows,
  reliable result collection and final grading.
- [Grading contract](docs/grading-contract.md): reference behavior for future
  submission capture and final grading.
- [Project site](docs/site.md): build the Maven site and publish it with GitHub Pages.

The server binds to `127.0.0.1:8080` by default. `/health` reports liveness;
`/status` reports available capabilities. `/ready` is scoped to configured
registration and does not verify GitHub permissions, connectivity or final grading.

Operate one service instance with private persistent storage and backups.
Updates to `main` run verification and publish a JAR and checksum as a GitHub
Release. Production updates remain manual with `sudo classroom-update`; see the
[release update guide](docs/deployment.md#update-from-a-github-release).
Automatic credential renewal is not implemented.
Java is the sole implementation; no Python runtime is required.
