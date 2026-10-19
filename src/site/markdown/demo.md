# A small change, a visible result

Meet Alex Student, a fictional learner exploring a quadratic-equation exercise.
This walkthrough uses example scores; each course defines its own rubric and
tests.

## 1. Accept: a repository of your own

Alex opens the course's assignment page, connects the GitHub account
`alex-student`, confirms the required details, and accepts the declaration.

The service creates a private copy of the instructor's template and grants
access. Alex accepts the GitHub invitation when needed, then opens the repository
as a Java project.

## 2. Explore: start with one question

The exercise considers **x² + px + q = 0**. Alex starts with `hasRealRoot()`,
a small predicate whose behavior is easy to discuss and test:

```java
public boolean hasRealRoot() {
    return p * p / 4 - q >= 0;
}
```

This introductory implementation assumes finite coefficients and intermediate
arithmetic that does not overflow. Floating-point extremes and numerical
robustness deserve separate investigation.

The exercise also includes `getSmallestRoot()`. Its implementation remains work
for the learner.

## 3. Test and improve: three pushes

Alex runs JUnit locally, pushes changes, and reads the feedback from each new
workflow run. The example rubric assigns one point to the first task and three
to the remaining task.

| Run | What changed | Result | Points |
| --- | --- | --- | --- |
| 01 | Explore the starting project and inspect the initial failures | Failed | 0/4 |
| 02 | Implement the first predicate; the remaining task still needs attention | Failed | 1/4 |
| 03 | Complete and check the remaining task | Passed | 4/4 |

Passing the available checks is a starting point for discussing correctness,
boundary cases, and numerical behavior.

## 4. Review: bring feedback back to the course

The instructor can synchronize GitHub feedback and inspect the latest known
result for each current enrollment. Earlier runs and reruns remain in the local
history. With the CLI helper installed and configured:

```sh
classroom feedback sync
classroom feedback list --latest --format table
```

A missing, expired, or invalid latest result appears as unavailable feedback;
an older score is not substituted. A missing result is not a zero.

These results are student workflow feedback. Automatic submission capture and
an isolated runner for authoritative final grading remain future work.
The [feedback guide](https://github.com/finmath/finmath-classroom/blob/main/docs/autograding.md)
describes collection and administration.

## What did the tests teach you?

Which cases did the tests cover? Where might floating-point arithmetic change
the answer? Could you explain the implementation without reading it aloud?

Whether Alex worked alone, with peers, or with permitted AI assistance, those
questions are where the learning continues.

[Try the offline walkthrough](https://github.com/finmath/finmath-classroom/blob/main/GETTING_STARTED.md)
to build the application, explore example configuration, and plan a small live
pilot.
