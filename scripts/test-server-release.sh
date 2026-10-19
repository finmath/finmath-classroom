#!/usr/bin/env bash
# Exercise the workflow's actual publication commands with a stateful fake gh.
# All writes stay in a temporary directory; no GitHub or network calls are made.
set -euo pipefail
release_test_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
python3 - "$release_test_root" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap

root = Path(sys.argv[1])
workflow = (root / '.github/workflows/release.yml').read_text()
marker = '      - name: Publish the verified assets together\n'
assert workflow.count(marker) == 1, 'Publication step must have one unique marker'
step = workflow.split(marker, 1)[1]
assert '\n      - ' not in step, 'Update test extraction if publication gains another step'
script_lines = step.split('        run: |\n', 1)[1].splitlines()
assert all(not line or line.startswith('          ') for line in script_lines)
publication = '\n'.join(line[10:] for line in script_lines) + '\n'
subprocess.run(['bash', '-n'], input=publication, text=True, check=True)

commit = '0123456789abcdef0123456789abcdef01234567'
other = 'b' * 40
repository = 'finmath/finmath-classroom'
tag = 'server-' + commit
jar = 'finmath-classroom.jar'
checksum = jar + '.sha256'
payload = 'verified server payload\n'
digest = hashlib.sha256(payload.encode()).hexdigest()
expected_assets = {jar: payload, checksum: digest + '  ' + jar + '\n'}

fake_gh = textwrap.dedent('''\
    #!/usr/bin/env python3
    import json
    import os
    from pathlib import Path
    import sys

    state_path = Path(os.environ['RELEASE_TEST_STATE'])
    state = json.loads(state_path.read_text())
    args = sys.argv[1:]
    state['calls'].append(args)
    commit = os.environ['RELEASE_COMMIT']
    tag = 'server-' + commit
    repository = os.environ['GH_REPO']
    status = 0

    def require(condition):
        if not condition:
            raise RuntimeError('Unexpected publication command: ' + repr(args))

    try:
        if args[:2] == ['release', 'view']:
            require(args[2:] == [tag, '--json', 'isDraft'])
            if state['release'] == 'absent' or state.get('fail_release_view'):
                status = 1
            else:
                print(json.dumps({'isDraft': state['release'] == 'draft'}))
        elif args[:1] == ['api']:
            if args[1] == 'repos/' + repository + '/git/matching-refs/tags/' + tag:
                require(len(args) == 2)
                if state.get('fail_matching_refs'):
                    status = 1
                elif state.get('invalid_matching_refs'):
                    print('{}')
                else:
                    refs = list(state.get('extra_refs', []))
                    if state['tag_commit'] is not None:
                        refs.append({'ref': 'refs/tags/' + tag, 'object': {'sha': state['tag_commit']}})
                    print(json.dumps(refs))
            elif args[1] == 'repos/' + repository + '/commits/' + tag:
                require(args[2:] == ['--jq', '.sha'])
                if state['tag_commit'] is None or state.get('fail_commit_lookup'):
                    status = 1
                else:
                    print(state['tag_commit'])
            elif args[1] == 'repos/' + repository + '/git/ref/heads/main':
                require(args[2:] == ['--jq', '.object.sha'])
                if state.get('fail_main_lookup'):
                    status = 1
                else:
                    print(state['main_commit'])
            else:
                require(False)
        elif args[:2] == ['release', 'create']:
            require(state['release'] == 'absent' and args[2] == tag)
            require('--draft' in args and '--latest=false' in args)
            require(args[args.index('--target') + 1] == commit)
            require(Path(args[args.index('--notes-file') + 1]).is_file())
            state['release'] = 'draft'
            state['target'] = commit
        elif args[:2] == ['release', 'upload']:
            require(state['release'] == 'draft')
            require(args[2:] == [tag, '--clobber', 'finmath-classroom.jar', 'finmath-classroom.jar.sha256'])
            state['assets']['finmath-classroom.jar'] = Path('finmath-classroom.jar').read_text()
            if state.get('fail_upload'):
                status = 1
            else:
                state['assets']['finmath-classroom.jar.sha256'] = Path('finmath-classroom.jar.sha256').read_text()
        elif args[:2] == ['release', 'edit']:
            require(state['release'] == 'draft' and args[2] == tag)
            require('--draft=false' in args)
            require(args[args.index('--target') + 1] == commit)
            require(set(state['assets']) == {'finmath-classroom.jar', 'finmath-classroom.jar.sha256'})
            state['target'] = commit
            state['tag_commit'] = state['tag_commit'] or state['target']
            state['release'] = 'published'
            latest = [arg for arg in args if arg.startswith('--latest=')]
            require(len(latest) == 1 and latest[0] in ['--latest=true', '--latest=false'])
            state['latest'] = latest[0] == '--latest=true'
        else:
            require(False)
    except Exception as error:
        print(error, file=sys.stderr)
        status = 99
    state_path.write_text(json.dumps(state))
    sys.exit(status)
''')

with tempfile.TemporaryDirectory(prefix='classroom-release-test-') as temporary:
    workspace = Path(temporary)
    binary = workspace / 'bin'
    binary.mkdir()
    (binary / 'gh').write_text(fake_gh)
    (binary / 'gh').chmod(0o755)

    def prepare(name, **changes):
        case = workspace / name
        (case / 'release').mkdir(parents=True)
        for asset, contents in expected_assets.items():
            (case / 'release' / asset).write_text(contents)
        state = dict(release='absent', target=None, tag_commit=None, main_commit=commit,
                     assets={}, latest=False, calls=[])
        state.update(changes)
        (case / 'state.json').write_text(json.dumps(state))
        return case

    def execute(case, success=True):
        environment = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                           GH_TOKEN='offline-placeholder', GH_REPO=repository, RELEASE_COMMIT=commit,
                           GITHUB_STEP_SUMMARY=str(case / 'summary'), RELEASE_TEST_STATE=str(case / 'state.json'))
        process = subprocess.run(['bash', '-euo', 'pipefail'], input=publication, text=True,
                                 cwd=case, env=environment, capture_output=True)
        assert (process.returncode == 0) == success, (
            f'{case.name}: unexpected exit {process.returncode}\n{process.stdout}\n{process.stderr}')
        state = json.loads((case / 'state.json').read_text())
        if success and state['release'] == 'published' and any(call[:2] == ['release', 'edit'] for call in state['calls']):
            assert state['assets'] == expected_assets, 'Published asset pair must match the verified build'
            assert state['tag_commit'] == commit, 'Published tag must point at the verified commit'
        print('Passed: ' + case.name)
        return state

    def called(state, command):
        return [call for call in state['calls'] if call[:2] == ['release', command]]

    state = execute(prepare('fresh_release'))
    assert state['release'] == 'published' and state['latest']
    assert len(called(state, 'create')) == len(called(state, 'upload')) == len(called(state, 'edit')) == 1
    actions = [call[1] for call in state['calls'] if call[0] == 'release']
    assert actions == ['view', 'create', 'upload', 'edit']

    state = execute(prepare('retry_existing_draft', release='draft', target=commit,
                            assets={jar: 'partial old upload'}))
    assert not called(state, 'create') and state['latest']

    state = execute(prepare('correct_unpublished_draft_target', release='draft', target=other))
    assert state['target'] == commit and not called(state, 'create')

    original = {jar: 'original published JAR', checksum: 'original published checksum'}
    state = execute(prepare('preserve_published_release', release='published', target=commit,
                            tag_commit=commit, assets=original, latest=True))
    assert state['assets'] == original and len(state['calls']) == 1

    case = prepare('upload_failure_stays_draft', fail_upload=True)
    state = execute(case, success=False)
    assert state['release'] == 'draft' and not called(state, 'edit')
    assert set(state['assets']) == {jar}
    state['fail_upload'] = False
    (case / 'state.json').write_text(json.dumps(state))
    state = execute(case)
    assert len(called(state, 'create')) == 1 and len(called(state, 'upload')) == 2

    state = execute(prepare('advanced_main_does_not_change_latest', main_commit=other))
    assert state['release'] == 'published' and not state['latest']

    state = execute(prepare('reject_mismatched_existing_tag', tag_commit=other), success=False)
    assert state['release'] == 'absent' and not called(state, 'upload') and not called(state, 'edit')

    state = execute(prepare('matching_existing_tag', tag_commit=commit))
    assert state['release'] == 'published'

    state = execute(prepare('ignore_similarly_prefixed_tag',
                            extra_refs=[{'ref': 'refs/tags/' + tag + '-other', 'object': {'sha': other}}]))
    assert state['release'] == 'published'
    assert not any(call[:2] == ['api', 'repos/' + repository + '/commits/' + tag] for call in state['calls'])

    for scenario in ['fail_matching_refs', 'invalid_matching_refs', 'fail_commit_lookup']:
        state = execute(prepare(scenario, tag_commit=commit, **{scenario: True}), success=False)
        assert state['release'] == 'absent'
        assert not called(state, 'create') and not called(state, 'upload') and not called(state, 'edit')

    original = {jar: 'existing draft JAR'}
    state = execute(prepare('release_view_failure_preserves_existing_draft', release='draft',
                            target=commit, assets=original, fail_release_view=True), success=False)
    assert state['release'] == 'draft' and state['assets'] == original
    assert not called(state, 'upload') and not called(state, 'edit')

    case = prepare('reject_corrupt_jar')
    (case / 'release' / jar).write_text('different bytes')
    state = execute(case, success=False)
    assert not state['calls']

    state = execute(prepare('main_lookup_failure_stays_draft', fail_main_lookup=True), success=False)
    assert state['release'] == 'draft' and not called(state, 'edit')

print('Publication checks passed using fake gh; no network or GitHub writes.')
PY
