# finmath classroom

**Turn a mathematical idea into working code.**

Give every learner a private GitHub repository, useful test feedback, and room to
try again. finmath classroom is a free, self-hosted alternative to GitHub Classroom
for small programming exercises in numerical methods and computational finance.

[Get started](https://github.com/finmath/finmath-classroom/blob/main/GETTING_STARTED.md)
· [Follow a learner](demo.html)
· [Source on GitHub](https://github.com/finmath/finmath-classroom)

## From assignment to insight

1. **Accept.** Connect a GitHub account, confirm the course declaration, and receive
   a private copy of the assignment template.
2. **Explore.** Open the Java project, read the question, inspect the tests, and
   turn an idea into an implementation.
3. **Test.** Run JUnit locally and push changes. The shared GitHub action publishes
   feedback from the assignment's rubric and test reports.
4. **Improve.** Use failures as clues. Revise, test again, and discuss why the
   implementation behaves as it does.

The [learner demo](demo.html) follows a fictional student's progress across three
pushes, from **0/4** to **1/4** to **4/4** points.

## Small exercises, big questions

A numerical method, a small simulation, or a computational finance experiment can
be enough to provoke useful questions. Change a parameter, test an assumption,
and examine the result.

Students can explore with or without AI assistance, under the course's rules.
The learning task remains the same: understand the method, inspect the evidence,
and explain the result. [Read the motivation](motivation.html).

## Registration and feedback today

The application provides GitHub sign-in, private template repositories, student
invitations, and configured staff access. Instructors can collect Maven/JUnit
feedback and inspect its history through local command-line tools. Optional
Moodle launches connect registration to an existing course.

| Available now | Planned |
| --- | --- |
| Registration and private assignment repositories | Automatic submission capture and source snapshots |
| Shared test feedback and artifact collection | An isolated runner with instructor-controlled tests |
| SQLite history and administrative CLI | Authoritative final grades and Moodle grade return |

Student workflow results are learning feedback. Authoritative final grading
requires an isolated instructor-controlled runner, which remains future work.

## Start with one exercise

The service uses **Java 17+, Maven, Spring MVC, and SQLite**. Assignment repositories
keep their own builds and Git histories. An offline walkthrough lets you explore
the application before configuring a live course.

[Open the getting-started guide](https://github.com/finmath/finmath-classroom/blob/main/GETTING_STARTED.md)
or read the [architecture notes](https://github.com/finmath/finmath-classroom/blob/main/docs/development.md).
