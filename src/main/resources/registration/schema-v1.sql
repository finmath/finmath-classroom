CREATE TABLE schema_version (version INTEGER NOT NULL);
INSERT INTO schema_version VALUES (1);
CREATE TABLE lectures (id TEXT PRIMARY KEY, title TEXT NOT NULL);
CREATE TABLE assignments (
 lecture_id TEXT NOT NULL REFERENCES lectures(id), id TEXT NOT NULL, definition TEXT NOT NULL,
 PRIMARY KEY (lecture_id, id)
);
CREATE TABLE students (
 lecture_id TEXT NOT NULL REFERENCES lectures(id), id TEXT NOT NULL, name TEXT NOT NULL,
 github_id INTEGER, github_login TEXT, identity_source TEXT NOT NULL, identity_subject TEXT,
 PRIMARY KEY (lecture_id, id), UNIQUE (lecture_id, github_id),
 UNIQUE (lecture_id, identity_source, identity_subject)
);
CREATE TABLE enrollments (
 lecture_id TEXT NOT NULL, assignment_id TEXT NOT NULL, student_id TEXT NOT NULL,
 accepted_name TEXT NOT NULL, identity_source TEXT NOT NULL, identity_subject TEXT NOT NULL,
 accepted_at TEXT NOT NULL, declaration_version TEXT NOT NULL, declaration TEXT NOT NULL,
 repository TEXT NOT NULL UNIQUE, repository_id INTEGER UNIQUE,
 status TEXT NOT NULL, problem TEXT, busy INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY (lecture_id, assignment_id, student_id),
 FOREIGN KEY (lecture_id, assignment_id) REFERENCES assignments(lecture_id, id),
 FOREIGN KEY (lecture_id, student_id) REFERENCES students(lecture_id, id)
);
