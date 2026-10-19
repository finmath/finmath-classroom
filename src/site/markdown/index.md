# finmath classroom

**A general-purpose classroom solution for programming assignments.**

finmath classroom is a self-hosted Java application for administering programming
assignments on GitHub. It provides student registration, private assignment
repositories, automated test feedback and collection of results.

The project was created as a free, flexible alternative to GitHub Classroom.
Instructors can create assignments for their own subjects by providing template
repositories, instructions and tests.

[Get started](https://github.com/finmath/finmath-classroom/blob/main/GETTING_STARTED.md)
· [Example assignment](demo.html)
· [Source on GitHub](https://github.com/finmath/finmath-classroom)

## Assignment workflow

1. The instructor configures a course and its assignments, including template
   repositories and test rubrics.
2. A student opens an assignment link, signs in with GitHub and confirms the
   required details. The service creates a private repository from the template
   and grants student and staff access.
3. The student implements the assignment and runs the tests locally.
   Pushing changes triggers a GitHub Actions workflow that runs the tests and
   publishes feedback.
4. The administration service downloads the feedback artifacts and stores the
   results. Instructors can list the latest results or inspect previous runs.

The [example assignment](demo.html) illustrates this workflow using a
quadratic-equation exercise.

## Functionality

Courses and assignments are configured through files and command-line tools.
The supplied feedback workflow uses Maven and JUnit. Optional Moodle LTI launches
connect registration to an existing course.

| Available now | Planned |
| --- | --- |
| Registration and private assignment repositories | Automatic submission capture and source snapshots |
| Shared test feedback and artifact collection | An isolated runner with instructor-controlled tests |
| SQLite history and administrative CLI | Authoritative final grades and Moodle grade return |

Student workflow results provide feedback. Authoritative final grading
requires an isolated instructor-controlled runner, which remains future work.

## Implementation and setup

The service uses **Java 17+, Maven, Spring MVC and SQLite**. Assignment repositories
keep their own builds and Git histories. GitHub provides sign-in, repository
hosting and Actions; the administration service runs on the instructor's server.

The [getting-started guide](https://github.com/finmath/finmath-classroom/blob/main/GETTING_STARTED.md)
includes an offline example and instructions for configuring a live assignment.
See also the [project motivation](motivation.html) and
[development notes](https://github.com/finmath/finmath-classroom/blob/main/docs/development.md).
