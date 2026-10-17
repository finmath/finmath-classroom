# Working on finmath-classroom

Read `README.md` and `MIGRATION.md` before changing implementation. This is the
independent administration repository; sibling lecture/assignment repositories
and the generated `finmath-lectures` workspace are separate projects.

## Technology and scope

- Implement new production functionality in Java. The user prefers Java/Spring Boot.
- Use the Maven build, Java 17 language target, and Spring Boot-managed versions.
- Use Jackson 3 (`tools.jackson.*`), not a second Jackson stack.
- Keep domain/offline tool logic independent of Spring; use imperative Spring MVC
  for HTTP. Avoid speculative frameworks, microservices, or deployment machinery.
- Preserve the Python prototype as a reference until Java feature parity is tested.
  Do not silently delete it or extend it as the primary production implementation.
- Report the implemented/pending boundary honestly. Health does not mean readiness;
  Java registration is implemented but requires configuration and a live pilot.
  Final grading is currently absent.

## Safety and ownership

- Preserve existing uncommitted work. The sibling exercise repositories contain
  instructor solutions; never copy, stage, commit, or publish them incidentally.
- Do not modify assignment POMs to inherit from this service or merge Git repositories.
- No live GitHub writes, invitations, deployment, push, or credential changes without
  a user request that authorizes that operation. Use fake adapters and fixtures first.
- Keep secrets, rosters, databases, and grade exports outside version control.
- Never execute student code in the administrative process or with its credentials.
- Student feedback artifacts are untrusted; final results require an isolated
  instructor-controlled runner. Schema/correlation checks do not prove provenance.
- Preserve identity checks, retry safety, deadline behavior, schema bounds, safe XML,
  formula-safe CSV, and distinction between absent/broken results and a zero grade.

## Verification and handover

- Run `mvn -B -ntp verify` for Java changes and relevant CLI smoke tests.
- `workspace --check` must be read-only; `--write` changes only owned generated files.
- Keep tests offline with respect to GitHub; builds may resolve Maven dependencies.
- Optional reference checks: `python3 -m unittest discover -s tools/classroom -p 'test_*.py'`
  and `python3 -m unittest discover -s tools -p 'test_workspace.py'`.
- Update `MIGRATION.md` when a migration boundary or compatibility contract changes.
- Distinguish executed verification from planned work; Python reference tests do
  not substitute for tests of Java enrollment or a live GitHub/Moodle pilot.
