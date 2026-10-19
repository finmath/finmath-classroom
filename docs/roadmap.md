# Roadmap

The [README](../README.md#what-is-available) describes implemented capabilities.
The remaining work and operational checks are below. Implemented components are
linked where relevant. See [development notes](development.md) for history and conventions.

## 1. Launch registration

Run one instance on a maintained HTTPS host with persistent private storage, an
off-server backup and a tested restore procedure. Verify the public OAuth callback,
provisioning-token permissions and expiry, organization policy, staff access and
student invitations using a learner account. Validate Moodle LTI against the actual
installation if needed; ordinary Moodle links can already use shared-key registration.

Registration can launch independently of automatic result collection if the course
has an explicit interim grading process. The configured deadline currently closes
acceptance/retries only: it neither stops repository pushes nor captures submissions.
Do not promise automatic deadline enforcement or final grades before those exist.

## 2. Share the student feedback workflow

The [composite action and migration guide](autograding.md) provide weighted
JUnit scoring, all-or-nothing Maven suite scoring, summaries and versioned result
artifacts. The shared action has now run on GitHub and generated `result.json`.
Complete the pilot, including server collection, before wider rollout. Advance
`v1` only to tested, compatible releases, keep specific release tags fixed, and
use `v2` for breaking changes.
It currently builds the scorer JAR from its resolved action source; a published
JAR with a verified checksum could shorten startup later. No assignment POM needs
to inherit from this service, and the generated
lecture workspace remains separate from the published action.

Review each template's weights, Java version and total timeout budget during
migration. Use the suite rubric for whole-Maven-test grading instead of inventing
per-method weights. Arbitrary commands and parameterized/dynamic method selectors
still need explicit support. Pilot a small supported assignment first.
Existing student repositories need a one-time update to adopt `@v1`; compatible
shared grader fixes then reach subsequent runs without repository changes.
Local rubric and workflow changes still need separate updates. Grading uses
Maven `-U` to check for updated SNAPSHOT test artifacts even with a restored cache.
Moving the tag or deploying tests does not itself trigger student workflows.
Keep unrelated style/documentation jobs outside the grade.

## 3. Operate feedback collection and verify recovery

The classroom server now downloads GitHub feedback artifacts and stores validated
results privately in `DATA/feedback.db`. The existing shared workflow needs no
change or classroom credential, and a server outage need not prevent student
feedback. The [collection guide](autograding.md#5-automatic-server-collection)
describes the implemented administrative commands:

```text
feedback sync --data DIR [--lecture ID] [--assignment ID] [--workflow FILE]
feedback list --data DIR [--lecture ID] [--assignment ID]
feedback show --data DIR --repository-id ID --run-id ID --attempt N
```

Synchronization reuses enrolled numeric repository IDs and the configured
assignment branch. Set a server-side `CLASSROOM_FEEDBACK_TOKEN` with Actions read
access; it falls back to `CLASSROOM_GITHUB_TOKEN`. Workflow selection defaults to
`classroom.yml`, with `CLASSROOM_FEEDBACK_WORKFLOW` and `--workflow` overrides.
The optional [systemd timer](../deploy/finmath-classroom-feedback.timer) starts a
sync five minutes after the previous invocation finishes. Deployment and enabling
the timer remain administrative steps; the code does not enable services itself.

The collector fetches completed push attempts, including failed workflows, and
cross-checks repository, run, attempt, exact commit and workflow identity against
GitHub metadata. It retains rerun history and receipt/artifact metadata; duplicate
collection preserves received results. Older commits remain labeled with their
own SHA. PR and manual feedback stay outside this collection, which does not
capture submissions or enforce deadlines.

Downloads use read-only GitHub API routes and bounded archives containing only
the expected `result.json`; the server does not extract arbitrary paths or execute
student code. Collection statuses expose missing, expired, unavailable and invalid
artifacts, with null scores. A received error or incomplete result also keeps its
top-level null score. This remains separate from `collect`, which only consumes
trusted final results.

Validate end-to-end collection with separate learner accounts, including
repeated sync, old-run reruns, temporary outages and rate-limit recovery, then
back up and restore `feedback.db` alongside the registration data. Subsequent syncs
retry missing/transient results, and rate-limit delays persist across restarts.
Set artifact retention longer than the expected recovery window: artifacts deleted
or expired before collection cannot be recovered by retrying.

A future organization/GitHub App webhook can notify the server when `workflow_run`
completes. A proposed `POST /hooks/github` would verify the raw-body HMAC, record
delivery IDs and pending work durably, and acknowledge promptly. Downloads would
run outside the request and database write transaction. This is a GitHub webhook
delivered to the service, not a privileged workflow that executes student code.
See [webhook practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks).

Periodic synchronization and manual retries would remain necessary alongside
webhooks: GitHub does not automatically redeliver failed deliveries.
See [failed deliveries](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries).

A direct result POST from the workflow is also possible using short-lived GitHub
OIDC identity, with checked audience/repository/workflow/run claims and idempotent
retries. It adds an authentication path and still needs artifact recovery after
outages, so defer it until there is a concrete need. Never distribute a shared
classroom API secret to student repositories. GitHub describes the available
identity claims in its [OIDC reference](https://docs.github.com/en/actions/reference/security/oidc).

This design makes receipt and correlation auditable, not the score authoritative.
A student can change tests, build files, reports and the calling workflow. A valid
webhook signature or OIDC token establishes origin; it cannot prove correct grading.
Show these collected values as feedback, separately from final marks.

## 4. Capture submissions and produce final grades

Implement signed push capture, durable source snapshots and cutoff manifests using
the [grading contract](grading-contract.md). Resolve its two open schema questions:
roster entries without GitHub usernames and a collision-free assignment identifier
across lectures. Decide and publish outage/late-delivery handling before using the
processing-time cutoff. Workflow completion time is not submission time: grading
may legitimately finish after a submission deadline.

Run authoritative grading in a separately isolated, instructor-controlled runner
with trusted tests, rubric and build policy. Use the preserved submission commit,
resource/time limits, and an explicit policy for compile errors and infrastructure
failures. Record the resolved grader commit and exact test artifact versions or
digests; mutable `v1` and SNAPSHOT coordinates alone cannot reproduce a result.
This version recording is not implemented yet. Student code must have no classroom
credentials or access to the administration process, trusted result storage or
other students' work. Sharing
the scorer is useful; copying an editable workflow result into the final-result
format does not make it trustworthy.

Feed those trusted results into the existing CSV collector. Moodle grade return,
roster synchronization and richer administration can follow once the submission
and grading contracts work end to end.

## Assistant pilot before student distribution

After installing the service or applying a reviewed update with the
[release updater](deployment.md#update-from-a-github-release),
send the assignment acceptance URLs to a small group of assistants. Give them
ordinary learner access for this test, using their own GitHub accounts.

- Open an acceptance URL in a fresh/private browser, sign in, confirm the name,
  accept the declaration, and accept the repository invitation. Check that the
  repository is private and that the student and staff can access it.
- Disconnect and reconnect on the acceptance page; check the green affirmative
  and red disconnect controls. Return to an accepted assignment and confirm it
  shows the same repository rather than creating another one.
- Push a deliberately failing solution, then a passing solution. Check the GitHub
  summary, points and uploaded feedback artifact for each run. Checkstyle/JavaDoc
  remain advisory and independent of grading.
- Collect with `sudo classroom feedback sync`, then inspect
  `sudo classroom feedback list --latest --format table`. Compare each person's
  repository, commit and points against GitHub. A new push creates a new run;
  **ATTEMPT** counts reruns within one run. Repeated collection must not duplicate
  results. Missing feedback must remain unavailable rather than becoming zero.
- Confirm the optional collection timer is enabled if automatic polling is wanted.
  Keep a consistent off-server backup and test its restoration separately before
  broader use. Record any failure with time, assignment, run URL and the displayed
  error reference; exclude tokens, registration keys and private student data from
  public issue reports.

This pilot is suitable for testing registration and student feedback. Current
deadlines only close acceptance/retries, and collected student-workflow scores
are not authoritative final grades. Agree an interim final-grading process before
using the service for assessed coursework.

## Checks for the next implementation

Use fixtures and disposable test repositories deliberately. Cover pass/partial/fail
scoring, compile errors, skipped/missing/duplicate tests, timeout and cancelled runs;
then duplicate/out-of-order deliveries, reruns, wrong repository/branch/SHA, invalid
signatures, bounded artifacts, rate limits, and recovery after a server outage.
Prove a missing result stays missing instead of becoming a zero mark. Run a live
pilot separately from the offline Java tests before rolling changes across templates.
