#!/usr/bin/env bash
# Offline argument-boundary checks; never calls sudo, systemd, Java or GitHub.
set -euo pipefail
wrapper_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
source "$wrapper_root/deploy/classroom"

has_argument() {
    local argument
    for argument in "${classroom_command[@]}"; do
        [[ "$argument" != "$1" ]] || return 0
    done
    return 1
}

for command in check assignments students list delete; do
    classroom_build_command registration "$command"
    ! has_argument --property=EnvironmentFile=/etc/finmath-classroom/classroom.env
done
for command in list show; do
    classroom_build_command feedback "$command"
    ! has_argument --property=EnvironmentFile=/etc/finmath-classroom/classroom.env
done
for command in import reconcile; do
    classroom_build_command registration "$command"
    has_argument --property=EnvironmentFile=/etc/finmath-classroom/classroom.env
done
classroom_build_command feedback sync
has_argument --property=EnvironmentFile=/etc/finmath-classroom/classroom.env
has_argument --wait
has_argument --pipe
has_argument --expand-environment=no
has_argument --property=User=finmath-classroom
has_argument --property=Group=finmath-classroom
has_argument --property=UMask=0077
[[ "${classroom_command[0]}" == /usr/bin/systemd-run ]]

literal='spaces ; $(must_not_run) ${CLASSROOM_GITHUB_TOKEN} *'
classroom_build_command registration list --lecture "$literal" --format table
has_argument "$literal"
expected_tail=(registration list --lecture "$literal" --format table --data /var/lib/finmath-classroom)
tail_start=$((${#classroom_command[@]} - ${#expected_tail[@]}))
for ((i=0; i<${#expected_tail[@]}; i++)); do
    [[ "${classroom_command[tail_start+i]}" == "${expected_tail[i]}" ]]
done
if classroom_build_command registration list --data /tmp/other 2>/dev/null; then exit 1; fi
if classroom_build_command feedback sync --data=/tmp/other 2>/dev/null; then exit 1; fi
if classroom_build_command serve --help 2>/dev/null; then exit 1; fi
if classroom_build_command feedback report 2>/dev/null; then exit 1; fi
if classroom_build_command registration 2>/dev/null; then exit 1; fi
[[ "${#classroom_command[@]}" == 0 ]]

bash "$wrapper_root/deploy/classroom" --help >/dev/null
if (( EUID != 0 )); then
    result=0
    bash "$wrapper_root/deploy/classroom" feedback list >/dev/null 2>&1 || result=$?
    [[ "$result" == 1 ]]
fi
printf '%s\n' 'Classroom wrapper checks passed (credential routing, literal arguments, fixed paths, command rejection; no server operations).'
