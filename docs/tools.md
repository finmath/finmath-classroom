# Offline tools and optional workspace

These commands run from the repository root using the JAR built by `mvn -B -ntp verify`.
They do not start Spring or an HTTP server. See [Getting started](../GETTING_STARTED.md)
for registration and [the registration reference](registration.md) for administration.

## JUnit feedback scoring

Score existing JUnit XML reports against a JSON rubric:

```sh
java -jar target/finmath-classroom.jar score \
  --rubric tools/classroom/rubric.example.json \
  --reports /path/to/surefire-reports \
  --output /private/service/feedback.json
```

Use your assignment's rubric; the bundled rubric is an illustrative example.
The output directory must exist. The command reads reports and does not execute
tests or student code. Exit codes are 0 for all passed, 1 for failed/incomplete,
and 2 for errors. Check the exit status before using an output; a rejected input
can leave an older output file in place.

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

## Python reference

The original tools in `tools/classroom/` remain as migration specifications,
including push capture and deadline manifests that have not been ported to Java.
They are not required by the Java CLI or server. Optional reference checks require
Python 3.10 or newer:

```sh
python3 -m unittest discover -s tools/classroom -p 'test_*.py'
python3 -m unittest discover -s tools -p 'test_workspace.py'
```

`tools/classroom/templates/feedback.yml.example` is an inactive workflow example.
Shared assignment-workflow distribution has not yet been implemented.
