# finmath-classroom

A self-hosted Java service for running programming assignments on GitHub. Students
accept an assignment through a web page, connect their GitHub account, and receive
a private repository created from an instructor's template. Instructors manage
courses, assignment settings and registrations through files and command-line tools.

The project provides the registration and repository-provisioning part of a
GitHub Classroom alternative. It is under development: automated submission
collection and authoritative final grading are still pending.

**Start here:** [Getting started](GETTING_STARTED.md) walks through an offline
trial, a local browser preview, and a first live GitHub assignment.

## How it works

1. An instructor configures a course and its assignments, including the GitHub
   organization, staff team, template repository and registration keys.
2. A student opens the assignment URL. They identify themselves either through
   a configured Moodle LTI 1.3 launch or by providing a self-declared name and
   the shared course and assignment keys.
3. The student signs in with GitHub and confirms their account and the declaration
   about submitting their own work under the course rules.
4. The service creates an independent private template copy in the teaching
   organization, grants the staff team access and invites the student as an
   outside collaborator with write access.
5. SQLite records the acceptance, declaration, numeric account/repository IDs and
   provisioning progress. Students can return to their repository link; instructors
   can list registrations, recover interrupted provisioning, or delete an
   acceptance to let a student register again.

Opening an assignment page does not create a repository. After acceptance, it
shows the saved repository and status, with retry controls when needed. Ambiguous
creation failures require instructor review.
Self-declared names do not prove university identity or claim a roster entry by name.

## What is available

| Capability | Status |
| --- | --- |
| Course and assignment configuration; optional rosters | Implemented |
| GitHub sign-in, shared-key registration and declaration | Implemented |
| Private template copies, staff access and student invitations | Implemented |
| SQLite persistence and administrative CLI | Implemented |
| Optional Moodle LTI 1.3 resource launches | Implemented; pilot with your Moodle installation |
| Local JUnit feedback scoring and trusted-result CSV collection | Implemented; operate on supplied files |
| Shared Maven/JUnit feedback action and versioned artifacts | Implemented locally; publication and GitHub pilot pending |
| Automatic feedback artifact collection | Pending |
| Submission fetching, push/deadline tracking and source snapshots | Pending in Java |
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
It uses Java 17, Spring MVC, SQLite/JDBC and Jackson 3. The registration logic is
independent of Spring, and the interface uses server-rendered HTML.

A private data directory holds course configuration and the database. Configuration
uses the term `lecture` for a course and groups its assignments in subdirectories.
Secrets, rosters and runtime data stay outside version control. Student repositories
keep their own Git histories and builds.

- [Registration reference](docs/registration.md): configuration fields, environment
  variables, GitHub permissions, Moodle setup and recovery.
- [Ubuntu/Plesk deployment](docs/deployment.md): private service setup, HTTPS,
  initial import, verification and backups.
- [Other tools](docs/tools.md): local scoring, CSV collection and optional
  Maven/IntelliJ workspace generation.
- [Autograding migration](docs/autograding.md): replacement GitHub action, rubric
  conversion and standardized uploaded results.
- [Development notes and history](docs/development.md): architecture, conventions
  and recorded verification.
- [Roadmap](docs/roadmap.md): launch preparation, shared feedback workflows,
  reliable result collection and final grading.
- [Grading contract](docs/grading-contract.md): reference behavior for future
  submission capture and final grading.

The server binds to `127.0.0.1:8080` by default. `/health` reports liveness;
`/status` reports available capabilities. `/ready` is scoped to configured
registration and does not verify GitHub permissions, connectivity or final grading.

Operate one service instance with private persistent storage and backups.
Automatic credential renewal and deployment automation are not implemented.
Java is the sole implementation; no Python runtime is required.
