# Offline tools and optional workspace

These classroom commands run from the repository root using the JAR built by
`mvn -B -ntp verify`.
They do not start Spring or an HTTP server. See [Getting started](../GETTING_STARTED.md)
for registration and [the registration reference](registration.md) for administration.

The examples use numerical methods and computational finance assignments; replace
the course and assignment IDs with your own. The executable JAR, service account
and installation paths still use `finmath-classroom`.

## JUnit feedback scoring

Score existing JUnit XML reports against a JSON rubric:

```sh
java -jar target/finmath-classroom.jar score \
  --rubric examples/grading/rubric.json \
  --reports /path/to/surefire-reports \
  --output /private/service/feedback.json
```

Use your assignment's rubric; the bundled rubric is an illustrative example.
The output directory must exist. The command reads reports and does not execute
tests or student code. Exit codes are 0 for all passed, 1 for failed/incomplete,
and 2 for errors. Check the exit status before using an output; a rejected input
can leave an older output file in place.

Add `--summary /private/service/feedback.md` to also append a Markdown summary.
The [autograding action and migration guide](autograding.md) describes shared Maven
test execution, artifact upload and automatic server collection. Validate the
end-to-end flow with a test assignment before distributing links to a class.

`feedback select --rubric FILE` prints the validated Surefire method selectors,
or an empty line for a whole-suite rubric (the caller must omit `-Dtest`).
`feedback report --rubric FILE --reports DIR --context FILE --test-exit-code NUMBER
--output FILE [--summary FILE]` packages existing reports and run context into the
versioned uploaded-result format. It never executes tests, and requires fresh
output files. Missing/error/timeout results have a null top-level score. See the
[result contract](autograding.md#3-uploaded-result-contract-version-1) for fields and
exit codes.

## Downloading feedback from GitHub

`feedback sync --data DIR` uses the enrolled repository identities to download
completed push-run feedback on each assignment's configured branch. It writes
validated results and collection history to private `DIR/feedback.db`. It reads
GitHub APIs and does not execute repository code. `feedback list --data DIR`
and `feedback show --data DIR --repository-id ID --run-id ID --attempt NUMBER`
read those saved results without network access or credentials.

For one row per enrolled student and assignment, use the server helper:

```sh
sudo classroom feedback list --latest --format table \
  --lecture numerical-methods --assignment quadratic-equation
```

Omit the selectors to include all current enrollments. With Java directly, use
`feedback list --data DIR --latest --format table`. The latest view selects the
newest known run and its highest attempt, including missing or invalid feedback
with an unavailable score. Enrollments without a stored attempt are also shown.
Run `feedback sync` first for a fresher local snapshot. The default listing remains
all-history JSON; `--latest` also works with JSON, and `--format table` can display
the full history. Deploy the updated JAR to use these options; the existing server
helper forwards them without reinstalling it.

The `COMMIT_TIME_UTC` column shows the timestamp of the displayed commit, in UTC
(for example, `2026-11-12T22:59:00Z`). JSON listings expose the same value as
`commitTimestamp`. It comes from GitHub's workflow-run `head_commit.timestamp`,
not the workflow start or feedback download time. A missing timestamp is shown
as `-` (JSON `null`). The next `feedback sync` automatically upgrades an older
feedback database and fills missing timestamps for known runs where GitHub
provides them, including already collected results; listing alone stays read-only.

Use this timestamp for a manual comparison with a deadline in the same time zone.
Git commit timestamps are supplied by the commit creator, so they do not prove
when GitHub received a submission. The row refers to the latest collected workflow
run, not necessarily the current branch head; no automatic late/on-time decision
is made.

Add the [Moodle export options](#export-latest-feedback-directly) to display this
table and write a scaled import CSV in the same command.

See [automatic collection](autograding.md#5-automatic-server-collection) for
permissions, filters and status semantics, and [deployment](deployment.md#6-enable-automatic-feedback-collection)
for the optional five-minute timer. These commands handle student feedback;
the `collect` command below requires separate trusted final-runner results.

## Trusted-result CSV collection

Combine final-runner result files using a submission manifest:

```sh
java -jar target/finmath-classroom.jar collect \
  --manifest /private/service/submissions.json \
  --results /private/service/final-results \
  --output /private/service/grades.csv
```

This reads supplied files; it does not download GitHub artifacts or perform grading.
Results are correlated to assignment, repository and commit. Missing or broken
results do not silently become zero scores. Input/schema errors return exit code 2.

Student-controlled feedback is not a trusted final result. The isolated final
runner and Java submission-manifest generation remain pending. See the
[grading contract](grading-contract.md) for the reference format and deadline rules.

## Moodle grade import

Export either the framework's saved latest feedback or an existing username/points
CSV. Both routes produce a two-column CSV for Moodle's **course gradebook import**.
They need no Moodle token and do not contact Moodle or change grades. Import the
result using the [steps below](#import-the-exported-csv), including the distinction
between gradebook overrides and Assignment grading.

### Export latest feedback directly

For students registered through Moodle, display the latest feedback and generate
the import file together. The command uses their saved Moodle user IDs automatically;
no username mapping file is needed:

```sh
sudo classroom feedback list --latest --format table \
  --lecture computational-finance --assignment discount-factor-interpolation \
  --moodle-output /var/lib/finmath-classroom/discount-factor-grades.csv \
  --moodle-max 10
```

The first CSV column is `userid`. In Moodle, map it to **User ID**, not **Username**
or **ID number**. The command reports the Moodle site associated with those IDs;
import the file back into that same site. The listing's **NAME** is a full/display
name, normally released by Moodle, while **STUDENT ID** is the framework's internal
ID. Neither needs to be copied or translated for this workflow.

The export uses the current saved Moodle identity, including a Moodle association
established after a student's initial direct registration. Each exported score must
have a verified Moodle identity with a positive numeric account ID, and all exported
IDs must belong to the same Moodle site. Missing, nonnumeric or duplicate IDs and
mixed Moodle sites stop the export instead of dropping scored students. The default
targets standard Moodle, which sends its numeric user ID as the LTI subject. A
custom or anonymizing LTI plugin may use different subject semantics; use the
[username mapping alternative](#optional-username-mapping) for those installations.
See Moodle's [LTI implementation](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/lti/locallib.php)
and [grade import fields](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/grade/import/grade_import_form.php).

The CSV uses the configured assignment title as its grade-column heading. Override
it with `--grade-item "Discount-factor interpolation"` if useful when mapping the
column in Moodle. Each score is scaled using that result's reported `maxScore`:
75 out of 100 becomes 7.50 out of 10. Add `--source-max 100` to require every exported
result to report that maximum; it checks for a rubric mismatch rather than replacing
the reported maximum. `--offset` and `--decimals` work as described below.

Only received results with status `passed` or `failed` and numeric scores are
exported. Missing, invalid or unavailable latest feedback is omitted and counted,
with no fallback to older scores; an explicit zero is retained and scaled. If no
scores can be exported, the command fails without creating a file. Review the
table and the export/skip counts before importing. The table and CSV use the same
selected latest results; no table parsing or intermediate points CSV is needed.

Export requires `--latest`, a single `--lecture` and `--assignment`,
`--moodle-output` and `--moodle-max`. The output must be a new file
in an existing directory. With the server helper, keep output under
`/var/lib/finmath-classroom`: the `finmath-classroom` service user needs write access
to the output directory. Keep exports private; the export is created with
owner-only permissions. The databases are read without
changes, and a missing feedback database is not created.

This uses the locally collected snapshot. For a fresher one, first run:

```sh
sudo classroom feedback sync \
  --lecture computational-finance --assignment discount-factor-interpolation
```

These scores still come from student-controlled workflows; exporting them does not
establish the current branch head, a deadline submission or authoritative grading.
Deploy the updated JAR before using these options. The existing `classroom` helper
already forwards them, so no helper reinstall or database migration is needed.
Students whose Moodle identities are already recorded do not need to register again.
For local use, replace `sudo classroom` with
`java -jar target/finmath-classroom.jar` and add `--data DIR`.

### Warm-up: whole Moodle points

For `computational-finance/quadratic-equation`, scale the total GitHub score out of
4 to Moodle points out of 2, then round halves upward to a whole point:

| GitHub points | Moodle points |
| ---: | ---: |
| 0 | 0 |
| 1 | 1 |
| 2 | 1 |
| 3 | 2 |
| 4 | 2 |

```sh
sudo classroom feedback list --latest --format table \
  --lecture computational-finance --assignment quadratic-equation \
  --source-max 4 --moodle-max 2 --decimals 0 \
  --moodle-output /var/lib/finmath-classroom/warmup-grades-rounded.csv
```

Round the **total score after scaling**, rather than rounding individual rubric
parts. For these integer GitHub scores, half-up rounding gives the same result as
rounding the scaled score upward. These options apply the warm-up rule to this
export; the command's default remains two decimal places. Use a new output filename
when repeating the export. Import `userid` as **User ID** using the
[Moodle import steps](#import-the-exported-csv).

### Optional username mapping

For students without a saved Moodle identity, or a Moodle installation with custom
LTI identifiers, add `--moodle-users /var/lib/finmath-classroom/moodle-users.json`
to the export command. This switches the **whole export** to username matching,
including any students who did register through Moodle. The CSV's first column is
then `username`, which must be mapped to **Username** during import.

Obtain the framework's stable student IDs alongside names and GitHub accounts with:

```sh
sudo classroom registration list --lecture computational-finance --format table
```

Save a private JSON object mapping each relevant **STUDENT ID** to that person's
actual Moodle username, readable by the `finmath-classroom` service user:

```json
{
  "student-id-from-registration-list-1": "student.one",
  "student-id-from-registration-list-2": "student.two"
}
```

Replace those example keys with the exact IDs from your registration listing;
automatically registered students usually have UUIDs. Verify the association using
your course records, not display-name similarity. Reuse this mapping for assignments
in the same lecture and extend it when new students register. Every student with an
exportable score needs a mapping in this mode. Missing mappings or duplicate Moodle
usernames reject the export rather than silently dropping scored students.

### Convert an existing points CSV

`moodle export` converts a reviewed list of Moodle usernames and assignment points.
Use one input file per assignment. For example:

```csv
username,points
student.one,75
student.two,100
student.three,
student.four,0
```

```sh
java -jar target/finmath-classroom.jar moodle export \
  --input /private/service/points.csv \
  --output /private/service/moodle-grades.csv \
  --source-max 100 --moodle-max 10 \
  --grade-item "Discount-factor interpolation"
```

This produces grades 7.50, 10.00 and 0.00; the row with blank points is omitted.
The output directory must exist and the output file must be new. Keep the input
and exports in private storage outside version control. Run this command with Java
directly; the `sudo classroom` server helper only supports registration and feedback.

Both export routes use this scaling (for latest feedback, `source-max` is the
result's reported maximum):

```text
moodle points = round(clamp(points / source-max * moodle-max + offset, 0, moodle-max))
```

`--offset` defaults to 0, in Moodle points. `--decimals` defaults to 2 and accepts
0–6; rounding is half up. The result is capped at the exact Moodle maximum even if
that maximum has more fractional digits than the requested precision. Set Moodle's
grade item maximum to `--moodle-max`; the converter cannot inspect that setting.
For a CSV headed `Moodle User Name,GitHub points`, add
`--username-column "Moodle User Name" --points-column "GitHub points"`.
Column names match exactly. Extra input columns are ignored after CSV validation.

The command accepts UTF-8 comma-separated CSV, including an optional BOM and quoted
fields; decimal numbers use a dot. It validates the whole file before writing:
blank scores are skipped and counted, explicit zero is retained, and duplicate
usernames (case insensitive), missing usernames, malformed numbers and scores
outside `[0, source-max]` fail with exit code 2. A file with no numeric scores fails.
CSV text that could be interpreted as a spreadsheet formula is rejected rather
than changing an identity by prefixing an apostrophe. Usernames are preserved,
including leading zeroes; avoid spreadsheet conversions that alter them. Numeric
values are limited to an absolute value of one billion and twelve fractional digits.

The input must already contain actual Moodle **usernames**, not display names,
GitHub logins, or LTI subject IDs. Current Moodle launches store an LTI subject,
which must not be assumed to be the Moodle username. The existing `collect` CSV
contains `student_id` and `score`; only select `--username-column student_id
--points-column score` if those IDs are verified Moodle usernames. This converter
does not resolve identities or make student-controlled workflow scores authoritative.

### Import the exported CSV

1. Open the Moodle course's **Grades → Import → CSV file**, upload the exported
   file, and select UTF-8 with a comma separator.
2. Under **Identify user by**, map `userid` to **User ID** for the default
   latest-feedback export. For an explicit username mapping or an existing points
   CSV converted with `moodle export`, map `username` to **Username** instead.
   **ID number** is a separate Moodle field; it is not the numeric User ID.
3. In grade item mappings, ignore the identity column and map the exported grade column
   (for example, `Discount-factor interpolation`) to the
   **existing** assignment grade item. The exported column title is a label for
   this manual mapping; it does not locate an activity automatically.
4. Review Moodle's preview, identities, scores and destination, then import. For
   a `userid` export, use the Moodle site reported by the export command.
   Rows omitted for blank scores leave existing grades untouched.

**Assignment caveat:** Moodle gradebook imports override activity grades. A grade
imported this way can no longer be changed through the Assignment's grading page
until its gradebook override is removed. This is appropriate if the course gradebook
is where you manage these points. See Moodle's [grade import documentation](https://docs.moodle.org/en/Grade_import).

If points should remain managed **inside the separate Moodle Assignment activity**
(such as the activity where students submit their GitHub repository URL), use its
**Offline grading worksheet** instead: enable it in Assignment feedback settings,
download the worksheet from that assignment, merge the scaled grades into its
Grade column, then upload and review the proposed changes. Preserve the worksheet's
participant identifiers and timestamps. It cannot be recreated from usernames and
points alone, and username may not be an exported identity field; use a verified
common field or an explicit username-to-participant mapping. The converter above
generates a gradebook CSV, not an Assignment worksheet. See Moodle's
[Assignment instructions](https://docs.moodle.org/en/Using_Assignment). Worksheet
grade import requires simple direct grading; it does not support Rubrics or
Marking guides.

### Later direct synchronization

For direct updates of a separate Assignment activity, a future adapter can use
Moodle's `mod_assign_save_grades` web service. It needs an administrator-configured
service/token, the internal assignment ID (not the activity URL's course-module ID),
and a verified mapping to numeric Moodle user IDs. Fetch existing grades and preview
the changes before writing; preserve attempt and grading-workflow semantics. See
Moodle's [web service setup](https://docs.moodle.org/405/en/Using_web_services) and
[Assignment service implementation](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/externallib.php).
LTI grade return would target the External tool's grade item, which is a different
destination from the separate Assignment. Neither direct sync nor worksheet merging
is implemented by this command.

## Optional Maven/IntelliJ workspace

The workspace generator creates an aggregate Maven project for navigating existing
repositories. Registration does not require it. The bundled `workspace/modules.json`
describes an existing checkout layout; supply your own list for another installation.

For sibling repositories `/path/to/repositories/lecture-library` and
`/path/to/repositories/assignment-one`, save this as `/private/service/modules.json`:

```json
{
  "modules": ["lecture-library", "assignment-one"]
}
```

Each module must have a Maven POM with explicit, unique coordinates. Select only
repositories you intend to edit; student submissions and solution repositories
are not added automatically.

```sh
java -jar target/finmath-classroom.jar workspace --check \
  --modules /private/service/modules.json \
  --repositories-root /path/to/repositories \
  --output /path/to/teaching-workspace

java -jar target/finmath-classroom.jar workspace --write \
  --modules /private/service/modules.json \
  --repositories-root /path/to/repositories \
  --output /path/to/teaching-workspace
```

`--check` is read-only. `--write` creates only owned generated workspace files and
refuses to overwrite unrelated files. It does not clone repositories, edit module
POMs, change Git state or run assignment builds. The workspace output must be
separate from its modules.

Open the generated `pom.xml` as an IntelliJ project. Each module keeps its own
Git repository and Maven build; the aggregate POM is not a shared parent.
Edits affect the original working trees. Running Maven at the aggregate root
runs all selected modules, so test individual assignments in their own directories.

Without explicit options, the generator uses `workspace/modules.json`, the parent
of the current directory as the repository root, and `../finmath-lectures` as output.
See [Maven aggregation](https://maven.apache.org/guides/introduction/introduction-to-the-pom.html).
