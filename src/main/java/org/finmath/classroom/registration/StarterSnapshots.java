package org.finmath.classroom.registration;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

/**
 * Maintains a separate starter lineage, without exposing the source repository's
 * original commits in student repositories. Nothing is ever pushed to the source.
 */
public final class StarterSnapshots {

	private static final String SOURCE_REF = "refs/classroom/source";
	private static final String PUBLISHED_REF = "refs/classroom/published";
	private static final String SOURCE_COMMIT_FOOTER = "Classroom-Source-Commit";
	private static final ConcurrentMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
	private final Path directory;
	private final CredentialsProvider credentials;

	public StarterSnapshots(Path directory, CredentialsProvider credentials) {
		this.directory = directory.toAbsolutePath().normalize();
		this.credentials = credentials;
	}

	/** Publishes the current source tree as the root or next commit of a starter lineage. */
	public String publish(String publicationKey, URI source, String branch, long sourceRepositoryId) throws IOException {
		Path path = publicationPath(publicationKey);
		validateBranch(branch);
		validateUri(source);
		if(sourceRepositoryId <= 0) throw new IOException("Invalid starter source repository identity");
		synchronized(LOCKS.computeIfAbsent(path, ignored -> new Object())) {
			prepareDirectory();
			try(Repository repository = open(path, true)) {
				pinSource(repository, source, branch, sourceRepositoryId);
				try(Transport transport = transport(repository, source)) {
					transport.setTagOpt(TagOpt.NO_TAGS);
					transport.fetch(NullProgressMonitor.INSTANCE,
							List.of(new RefSpec("+refs/heads/" + branch + ":" + SOURCE_REF)));
				} catch(IOException | RuntimeException exception) {
					throw new IOException("Unable to fetch the starter source");
				}
				Ref sourceRef = repository.exactRef(SOURCE_REF);
				if(sourceRef == null || sourceRef.getObjectId() == null) {
					throw new IOException("The starter source branch is missing");
				}
				Ref publishedRef = repository.exactRef(PUBLISHED_REF);
				ObjectId previous = publishedRef == null ? null : publishedRef.getObjectId();
				try(RevWalk walk = new RevWalk(repository); ObjectInserter inserter = repository.newObjectInserter()) {
					RevCommit sourceCommit = walk.parseCommit(sourceRef.getObjectId());
					if(previous != null) {
						RevCommit published = walk.parseCommit(previous);
						if(published.getFooterLines(SOURCE_COMMIT_FOOTER).equals(List.of(sourceCommit.name()))) {
							return previous.name();
						}
					}
					PersonIdent identity = new PersonIdent("Classroom starter", "classroom@localhost",
							Instant.ofEpochSecond(sourceCommit.getCommitTime()), ZoneOffset.UTC);
					CommitBuilder commit = new CommitBuilder();
					commit.setTreeId(sourceCommit.getTree());
					if(previous != null) commit.setParentId(previous);
					commit.setAuthor(identity);
					commit.setCommitter(identity);
					commit.setMessage("Classroom starter snapshot\n\n"
							+ "Classroom-Source-Repository: " + source.toASCIIString() + "\n"
							+ "Classroom-Source-Repository-Id: " + sourceRepositoryId + "\n"
							+ "Classroom-Source-Branch: " + branch + "\n"
							+ SOURCE_COMMIT_FOOTER + ": " + sourceCommit.name() + "\n");
					ObjectId snapshot = inserter.insert(commit);
					inserter.flush();
					RefUpdate update = repository.updateRef(PUBLISHED_REF);
					update.setExpectedOldObjectId(previous == null ? ObjectId.zeroId() : previous);
					update.setNewObjectId(snapshot);
					RefUpdate.Result result = update.update();
					if(result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD
							&& result != RefUpdate.Result.NO_CHANGE) {
						throw new IOException("Unable to save the starter publication");
					}
					return snapshot.name();
				}
			}
		}
	}

	/**
	 * Initializes an empty destination with a known published snapshot. Exact retries
	 * succeed; any unexpected remote content is rejected without overwriting it.
	 */
	public void initialize(String publicationKey, URI destination, String branch, String snapshotCommit) throws IOException {
		Path path = publicationPath(publicationKey);
		validateBranch(branch);
		validateUri(destination);
		if(snapshotCommit == null || !ObjectId.isId(snapshotCommit)) throw new IOException("Invalid starter snapshot");
		synchronized(LOCKS.computeIfAbsent(path, ignored -> new Object())) {
			prepareDirectory();
			try(Repository repository = open(path, false); RevWalk walk = new RevWalk(repository)) {
				Ref published = repository.exactRef(PUBLISHED_REF);
				if(published == null || published.getObjectId() == null) throw new IOException("The starter publication is missing");
				RevCommit snapshot = walk.parseCommit(ObjectId.fromString(snapshotCommit));
				if(!walk.isMergedInto(snapshot, walk.parseCommit(published.getObjectId()))) {
					throw new IOException("The requested commit is not a published starter snapshot");
				}
				String target = Constants.R_HEADS + branch;
				try(Transport transport = transport(repository, destination); PushConnection connection = transport.openPush()) {
					Map<String, Ref> advertised = connection.getRefsMap();
					Ref existing = advertised.get(target);
					for(Ref ref : advertised.values()) {
						if(ref.getObjectId() == null || ref.getObjectId().equals(ObjectId.zeroId())) continue;
						if((ref.getName().equals(target) || ref.getName().equals(Constants.HEAD))
								&& ref.getObjectId().equals(snapshot)) continue;
						throw new IOException("The destination repository already contains unexpected content");
					}
					if(existing != null && snapshot.equals(existing.getObjectId())) return;
					RemoteRefUpdate update = new RemoteRefUpdate(repository, snapshot.name(), target,
							false, null, ObjectId.zeroId());
					connection.push(NullProgressMonitor.INSTANCE, Map.of(target, update));
					if(update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
						throw new IOException("The destination starter branch could not be initialized");
					}
				} catch(IOException | RuntimeException exception) {
					throw new IOException("Unable to initialize the student repository safely");
				}
			}
		}
	}

	private Path publicationPath(String key) throws IOException {
		if(key == null || !key.matches("[0-9a-f]{64}")) throw new IOException("Invalid starter publication key");
		return directory.resolve(key + ".git");
	}

	private static void validateBranch(String branch) throws IOException {
		if(branch == null || !Repository.isValidRefName(Constants.R_HEADS + branch)) {
			throw new IOException("Invalid starter branch");
		}
	}

	private static void validateUri(URI uri) throws IOException {
		if(uri == null || (!"https".equals(uri.getScheme()) && !"file".equals(uri.getScheme()))
				|| uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
				|| ("file".equals(uri.getScheme()) && uri.getRawAuthority() != null)) {
			throw new IOException("Invalid starter repository address");
		}
	}

	private void prepareDirectory() throws IOException {
		if(Files.isSymbolicLink(directory)) throw new IOException("The starter storage directory must not be a symbolic link");
		Files.createDirectories(directory);
		if(!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid starter storage directory");
		if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
		}
	}

	private static Repository open(Path path, boolean create) throws IOException {
		if(Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
			throw new IOException("Invalid starter publication directory");
		}
		if(create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			if(Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
				Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			} else {
				Files.createDirectory(path);
			}
		}
		// Do not read GIT_* environment overrides for this administrator-owned store.
		Repository repository = new FileRepositoryBuilder().setGitDir(path.toFile()).build();
		if(!repository.getObjectDatabase().exists()) {
			if(!create) {
				repository.close();
				throw new IOException("The starter publication is missing");
			}
			try {
				repository.create(true);
			} catch(IOException exception) {
				repository.close();
				throw exception;
			}
		}
		if(!repository.isBare() || !repository.getConfig().getBoolean("core", null, "bare", false)) {
			repository.close();
			throw new IOException("Starter publications require a bare Git repository");
		}
		return repository;
	}

	private static void pinSource(Repository repository, URI source, String branch, long id) throws IOException {
		StoredConfig config = repository.getConfig();
		String existingSource = config.getString("classroom", "source", "repository");
		if(existingSource != null && (!existingSource.equals(source.toASCIIString())
				|| !Long.toString(id).equals(config.getString("classroom", "source", "repositoryId"))
				|| !branch.equals(config.getString("classroom", "source", "branch")))) {
			throw new IOException("The starter source identity has changed for this publication");
		}
		if(existingSource == null) {
			config.setString("classroom", "source", "repository", source.toASCIIString());
			config.setString("classroom", "source", "repositoryId", Long.toString(id));
			config.setString("classroom", "source", "branch", branch);
			config.setString("http", null, "followRedirects", "false");
			config.save();
		}
	}

	private Transport transport(Repository repository, URI uri) throws IOException {
		Transport transport;
		try {
			transport = Transport.open(repository, new URIish(uri.toASCIIString()));
		} catch(URISyntaxException exception) {
			throw new IOException("Invalid starter repository address");
		}
		transport.setTimeout(30);
		transport.setCredentialsProvider(credentials);
		// These defaults keep file transports in JGit's internal implementation.
		transport.setOptionUploadPack("git-upload-pack");
		transport.setOptionReceivePack("git-receive-pack");
		transport.setCheckFetchedObjects(true);
		return transport;
	}
}
