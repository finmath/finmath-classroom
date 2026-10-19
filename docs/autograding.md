# Autograding action and migration

The [shared action](../action.yml) replaces `education/autograding` for Maven
assignments graded by named JUnit methods or by the whole test suite. It runs tests once, scores
Surefire XML reports, writes a GitHub job summary and uploads a versioned result.
The Java scorer only reads files; Maven executes assignment code on the runner.
Use disposable GitHub-hosted runners with no classroom credentials.

The shared action lives in `finmath/finmath-classroom`. It has now run on GitHub
and generated `result.json`. Server collection is implemented with administrative
commands and an optional timer; its end-to-end GitHub pilot remains pending.
Authoritative final grading remains future work.

## 1. Migrate `.github/classroom/autograding.json`

Replace each method-specific Maven command with the method's fully qualified test
class, method name and existing point weight. For example:

```json
{
  "tests": [
    {
      "class_name": "info.quantlab.numericalmethods.assignments.montecarlo.controlvariate.AsianOptionWithBSControlVariateTest",
      "method": "testBasicFunctionality",
      "points": 5
    }
  ]
}
```

Repeat for the other graded methods, preserving their weights. The complete
[example rubric](../examples/grading/rubric.json) has five methods totaling 22 points.
Use the class name reported as `classname` in Surefire XML, including its package.
For method grading, the file contains exactly `tests`, with exactly `class_name`, `method` and `points`
per entry; filenames can remain `autograding.json`. This is the existing rubric
format, now also used to select which methods Maven runs.

| Old field | Migration |
| --- | --- |
| `run` containing `-Dtest=Class#method` | Extract the full class and method names; remove the command string. |
| `points` | Keep unchanged; positive decimal values are supported. |
| `name` | Remove; the job summary displays test method names. |
| Empty `setup`, `input`, `output` | Remove. |
| `comparison: included` with empty expected output | Remove; JUnit assertions determine pass/fail. |
| `timeout` | Set the action's `timeout-minutes`; it now covers the entire Maven invocation. |

The action invokes Maven with all selectors in one quoted `-Dtest` argument:

```text
mvn -U -B -ntp -Dcheckstyle.skip=true "-Dtest=package.Test#first,package.Test#second" clean test
```

There is no `grep` output filter. Maven logs remain available, and XML determines
the score. Running the methods together avoids successive invocations overwriting
each other's reports. Surefire supports class/method selection; check your project's
plugin/provider compatibility in the pilot. [Surefire selection reference](https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html)

`-U` checks remote repositories for updated SNAPSHOT dependencies on each grading
run, even when Maven dependencies were restored from the workflow cache. This lets
instructors deploy updated test artifacts while an assignment is running; their
interfaces and the rubric's method names must remain compatible. It also checks
for missing releases. [Maven CLI reference](https://maven.apache.org/ref/3.9.11/maven-embedder/cli.html)

**Review timeout budgets:** the old timeout was per grading entry. Five entries
with ten minutes each do not have the same budget as a single ten-minute run.
Choose the new total intentionally, including compilation, and leave additional
time in the job timeout for building the scorer and uploading feedback.

For a legacy rule awarding all points only when `mvn test` succeeds, use the
[suite rubric](../examples/grading/suite-rubric.json):

```json
{"suite": {"points": 5}}
```

Keep the original point total. `suite` and `tests` are alternative formats, not
fields to combine. Suite mode runs `mvn -U -B -ntp -Dcheckstyle.skip=true clean test`
without a `-Dtest` filter, retaining Maven's configured test discovery. Complete
passing reports and a successful Maven exit earn the full total; assertion failures
earn zero. Missing, skipped, duplicate or invalid results and process errors leave
the uploaded score unavailable. This preserves all-or-nothing points without
inventing per-method weights. Suite mode does not declare which tests must exist;
it remains student feedback, not proof of test coverage or an authoritative grade.

Both formats support a single Maven module. Method grading requires ordinary,
uniquely named JUnit methods; parameterized/dynamic method selectors, arbitrary
commands and stdin/stdout comparisons remain unsupported. Necessary setup belongs in the Maven build or an explicit
workflow step whose output survives `clean`. Existing custom Maven flags need
review; the action does not execute legacy `run` strings.

## 2. Replace the grading job

Publish this repository's action, Java sources and scripts together, including
the `v1` tag, before publishing the assignment workflow changes. This is a
**composite action used inside `steps`**, not a reusable workflow called with a
job-level `uses` or a path to `.github/workflows`.

```yaml
autograding:
  name: Autograding
  runs-on: ubuntu-24.04
  timeout-minutes: 30
  permissions:
    contents: read
  steps:
    - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      with:
        persist-credentials: false
    - name: Set up assignment Java
      uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0
      with:
        distribution: zulu
        java-version: '17'
        cache: maven
    - name: Test and upload feedback
      uses: finmath/finmath-classroom@v1
      with:
        rubric: .github/classroom/autograding.json
        timeout-minutes: '10'
        retention-days: '30'
```

The maintained `v1` tag advances only to tested, backward-compatible releases.
Existing repositories using `@v1` receive those grader fixes on subsequent runs
without a pull request. Keep release tags such as `v1.0.0` fixed; use one of those
or a full commit SHA when you want to hold the grader version. Breaking changes
belong in `v2`. This follows [GitHub's action release guidance](https://docs.github.com/en/actions/how-tos/create-and-publish-actions/manage-custom-actions#using-release-management-for-actions).

For initial publication, the tags are `v1.0.0` and `v1`. The publication sequence
is the service and its reviewed tags first, then the template workflow commits:

```sh
git push origin main
git push origin refs/tags/v1.0.0:refs/tags/v1.0.0 refs/tags/v1:refs/tags/v1
```

Updating `v1` or deploying a SNAPSHOT does not itself trigger student workflows.
The shared action controls execution, scoring and upload; rubric weights, test
selection and surrounding workflow jobs remain files in each student repository.
Those local changes still need a repository update. A fixed grader reference does
not fix SNAPSHOT test contents. Recording the actual resolved grader and test
artifact versions for reproducible final grading remains future work.

The [complete workflow example](../examples/grading/classroom.yml.example) retains
five visible jobs:

| Job | Role |
| --- | --- |
| 🛠️ Build | Compiles without tests or checkstyle. |
| 📚 JavaDoc | Advisory documentation check, after a successful build. |
| 🔍 Checkstyle | Advisory style check, after a successful build. |
| 🧪 Autograding | Runs independently and awards only rubric points. |
| 🥉🥈🥇 Summary | Shows the four job outcomes with status icons, including failures and skipped jobs. |

JavaDoc and checkstyle failures remain visibly red and can make the overall
workflow red, but they do not change points or block autograding. If branch
protection is used, leave these advisory jobs out of the required checks. The
summary reads GitHub's `needs` results directly; the autograding action provides
the points and per-test table in its own job summary. No annotations API or extra
token permissions are needed.

For a POM without Checkstyle configuration, set the example's checkstyle job
condition to `if: ${{ false }}`; the summary then shows it as skipped.

The same example can be installed across templates sharing the Maven layout,
Java version and timeout budget. Use the maintained `@v1` grader reference for
the common workflow, then migrate each assignment's `autograding.json` and retain
its tests. The workflow-level `JAVA_VERSION` defaults to 17; adjust it for projects
requiring another version. Retain an intentional total test timeout and allow
another 20 minutes in the grading job for scorer setup and artifact upload.
For a migration, summing the previous per-entry budgets preserves their maximum
sequential allowance, but does not reproduce individual test timeouts. Projects
without grading rules can retain ordinary ungraded test jobs until a rubric is chosen.

The example supports `push`, ordinary `pull_request` and manual `workflow_dispatch`.
For a pull request, GitHub's default checkout tests the synthetic merge commit;
the result records that exact `GITHUB_SHA`, `event: pull_request` and a ref such as
`refs/pull/42/merge`. The action still requires checkout HEAD to match `GITHUB_SHA`.
PR results are feedback on the merge candidate, not a submission on the assignment
branch. Server feedback collection selects push runs on the configured assignment
branch; it does not substitute PR or manual runs. Capturing deadline submissions
remains future work. The privileged
`pull_request_target` event remains unsupported.
See [GitHub's pull-request event semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request).
Call the action once per workflow run, since artifact names identify a run and
attempt, not a matrix job.

Inputs are `rubric`, `working-directory`, `timeout-minutes` and `retention-days`.
The working directory defaults to the checkout root; the rubric path is relative
to that directory. Reports come from `target/surefire-reports`. The action supports
Ubuntu runners with Bash, Maven, Git, jq and GNU timeout. Retention must fit the
repository's policy. A cancelled runner or failed setup/upload may leave no artifact.

The action installs a separate Java 17 runtime for the scorer and builds the JAR
from the resolved action source in its own directory. The assignment retains the
Java version selected above, including Java 11 if needed. Building the scorer is
an initial distribution mechanism; there is no JAR release to download yet.
The shared action's external action dependencies remain pinned to commits. GitHub
documents this step-sharing mechanism in its [composite action guide](https://docs.github.com/en/actions/tutorials/create-actions/create-a-composite-action).

Replace the old grading-summary job that scrapes `education/autograding` annotations
with the summary in the complete example. Maven caching is handled by
`setup-java`'s `cache: maven`; the separate `actions/cache@v3` steps are unnecessary.
The JavaDoc command uses the Maven Javadoc plugin's current `-Ddoclint=none`
property instead of `additionalparam`. Review plugin compatibility for older POMs.
See the [Javadoc plugin reference](https://maven.apache.org/plugins/maven-javadoc-plugin/javadoc-mojo.html#doclint).
Updating a template affects future copies. Existing student repositories need a
one-time workflow update to adopt `@v1`, and separate updates for local workflow
or rubric changes; compatible changes inside the shared action then follow `v1`.

## 3. Uploaded result contract: version 1

Artifact name: **`classroom-feedback-<run_id>-<run_attempt>`**.
It contains one UTF-8 JSON file, **`result.json`**, conforming to
[feedback-result-v1.schema.json](../schemas/feedback-result-v1.schema.json).
A [complete example](../examples/grading/result.json) illustrates the format.

| Field | Meaning |
| --- | --- |
| `schema_version`, `kind` | `1` and `finmath-classroom-feedback`; incompatible changes require a new version. |
| `authoritative` | Always `false`. |
| `generated_at` | UTC time the result was packaged, not submission time. |
| `context` | Numeric `repository_id`, `run_id`, `run_attempt`; repository full name, tested `commit`, Git `ref`, `event`, `workflow_ref`, `workflow_sha`. |
| `rubric_sha256` | SHA-256 of the exact rubric bytes captured before testing. |
| `execution` | Maven/timeout `exit_code` and process `status`. |
| `status` | Effective feedback state, taking process failure and report completeness into account. |
| `score`, `max_score` | Available earned points or `null`, and the rubric total. |
| `feedback` | Local scorer v1 output, including weighted method cases or one aggregate suite case, and diagnostic errors. |

Method cases retain `class_name` and `method`. Suite feedback instead has one case
with `suite: "Maven test suite"`, the full point weight, and an aggregate status.
Its `occurrences` is 0 when no test cases were reported, otherwise 1; it does not
count individual methods. The suite alternative extends the v1 schema before its
first publication; existing method results are unchanged.

Process status is `completed` for exit 0 or 1, `timed_out` for GNU timeout's exit
124, and `error` for other exit codes. Exit 137 is ambiguous (for example, forced
termination or out-of-memory), so it is an error rather than a proven timeout.
`completed` means the command returned normally, not that all tests passed.

| Effective `status` | Top-level `score` |
| --- | --- |
| `passed` | Full points, with successful command execution and complete passing reports. |
| `failed` | Earned partial points, possibly zero, from complete reports containing assertion failures. |
| `incomplete` | `null`: required reports/tests are missing, skipped or duplicated. |
| `error` | `null`: invalid reports, JUnit errors, abnormal execution, or a failed command despite passing reports. |
| `timed_out` | `null`, even if some reports were written before the timeout. |

Consumers must use the **top-level status and score**. Nested `feedback.score`
is diagnostic and may contain zero or partial points even when the result is
unavailable. `max_score` equals the nested rubric total; a numeric top-level score
equals the nested score and stays between zero and that total. A failed Maven
command without reports remains incomplete; the exit code/log explains the failure.
The producer does not guess whether a missing report means a compilation or
infrastructure error. Artifact absence is a collector state, not a zero-score file.

The tuple `(repository_id, run_id, run_attempt)` identifies a result. Preserve
attempt history and cross-check context against GitHub's API before associating
feedback with an enrollment and exact commit. Never select an artifact by name
alone or let an older notification overwrite a newer attempt. The rubric digest
identifies the submitted rubric; it does not prove an instructor approved it.
Student-controlled metadata/reports are claims, not authentication or final grades.
The existing `collect` command accepts a different, trusted final-result format.

The action attempts upload before returning the grading failure status, so failed
tests still produce feedback. Server synchronization polls for recoverable
artifacts, independently of workflow success or a classroom server outage.
Choose retention long enough for the expected outage and recovery window; an
artifact deleted or expired before collection cannot be recovered by polling.

## 4. Local checks and rollout

After building the service JAR, select methods or package existing reports without
executing assignment code:

```sh
java -jar target/finmath-classroom.jar feedback select \
  --rubric examples/grading/rubric.json

java -jar target/finmath-classroom.jar feedback report \
  --rubric examples/grading/rubric.json \
  --reports /path/to/surefire-reports \
  --context examples/grading/context.json \
  --test-exit-code 1 \
  --output /private/service/result.json \
  --summary /private/service/summary.md
```

The example context is fictional; the action constructs real context from the run
environment. Output directories must exist, and output files must be new. Report
exit codes are 0 for passed, 1 for failed/incomplete, and 2 for errors/timeouts.
An input error can prevent output entirely. The local `score` command also accepts
suite rubrics. Existing method rubrics and their feedback retain their format.

Run `mvn -B -ntp verify`, then `bash scripts/test-autograding.sh` for the action's
offline shell integration checks with a fake Maven process and the real JAR. These
checks do not run student code or contact GitHub. The action has executed on
GitHub and generated a result; complete the collection pilot with passing and
failing tests, a timeout and a rerun. Inspect the summary, uploaded artifact and
server's `feedback list`/`show` output, including attempt number, exact commit and
null scores for unavailable results. Stop collection temporarily, then verify
that synchronization recovers retained artifacts after restart. Verify weights
against the old configuration before changing other templates.

## 5. Automatic server collection

The existing shared workflow already uploads the required artifact. Enabling
collection requires no workflow change or classroom credential in student
repositories. The server uses GitHub's read-only APIs to download feedback; it
does not check out repositories, execute student code or run Maven.

Use the imported registration data directory and a server credential with
**Actions: read** access to the enrolled private repositories. Set
`CLASSROOM_FEEDBACK_TOKEN` for a dedicated credential; when unset or blank, the
collector uses `CLASSROOM_GITHUB_TOKEN`, which must have that permission too.
Keep credentials in the private service environment file described in the
[deployment guide](deployment.md). GitHub documents access requirements in its
[artifact API](https://docs.github.com/en/rest/actions/artifacts).

If collection reports HTTP 403 while registration works, check **Actions →
Read-only** on the effective server token first. The original provisioning grants
do not include it. For a fine-grained PAT, edit the existing token in GitHub,
retain its provisioning permissions, ensure it covers the teaching organization
and generated student repositories, and obtain organization approval if prompted.
When the token value stays the same, rerun `feedback sync`; no service restart or
JAR update is needed. If a dedicated `CLASSROOM_FEEDBACK_TOKEN` is configured,
changing the fallback token's permissions will not change collector access.
HTTP 403 can also reflect organization access policies; the status alone does not
identify the exact denied operation.

```sh
java -jar target/finmath-classroom.jar feedback sync \
  --data /var/lib/finmath-classroom \
  --lecture numerical-methods --assignment quadratic-equation

java -jar target/finmath-classroom.jar feedback list \
  --data /var/lib/finmath-classroom \
  --lecture numerical-methods --assignment quadratic-equation

java -jar target/finmath-classroom.jar feedback show \
  --data /var/lib/finmath-classroom \
  --repository-id 123456 --run-id 987654 --attempt 1
```

Lecture and assignment IDs come from local configuration; repository, run and
attempt IDs for `show` come from `list`. Omit the lecture and assignment filters
to synchronize or list all enrolled repositories; `--assignment` requires
`--lecture`. The selected workflow defaults to `classroom.yml`. Override it with
`CLASSROOM_FEEDBACK_WORKFLOW` or, for one synchronization, `--workflow feedback.yml`.
Use scoped synchronizations when assignments have different workflow filenames.
`list` and `show` read local data without credentials or network access.

Synchronization records completed push attempts on each assignment's configured
branch, including failed workflows. It revisits older runs to discover reruns,
checks repository/run/attempt/commit and workflow identity against GitHub metadata,
and accepts only the expected `result.json` from a bounded ZIP. The archive limit
is 2 MiB and the uncompressed JSON limit is 1 MiB. The server verifies a supplied
artifact SHA-256 digest and validates the result's fields, scores and statuses.

The first synchronization creates private `DATA/feedback.db` beside `classroom.db`.
It stores the validated JSON, enrollment association, commit, run and attempt,
workflow identity, artifact ID/digest, archive checksum and receipt time. Back up
both databases and the configuration. Repeated collection is harmless; received
attempts remain available locally after GitHub removes their artifacts, and reruns
remain separate historical records. A stored commit is a history label, not a
claim that it is still the branch head.

`list` reports collection states including `pending`, `missing`, `expired`,
`unavailable` and `invalid`. Missing or invalid feedback has a null score; valid
incomplete, error and timeout results also retain their top-level null score.
The sync report returns received, already-stored, unavailable and error counts.
Exit 2 indicates errors or a deferred rate limit; exit 0 means the scan completed,
so inspect unavailable counts before assuming every result was collected.
Transient failures and missing artifacts are retried by subsequent syncs; a saved
rate-limit delay survives a service restart.

Each run/artifact listing is bounded to 100 pages of 100 records, and runs to
1,000 attempts. Exceeding a listing limit reports an error rather than silently
presenting a partial scan as complete. Synchronization rescans run history so
reruns of older commits are discovered; API usage grows with that history.

The supplied [systemd timer](../deploy/finmath-classroom-feedback.timer) runs the
[collection service](../deploy/finmath-classroom-feedback.service) five minutes
after its previous invocation finishes, starting about two minutes after boot
with up to thirty seconds of jitter. Enable it using the deployment guide to make
downloads automatic. Manual sync remains available for immediate recovery.
Webhooks and direct workflow result POSTs are future work.

Collected values remain non-authoritative student feedback. API correlation and
artifact checksums identify the downloaded result; they cannot prove that a
student used approved tests, reports, rubric or workflow. Final marks still require
instructor-controlled grading and the separate trusted-result `collect` command.
