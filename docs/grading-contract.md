# Grading migration contract

This document is for contributors implementing submission capture and final grading.
For the available offline commands, see [Tools](tools.md).

This is the Python reference contract to preserve or explicitly revise when Java
push processing is implemented. The current Java deadline closes enrollment only.

The last authenticated configured-branch push **processed by the server before
the deadline** is selected. Commit-author timestamps are never trusted. Database
transactions are short and no network calls hold their write lock. Pushes first
processed after the cutoff, including delayed/redelivered webhooks, are ineligible;
publish this policy and manually review outages or near-deadline delivery failures.
The database records SHAs, not source archives. Preserve source snapshots before
using this in a real course: force pushes can make old commits unavailable later.

After the deadline, `export-manifest` writes a new JSON file. A future trusted
runner will consume that manifest and output one result per submission:

```json
{
  "assignment": "term-course-assignment",
  "repository": "teaching-org/term-assignment-student",
  "commit": "0123456789abcdef0123456789abcdef01234567",
  "status": "failed",
  "score": 15,
  "max_score": 22,
  "graded_at": "2026-12-16T10:00:00+00:00"
}
```

Here `failed` means less than full marks, not necessarily failure of the course.
Infrastructure/incomplete/error results must use a null score; compile-error and
timeout results may explicitly use zero or leave it null, according to course
policy. Feedback JSON deliberately has a different schema. Metadata matching does
not prove provenance: the results directory must be writable only by the trusted
grader/administrator.

The Java collector already accepts these files:

```sh
java -jar target/finmath-classroom.jar collect \
  --manifest /private/service/submissions.json \
  --results /private/service/final-results \
  --output /private/service/grades.csv
```
