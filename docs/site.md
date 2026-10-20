# Project site

The public site at <https://www.finmath.net/finmath-classroom/> introduces the
project, its motivation and an example assignment with illustrative test results.
Operational reference documentation remains in this repository and is linked
from the site.

## Build and preview

Use JDK 17 or newer and Maven 3.6.3 or newer:

```sh
mvn -B -ntp site
bash scripts/check-site.sh
bash scripts/test-site.sh
```

Maven Site Plugin renders Markdown from `src/site/markdown` into `target/site`,
using Maven Doxia and the Fluido skin, as in the other finmath projects. A shared
stylesheet provides the project colors and responsive layout. Open
`target/site/index.html` in a browser to preview it. After deleting or renaming
pages, or changing the Maven site configuration, run `mvn -B -ntp clean site` to
remove obsolete generated output and rebuild every page.

| Source | Purpose |
| --- | --- |
| `src/site/site.xml` | Maven skin, navigation and site metadata |
| `src/site/markdown/*.md` | Overview, motivation and example content |
| `src/site/resources/css/site.css` | Shared responsive layout and typography |
| `.github/workflows/pages.yml` | Build validation and GitHub Pages deployment |

Edit the Markdown sources using ordinary headings, paragraphs, lists, tables and
fenced code blocks. Use links such as `demo.html` for other generated pages.
Navigation belongs in `site.xml`; presentation belongs in the shared CSS. Keep
generated HTML in `target/site`, rather than maintaining duplicate HTML pages.

Only public content belongs under `src/site`. Keep deployment-specific notes and
data under ignored `local/`; never copy private course acceptance URLs, credentials,
rosters, databases or instructor solutions into the site. Examples use fictional
students and illustrative scores. The quadratic-equation demo deliberately leaves
the smallest-root implementation as an exercise.

## Public demo

The example page links to the separate `numerical-methods-demo` lecture and
`quadratic-equation-demo` assignment. This is an intentionally public registration
link: anyone following it can sign in and accept the demo. Its public keys apply
only to this demo; existing course keys must not be reused.

The server configuration belongs in ignored `local/teaching/lectures`, with an
empty roster and a distinct repository prefix. The demo initially has no Moodle
mapping. An optional LTI 1.3 mapping lets a Moodle activity launch the same assignment
through the shared `/lti/entry` target, using the activity's `classroom_lecture` and
`classroom_assignment` custom parameters. The public link continues to supply
registration keys. Import the configuration and restart the application before
publishing the demo link.
Building or publishing the Maven site does not deploy the classroom configuration.
The private deployment instructions are in `local/teaching/demo-setup.md`.

Moodle mappings are frozen when an assignment is imported. Adding one to an
already imported demo requires a reviewed configuration migration that preserves
its repository settings and existing acceptances; changing JSON and reimporting
alone is insufficient. Test with a learner account that has not accepted the demo
to inspect the Moodle name handover. Existing acceptances retain their recorded
name and repository.

`scripts/check-site.sh` permits only the exact public demo link in `demo.html`.
Other registration-key links remain prohibited. When changing the demo URL,
update the explicit allowance in that script and run its regression checks.

The screenshots under `src/site/resources/images` show the actual registration
interface rendered locally with fictional data and a fake GitHub adapter. They
are not captures of real student accounts or records.

To regenerate them, use JDK 17+, Maven, Node 22+ and Chrome or Chromium:

```sh
bash scripts/capture-demo-screenshots.sh
```

Set `CHROME_BIN` to the browser executable if it is not found automatically. The
script runs the controller tests, renders the registration pages with temporary
fixtures and captures them with external HTTP requests blocked. It does not use
the server's configuration or credentials. The Java and browser helpers are in
`scripts/demo/`; the screenshot tools are optional for normal application and
site builds because the PNG files are committed.

## GitHub Pages

In the repository's **Settings → Pages → Build and deployment**, select
**GitHub Actions** as the source. No `gh-pages` branch is required. This project
inherits the organization's existing `www.finmath.net` Pages domain, with HTTPS
enforced. A fork can use its default `https://OWNER.github.io/REPOSITORY/` address;
update the project URL in `pom.xml`, site links, and the
repository guard in the workflow before enabling publication.

The **Project site** workflow builds and checks pull requests. Pushes to `main`
also upload only `target/site` and publish it through the `github-pages`
environment. Publication uses the repository's short-lived workflow token with
Pages and OIDC permissions; it does not use classroom credentials or access an
application server. A newer main commit supersedes an older build waiting to
deploy. **Run workflow** on `main` can retry the publication.

These are static project pages. The Java classroom service and its OAuth callback
still run on a separately configured HTTPS server; GitHub Pages does not host that
service or its private data. The server-release workflow and feedback action's
`v1` reference remain separate from site publication.

See the official [Maven Site usage](https://maven.apache.org/plugins/maven-site-plugin/usage.html)
and [GitHub Pages workflow guide](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).
