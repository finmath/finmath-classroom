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
```

`check` and `assignments` read configuration only. `students` and `list` open the
existing database read-only and emit private administrative JSON. Listings omit keys.
Edit organization, template, staff team, branch, prefix, deadline and any Moodle
mapping **before the first import**: these values are frozen in the database.

`import` creates Java SQLite schema version 1 and pins supplied usernames to numeric
GitHub IDs using read-only API calls. A roster without GitHub usernames imports offline.
The database uses owner-only permissions (`0600` on POSIX systems) and is independent
of the Python database. Back up the private directory while the service is stopped;
do not point Java at a Python prototype database.

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

Register the OAuth application's callback as
`https://assignments.example.edu/auth/github/callback`. Student sign-in requests
no repository scope; its token identifies the account, while provisioning uses
the separate server credential. Roster import needs only the provisioning token
when usernames are supplied. Never place server credentials in student repositories.

The provisioning credential must cover the template and newly generated repositories.
For fine-grained tokens, the implemented endpoints require repository Administration
write and Contents read for
[template generation](https://docs.github.com/en/rest/repos/repos#create-a-repository-using-a-template),
Administration write for
[student invitations](https://docs.github.com/en/rest/collaborators/collaborators#add-a-repository-collaborator),
and organization Members read plus repository Administration write and Metadata read
for [staff-team grants](https://docs.github.com/en/rest/teams/teams#add-or-update-team-repository-permissions).
Organization policy must permit these operations. Supplied GitHub App tokens are
usable, but automatic renewal is not implemented.

Enable the template, match its default branch to `branch`, and review its committed
contents for solutions. Acceptance creates an independent private repository named
`<repositoryPrefix>-<numericGitHubId>`, grants the configured staff team `maintain`,
and invites the student with `write` access. Students remain outside collaborators.
The service does not change organization membership or base permissions; configure
the organization's base permissions appropriately before a pilot.

Distribute `https://assignments.example.edu/accept/numerical-methods/example-exercise`
for standalone registration. The student connects GitHub, supplies their name and
both keys, and confirms the ownership/authorship declaration. Only the confirmed
POST creates a repository. The database records acceptance time, declaration version,
numeric account/repository IDs, repository URL and provisioning status. An optional
deadline rejects acceptance and retries at or after the cutoff.

Run one service instance behind an HTTPS reverse proxy for public use, with request
limits, registration rate limiting and monitoring. The server binds to loopback by
default. A remote browser cannot reach a service running only on your laptop
through `127.0.0.1`; use the public HTTPS origin for a student pilot. Suppress
OAuth/LTI query strings and request bodies in logs.
Sessions and pending logins are in memory; interrupted sign-ins restart after a
service restart. Hosting and a live GitHub pilot have not been performed.

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

Reopening an assignment and accepting again resumes recorded staff/invitation
failures after checking repository identity. Admin listings distinguish pending
invitations, active access, retryable failures and cases needing inspection.
Invitation acceptance is refreshed on a subsequent acceptance attempt.
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
