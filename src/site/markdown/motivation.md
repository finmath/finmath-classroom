# More time for the mathematical question

Programming exercises connect a mathematical method to its behavior on a
computer. The surrounding classroom tools should make that connection easier
to explore.

## A small lab can teach a lot

An exercise does not need to be a large software project. Implementing a
numerical method or a computational finance experiment raises useful questions:
Does the code match the definition? Which cases fail? How does floating-point
arithmetic affect the result?

finmath classroom supports focused practice. Learners start from an instructor's
template, work in their own private GitHub repository, and receive feedback from
Maven and JUnit. The next experiment is another change and test run away.

## Feedback while the question is still fresh

A passing check can confirm one part of an approach; a failure can point to a
missing case or mistaken assumption. Weighted test methods and whole-suite
rubrics let an instructor choose how to structure that feedback.

> A test result is a reason to investigate. Change something, observe the result,
> and explain what you learned.

The administration tools collect results into a local history, preserving the
difference between a failed test and feedback that was never received.

## Room for exploration, including AI

Students may work with or without AI tools, according to the course's rules.
An explanation or code suggestion is a starting point for investigation.
Students still need to inspect the implementation, choose useful test cases,
and explain why an answer is appropriate.

Quick feedback supports that conversation alongside mathematical reasoning,
discussion with the instructor, and the course's assessment policy.

## Administration you can understand

The service uses Java, Spring MVC, SQLite, and ordinary configuration files.
It provides a browser flow for learners and command-line tools for instructors.
Assignment repositories retain their own builds and Git histories.

Hosting the administration service gives a course control over its configuration
and stored registration data. GitHub supplies repository hosting, sign-in, and
Actions. A live installation needs the corresponding GitHub setup and a
maintained server.

GitHub Classroom was retired on August 28, 2026. This project offers a free,
self-hosted alternative focused on registration, private assignment repositories,
and test feedback. See [GitHub's retirement announcement](https://github.blog/changelog/2026-08-27-github-classroom-deprecated/).

## See the idea in practice

The [learner demo](demo.html) follows a fictional quadratic-equation exercise
from acceptance to feedback. To explore the implementation, read the
[architecture notes](https://github.com/finmath/finmath-classroom/blob/main/docs/development.md)
and [roadmap](https://github.com/finmath/finmath-classroom/blob/main/docs/roadmap.md),
or begin with the [getting-started guide](https://github.com/finmath/finmath-classroom/blob/main/GETTING_STARTED.md).
