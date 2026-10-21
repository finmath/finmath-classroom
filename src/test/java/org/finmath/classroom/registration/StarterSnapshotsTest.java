package org.finmath.classroom.registration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StarterSnapshotsTest {

	private static final String KEY = "a".repeat(64);
	private static final byte[] BINARY = {0, 1, 2, (byte) 255, 0};
	@TempDir Path directory;

	@Test
	void initialStudentsShareOneRootWithExactFilesAndNoOriginalHistory() throws IOException {
		try(Repository source = repository("source"); Repository first = repository("first"); Repository second = repository("second")) {
			ObjectId originalRoot = commit(source, null, "old", 1);
			ObjectId originalTip = commit(source, originalRoot, "starter", 2);
			StarterSnapshots snapshots = snapshots();
			String snapshot = snapshots.publish(KEY, source.getDirectory().toURI(), "main", 123);
			snapshots.initialize(KEY, first.getDirectory().toURI(), "main", snapshot);
			snapshots.initialize(KEY, second.getDirectory().toURI(), "main", snapshot);
			assertEquals(snapshot, first.resolve("refs/heads/main").name());
			assertEquals(snapshot, second.resolve("refs/heads/main").name());
			try(RevWalk walk = new RevWalk(first)) {
				RevCommit imported = walk.parseCommit(ObjectId.fromString(snapshot));
				assertEquals(0, imported.getParentCount());
				assertEquals("Classroom starter", imported.getAuthorIdent().getName());
				assertTrue(imported.getFullMessage().contains("Classroom-Source-Repository-Id: 123"));
				assertTrue(imported.getFullMessage().contains("Classroom-Source-Commit: " + originalTip.name()));
				assertEquals(2, imported.getCommitTime());
				assertEquals(List.of(snapshot), history(first));
				assertFalse(first.getObjectDatabase().has(originalRoot));
				assertFalse(first.getObjectDatabase().has(originalTip));
				assertTree(first, imported, "starter");
				assertTree(second, imported, "starter");
			}
			assertEquals(originalTip, source.resolve("refs/heads/main"));
			assertNull(source.exactRef("refs/classroom/published"));
			assertNull(first.exactRef("refs/classroom/source"));
			assertNull(first.exactRef("refs/classroom/published"));
		}
	}

	@Test
	void sourceUpdatesBecomeChildrenAndRestartReusesPublicationForLateStudents() throws IOException {
		try(Repository source = repository("source"); Repository early = repository("early"); Repository late = repository("late");
				Repository resumed = repository("resumed")) {
			ObjectId original = commit(source, null, "starter", 1);
			String first = snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			snapshots().initialize(KEY, early.getDirectory().toURI(), "main", first);
			ObjectId changed = commit(source, original, "corrected", 2);
			String second = snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			assertEquals(second, snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123));
			snapshots().initialize(KEY, late.getDirectory().toURI(), "main", second);
			// An acceptance interrupted before its push retains the snapshot it originally reserved.
			snapshots().initialize(KEY, resumed.getDirectory().toURI(), "main", first);
			assertEquals(List.of(second, first), history(late));
			assertEquals(List.of(first), history(early));
			assertEquals(List.of(first), history(resumed));
			try(RevWalk walk = new RevWalk(late)) {
				RevCommit imported = walk.parseCommit(ObjectId.fromString(second));
				assertEquals(first, imported.getParent(0).name());
				assertTree(late, imported, "corrected");
			}
			assertFalse(late.getObjectDatabase().has(original));
			assertFalse(late.getObjectDatabase().has(changed));
			assertEquals(changed, source.resolve("refs/heads/main"));
		}
	}

	@Test
	void concurrentFirstPublishersInSeparateInstancesConvergeOnOneSnapshot() throws Exception {
		try(Repository source = repository("source")) {
			commit(source, null, "starter", 1);
			ExecutorService workers = Executors.newFixedThreadPool(2);
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			try {
				var task = (java.util.concurrent.Callable<String>) () -> {
					ready.countDown();
					if(!start.await(10, TimeUnit.SECONDS)) throw new IOException("Concurrent publication did not start");
					return snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
				};
				var first = workers.submit(task);
				var second = workers.submit(task);
				assertTrue(ready.await(10, TimeUnit.SECONDS));
				start.countDown();
				assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
				try(Repository stored = openPublication(); RevWalk walk = new RevWalk(stored)) {
					assertEquals(0, walk.parseCommit(stored.resolve("refs/classroom/published")).getParentCount());
				}
			} finally {
				start.countDown();
				workers.shutdownNow();
			}
		}
	}

	@Test
	void sourceCommitWithSameTreeStillPublishesANewChild() throws IOException {
		try(Repository source = repository("source"); Repository student = repository("student")) {
			ObjectId original = commit(source, null, "starter", 1);
			String first = snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			commit(source, original, "starter", 2);
			String second = snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			assertFalse(first.equals(second));
			snapshots().initialize(KEY, student.getDirectory().toURI(), "main", second);
			assertEquals(List.of(second, first), history(student));
		}
	}

	@Test
	void exactInitializationRetrySucceedsButStudentWorkAndOtherBranchesAreNeverOverwritten() throws IOException {
		try(Repository source = repository("source"); Repository student = repository("student"); Repository other = repository("other")) {
			commit(source, null, "starter", 1);
			String snapshot = snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			snapshots().initialize(KEY, student.getDirectory().toURI(), "main", snapshot);
			snapshots().initialize(KEY, student.getDirectory().toURI(), "main", snapshot);
			ObjectId work = commit(student, ObjectId.fromString(snapshot), "solution", 3);
			assertThrows(IOException.class, () -> snapshots().initialize(KEY, student.getDirectory().toURI(), "main", snapshot));
			assertEquals(work, student.resolve("refs/heads/main"));
			snapshots().initialize(KEY, other.getDirectory().toURI(), "main", snapshot);
			update(other, "refs/heads/extra", ObjectId.fromString(snapshot));
			assertThrows(IOException.class, () -> snapshots().initialize(KEY, other.getDirectory().toURI(), "main", snapshot));
			assertEquals(ObjectId.fromString(snapshot), other.resolve("refs/heads/extra"));
		}
	}

	@Test
	void failedFetchDoesNotAdvancePublicationAndSourceIdentityCannotChange() throws IOException {
		try(Repository source = repository("source"); Repository replacement = repository("replacement")) {
			commit(source, null, "starter", 1);
			String first = snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			RefUpdate remove = source.updateRef("refs/heads/main");
			remove.setForceUpdate(true);
			assertEquals(RefUpdate.Result.FORCED, remove.delete());
			assertThrows(IOException.class, () -> snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123));
			try(Repository stored = openPublication()) {
				assertEquals(first, stored.resolve("refs/classroom/published").name());
			}
			assertThrows(IOException.class, () -> snapshots().publish(KEY, source.getDirectory().toURI(), "main", 456));
			assertThrows(IOException.class, () -> snapshots().publish(KEY, source.getDirectory().toURI(), "different", 123));
			assertThrows(IOException.class, () -> snapshots().publish(KEY, replacement.getDirectory().toURI(), "main", 123));
		}
	}

	@Test
	void credentialsAreNotPersistedAndSourceCommitsCannotBePushedAsSnapshots() throws IOException {
		try(Repository source = repository("source"); Repository student = repository("student")) {
			ObjectId original = commit(source, null, "starter", 1);
			StarterSnapshots snapshots = new StarterSnapshots(directory.resolve("snapshots"),
					new UsernamePasswordCredentialsProvider("x-access-token", "secret-token-value"));
			String snapshot = snapshots.publish(KEY, source.getDirectory().toURI(), "main", 123);
			try(Repository stored = openPublication()) {
				String config = stored.getConfig().toText();
				assertFalse(config.contains("secret-token-value"));
				assertFalse(config.contains("x-access-token"));
				assertEquals("false", stored.getConfig().getString("http", null, "followRedirects"));
			}
			assertThrows(IOException.class, () -> snapshots.initialize(KEY, student.getDirectory().toURI(), "main", original.name()));
			assertNull(student.resolve("refs/heads/main"));
			snapshots.initialize(KEY, student.getDirectory().toURI(), "main", snapshot);
		}
	}

	@Test
	void rejectsInvalidKeysBranchesAndCredentialBearingAddresses() throws IOException {
		try(Repository source = repository("source")) {
			assertThrows(IOException.class, () -> snapshots().publish("../outside", source.getDirectory().toURI(), "main", 123));
			assertThrows(IOException.class, () -> snapshots().publish(KEY, source.getDirectory().toURI(), "../main", 123));
			assertThrows(IOException.class, () -> snapshots().publish(KEY,
					java.net.URI.create("https://token:secret@example.invalid/repo.git"), "main", 123));
		}
	}

	@Test
	void rejectsSymlinkStorageAndPublicationsAndKeepsStoragePrivate() throws IOException {
		try(Repository source = repository("source")) {
			commit(source, null, "starter", 1);
			Path storage = directory.resolve("snapshots");
			Files.createDirectories(storage);
			Files.createSymbolicLink(storage.resolve(KEY + ".git"), source.getDirectory().toPath());
			assertThrows(IOException.class, () -> snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123));
			assertNull(source.exactRef("refs/classroom/published"));
			Path linked = directory.resolve("linked-snapshots");
			Files.createSymbolicLink(linked, storage);
			assertThrows(IOException.class, () -> new StarterSnapshots(linked, null)
					.publish(KEY, source.getDirectory().toURI(), "main", 123));
			Files.delete(storage.resolve(KEY + ".git"));
			snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123);
			if(Files.getFileStore(storage).supportsFileAttributeView("posix")) {
				assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(storage));
				assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(storage.resolve(KEY + ".git")));
			}
		}
	}

	@Test
	void rejectsAStoredRepositoryWithAWorkingTree() throws IOException {
		try(Repository source = repository("source")) {
			commit(source, null, "starter", 1);
			Path storage = directory.resolve("snapshots");
			Files.createDirectories(storage);
			try(Repository nonbare = new FileRepositoryBuilder().setGitDir(storage.resolve(KEY + ".git").toFile())
					.setWorkTree(directory.resolve("working-tree").toFile()).build()) {
				nonbare.create(false);
				assertThrows(IOException.class, () -> snapshots().publish(KEY, source.getDirectory().toURI(), "main", 123));
			}
		}
	}

	private StarterSnapshots snapshots() {
		return new StarterSnapshots(directory.resolve("snapshots"), null);
	}

	private Repository openPublication() throws IOException {
		return FileRepositoryBuilder.create(directory.resolve("snapshots").resolve(KEY + ".git").toFile());
	}

	private Repository repository(String name) throws IOException {
		Repository repository = FileRepositoryBuilder.create(directory.resolve(name + ".git").toFile());
		repository.create(true);
		return repository;
	}

	private static ObjectId commit(Repository repository, ObjectId parent, String text, int time) throws IOException {
		try(ObjectInserter inserter = repository.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			tree.append("binary", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, BINARY));
			tree.append("executable", FileMode.EXECUTABLE_FILE, inserter.insert(Constants.OBJ_BLOB, "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)));
			tree.append("link", FileMode.SYMLINK, inserter.insert(Constants.OBJ_BLOB, "marker".getBytes(StandardCharsets.UTF_8)));
			tree.append("marker", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8)));
			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(inserter.insert(tree));
			if(parent != null) commit.setParentId(parent);
			PersonIdent author = new PersonIdent("Original teacher", "teacher@example.invalid", Instant.ofEpochSecond(time), ZoneOffset.UTC);
			commit.setAuthor(author);
			commit.setCommitter(author);
			commit.setMessage("Original source commit " + time);
			ObjectId id = inserter.insert(commit);
			inserter.flush();
			update(repository, "refs/heads/main", id);
			return id;
		}
	}

	private static void update(Repository repository, String name, ObjectId id) throws IOException {
		RefUpdate update = repository.updateRef(name);
		update.setNewObjectId(id);
		RefUpdate.Result result = update.update();
		assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD);
	}

	private static List<String> history(Repository repository) throws IOException {
		List<String> result = new ArrayList<>();
		try(RevWalk walk = new RevWalk(repository)) {
			walk.markStart(walk.parseCommit(repository.resolve("refs/heads/main")));
			for(RevCommit commit : walk) result.add(commit.name());
		}
		return result;
	}

	private static void assertTree(Repository repository, RevCommit commit, String text) throws IOException {
		assertEntry(repository, commit, "binary", FileMode.REGULAR_FILE, BINARY);
		assertEntry(repository, commit, "executable", FileMode.EXECUTABLE_FILE, "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
		assertEntry(repository, commit, "link", FileMode.SYMLINK, "marker".getBytes(StandardCharsets.UTF_8));
		assertEntry(repository, commit, "marker", FileMode.REGULAR_FILE, text.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertEntry(Repository repository, RevCommit commit, String name, FileMode mode, byte[] bytes) throws IOException {
		try(TreeWalk tree = TreeWalk.forPath(repository, name, commit.getTree())) {
			assertEquals(mode, tree.getFileMode(0));
			assertArrayEquals(bytes, repository.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes());
		}
	}
}
