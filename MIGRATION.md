# Java migration and handover

## Decision and scope

Java with Spring Boot is the implementation target. The early Python prototype
remains a reference for behavior that has not yet been ported.

Java now provides the offline tools and assignment registration: private lecture
configuration, SQLite persistence, GitHub sign-in, repository provisioning, and
optional Moodle LTI 1.3 launches. This is not a complete conversion of push capture
and final grading. No live database, deployment, published artifact or GitHub
configuration has been migrated. Keep the Python reference while those remaining
contracts are ported; do not maintain two production stacks.

## Structure

One Maven application, targeting Java 17 and using Spring Boot 4.1.1:

- `org.finmath.classroom.cli`: explicit commands and argument validation.
- `org.finmath.classroom.grading`: plain Java feedback scoring and trusted-result CSV collection.
- `org.finmath.classroom.workspace`: plain Java generation of the separate lecture workspace.
- `org.finmath.classroom.io`: bounded input, strict JSON, safe XML, and private atomic output.
- `org.finmath.classroom.registration`: plain Java configuration, SQLite store, enrollment service and GitHub adapter.
- `org.finmath.classroom.web`: Spring MVC acceptance forms, GitHub OAuth, optional LTI launches and status endpoints.

Jackson 3 (`tools.jackson.*`), SQLite JDBC and Spring Security OAuth2 JOSE versions
are managed by the Spring Boot parent. GitHub uses Java's HTTP client; no GitHub SDK,
reactive stack, frontend framework or deployment machinery was added. JWT validation
does not introduce a second Jackson stack.

## Migration status

| Capability | Java status | Reference / remaining work |
| --- | --- | --- |
| Maven build, executable JAR, explicit CLI | Implemented | Verify with `mvn verify` |
| JUnit feedback scorer | Implemented | `tools/classroom/score.py`; feedback only |
| Trusted-result CSV collector | Implemented | `tools/classroom/collect.py`; does not execute grading |
| Lecture workspace generator | Implemented | `tools/workspace.py`; same explicit module list |
| Lecture configuration and administrative listings | Implemented | Private JSON directories; read-only check/list commands |
| Roster import and persistent account identity | Implemented | Optional usernames pinned to numeric GitHub IDs; no name-based roster claiming |
| Student acceptance and ownership/authorship declaration | Implemented | GitHub OAuth, shared-key fallback or validated Moodle launch; GET remains read-only |
| Moodle LTI 1.3 resource launches | Implemented, local tests only | One configured deployment; validate against the intended Moodle installation |
| Private template copies and invitations | Implemented, fake/local API tests | Persisted retry states and explicit reconciliation; no live GitHub writes performed |
| Server health and registration readiness | Implemented | Readiness covers configured registration only; no remote connectivity probe |
| Authenticated push capture and deadline manifests | Not ported | Preserve timestamp and exact-SHA contract |
| Durable source snapshots | Never implemented | Needed before real-course use |
| Isolated authoritative final grader | Never implemented | Runner backend and isolation remain to be designed |
| Production hosting, monitoring, credential renewal | Not implemented | Clarify server and operational requirements |
| Student feedback workflow cutover | Not switched | Existing example still invokes the Python scorer |

Java registration tests exercise fake GitHub adapters, local HTTP endpoints,
temporary databases and signed launch fixtures. They do not validate an actual
organization's policies, token permissions or Moodle installation. Python tests
remain reference evidence for unported push/deadline behavior.

## Contract and intentional differences

The Java data root contains `lectures/<lecture>/lecture.json`, nested
`assignments/<assignment>/assignment.json`, and a private `classroom.db`. SQLite
schema version 1 is independent of the Python schema; only `registration import`
initializes it. The server requires an already imported database. Read-only CLI
commands never initialize or migrate it. Keep the directory private (`0700`) and
database owner-only (`0600`); back up while the service is stopped.

The Python signed, account-bound enrollment link is replaced by GitHub OAuth
account verification plus a confirmed POST. Standalone registration requires both
shared keys and stores a self-declared name. A valid Moodle resource launch supplies
its signed subject and optionally a name; missing names remain self-declared.
An unbound roster entry is never claimed merely by a matching name. Preassigned
GitHub IDs match existing roster entries; otherwise registration creates a record.
Moodle subject IDs are not assumed to be institutional student numbers.
Enrollment listings preserve the name recorded at acceptance; the separate student
listing retains the local roster name. Persisted repository links remain available
after a new sign-in and after the acceptance deadline.

Moodle validation covers signature, issuer, audience, deployment, timestamps,
nonce/state, learner role, target URI and configured course/resource-link IDs.
Only manual registration and resource launches are supported: no dynamic registration,
deep linking, roster synchronization or grade return. Sessions and pending launches
are in memory in one service instance. Restarting requires new interrupted logins.

Import freezes organization, staff team, template, repository prefix, branch,
deadline and Moodle mapping. Titles and registration keys are reloadable on restart.
An optional deadline rejects acceptance/retries at and after the cutoff; it does
not capture submissions. Repository names use the prefix and numeric GitHub account
ID. Staff receive `maintain`, students receive repository-specific `write`, and
organization-wide permissions remain an instructor responsibility.

Repository IDs are saved before staff/invitation grants. Recorded partial failures
can be retried; ambiguous creation or a crash requires inspection. The
`registration reconcile` command checks an explicitly supplied repository ID and
records it without GitHub writes. Stop the service and verify owner, privacy and
contents first. Existing pinned IDs cannot be replaced. Unknown same-name
repositories are never adopted automatically; a reservation with no repository
still needs manual inspection. Invitations are refreshed by a later acceptance,
not a background poll.

See the [registration reference](docs/registration.md) for exact configuration, credentials,
OAuth callback and Moodle endpoint setup. The student OAuth credential and server
provisioning token are separate. App-token renewal, production hosting and a live
learner-account pilot remain operational work.

Keep rubric JSON, feedback JSON, submission manifest v1, trusted-result JSON,
and CSV columns compatible with the reference. Preserve deterministic partial
scores, duplicate/missing-test handling, strict schemas, and input size limits.
Numeric JSON formatting need not be byte-identical (`5` versus `5.0`).

Java CLI validation errors return 2, including collector errors (the reference
collector returned 1). The Java scorer may reject an invalid rubric without
creating an error output file. Malformed reports produce error feedback rather
than credited passes. Consumers must check both exit status and output validity;
never reuse a previous run's result file after a failed command.

The Java workspace generator recognizes the old Python-generated ownership
marker. It only overwrites generated workspace files, never assignment POMs,
sources, Git state, or unrelated IDE files. Its output is an editing convenience,
not a list of assignments approved for the next term.

`examples/java-build.yml.example` is a non-active CI template for this
administration project. It does not publish the JAR. No workflow has been
installed in the assignment repositories.

## Next migration slice

1. Review the intended HTTPS host, backups, credential rotation and organization policy.
2. When explicitly authorized, register the GitHub OAuth application and Moodle
   External tool, then pilot acceptance with a learner/test account. Verify actual
   context/resource IDs, name release, invitations and staff permissions.
3. Add signed push processing and cutoff manifests, then durable source capture.
   Preserve the reference processing-time cutoff or revise it explicitly with tests.
4. Design the isolated final-grading runner separately, with trusted tests/build
   configuration and explicit handling of timeouts and infrastructure failures.
   Review the chosen runner's executor and isolation before using it.
5. Reuse the Java feedback scorer in a shared assignment workflow after choosing
   its distribution/versioning contract. Student feedback remains untrusted.

The intended shape is one modest Spring MVC service with plain Java domain logic
and command-line tools, not microservices or an elaborate enterprise platform.

## Invariants to preserve

- Avoid requiring paid organization seats for each student.
- Students are outside collaborators on their own private repositories, not broad
  organization members. Staff permissions remain explicit.
- Create independent template copies, not true forks. Review templates for solutions.
- Resolve and pin numeric GitHub account and repository IDs. A username URL alone
  is not an authorization mechanism.
- Unknown same-name repositories require inspection; never automatically adopt them.
- Student-modifiable workflows/POMs/tests/reports are feedback, not authoritative grades.
- Final grading never runs student code in the administrative service or with its credentials.
- Match final results to assignment, repository, and exact submitted commit.
- Missing/infrastructure results must not silently become zero marks.
- Do not trust Git author timestamps for deadlines. The reference uses server processing
  time and documents delayed-webhook/outage limitations; preserve or explicitly revise
  that policy, with tests, before course use.
- The database's recorded SHA is not a durable source archive.
- Course workspaces and assignment/lecture repositories remain independent.
  They may contain instructor solutions. Never stage or publish those as a side effect.

## Verification commands

From `finmath-classroom`:

```sh
mvn -B -ntp verify
java -jar target/finmath-classroom.jar --help
java -jar target/finmath-classroom.jar registration check --data examples/registration
java -jar target/finmath-classroom.jar registration assignments --data examples/registration
python3 -m unittest discover -s tools/classroom -p 'test_*.py'
python3 -m unittest discover -s tools -p 'test_workspace.py'
```

The Python commands are optional reference checks, not Java runtime dependencies.
For an existing lecture workspace, also run the read-only workspace check with
the appropriate module list and repository root; see [offline tools](docs/tools.md).

Registration-slice verification on 2026-10-17:

- `mvn -B -ntp verify`: passed 162 Java tests with no failures, errors or skips;
  executable JAR produced. Build/test runtime was JDK 25.0.3, targeting Java 17.
- Tests include the configured Spring MVC/OAuth flow through actual temporary
  SQLite persistence and a fake GitHub boundary, concurrent acceptance, partial
  failures/reconciliation, account ownership, and signed Moodle launch fixtures.
- The packaged JAR ran on JDK 17.0.12: help, read-only configured workspace check,
  registration check/import/students/list, and JSON output parsing all passed.
  The sample assignment listing also ran successfully.
- Temporary loopback servers ran on Java 17 both unconfigured and configured
  with dummy GitHub credentials. Health/status returned 200, readiness returned
  503 and 200 respectively, and the configured registration form returned 200
  with an HttpOnly session cookie and no-store headers. These read-only requests
  left the database unchanged; both servers and temporary data were cleaned up.
- The packaged dependencies contain Jackson 3 core/databind only (plus its shared
  annotations dependency). No second Jackson runtime was added.
- No live GitHub requests, repositories, invitations, deployment, commit or push
  occurred. Deployment-specific Moodle settings and a live GitHub pilot remain unverified.
  Python reference tests were not rerun in this slice.

Previous foundation-phase verification on 2026-10-17 (before registration):

- `mvn -B -ntp verify`: passed 85 Java tests with no failures, errors, or skips;
  executable JAR produced. Build/test runtime was JDK 25.0.3, targeting Java 17.
- The packaged JAR's help and read-only workspace check ran on JDK 17.0.12;
  the configured local workspace validated without regenerating it.
- Packaged Spring Boot startup on JDK 17.0.12 was checked on a temporary
  loopback-only port: `/health` and `/status` returned 200; `/ready` returned 503
  as intended. The temporary server was stopped afterward.
- All 60 Python classroom reference tests and 8 workspace tests still pass.
- No live GitHub call, deployment, invitation, commit, or push was performed.

That Maven/JDK 25 run printed compatibility/deprecation warnings from Maven/test tooling;
they did not fail verification. Do not infer completeness of the unported service
from these tests or from the Python test count.
