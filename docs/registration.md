# Registration reference

See [Getting started](../GETTING_STARTED.md) for a guided local trial. This reference
covers configuration, credentials, Moodle integration and recovery. Run commands
from the repository root after building the JAR.

## Data and configuration

Copy [examples/registration](../examples/registration) into a private service directory
outside version control and replace its placeholders. Keep the directory accessible
only to its owner (`chmod 700`). These lecture folders are administration data,
independent of the lecture repositories and generated IntelliJ workspace.

```text
/private/service/classroom/
  lectures/
    numerical-methods/
      lecture.json
      assignments/
        example-exercise/
          assignment.json
  classroom.db                 Created by registration import
```

`lecture.json` supplies `title`, `organization`, optional `staffTeam`, optional
`registrationKey`, and an optional inline `students` array. Each student has an
`id`, `name`, and optional `githubUsername`. `assignment.json` supplies `title`,
`template` (an `owner/repository` or GitHub HTTPS URL), `repositoryPrefix`, optional
`branch` (default `main`), optional ISO-8601 `deadline` with an offset, and optional
`registrationKey`. Folder names provide the lecture and assignment IDs.

Both keys must be supplied for standalone registration. Omitting either key or
setting it to `null` disables that route. Replace example keys with independently
generated random values. For Moodle, supply `moodleCourseId` and `moodleResourceId`
together before the first import. Omit both for standalone registration. Adding
a Moodle mapping after import requires a reviewed migration or a new assignment
ID and repository prefix. Use a separate disposable data directory for experiments.

```sh
java -jar target/finmath-classroom.jar registration check --data /private/service/classroom
java -jar target/finmath-classroom.jar registration import --data /private/service/classroom
java -jar target/finmath-classroom.jar registration assignments --data /private/service/classroom
java -jar target/finmath-classroom.jar registration students --data /private/service/classroom --lecture numerical-methods
java -jar target/finmath-classroom.jar registration list --data /private/service/classroom --lecture numerical-methods --assignment example-exercise
java -jar target/finmath-classroom.jar registration list --data /private/service/classroom --lecture numerical-methods --format table
```

`check` and `assignments` read configuration only. `students` and `list` open the
existing database read-only and emit private administrative JSON. Add `--format table`
to `list` for a readable table with the student IDs used by administrative commands.
Listings omit keys.
Edit organization, template, staff team, branch, prefix, deadline and any Moodle
mapping **before the first import**: these values are frozen in the database.

`import` creates Java SQLite schema version 1 and pins supplied usernames to numeric
GitHub IDs using read-only API calls. A roster without GitHub usernames imports offline.
The database uses owner-only permissions (`0600` on POSIX systems). Back up the
private directory while the service is stopped. Databases from the retired
prototype are incompatible; initialize a new database with `registration import`.

Unassigned roster names cannot be claimed by typing a matching name. A student
without a prebound account creates their own registration record; shared-key names
remain self-declared. A verified GitHub account matches a preassigned roster entry
by numeric ID. Moodle identities use the signed subject, which is not necessarily
a university student number. Account corrections and merging duplicate records
require deliberate administrative work.

### Preassigning a student and testing registration

Add a GitHub username to that student's private roster entry, for example:

```json
"students": [
  {"id": "student-001", "name": "Alex Example", "githubUsername": "alex-example"}
]
```

`id` is your lecture's student identifier, not a GitHub ID. Import resolves
`githubUsername` to the numeric GitHub ID and stores the binding. The configuration
does not accept a `githubId` field. Future sign-ins match the numeric ID, including
after a GitHub username change. If you re-import after a username change, update
`githubUsername` first; import refuses to replace an already pinned numeric ID.

For an existing service, stop it and back up its private data directory. Keep an
existing student's ID when adding their GitHub username; do not create a second
roster entry for the same account. Run `registration check`, `registration import`
with the provisioning credential, and `registration students --lecture LECTURE`,
using the service's data directory and operating-system user, then restart it.
Import performs read-only GitHub account lookups; it does not create repositories,
invite students or accept assignments. It preserves existing student names and
acceptances. Removing an entry from the file does not delete its stored record.

The student opens an assignment, connects that GitHub account, checks their name,
supplies the registration keys (or uses a Moodle launch) and confirms the declaration.
Only then is the assignment accepted and provisioned. The roster is not an allowlist:
other authenticated accounts with the required keys can still register separately.

To test, use an assignment that account has not accepted yet. Verify that the form
prefills the roster name, then accept and compare the student ID in `registration
list` with the roster ID. An already accepted assignment shows its saved acceptance
instead of a new form. Avoid deleting a working acceptance merely to test prefilling;
its GitHub repository would remain and could collide with a new registration.

Import freezes organization, staff team, template, repository prefix, branch,
deadline, and Moodle activity mapping. Use new assignment IDs and prefixes for new
terms. Changing frozen settings requires a reviewed database migration. Titles and
keys can change without reassigning ownership; restart the server to reload them.

## Server configuration

Configure these environment variables before `serve`:

| Variable | Purpose |
| --- | --- |
| `CLASSROOM_DATA` | Absolute private directory containing `lectures/` and imported `classroom.db` |
| `CLASSROOM_PUBLIC_URL` | Public HTTPS origin, e.g. `https://assignments.example.edu`; loopback HTTP is allowed for development |
| `CLASSROOM_GITHUB_CLIENT_ID` | GitHub OAuth application's client ID for student sign-in |
| `CLASSROOM_GITHUB_CLIENT_SECRET` | That OAuth application's client secret |
| `CLASSROOM_GITHUB_TOKEN` | Separate instructor credential for template reads, repository creation and access grants |

## GitHub credentials

### Student sign-in

Create a [GitHub OAuth application](https://github.com/settings/applications/new)
with your service's public origin as its homepage. Register its callback as
`https://assignments.example.edu/auth/github/callback`. Student sign-in requests
no repository scope; its token identifies the account, while provisioning uses
the separate server credential. Roster import needs only the provisioning token
when usernames are supplied. Never place server credentials in student repositories.

### Provisioning token

Create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new)
in your personal GitHub settings: **Developer settings → Personal access tokens →
Fine-grained tokens → Generate new token**.

1. Choose a name such as `finmath-classroom-pilot` and an expiration, such as 30 days.
2. Set **Resource owner** to your teaching organization.
3. Select **All repositories** so the token covers the template and future student
   repositories. Selecting only the template would exclude the generated repositories.
4. Under **Repository permissions**, set Administration to **Read and write**,
   Contents to **Read-only**, and Metadata to **Read-only**.
5. Separately, under **Organization permissions**, add **Members** and set it to
   **Read-only**. This is required when a staff team is configured. **All repositories**
   controls repository access; it does not grant organization permissions.
6. Click **Generate token**.

| Category | Permission | Access |
| --- | --- | --- |
| Repository | Administration | Read and write |
| Repository | Contents | Read-only |
| Repository | Metadata | Read-only |
| Organization | Members | Read-only |

If this same token is also used for automatic feedback collection, additionally
grant **Repository permissions → Actions → Read-only**. Without it, provisioning
can work while `feedback sync` receives HTTP 403. A dedicated nonblank
`CLASSROOM_FEEDBACK_TOKEN` takes precedence over `CLASSROOM_GITHUB_TOKEN` for
collection; update the permissions of the token actually used. See the
[collection setup](autograding.md#5-automatic-server-collection).

These permissions cover
[template generation](https://docs.github.com/en/rest/repos/repos#create-a-repository-using-a-template),
[student invitations](https://docs.github.com/en/rest/collaborators/collaborators#add-a-repository-collaborator)
and [staff-team grants](https://docs.github.com/en/rest/teams/teams#add-or-update-team-repository-permissions).

Copy the generated value and paste it at the **GitHub provisioning token** prompt
in the getting-started instructions or pilot launcher. The input remains invisible;
press Enter to submit it. The value becomes `CLASSROOM_GITHUB_TOKEN` in the service's
environment, independently of the OAuth application's client ID and secret.

If GitHub marks the token pending, an organization owner must approve it first.
Organization policy and the token owner's own access must permit these operations.
See [GitHub's token setup instructions](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
for organization approval and restrictions. Supplied GitHub App tokens are also
usable. Automatic renewal is not implemented; replace expired tokens yourself.

If the token summary says **"This token does not have any organization permissions"**,
the Members permission is missing. Open [your fine-grained tokens](https://github.com/settings/personal-access-tokens),
select the provisioning token, edit its permissions, and add **Organization
permissions → Members → Read-only**. Save the changes and obtain organization
approval if GitHub requests it. If you keep the same token value, the running
service can use its updated permissions; if you replace the token, restart the
service with the new `CLASSROOM_GITHUB_TOKEN` value.

Repository creation can succeed before a missing Members permission stops the
staff-team grant. For an enrollment marked `retryable` with a recorded repository
ID, return to the assignment and click **Retry repository setup** after correcting
the token. The service reuses the recorded repository. See [Recovery](#recovery)
for other states.

### Repository access

Enable the template, match its default branch to `branch`, and review its committed
contents for solutions. Acceptance creates an independent private repository named
`<prefix up to 78 characters>-<8 hex characters>-<username up to 12 characters>`, grants the configured staff team `maintain`,
and invites the student with `write` access. Students remain outside collaborators.
The service does not change organization membership or base permissions; configure
the organization's base permissions appropriately before a pilot.

The acceptance error **"The instructor must enable the template and check its
default branch"** means the template preflight failed. For shared-key acceptance,
the registration keys have already passed validation. Check the template flag
and its current default branch on GitHub; an assignment configured with `master`
will fail if the template uses `main`. This check happens before repository
creation. Correct these settings before the initial import. For an already
imported assignment, changing the JSON alone is insufficient: the branch is
frozen in SQLite too. Use a reviewed migration that preserves registrations;
inspect any existing or uncertain repositories before changing their expected
branch. After correction, the student can use **Retry repository setup**.

Only the generated name truncates the configured prefix to its first 78 characters;
the configuration and frozen assignment definition stay intact. The final segment
is the first 12 characters of the lowercase GitHub username at first acceptance.
The maximum name length is exactly `78 + 1 + 8 + 1 + 12 = 100`, within
[GitHub's limit](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

The eight-character lowercase hexadecimal value is a random registration-specific
identifier. It is allocated and saved with the repository name before contacting
GitHub; the full numeric GitHub account and repository IDs remain the identity
checks. Locally reserved names are compared case-insensitively, with another random
value tried on collision. The short value alone is not a globally unique account ID.
An unknown repository collision at GitHub or an ambiguous creation response still
requires instructor review; the service never silently adopts or renames it.

Recorded repository names remain unchanged on retries, server restarts or username
changes, including older names and reservations where creation has not yet finished.
Deploying this naming change requires the updated JAR, with no database migration
or edits to existing assignment prefixes.

Distribute `https://assignments.example.edu/accept/numerical-methods/example-exercise`
for standalone registration. The student connects GitHub, supplies their name and
both keys, and confirms the ownership/authorship declaration. Only the confirmed
POST creates a repository. The database records acceptance time, declaration version,
numeric account/repository IDs, repository URL and provisioning status. An optional
deadline rejects acceptance and retries at or after the cutoff.

After GitHub sign-in, the editable name field uses the name already associated
with that numeric account in the lecture, if present. Otherwise it suggests the
GitHub profile's optional `name`, not the login handle. Missing or unusable profile
names leave the field blank. Other roster entries are never shown. This uses the
existing authenticated-user lookup and requires no additional OAuth scope.
The student reviews and confirms the name; a profile name is not verified university
identity. The acceptance stores the submitted name without overwriting the roster
name. A name supplied by a validated Moodle launch retains precedence and cannot
be changed through the form.

After acceptance, the page shows the saved repository and status. It no longer
asks for a name, keys or another declaration. Eligible access failures show
**Retry repository setup**; pending invitations show **Refresh access status**.
Both buttons reuse the recorded acceptance and repository.

### Prefilling registration keys

A normal link, including a link from Moodle, can supply either or both keys:

```text
https://assignments.example.edu/accept/numerical-methods/example-exercise?lectureKey=LECTURE_KEY&assignmentKey=ASSIGNMENT_KEY
```

URL-encode the key values. The server remembers them in the browser session for
that assignment, redirects to its clean URL, and prefills the form after GitHub
sign-in. They are not forwarded to GitHub. A new key-bearing URL replaces the
saved pair; an omitted key stays blank. The supplied keys still need to match
the configuration, and students must confirm their account and declaration.

Share these links through your course's Moodle page: they contain the shared
registration keys. The original URL may remain in browser history or proxy logs.

### Hosting

For Ubuntu with Plesk, use the [deployment guide](deployment.md) and its systemd
service and environment examples.

Run one service instance behind an HTTPS reverse proxy for public use, with request
limits, registration rate limiting and monitoring. The server binds to loopback by
default. A remote browser cannot reach a service running only on your laptop
through `127.0.0.1`; use the public HTTPS origin for a student pilot. Suppress
registration-key and OAuth/LTI query strings and request bodies in logs.
Sessions and pending logins are in memory; interrupted sign-ins restart after a
service restart. Public hosting and a complete learner-account pilot still require
validation.

## Moodle launch

Use a manually registered **LTI 1.3 External tool**, launched in a new window or
top-level page. Ordinary links with name/ID parameters do not establish identity.
The service accepts resource launches from one trusted Moodle deployment; it does
not synchronize the full roster or return grades.

| Variable | Value from Moodle tool registration |
| --- | --- |
| `CLASSROOM_MOODLE_ISSUER` | Exact platform issuer; unset disables LTI |
| `CLASSROOM_MOODLE_CLIENT_ID` | Tool client ID |
| `CLASSROOM_MOODLE_DEPLOYMENT_ID` | Authorized deployment ID |
| `CLASSROOM_MOODLE_AUTH_URL` | Platform OIDC authorization endpoint |
| `CLASSROOM_MOODLE_JWKS_URL` | Platform public signing-key endpoint |

At the service's public HTTPS origin, register `/lti/login` as the OIDC initiation
URL and `/lti/launch` as the redirect URI. Set the activity target to its `/accept/...`
URL. Configure launch-only use, with deep linking and grade/roster services disabled.
For Moodle's public-key setting, select RSA key and leave the tool key blank: this
inbound-only flow does not sign tool requests. The platform signing-key endpoint
is still required. See Moodle's [tool configuration form](https://github.com/moodle/moodle/blob/MOODLE_501_STABLE/public/mod/lti/edit_form.php).

Before import, set `moodleCourseId` and `moodleResourceId` to the actual signed
context and resource-link IDs for that activity; do not assume browser URL numbers
are these identifiers. Ask the Moodle administrator for the activity's resource-link
ID; it differs from the course-module ID in an activity URL, as shown in Moodle's
[launch construction](https://github.com/moodle/moodle/blob/MOODLE_501_STABLE/public/mod/lti/locallib.php).
Configure Moodle to release a name if desired; otherwise the form asks for a
self-declared name.

Launch validation checks signature, issuer, audience, deployment, timestamps,
nonce, browser state, learner role, and mapped course/activity. Students then connect
GitHub and confirm acceptance without registration keys. Pilot with a learner account:
an instructor-only launch is rejected. Tests use local fixtures; validate your
own Moodle deployment and its privacy/registration settings in a pilot.
See also [Moodle External tools](https://docs.moodle.org/502/en/LTI_External_tools).

## Recovery

### Diagnose an incomplete registration

**"Registration could not finish"** is a generic response to an I/O failure. It
does not establish that the registration keys are wrong. Note the error reference
shown on the page and find its matching entry in the application log. Under the
supplied systemd deployment:

```sh
journalctl -u finmath-classroom.service --since '15 minutes ago' --no-pager
```

GitHub failures identify the operation, failure category and HTTP status when
available. Logs omit request URLs, credentials, response bodies and raw exception
details. Older deployed versions show no reference and do not log these handled
errors; updating the JAR is necessary to obtain those diagnostics.

Also list the affected assignment in the default JSON format, which includes its
recorded numeric repository ID and problem:

```sh
java -jar target/finmath-classroom.jar registration list \
  --data /private/service/classroom --lecture numerical-methods \
  --assignment example-exercise
```

| Saved state | Next step |
| --- | --- |
| No registration | Inspect the sign-in/account lookup or local storage failure. |
| `retryable`, no repository ID | Inspect the template lookup and preflight checks. |
| `retryable`, recorded repository ID | Inspect repository lookup, staff access and student invitation; retry reuses the recorded repository. |
| `needs_attention` or persistent `creating` | Inspect the expected repository and numeric identity before reconciliation; creation may already have succeeded. |

Preserve the failed registration while investigating. A repository with the
expected name alone is insufficient evidence to adopt it. Deleting the local
record loses its recovery reference and leaves any GitHub repository in place.

### Let a student register again

Use this for a deliberate fresh start. For an incomplete repository setup, use
the retry controls described below instead.

1. List registrations with `registration list --data /private/service/classroom
   --lecture numerical-methods --assignment example-exercise --format table`
   and copy the intended **student ID**.
2. Stop the service and back up its private data directory.
3. Decide whether to retain the old GitHub repository and its work. Any deletion
   or access change is a separate manual operation; the new acceptance will receive
   a new random name identifier.
4. Delete that assignment's local acceptance:

   ```sh
   java -jar target/finmath-classroom.jar registration delete \
     --data /private/service/classroom --lecture numerical-methods \
     --assignment example-exercise --student STUDENT_ID
   ```

5. Restart the service. The student reloads the assignment page and accepts again.

`delete` removes exactly one enrollment, including its repository reference and
recorded declaration. It retains the student's identity and other assignments.
The result identifies the removed enrollment. It requires no GitHub credential
and does not delete a GitHub repository, revoke access or change the configuration.
An unknown student/enrollment or an operation in progress is rejected.

The next acceptance checks the current keys and deadline, records a new declaration,
and creates a repository using the current naming rule and a freshly allocated
identifier. The new name must be free;
an existing repository is never adopted automatically. Deletion does not extend
the assignment deadline or permit changing settings frozen at import.

### Resume repository setup

Reopen the assignment and use **Retry repository setup** to resume recorded
staff/invitation failures after checking repository identity. Use **Refresh access
status** after accepting a GitHub invitation. These actions require the same
verified GitHub account and remain subject to the deadline. Admin listings
distinguish pending invitations, active access, retryable failures and cases
needing inspection. Viewing the page alone does not contact GitHub or retry work.
After signing in again, students can view an already recorded repository link
without reaccepting, including after the deadline.

A crash or lost creation response may leave an ambiguous operation. Stop the service
and inspect the expected repository's numeric ID, owner, privacy and contents before
recording it explicitly:

```sh
java -jar target/finmath-classroom.jar registration reconcile \
  --data /private/service/classroom --lecture numerical-methods \
  --assignment example-exercise --student STUDENT_ID --repository-id 123456789
```

This makes read-only GitHub checks and updates local state so acceptance can resume.
It never creates a repository or replaces an already pinned repository ID. Unknown
same-name repositories are never adopted automatically. If no repository exists,
manual inspection of the reservation remains necessary. Never delete and recreate
a student's repository merely to retry provisioning.
