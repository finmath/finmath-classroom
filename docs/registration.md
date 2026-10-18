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
`<repositoryPrefix>-<githubUsername>-<numericGitHubId>`, grants the configured staff team `maintain`,
and invites the student with `write` access. Students remain outside collaborators.
The service does not change organization membership or base permissions; configure
the organization's base permissions appropriately before a pilot.

The username is lowercase and reflects the account at first acceptance. Recorded
repository names remain unchanged on retries or username changes, including names
created by older versions. New names must fit within 100 characters; use a prefix
of at most 40 characters to leave room for any supported username and numeric ID.

Distribute `https://assignments.example.edu/accept/numerical-methods/example-exercise`
for standalone registration. The student connects GitHub, supplies their name and
both keys, and confirms the ownership/authorship declaration. Only the confirmed
POST creates a repository. The database records acceptance time, declaration version,
numeric account/repository IDs, repository URL and provisioning status. An optional
deadline rejects acceptance and retries at or after the cutoff.

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

### Let a student register again

Use this for a deliberate fresh start. For an incomplete repository setup, use
the retry controls described below instead.

1. List registrations with `registration list --data /private/service/classroom
   --lecture numerical-methods --assignment example-exercise --format table`
   and copy the intended **student ID**.
2. Stop the service and back up its private data directory.
3. Manually delete the student's GitHub repository after retaining any work you need.
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
and creates a repository using the current naming rule. The new name must be free;
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
