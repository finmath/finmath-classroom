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

The [composite action and migration guide](autograding.md) now provide weighted
JUnit scoring, summaries and versioned result artifacts. Publish the action and
pilot it on GitHub before rolling it out. It currently builds the scorer JAR from
its pinned source; a published JAR with a verified checksum could shorten startup
later. No assignment POM needs to inherit from this service, and the generated
lecture workspace remains separate from the published action.

Review each template's weights, Java version and total timeout budget during
migration. Whole-command/all-or-nothing grading and parameterized/dynamic tests
still need explicit support; do not change their semantics by mechanically
converting them to per-method points. Pilot a small supported assignment first.
Existing student repositories need a separate update because template changes do
not propagate to independent copies. Keep unrelated style/documentation jobs
outside the grade and update their old action dependencies separately.

## 3. Collect feedback reliably

Make the GitHub artifact the recoverable copy; the classroom server downloads and
stores it privately. The workflow needs no classroom credential, and an outage of
the classroom server need not prevent student feedback.

Start with two proposed administrative commands:

```text
feedback sync --data DIR [--lecture ID] [--assignment ID]
feedback list --data DIR [--lecture ID] [--assignment ID]
```

They should reuse the enrolled repository IDs and a server-side credential with
Actions read access. Keep this separate from the existing `collect` command, which
only consumes trusted final results. Use the existing application and SQLite; no
separate queue service is needed.

For each configured feedback workflow and enrolled repository, synchronization
fetches completed push runs on the configured branch. Pull-request merge feedback
and manual runs remain separate from submission selection. Match repository ID, run ID,
attempt and exact `head_sha` against GitHub API metadata. Store those identifiers,
artifact ID/digest, workflow identity and receipt time alongside the feedback.
Keep rerun history; duplicate collection must be harmless, and older notifications
must not overwrite newer attempts. A result for an older commit must remain labeled
with that commit rather than looking current.

GitHub lists artifacts by run, so the artifact name and envelope must distinguish
attempts. Read only the expected JSON from a size-bounded archive; do not execute
its contents or extract arbitrary paths. Fetch through known GitHub API routes,
not arbitrary callback URLs. See the [artifact API](https://docs.github.com/en/rest/actions/artifacts)
and [run-attempt API](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt).

After synchronization works, an organization/GitHub App webhook can notify the
server when `workflow_run` completes. A proposed `POST /hooks/github` verifies the
raw-body HMAC, records delivery IDs and pending work durably, and acknowledges
promptly. Downloads run outside the request and database write transaction.
This is a GitHub webhook delivered to the service, not a privileged workflow that
executes student code. See [webhook practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks).

Periodic synchronization and manual retries remain necessary: GitHub does not
automatically redeliver failed webhooks. Retry transient API/rate-limit failures
with backoff and show unavailable, expired and invalid artifacts explicitly.
Set retention long enough for the expected recovery window and save fetched
results locally; deleted or expired artifacts cannot be recovered by retries.
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
failures. Student code must have no classroom credentials or access to the
administration process, trusted result storage or other students' work. Sharing
the scorer is useful; copying an editable workflow result into the final-result
format does not make it trustworthy.

Feed those trusted results into the existing CSV collector. Moodle grade return,
roster synchronization and richer administration can follow once the submission
and grading contracts work end to end.

## Checks for the next implementation

Use fixtures and disposable test repositories deliberately. Cover pass/partial/fail
scoring, compile errors, skipped/missing/duplicate tests, timeout and cancelled runs;
then duplicate/out-of-order deliveries, reruns, wrong repository/branch/SHA, invalid
signatures, bounded artifacts, rate limits, and recovery after a server outage.
Prove a missing result stays missing instead of becoming a zero mark. Run a live
pilot separately from the offline Java tests before rolling changes across templates.
