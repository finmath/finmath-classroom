# Autograding action and migration

The [shared action](../action.yml) replaces `education/autograding` for Maven
assignments graded by named JUnit methods or by the whole test suite. It runs tests once, scores
Surefire XML reports, writes a GitHub job summary and uploads a versioned result.
The Java scorer only reads files; Maven executes assignment code on the runner.
Use disposable GitHub-hosted runners with no classroom credentials.

The shared action lives in `finmath/finmath-classroom`. Its pinned commit must be
pushed there before assignment workflows can use it; a live GitHub pilot is still
required. Automatic server
collection and authoritative final grading remain pending.

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
mvn -B -ntp -Dcheckstyle.skip=true "-Dtest=package.Test#first,package.Test#second" clean test
```

There is no `grep` output filter. Maven logs remain available, and XML determines
the score. Running the methods together avoids successive invocations overwriting
each other's reports. Surefire supports class/method selection; check your project's
plugin/provider compatibility in the pilot. [Surefire selection reference](https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html)

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
fields to combine. Suite mode runs `mvn -B -ntp -Dcheckstyle.skip=true clean test`
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

Publish this repository's action, Java sources and scripts together to
`https://github.com/finmath/finmath-classroom`. The example pins the grader to
commit `1db382921c5d48b38bbcde50bc113dbee75aad46`; push that commit before GitHub
can resolve it, then publish the assignment workflow changes. This is a
**composite action used inside `steps`**, not a reusable
workflow called with a job-level `uses` or a path to `.github/workflows`.

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
      uses: finmath/finmath-classroom@1db382921c5d48b38bbcde50bc113dbee75aad46
      with:
        rubric: .github/classroom/autograding.json
        timeout-minutes: '10'
        retention-days: '30'
```

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
Java version and timeout budget. Use the same reviewed grader commit for
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
branch. A future submission collector must select push results on the configured
branch separately; it must not substitute PR or manual runs. The privileged
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
from the pinned action source in its own directory. The assignment retains the
Java version selected above, including Java 11 if needed. Building the scorer is
an initial distribution mechanism; there is no JAR release to download yet.
The shared action's dependencies are also pinned to commits. GitHub documents this
step-sharing mechanism in its [composite action guide](https://docs.github.com/en/actions/tutorials/create-actions/create-a-composite-action).

Replace the old grading-summary job that scrapes `education/autograding` annotations
with the summary in the complete example. Maven caching is handled by
`setup-java`'s `cache: maven`; the separate `actions/cache@v3` steps are unnecessary.
The JavaDoc command uses the Maven Javadoc plugin's current `-Ddoclint=none`
property instead of `additionalparam`. Review plugin compatibility for older POMs.
See the [Javadoc plugin reference](https://maven.apache.org/plugins/maven-javadoc-plugin/javadoc-mojo.html#doclint).
Updating a template affects future copies. Existing student repositories require
a separate, reviewed workflow/configuration update.

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
tests still produce feedback. A future server collector must recover missed
notifications by polling while artifacts remain available; GitHub does not
automatically redeliver failed webhooks. [Delivery behavior](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries)

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
checks do not run student code or contact GitHub. Before rolling out, pilot on
GitHub with passing and failing tests, a timeout, and a rerun; inspect the summary
and downloaded artifact, its attempt number and the exact commit. Verify weights
against the old configuration before changing other templates.
