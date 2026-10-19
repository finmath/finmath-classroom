# Project site

The public site at <https://www.finmath.net/finmath-classroom/> introduces the
project, its teaching motivation and a fictional assignment-feedback demo.
Operational reference documentation remains in this repository and is linked
from the site.

## Build and preview

Use JDK 17 or newer and Maven 3.6.3 or newer:

```sh
mvn -B -ntp site
bash scripts/check-site.sh
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
data under ignored `local/`; never copy acceptance URLs with registration keys,
credentials, rosters, databases or instructor solutions into the site. Examples
use fictional students and illustrative scores. The quadratic-equation demo
deliberately leaves the smallest-root implementation as an exercise.

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
