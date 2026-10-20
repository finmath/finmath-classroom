#!/usr/bin/env bash
# Validate the generated public Maven site locally; never follows external links.
set -euo pipefail
if (( $# > 1 )); then
    printf '%s\n' 'Usage: bash scripts/check-site.sh [site-directory]' >&2
    exit 2
fi
site_check_project=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
python3 - "$site_check_project" "${1:-$site_check_project/target/site}" <<'PY'
import html
import os
from pathlib import Path
import re
import sys
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

project, requested = map(Path, sys.argv[1:])
if requested.is_symlink() or not requested.is_dir():
    sys.exit("Site check failed: artifact directory is missing or a symlink.")
root = requested.resolve()
try:
    pom = ET.parse(project / "pom.xml")
    site_url = urlsplit(pom.findtext("{http://maven.apache.org/POM/4.0.0}url", ""))
except (OSError, ET.ParseError, ValueError):
    sys.exit("Site check failed: cannot read the public site URL from pom.xml.")
prefix = unquote(site_url.path).rstrip("/")
errors = set()
references = []
anchors = {}
required = ("index.html", "motivation.html", "demo.html")
markup_suffixes = {".html", ".htm", ".xhtml", ".svg"}
private_key = re.compile(r"(?:lecturekey|assignmentkey)", re.IGNORECASE)
# These two keys deliberately enroll anyone in the isolated public demo only.
# Pin the entire URL; teaching links must never inherit this exception.
public_demo_page = Path("demo.html")
public_demo_url = (
    "https://classroom.finmath.net/accept/numerical-methods-demo/quadratic-equation-demo"
    "?lectureKey=public-numerical-methods-demo&assignmentKey=public-quadratic-equation-demo"
)
public_demo_href = re.compile(r'''(?<!\S)href=(["'])''' + re.escape(public_demo_url) + r"\1")
public_demo_links = 0
css_url = re.compile(r"url\(\s*(['\"]?)(.*?)\1\s*\)", re.IGNORECASE | re.DOTALL)
css_import = re.compile(r"@import\s+(['\"])(.*?)\1", re.IGNORECASE | re.DOTALL)


def fail(path, reason):
    # Only artifact filenames and fixed reasons are reported, never URLs or file contents.
    errors.add((path.as_posix(), reason))


def decoded(value):
    for _ in range(3):
        updated = unquote(html.unescape(value))
        if updated == value:
            break
        value = updated
    return value


def private_content(path, text):
    value = decoded(text)
    if re.search(r"/users/", value, re.IGNORECASE):
        fail(path, "contains a local user path")
    if re.search(r"\bfile\s*://", value, re.IGNORECASE):
        fail(path, "contains a file URL")
    if path == public_demo_page:
        # Exempt only a complete, quoted href, never free text or URL prefixes.
        value = public_demo_href.sub("", value)
    if re.search(r"(?:lecturekey|assignmentkey)\s*=", value, re.IGNORECASE):
        fail(path, "contains a registration query key")


def reference(path, value, *, public_demo_anchor=False):
    global public_demo_links
    if private_key.search(decoded(value)):
        if public_demo_anchor and path == public_demo_page and decoded(value) == public_demo_url:
            public_demo_links += 1
        else:
            fail(path, "link contains a registration key")
    references.append((path, value))


def css_references(path, text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    for pattern in (css_url, css_import):
        for match in pattern.finditer(text):
            reference(path, match.group(2).strip())


class Document(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.style = False
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get("id"):
            self.ids.add(attrs["id"])
        if tag == "a" and attrs.get("name"):
            self.ids.add(attrs["name"])
        if tag == "base" and attrs.get("href"):
            fail(self.path, "HTML base URLs are unsupported by the artifact checker")
        for key, value in attrs.items():
            if value is None:
                continue
            if key in {"href", "src", "poster", "action", "formaction", "cite", "background", "xlink:href"} or (tag == "object" and key == "data"):
                reference(self.path, value, public_demo_anchor=tag == "a" and key == "href")
            elif key == "srcset" and not value.lstrip().startswith("data:"):
                for candidate in value.split(","):
                    if candidate.strip():
                        reference(self.path, candidate.split()[0])
            elif key == "style":
                css_references(self.path, value)
        if tag == "meta" and attrs.get("http-equiv", "").lower() == "refresh":
            match = re.search(r"(?:^|;)\s*url\s*=\s*(.*)", attrs.get("content", ""), re.IGNORECASE)
            if match:
                reference(self.path, match.group(1).strip(" '\""))
        if tag == "style":
            self.style = True

    def handle_endtag(self, tag):
        if tag == "style":
            self.style = False

    def handle_data(self, data):
        if self.style:
            css_references(self.path, data)


files = []
for directory, dirs, names in os.walk(root, followlinks=False):
    for name in sorted(dirs + names):
        path = Path(directory) / name
        relative = path.relative_to(root)
        if path.is_symlink():
            fail(relative, "symlinks cannot be published")
            if name in dirs:
                dirs.remove(name)
            continue
        if any(part.lower() in {"local", ".git", ".ssh", ".aws"} for part in relative.parts):
            fail(relative, "private directory cannot be published")
            if name in dirs:
                dirs.remove(name)
            continue
        if re.search(r"(?:\.db(?:-(?:wal|shm|journal))?|\.env)$", name, re.IGNORECASE) or name.lower().startswith(".env."):
            fail(relative, "database or environment file cannot be published")
            if name in dirs:
                dirs.remove(name)
            continue
        if path.is_file():
            files.append(relative)
        elif not path.is_dir():
            fail(relative, "non-regular artifact cannot be published")

for name in required:
    if Path(name) not in files:
        fail(Path(name), "required page is missing")

for relative in sorted(files):
    try:
        content = (root / relative).read_bytes()
    except OSError:
        fail(relative, "cannot read artifact")
        continue
    private_content(relative, content.decode("utf-8", errors="replace"))
    if relative.suffix.lower() not in markup_suffixes | {".css"}:
        continue
    try:
        text = content.decode("utf-8")
        if relative.suffix.lower() == ".css":
            css_references(relative, text)
        else:
            document = Document(relative)
            document.feed(text)
            document.close()
            anchors[relative] = document.ids
    except (UnicodeError, ValueError):
        fail(relative, "cannot parse UTF-8 markup or stylesheet")

if public_demo_links != 1:
    fail(public_demo_page, "must contain exactly one approved public demo acceptance link")

local_count = 0
for source, value in references:
    try:
        link = urlsplit(value.strip())
    except ValueError:
        fail(source, "invalid link syntax")
        continue
    path = unquote(link.path)
    if link.scheme or link.netloc:
        same_site = link.scheme in {"", "http", "https"} and link.netloc.lower() == site_url.netloc.lower()
        if not same_site or (prefix and path != prefix and not path.startswith(prefix + "/")):
            continue
    if path.startswith("/"):
        if prefix and path != prefix and not path.startswith(prefix + "/"):
            fail(source, "root-relative link leaves the configured project site")
            continue
        path = path[len(prefix):].lstrip("/")
        target = root / path
    else:
        target = root / source.parent / path if path else root / source
    try:
        target = target.resolve()
        relative = target.relative_to(root)
    except (OSError, RuntimeError, ValueError):
        fail(source, "local link leaves the site artifact")
        continue
    if target.is_dir():
        target /= "index.html"
        relative /= "index.html"
    local_count += 1
    if not target.is_file() or relative not in files:
        fail(source, "local link or asset target is missing")
        continue
    fragment = unquote(link.fragment).split(":~:text=", 1)[0]
    if fragment and relative.suffix.lower() in markup_suffixes and fragment not in anchors.get(relative, set()):
        fail(source, "local link fragment is missing")

if errors:
    for filename, reason in sorted(errors):
        print(f"Site check failed: {filename!r}: {reason}.", file=sys.stderr)
    sys.exit(1)
print(f"Site checks passed ({len(required)} required pages, {len(files)} files, {local_count} local references).")
PY
