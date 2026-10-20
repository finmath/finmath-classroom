#!/usr/bin/env bash
# Offline regression checks for the public demo exception and private-data guard.
set -euo pipefail
site_test_project=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
python3 - "$site_test_project" <<'PY'
import html
from pathlib import Path
import subprocess
import sys
import tempfile

project = Path(sys.argv[1])
public_url = (
    "https://classroom.finmath.net/accept/numerical-methods-demo/quadratic-equation-demo"
    "?lectureKey=public-numerical-methods-demo&assignmentKey=public-quadratic-equation-demo"
)
private_marker = "TEST_PRIVATE_SENTINEL"


def anchor(url, quote='"'):
    return f"<a href={quote}{html.escape(url, quote=False)}{quote}>Accept demo</a>"


valid = anchor(public_url)
cases = [
    ("public anchor", valid, {}, True),
    ("single quoted anchor", anchor(public_url, "'"), {}, True),
    ("literal ampersand", valid.replace("&amp;", "&"), {}, True),
    ("missing anchor", "<p>Demo</p>", {}, False),
    ("duplicate anchor", valid + valid, {}, False),
    ("wrong page", "<p>Demo</p>", {"index.html": valid}, False),
    ("public anchor elsewhere too", valid, {"index.html": valid}, False),
    ("free text URL", valid + "<p>" + html.escape(public_url) + "</p>", {}, False),
    ("unquoted href", f"<a href={html.escape(public_url)}>Accept demo</a>", {}, False),
    ("stylesheet href", valid + valid.replace("<a ", "<link "), {}, False),
    ("image source", valid + valid.replace("<a href=", "<img src="), {}, False),
    ("form action", valid + valid.replace("<a href=", "<form action="), {}, False),
    ("private environment file", valid, {".env": private_marker}, False),
    ("private database", valid, {"classroom.db": private_marker}, False),
    ("private query in text", valid + "<p>lectureKey=" + private_marker + "</p>", {}, False),
    ("private local path", valid + "<p>/Users/" + private_marker + "</p>", {}, False),
    ("file URL", valid + '<a href="file:///tmp/' + private_marker + '">File</a>', {}, False),
]

mutations = {
    "private lecture key": public_url.replace("public-numerical-methods-demo", private_marker),
    "private assignment key": public_url.replace("public-quadratic-equation-demo", private_marker),
    "other lecture": public_url.replace("/numerical-methods-demo/", "/numerical-methods/"),
    "other assignment": public_url.replace("/quadratic-equation-demo?", "/other-exercise?"),
    "other origin": public_url.replace("classroom.finmath.net", "example.invalid"),
    "HTTP origin": public_url.replace("https:", "http:"),
    "extra parameter": public_url + "&extra=value",
    "duplicate parameter": public_url + "&lectureKey=" + private_marker,
    "missing parameter": public_url.split("&", 1)[0],
    "fragment": public_url + "#fragment",
    "public key suffix": public_url + private_marker,
    "encoded private key": public_url.replace("lectureKey=public-numerical-methods-demo", "%6cectureKey=" + private_marker),
}
for name, url in mutations.items():
    cases.append((name, anchor(url), {}, False))
cases.append(("private link beside public link", valid + anchor(mutations["private lecture key"]), {}, False))
cases.append(("private link in another file", valid, {"other.html": anchor(mutations["private assignment key"])}, False))
cases.append(("private CSS link", valid, {"extra.css": 'body { background: url("' + mutations["private lecture key"] + '"); }'}, False))

with tempfile.TemporaryDirectory(prefix="classroom-site-tests-") as temporary:
    for number, (name, demo, extra, expected) in enumerate(cases):
        site = Path(temporary) / str(number)
        site.mkdir()
        files = {"index.html": '<a href="demo.html">Demo</a>', "motivation.html": "<p>Motivation</p>", "demo.html": demo}
        files.update(extra)
        for filename, content in files.items():
            (site / filename).write_text(content, encoding="utf-8")
        result = subprocess.run(["bash", str(project / "scripts/check-site.sh"), str(site)], capture_output=True, text=True)
        output = result.stdout + result.stderr
        assert (result.returncode == 0) == expected, f"Unexpected result for {name}"
        assert private_marker not in output and public_url not in output, f"Artifact content leaked for {name}"
        if not expected:
            assert result.returncode == 1 and "Site check failed:" in result.stderr, f"Missing sanitized failure for {name}"

print(f"Site checker regression checks passed ({len(cases)} offline cases).")
PY
