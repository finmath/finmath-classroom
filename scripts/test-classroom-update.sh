#!/usr/bin/env bash
# Offline release/update scenarios. Never invokes real curl, systemd, Java or sudo.
set -euo pipefail
update_test_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
update_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/classroom-update-test.XXXXXXXX")
trap 'rm -rf -- "$update_test_dir"' EXIT
update_test_tag=server-0123456789abcdef0123456789abcdef01234567
export update_test_root update_test_tag

cat >"$update_test_dir/runner.sh" <<'RUNNER'
set -euo pipefail
source "$update_test_root/deploy/classroom-update"
classroom_update_download() {
    printf 'download %s\n' "$1" >>"$test_case/events"
    case "$1" in
        https://api.github.com/repos/finmath/finmath-classroom/releases/latest|\
        "https://api.github.com/repos/finmath/finmath-classroom/releases/tags/$update_test_tag")
            command cp "$test_case/release.json" "$2" ;;
        "https://github.com/finmath/finmath-classroom/releases/download/$update_test_tag/finmath-classroom.jar")
            command cp "$test_case/new.jar" "$2" ;;
        "https://github.com/finmath/finmath-classroom/releases/download/$update_test_tag/finmath-classroom.jar.sha256")
            command cp "$test_case/checksum" "$2" ;;
        *) return 90 ;;
    esac
}
classroom_update_systemctl() {
    local action=$1 unit state_file
    shift
    for unit in "$@"; do :; done
    case "$unit" in
        finmath-classroom.service) state_file="$test_case/web" ;;
        finmath-classroom-feedback.timer) state_file="$test_case/timer" ;;
        finmath-classroom-feedback.service) state_file="$test_case/collector" ;;
        *) return 91 ;;
    esac
    case "$action" in
        show)
            if [[ "$(cat "$state_file")" == not-found ]]; then printf '%s\n' not-found; return 1; fi
            printf '%s\n' loaded ;;
        is-active) [[ "$(cat "$state_file")" == active ]] ;;
        stop|start)
            printf '%s %s\n' "$action" "$unit" >>"$test_case/events"
            [[ "$(cat "$state_file")" != not-found ]] || return 92
            if [[ "$scenario" == stop_failure && "$action" == stop && "$unit" == finmath-classroom-feedback.service ]]; then return 1; fi
            if [[ "$action" == stop ]]; then printf '%s\n' inactive >"$state_file"; else printf '%s\n' active >"$state_file"; fi ;;
        *) return 93 ;;
    esac
}
classroom_update_install() {
    [[ "$2" == "$test_case/app/"* ]] || return 94
    command cp "$1" "$2"
    chmod 644 "$2"
}
classroom_update_private_directory() { command install -d -m 700 -- "$1"; }
classroom_update_smoke() {
    printf '%s\n' smoke >>"$test_case/events"
    cmp "$1" "$test_case/new.jar"
    [[ "$scenario" != smoke_failure ]]
}
classroom_update_ready() {
    printf '%s\n' ready >>"$test_case/events"
    if [[ "$scenario" == startup_failure ]] && cmp -s "$test_case/app/finmath-classroom.jar" "$test_case/new.jar"; then return 1; fi
}
cp() {
    if [[ "$scenario" == backup_failure && "$1" == -a ]]; then return 1; fi
    command cp "$@"
}
mv() {
    if [[ "$scenario" == replace_failure && ! -f "$test_case/replace-failed" ]]; then
        touch "$test_case/replace-failed"
        return 1
    fi
    command mv "$@"
}
classroom_update_run "$requested" "$test_case/app" "$test_case/data" "$test_case/backups"
RUNNER

prepare_case() {
    scenario=$1
    test_case="$update_test_dir/$scenario"
    requested=
    export scenario test_case requested
    mkdir -p "$test_case/app" "$test_case/data/lectures"
    chmod 755 "$test_case/app"
    printf '%s\n' 'previous JAR' >"$test_case/old.jar"
    cp "$test_case/old.jar" "$test_case/app/finmath-classroom.jar"
    printf '%s\n' 'new release payload' >"$test_case/new.jar"
    printf '%s\n' registration >"$test_case/data/classroom.db"
    printf '%s\n' sidecar >"$test_case/data/classroom.db-wal"
    printf '%s\n' feedback >"$test_case/data/feedback.db"
    printf '%s\n' journal >"$test_case/data/feedback.db-journal"
    printf '%s\n' configuration >"$test_case/data/lectures/fixture.json"
    printf '%s\n' active >"$test_case/web"
    printf '%s\n' active >"$test_case/timer"
    printf '%s\n' inactive >"$test_case/collector"
    : >"$test_case/events"
    local digest
    digest=$(sha256sum "$test_case/new.jar")
    printf '%s  finmath-classroom.jar\n' "${digest%% *}" >"$test_case/checksum"
    jq -n --arg tag "$update_test_tag" '{tag_name: $tag, draft: false, prerelease: false,
        assets: ["finmath-classroom.jar", "finmath-classroom.jar.sha256"] | map({name: ., state: "uploaded",
          browser_download_url: ("https://github.com/finmath/finmath-classroom/releases/download/" + $tag + "/" + .)})}' \
        >"$test_case/release.json"
}

run_case() {
    local expected=$1 status=0
    bash "$update_test_dir/runner.sh" >"$test_case/output" 2>&1 || status=$?
    if [[ "$expected" == success && "$status" != 0 || "$expected" == failure && "$status" == 0 ]]; then
        cat "$test_case/output" >&2
        printf 'Unexpected status %s in %s\n' "$status" "$scenario" >&2
        exit 1
    fi
    [[ "$(cat "$test_case/data/classroom.db")" == registration ]]
    [[ "$(cat "$test_case/data/classroom.db-wal")" == sidecar ]]
    [[ "$(cat "$test_case/data/feedback.db")" == feedback ]]
    [[ "$(cat "$test_case/data/feedback.db-journal")" == journal ]]
    [[ "$(cat "$test_case/data/lectures/fixture.json")" == configuration ]]
    shopt -s nullglob
    local temporary=("$test_case/app/".classroom-update.* "$test_case/app/".finmath-classroom.*)
    [[ "${#temporary[@]}" == 0 ]]
    printf 'Passed: %s\n' "$scenario"
}

assert_no_stop() {
    ! grep -q '^stop ' "$test_case/events"
    cmp "$test_case/old.jar" "$test_case/app/finmath-classroom.jar"
    [[ "$(cat "$test_case/web")" == active && "$(cat "$test_case/timer")" == active ]]
}

prepare_case success
run_case success
cmp "$test_case/new.jar" "$test_case/app/finmath-classroom.jar"
[[ "$(cat "$test_case/web")" == active && "$(cat "$test_case/timer")" == active ]]
[[ "$(grep -c '^download https://api.github.com/' "$test_case/events")" == 1 ]]
[[ "$(grep -c '^download https://github.com/' "$test_case/events")" == 2 ]]
[[ "$(sed -n '1,4p' "$test_case/events" | tail -1)" == smoke ]]
[[ "$(sed -n '5,7p' "$test_case/events")" == $'stop finmath-classroom-feedback.timer\nstop finmath-classroom-feedback.service\nstop finmath-classroom.service' ]]
backups=("$test_case/backups/"*)
[[ "${#backups[@]}" == 1 ]]
cmp "$test_case/old.jar" "${backups[0]}/finmath-classroom.jar"
diff -r "$test_case/data" "${backups[0]}/data"

prepare_case explicit_release
requested=$update_test_tag
run_case success
grep -q "/releases/tags/$update_test_tag" "$test_case/events"
! grep -q '/releases/latest' "$test_case/events"

prepare_case absent_optional_units
printf '%s\n' not-found >"$test_case/timer"
printf '%s\n' not-found >"$test_case/collector"
run_case success
! grep -q '^\(start\|stop\) finmath-classroom-feedback' "$test_case/events"

prepare_case inactive_timer
printf '%s\n' inactive >"$test_case/timer"
run_case success
[[ "$(cat "$test_case/timer")" == inactive ]]
! grep -q '^start finmath-classroom-feedback.timer' "$test_case/events"

prepare_case checksum_failure
printf '%064d  finmath-classroom.jar\n' 0 >"$test_case/checksum"
run_case failure
assert_no_stop

prepare_case checksum_path
digest=$(sha256sum "$test_case/new.jar")
printf '%s  ../finmath-classroom.jar\n' "${digest%% *}" >"$test_case/checksum"
run_case failure
assert_no_stop

prepare_case mismatched_asset
jq '.assets[0].browser_download_url = "https://example.org/other.jar"' "$test_case/release.json" >"$test_case/changed.json"
mv "$test_case/changed.json" "$test_case/release.json"
run_case failure
assert_no_stop

prepare_case invalid_tag
jq '.tag_name = "server-$(touch should-not-run)"' "$test_case/release.json" >"$test_case/changed.json"
mv "$test_case/changed.json" "$test_case/release.json"
run_case failure
assert_no_stop

prepare_case duplicate_asset
jq '.assets += [.assets[0]]' "$test_case/release.json" >"$test_case/changed.json"
mv "$test_case/changed.json" "$test_case/release.json"
run_case failure
assert_no_stop

prepare_case prerelease
jq '.prerelease = true' "$test_case/release.json" >"$test_case/changed.json"
mv "$test_case/changed.json" "$test_case/release.json"
run_case failure
assert_no_stop

prepare_case smoke_failure
run_case failure
assert_no_stop

for scenario in startup_failure backup_failure stop_failure replace_failure; do
    prepare_case "$scenario"
    run_case failure
    cmp "$test_case/old.jar" "$test_case/app/finmath-classroom.jar"
    [[ "$(cat "$test_case/web")" == active && "$(cat "$test_case/timer")" == active ]]
    grep -q 'Previous application is ready' "$test_case/output"
done

bash "$update_test_root/deploy/classroom-update" --help >/dev/null
if (( EUID != 0 )); then
    status=0
    bash "$update_test_root/deploy/classroom-update" >"$update_test_dir/unprivileged" 2>&1 || status=$?
    [[ "$status" != 0 ]]
    grep -q 'Run with sudo' "$update_test_dir/unprivileged"
fi
printf '%s\n' 'Updater checks passed without network, sudo, systemd or Java operations.'
