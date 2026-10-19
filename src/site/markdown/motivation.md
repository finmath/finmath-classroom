# Motivation

The aim of finmath classroom is to provide a powerful, flexible and free
alternative to GitHub Classroom for administering programming assignments.

## Background

The project originated from coding assignments in mathematical finance. These
assignments use Java and JUnit tests to give students feedback on their
implementations. GitHub Classroom provided the registration and repository setup
for this workflow.

finmath classroom is a general-purpose solution for administering programming
assignments. Instructors define the subject and content through their own
template repositories, instructions and tests. Registration, repository creation
and result collection apply across subjects.

The retirement of GitHub Classroom prompted the development of an independent
administration service. The existing assignment repositories and tests could
continue to be used. See [GitHub's retirement announcement](https://github.blog/changelog/2026-08-27-github-classroom-deprecated/).

## Requirements

The main requirements were:

- Student registration through GitHub sign-in.
- Private assignment repositories created from instructor templates, with access
  for students and teaching staff.
- Automated feedback from Maven and JUnit, with configurable test scores.
- Automatic collection of results into a table, including previous runs.
- Control over course configuration and stored registration data.
- The ability to extend the service, for example with Moodle integration.

finmath classroom implements these functions in a Java application with a SQLite
database, a web interface for registration and command-line tools for
administration. Moodle LTI launches are supported; grade return remains planned.

## Independent administration

Running the administration service on an instructor-controlled server allows its
functionality to be adapted to the course. Course configuration, result collection
and integrations can be developed independently of GitHub Classroom.

GitHub continues to provide repository hosting, sign-in and Actions. Assignment
repositories retain their own builds and Git histories. The service uses Java,
Spring MVC and SQLite, with Maven for the build.

## Further development

The current implementation covers registration, repository creation and test
feedback collection. Student workflow results are not authoritative final grades.
Automatic submission capture and an isolated runner with instructor-controlled
tests are planned, together with Moodle grade return.

See the [example assignment](demo.html),
[development notes](https://github.com/finmath/finmath-classroom/blob/main/docs/development.md)
and [roadmap](https://github.com/finmath/finmath-classroom/blob/main/docs/roadmap.md).
