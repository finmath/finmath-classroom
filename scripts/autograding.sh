#!/usr/bin/env bash
# Student feedback only: the assignment controls its build, tests and reports.
set -euo pipefail

: "${JAR_PATH:?Missing grader JAR}" "${GRADER_JAVA:?Missing grader Java}"
: "${GITHUB_WORKSPACE:?Missing workspace}" "${RUNNER_TEMP:?Missing runner temp directory}"
: "${GITHUB_OUTPUT:?Missing step output file}" "${GITHUB_STEP_SUMMARY:?Missing step summary file}"

case "${GITHUB_EVENT_NAME:-}" in
  push|pull_request|workflow_dispatch) ;;
  *) echo 'Feedback supports push, pull_request and workflow_dispatch events only.' >&2; exit 2 ;;
esac
minutes=${INPUT_TIMEOUT_MINUTES:-10}
if [[ ! "$minutes" =~ ^[1-9][0-9]{0,2}$ ]] || (( minutes > 360 )); then
  echo 'timeout-minutes must be an integer from 1 to 360.' >&2
  exit 2
fi
for command in git jq mvn timeout; do
  command -v "$command" >/dev/null
done

workspace=$(cd -- "$GITHUB_WORKSPACE" && pwd -P)
project=$(cd -- "$workspace/${INPUT_WORKING_DIRECTORY:-.}" && pwd -P)
case "$project/" in
  "$workspace/"*) ;;
  *) echo 'working-directory must remain inside the checked-out repository.' >&2; exit 2 ;;
esac
runner_temp=$(cd -- "$RUNNER_TEMP" && pwd -P)
case "$runner_temp/" in
  "$workspace/"*) echo 'RUNNER_TEMP must be outside the checked-out repository.' >&2; exit 2 ;;
esac
cd -- "$project"
if [[ "$(git rev-parse HEAD)" != "${GITHUB_SHA:?Missing commit SHA}" ]]; then
  echo 'The checked-out commit does not match GITHUB_SHA.' >&2
  exit 2
fi

# Snapshot the rubric and run identity before executing the assignment.
results=$(mktemp -d "$runner_temp/classroom-feedback.XXXXXX")
cp -- "${INPUT_RUBRIC:-.github/classroom/autograding.json}" "$results/rubric.json"
selection=$("$GRADER_JAVA" -jar "$JAR_PATH" feedback select --rubric "$results/rubric.json")
jq -n \
  --argjson repository_id "${GITHUB_REPOSITORY_ID:?Missing repository ID}" \
  --arg repository "${GITHUB_REPOSITORY:?Missing repository name}" \
  --arg commit "$GITHUB_SHA" --arg ref "${GITHUB_REF:?Missing ref}" \
  --argjson run_id "${GITHUB_RUN_ID:?Missing run ID}" \
  --argjson run_attempt "${GITHUB_RUN_ATTEMPT:?Missing run attempt}" \
  --arg event "$GITHUB_EVENT_NAME" \
  --arg workflow_ref "${GITHUB_WORKFLOW_REF:?Missing workflow ref}" \
  --arg workflow_sha "${GITHUB_WORKFLOW_SHA:?Missing workflow SHA}" \
  '{repository_id: $repository_id, repository: $repository, commit: $commit, ref: $ref,
    run_id: $run_id, run_attempt: $run_attempt, event: $event,
    workflow_ref: $workflow_ref, workflow_sha: $workflow_sha}' > "$results/context.json"

# Maven can fail before its clean phase; never score reports left in the checkout.
if [[ -L target ]]; then
  echo 'The generated target directory must not be a symbolic link.' >&2
  exit 2
fi
rm -rf -- target/surefire-reports
maven_args=(-B -ntp -Dcheckstyle.skip=true)
if [[ -n "$selection" ]]; then
  maven_args+=("-Dtest=$selection")
fi
test_exit=0
timeout --signal=TERM --kill-after=30s "${minutes}m" \
  mvn "${maven_args[@]}" clean test || test_exit=$?

report_exit=0
"$GRADER_JAVA" -jar "$JAR_PATH" feedback report \
  --rubric "$results/rubric.json" --reports "$project/target/surefire-reports" \
  --context "$results/context.json" --test-exit-code "$test_exit" \
  --output "$results/result.json" --summary "$results/summary.md" || report_exit=$?
case "$report_exit" in
  0|1|2) ;;
  *) echo 'The feedback tool did not complete.' >&2; exit 2 ;;
esac
if [[ ! -s "$results/result.json" || ! -s "$results/summary.md" ]]; then
  echo 'The feedback tool did not produce a result and summary.' >&2
  exit 2
fi
cat "$results/summary.md" >> "$GITHUB_STEP_SUMMARY"
printf 'result-path=%s\nexit-code=%s\n' "$results/result.json" "$report_exit" >> "$GITHUB_OUTPUT"
# The composite action uploads the result before applying this outcome to the job.
