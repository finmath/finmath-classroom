#!/usr/bin/env bash
# Capture production registration HTML using fictional local fixtures; no GitHub calls.
# Requires JDK 17+, Maven, Node 22+, and Chrome/Chromium (override with CHROME_BIN).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -n "${CHROME_BIN:-}" ]]; then
  demo_chrome="$CHROME_BIN"
elif [[ -x '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' ]]; then
  demo_chrome='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
else
  demo_chrome="$(command -v chromium || command -v chromium-browser || command -v google-chrome)"
fi
demo_work="$(mktemp -d "${TMPDIR:-/tmp}/classroom-demo.XXXXXX")"
trap 'rm -rf "$demo_work"' EXIT

# The focused UI tests also provide Maven's resolved test classpath, without an extra plugin.
mvn -B -ntp -Dtest=RegistrationControllerTest test
demo_classpath="$(node -e '
  const fs = require("node:fs");
  const report = fs.readFileSync("target/surefire-reports/TEST-org.finmath.classroom.web.RegistrationControllerTest.xml", "utf8");
  const value = report.match(/<property name="java.class.path" value="([^"]*)"/)[1];
  process.stdout.write(value.replace(/&quot;/g, String.fromCharCode(34)).replace(/&apos;/g, String.fromCharCode(39))
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&"));
')"
javac --release 17 -cp "$demo_classpath" -d "$demo_work/classes" scripts/demo/RenderRegistrationPages.java
java -cp "$demo_work/classes:$demo_classpath" \
  org.finmath.classroom.registration.RenderRegistrationPages "$demo_work/pages"
node scripts/demo/capture-registration.cjs "$demo_work/pages" src/site/resources/images "$demo_chrome"
