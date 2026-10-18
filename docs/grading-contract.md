# Grading migration contract

This document is for contributors implementing submission capture and final grading.
For the available offline commands, see [Tools](tools.md).

The contract below preserves the submission behavior established during prototyping.
Java currently closes enrollment at an optional deadline; it does not receive push
webhooks or provide an `export-manifest` command. Preserve or explicitly revise
these rules when implementing those features.

## Authenticated push capture

- Verify the GitHub signature as HMAC-SHA256 over the exact raw request body,
  using an independent random secret of at least 32 bytes and constant-time
  comparison. Reject invalid signatures without recording a delivery. Bound
  request bodies; the prototype limit was 10 MiB.
- Authenticate ping and other event types too. Acknowledge pings and ignore
  unrelated events. Require a bounded delivery identifier; the prototype accepted
  1–100 letters, digits or hyphens.
- For push events, validate the numeric repository ID, full repository name,
  `ref`, and 40-character hexadecimal `after` SHA. Match both the enrolled repository
  ID and its name, comparing names case-insensitively. Ignore unknown/mismatched
  repositories, branches other than the configured branch, deleted refs and the
  all-zero deletion SHA.
- Deduplicate delivery IDs durably. Redelivery preserves the original processing
  timestamp and eligibility. Record authenticated late pushes as ineligible.
- Select the last matching push **processed strictly before the deadline**. Use
  server UTC processing time, never commit-author time. Break equal-timestamp ties
  by persistent ingestion order. Keep transactions short and network calls outside
  database write locks.

A push first processed at or after the cutoff is ineligible, even if GitHub sent
it earlier. Publish this policy and review outages and near-deadline delivery
failures manually. A recorded SHA is not a durable source archive: preserve source
snapshots because force pushes can make old commits unavailable.

## Submission manifest v1

A future exporter must refuse export before the deadline, read a consistent
database snapshot and create a new file without overwriting an existing manifest.
Include every roster student, sorted by student ID, with these states:

| Status | Repository | Commit and received time |
| --- | --- | --- |
| `submitted` | Assigned repository | Last eligible push |
| `no_submission` | Assigned repository | Both null |
| `not_enrolled` | Null | Both null |

The existing Java collector accepts this exact schema; timestamps include a UTC
offset. Unknown fields are rejected. For example:

```json
{
  "version": 1,
  "assignment": "term-course-assignment",
  "deadline": "2026-12-15T23:00:00Z",
  "generated_at": "2026-12-16T10:00:00Z",
  "submissions": [
    {
      "student_id": "student-1",
      "github_username": "example-student",
      "repository": "teaching-org/term-assignment-123",
      "commit": "0123456789abcdef0123456789abcdef01234567",
      "received_at": "2026-12-15T22:59:00Z",
      "status": "submitted"
    }
  ]
}
```

Two integration decisions remain: manifest v1 requires a GitHub username even for
unenrolled rows, while Java permits roster students without one; and its single
`assignment` identifier needs a collision-free mapping from Java's lecture and
assignment IDs. Resolve these explicitly before adding export. The collector's
timestamp validation allows equality with the deadline; the exporter must still
enforce the stricter push-selection rule above.

Future acceptance tests should cover forged signatures, duplicate redelivery across
the cutoff, exact-cutoff and late processing, wrong branch/repository identity,
deleted refs, equal-time ordering, and all three roster states.

## Trusted final results

An isolated instructor-controlled runner must produce one result for each submitted
repository and exact commit. Its build configuration and tests are trusted; student
workflows and feedback artifacts are not. This runner remains unimplemented.

Example result matching the manifest above:

```json
{
  "assignment": "term-course-assignment",
  "repository": "teaching-org/term-assignment-123",
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

Missing result files remain `missing_result` with blank marks. Unsubmitted and
unenrolled students also have blank marks; absence or infrastructure failure must
not silently become a zero grade.

The Java collector already accepts these files:

```sh
java -jar target/finmath-classroom.jar collect \
  --manifest /private/service/submissions.json \
  --results /private/service/final-results \
  --output /private/service/grades.csv
```
