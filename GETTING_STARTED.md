# Getting started

This walkthrough starts with an offline local trial, then an optional test against
GitHub. Run commands from the repository root using Bash. You need JDK 17 or newer
and Maven 3.6.3 or newer. Full configuration details are in the
[registration reference](docs/registration.md).

## 1. Build

```sh
java -version
mvn -version
mvn -B -ntp verify
java -jar target/finmath-classroom.jar --help
```

The first build downloads Maven dependencies. Tests use temporary data and fake or
local GitHub endpoints; they do not create GitHub repositories. The executable is
`target/finmath-classroom.jar`.

## 2. Try the administration commands offline

Create a fresh copy of the examples under `local/`, which is ignored by Git:

```sh
(
  set -e
  umask 077
  mkdir -p local
  mkdir local/tryout
  chmod 700 local/tryout
  cp -R examples/registration/lectures local/tryout/

  java -jar target/finmath-classroom.jar registration check --data local/tryout
  java -jar target/finmath-classroom.jar registration import --data local/tryout
  java -jar target/finmath-classroom.jar registration assignments --data local/tryout
  java -jar target/finmath-classroom.jar registration students --data local/tryout
  java -jar target/finmath-classroom.jar registration list --data local/tryout
)
```

The block stops if `local/tryout` already exists; choose a new directory for another
fresh trial. Check reports one lecture, one assignment and two students. Import
creates `local/tryout/classroom.db`. The student listing contains the two example
roster entries; the enrollment listing is initially `[]`.

These example students have no GitHub usernames, so import needs no credentials or
GitHub connection. Check and listing commands are read-only. The placeholders are
sufficient for this offline trial; use a separate data directory for a live pilot.

### Optional: preview the first browser page

The following placeholder credentials apply only inside this subshell:

```sh
(
  export CLASSROOM_DATA="$PWD/local/tryout"
  export CLASSROOM_PUBLIC_URL=http://127.0.0.1:8080
  export CLASSROOM_GITHUB_CLIENT_ID=preview-placeholder
  export CLASSROOM_GITHUB_CLIENT_SECRET=preview-placeholder
  export CLASSROOM_GITHUB_TOKEN=preview-placeholder
  export CLASSROOM_MOODLE_ISSUER=
  java -jar target/finmath-classroom.jar serve
)
```

Open <http://127.0.0.1:8080/accept/numerical-methods/example-exercise>. You should see
the assignment title and **Connect GitHub account** button. This previews the first
sign-in page only. The running application uses the real GitHub adapter; placeholder
credentials cannot complete OAuth or provision a repository. Stop it with Ctrl+C.

`/health` reports liveness. `/ready` reports configured registration, even with these
placeholders: it does not verify GitHub credentials or connectivity. `/status`
continues to report `finalGradingAvailable: false`.

The full browser acceptance path, including persistence and retrying without a
second repository, is exercised with a fake GitHub boundary by this focused test:

```sh
mvn -B -ntp -Dtest=RegistrationIntegrationTest test
```

That test already runs in `verify`; use the focused command when working on that
flow without needing a live GitHub account.

## 3. Prepare a live GitHub pilot

Use a new directory so the offline trial's imported settings remain separate:

```sh
(
  set -e
  umask 077
  mkdir -p local
  mkdir local/pilot
  chmod 700 local/pilot
  cp -R examples/registration/lectures local/pilot/
)
```

**Edit the configuration before importing it.** The database freezes repository
ownership, template, naming, branch, deadline, staff team and Moodle mapping.

- In `local/pilot/lectures/numerical-methods/lecture.json`, set your `organization`,
  an existing `staffTeam` slug (or `null`), and a random `registrationKey`. For a
  simple pilot, replace the sample `students` array with `[]`.
- In `local/pilot/lectures/numerical-methods/assignments/example-exercise/assignment.json`,
  set an enabled, reviewed `template`, a new `repositoryPrefix`, its default `branch`,
  and another random `registrationKey`. Remove both `moodleCourseId` and
  `moodleResourceId` for this standalone pilot. Leave `deadline` absent initially.

The template's committed contents will be copied. Use a template containing only
the material the test student should receive.

Create a GitHub OAuth application with homepage `http://127.0.0.1:8080` and callback:

```text
http://127.0.0.1:8080/auth/github/callback
```

Use `127.0.0.1` consistently in the callback, server configuration and browser.
Prepare a separate instructor provisioning token with access to the template and
new repositories; see [GitHub credentials](docs/registration.md#github-credentials)
for endpoint permissions and organization requirements. OAuth identifies the
student account; the provisioning token performs repository operations.

The following Bash prompts keep secret values out of command history and apply
them only to this subshell. Import runs after configuration validation:

```sh
(
  set -e
  export CLASSROOM_DATA="$PWD/local/pilot"
  export CLASSROOM_PUBLIC_URL=http://127.0.0.1:8080
  export CLASSROOM_MOODLE_ISSUER=
  read -r -p 'GitHub OAuth client ID: ' CLASSROOM_GITHUB_CLIENT_ID
  read -r -s -p 'GitHub OAuth client secret: ' CLASSROOM_GITHUB_CLIENT_SECRET
  printf '\n'
  read -r -s -p 'GitHub provisioning token: ' CLASSROOM_GITHUB_TOKEN
  printf '\n'
  export CLASSROOM_GITHUB_CLIENT_ID CLASSROOM_GITHUB_CLIENT_SECRET CLASSROOM_GITHUB_TOKEN

  java -jar target/finmath-classroom.jar registration check --data "$CLASSROOM_DATA"
  java -jar target/finmath-classroom.jar registration import --data "$CLASSROOM_DATA"
  java -jar target/finmath-classroom.jar serve
)
```

Open the same assignment URL as in the preview. Connect a test student's GitHub
account, enter their name and your two registration keys, and confirm the declaration.
**Clicking Accept assignment now creates a real private GitHub repository and
grants staff/student access.** Accept the GitHub invitation if one is issued.

In another terminal, inspect the saved state:

```sh
java -jar target/finmath-classroom.jar registration students --data local/pilot
java -jar target/finmath-classroom.jar registration list --data local/pilot \
  --lecture numerical-methods --assignment example-exercise
```

The enrollment includes numeric GitHub IDs, the repository URL and a status such as
`invitation_pending` or `active`. Signing in again shows the saved repository link.
Repeated acceptance resumes eligible access grants without creating a second copy.
Stop the server with Ctrl+C; the database persists for the next start.

If you kept the example roster, your test student creates a separate record; typing
an example student's name does not claim that row. To bind a real roster entry,
configure its GitHub username before import. See the reference for
[identity and recovery behavior](docs/registration.md).

## 4. Share an assignment, then add Moodle integration

For other users, host the service at a public HTTPS origin and update the OAuth
application's callback accordingly. A normal Moodle URL resource can link to the
assignment's `/accept/<lecture>/<assignment>` page immediately; students use the
shared-key flow. That link does not supply trusted Moodle identity.

Signed identity requires an LTI 1.3 External tool and its verified course/activity
mapping. Follow the [Moodle setup reference](docs/registration.md) once the public
host and platform registration are available. Import the actual mapping before
using that assignment. Adding a mapping to an already imported pilot assignment
requires a deliberate migration; a fresh assignment ID and repository prefix are
the simpler choice for a new pilot. Restart the service after configuration changes.

Registration does not yet collect submissions or perform authoritative final grading.
See [MIGRATION.md](MIGRATION.md) for the implementation boundary.
