#!/usr/bin/env bash
# Offline integration checks: real feedback CLI, fake assignment tools, no student code.
set -euo pipefail
project_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
jar=${1:-$project_root/target/finmath-classroom.jar}
export JAR_PATH="$(cd -- "$(dirname -- "$jar")" && pwd -P)/$(basename -- "$jar")"
export REAL_JAVA=${GRADER_JAVA:-$(command -v java)}
export ORIGINAL_JAVA_HOME=${JAVA_HOME:-}
suite=$(mktemp -d "${TMPDIR:-/tmp}/classroom-action-test.XXXXXX")
trap 'rm -rf -- "$suite"' EXIT
mkdir "$suite/bin"

cat > "$suite/bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'rev-parse HEAD' ]]
printf '%s\n' "${FAKE_GIT_SHA:-$GITHUB_SHA}"
EOF
cat > "$suite/bin/timeout" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == --signal=TERM && "$2" == --kill-after=30s && "$3" == 7m ]]
shift 3
[[ "$1" == mvn ]]
status=0
"$@" || status=$?
exit "${FAKE_TIMEOUT_EXIT:-$status}"
EOF
cat > "$suite/bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$JAVA_HOME" == assignment-java-home ]]
[[ ! -e target/surefire-reports ]]
[[ "$1" == -B && "$2" == -ntp && "$3" == -Dcheckstyle.skip=true ]]
if [[ "$FAKE_SUITE" == yes ]]; then
  [[ "$#" == 5 && "$4" == clean && "$5" == test ]]
else
  [[ "$#" == 6 && "$4" == '-Dtest=example.Test#testOne,example.Test#testTwo' && "$5" == clean && "$6" == test ]]
fi
printf 'Maven invocation\n' >> "$FAKE_MAVEN_LOG"
# Both metadata and the rubric snapshot must exist before Maven starts.
for context in "$RUNNER_TEMP"/classroom-feedback.*/context.json; do
  [[ -s "$context" && -s "${context%/*}/rubric.json" ]]
done
if [[ "${FAKE_CHANGE_RUBRIC:-}" == yes ]]; then
  printf '{"tests":[]}\n' > "$INPUT_RUBRIC"
fi
case "$FAKE_TEST_STATE" in
  missing) exit 1 ;;
  pass) second='<testcase classname="example.Test" name="testTwo"/>' ;;
  partial) second='<testcase classname="example.Test" name="testTwo"><failure/></testcase>' ;;
  malformed) second='<broken>' ;;
  *) exit 99 ;;
esac
mkdir -p target/surefire-reports
printf '<testsuite><testcase classname="example.Test" name="testOne"/>%s</testsuite>\n' "$second" \
  > target/surefire-reports/TEST-example.Test.xml
exit "$FAKE_TEST_EXIT"
EOF
cat > "$suite/bin/java" <<'EOF'
#!/usr/bin/env bash
echo 'The assignment Java must not run the grader.' >&2
exit 99
EOF
cat > "$suite/grader-java" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$JAVA_HOME" == assignment-java-home ]]
if [[ -n "$ORIGINAL_JAVA_HOME" ]]; then
  export JAVA_HOME="$ORIGINAL_JAVA_HOME"
else
  unset JAVA_HOME
fi
exec "$REAL_JAVA" "$@"
EOF
chmod +x "$suite/bin/git" "$suite/bin/mvn" "$suite/bin/timeout" "$suite/bin/java" "$suite/grader-java"
export PATH="$suite/bin:$PATH" JAVA_HOME=assignment-java-home GRADER_JAVA="$suite/grader-java"
export GITHUB_REPOSITORY_ID=123 GITHUB_REPOSITORY=example/assignment
export GITHUB_RUN_ID=456 GITHUB_RUN_ATTEMPT=2

prepare_case() {
  export GITHUB_WORKSPACE="$suite/$1/workspace with spaces" RUNNER_TEMP="$suite/$1/runner temp"
  export GITHUB_OUTPUT="$suite/$1/outputs" GITHUB_STEP_SUMMARY="$suite/$1/summary"
  export FAKE_MAVEN_LOG="$suite/$1/maven.log" GITHUB_EVENT_NAME=push
  export GITHUB_SHA=0123456789abcdef0123456789abcdef01234567 GITHUB_REF=refs/heads/main
  export GITHUB_WORKFLOW_REF=example/assignment/.github/workflows/feedback.yml@refs/heads/main
  export GITHUB_WORKFLOW_SHA=89abcdef0123456789abcdef0123456789abcdef
  export INPUT_WORKING_DIRECTORY=module INPUT_RUBRIC='rubric config.json' INPUT_TIMEOUT_MINUTES=7
  export FAKE_TEST_STATE=pass FAKE_TEST_EXIT=0 FAKE_SUITE=no
  unset FAKE_GIT_SHA FAKE_TIMEOUT_EXIT FAKE_CHANGE_RUBRIC
  mkdir -p "$GITHUB_WORKSPACE/module/target/surefire-reports" "$RUNNER_TEMP"
  : > "$GITHUB_OUTPUT"
  : > "$GITHUB_STEP_SUMMARY"
  cat > "$GITHUB_WORKSPACE/module/$INPUT_RUBRIC" <<'EOF'
{"tests":[{"class_name":"example.Test","method":"testOne","points":2},{"class_name":"example.Test","method":"testTwo","points":3}]}
EOF
  # Deliberately stale partial feedback must not survive a build that cannot clean.
  cat > "$GITHUB_WORKSPACE/module/target/surefire-reports/TEST-stale.xml" <<'EOF'
<testsuite><testcase classname="example.Test" name="testOne"/><testcase classname="example.Test" name="testTwo"><failure/></testcase></testsuite>
EOF
  result_path= report_exit= script_exit=0
}

run_feedback() {
  bash "$project_root/scripts/autograding.sh" > "$suite/output.log" 2>&1 || script_exit=$?
  while IFS='=' read -r key value; do
    case "$key" in
      result-path) result_path=$value ;;
      exit-code) report_exit=$value ;;
    esac
  done < "$GITHUB_OUTPUT"
}

check_result() {
  if [[ "$script_exit" != 0 || "$report_exit" != "$3" ]]; then
    cat "$suite/output.log" >&2
    echo "Unexpected action outcome: script=$script_exit, report=$report_exit" >&2
    exit 1
  fi
  [[ "$result_path" == "$RUNNER_TEMP/"*/result.json && -s "$GITHUB_STEP_SUMMARY" ]]
  [[ "$(wc -l < "$FAKE_MAVEN_LOG")" -eq 1 ]]
  jq -e --arg status "$1" --argjson score "$2" --argjson exit_code "$4" \
    --arg commit "$GITHUB_SHA" --arg ref "$GITHUB_REF" \
    --arg workflow_ref "$GITHUB_WORKFLOW_REF" --arg workflow_sha "$GITHUB_WORKFLOW_SHA" \
    '.kind == "finmath-classroom-feedback" and .authoritative == false and
     .status == $status and .score == $score and .max_score == 5 and
     .execution.exit_code == $exit_code and
     .context.repository_id == 123 and .context.repository == "example/assignment" and
     .context.commit == $commit and .context.ref == $ref and
     .context.run_id == 456 and .context.run_attempt == 2 and
     .context.workflow_ref == $workflow_ref and .context.workflow_sha == $workflow_sha' "$result_path" >/dev/null
}

prepare_case passed
export FAKE_CHANGE_RUBRIC=yes
run_feedback
check_result passed 5 0 0
jq -e '.context.event == "push"' "$result_path" >/dev/null

prepare_case partial
export FAKE_TEST_STATE=partial FAKE_TEST_EXIT=1 GITHUB_EVENT_NAME=workflow_dispatch
run_feedback
check_result failed 2 1 1
jq -e '.context.event == "workflow_dispatch"' "$result_path" >/dev/null

prepare_case pull_request
export GITHUB_EVENT_NAME=pull_request GITHUB_REF=refs/pull/42/merge
export GITHUB_SHA=abcdef0123456789abcdef0123456789abcdef01
export GITHUB_WORKFLOW_REF=example/assignment/.github/workflows/feedback.yml@refs/pull/42/merge
export GITHUB_WORKFLOW_SHA="$GITHUB_SHA"
run_feedback
check_result passed 5 0 0
jq -e '.context.event == "pull_request"' "$result_path" >/dev/null

prepare_case missing
export FAKE_TEST_STATE=missing
run_feedback
check_result incomplete null 1 1

prepare_case timeout
export FAKE_TIMEOUT_EXIT=124
run_feedback
check_result timed_out null 2 124

prepare_case killed
export FAKE_TEST_STATE=partial FAKE_TEST_EXIT=1 FAKE_TIMEOUT_EXIT=137
run_feedback
check_result error null 2 137

prepare_case malformed
export FAKE_TEST_STATE=malformed FAKE_TEST_EXIT=1
run_feedback
check_result error null 2 1

prepare_case failed_command
export FAKE_TEST_EXIT=1
run_feedback
check_result error null 2 1

for suite_case in passed failed missing; do
  prepare_case "suite_$suite_case"
  export FAKE_SUITE=yes
  printf '{"suite":{"points":5}}\n' > "$GITHUB_WORKSPACE/module/$INPUT_RUBRIC"
  case "$suite_case" in
    passed) export FAKE_CHANGE_RUBRIC=yes ;;
    failed) export FAKE_TEST_STATE=partial FAKE_TEST_EXIT=1 ;;
    missing) export FAKE_TEST_STATE=missing ;;
  esac
  run_feedback
  case "$suite_case" in
    passed) check_result passed 5 0 0 ;;
    failed) check_result failed 0 1 1 ;;
    missing) check_result incomplete null 1 1 ;;
  esac
done

for invalid in rubric commit event timeout path target_symlink; do
  prepare_case "invalid_$invalid"
  case "$invalid" in
    rubric) printf '{"tests":[]}\n' > "$GITHUB_WORKSPACE/module/$INPUT_RUBRIC" ;;
    commit) export FAKE_GIT_SHA=89abcdef0123456789abcdef0123456789abcdef ;;
    event) export GITHUB_EVENT_NAME=pull_request_target ;;
    timeout) export INPUT_TIMEOUT_MINUTES='1; exit 0' ;;
    path) export INPUT_WORKING_DIRECTORY=.. ;;
    target_symlink)
      mv "$GITHUB_WORKSPACE/module/target" "$suite/preserved-target"
      ln -s "$suite/preserved-target" "$GITHUB_WORKSPACE/module/target"
      ;;
  esac
  run_feedback
  [[ "$script_exit" -ne 0 && ! -s "$GITHUB_OUTPUT" && ! -e "$FAKE_MAVEN_LOG" ]]
done
[[ -s "$suite/preserved-target/surefire-reports/TEST-stale.xml" ]]
printf 'Autograding integration checks passed (17 scenarios, no student code executed).\n'
