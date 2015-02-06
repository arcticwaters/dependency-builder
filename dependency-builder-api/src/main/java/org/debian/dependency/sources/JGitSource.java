/*
 * Copyright 2015 Andrew Schurman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.debian.dependency.sources;

import java.io.File;
import java.io.IOException;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/** A git implementation of {@link Source} using JGit. */
@Component(role = Source.class, hint = "default", instantiationStrategy = "per-lookup")
public class JGitSource extends AbstractLogEnabled implements Source {
	private static final String COMMIT_MESSAGE_PREFIX = "[dependency-builder]";
	private static final String WORK_BRANCH = "dependency-builder-maven-plugin";
	private Git git;

	@Override
	public File getLocation() {
		if (git == null) {
			throw new IllegalStateException("Not initialized");
		}
		return git.getRepository().getWorkTree();
	}

	@Override
	public void initialize(final File location, final String origin) throws IOException {
		try {
			openRepo(location, origin);
			setupRepo();

			git.checkout()
					.setName(WORK_BRANCH)
					.call();

			git.clean()
					.setCleanDirectories(true)
					.setIgnore(false)
					.call();
		} catch (GitAPIException e) {
			throw new IOException("Unable to initialize", e);
		}
	}

	private void openRepo(final File location, final String origin) throws IOException, GitAPIException {
		try {
			git = Git.open(location);

			if (git.getRepository().isBare()) {
				throw new IllegalArgumentException("Cannot work with bare git repos, convert to a non-bare repo");
			} else if (git.getRepository().getRef(Constants.HEAD).getObjectId() == null) {
				throw new IllegalArgumentException("Repository must have " + Constants.HEAD + " setup and pointing to a valid commit");
			}
		} catch (RepositoryNotFoundException e) {
			getLogger().debug("No existing git repository found, creating from " + location);
			git = Git.init()
					.setDirectory(location)
					.setBare(false)
					.call();

			git.add()
					.addFilepattern(".")
					.call();

			// we don't want to track other SCM metadata files
			RmCommand removeAction = git.rm();
			for (String pattern : FileUtils.getDefaultExcludes()) {
				if (pattern.startsWith(".git")) {
					continue;
				}
				removeAction.addFilepattern(pattern);
			}
			removeAction.call();

			git.commit()
					.setMessage(COMMIT_MESSAGE_PREFIX + " Import upstream from " + origin)
					.call();
		}
	}

	private void setupRepo() throws GitAPIException, IOException {
		if (git.status().call().hasUncommittedChanges()) {
			throw new IllegalStateException("Uncommitted changes detected in tree. Remove before continuing.");
		}

		Ref branchRef = null;
		for (Ref ref : git.branchList().call()) {
			if (WORK_BRANCH.equals(Repository.shortenRefName(ref.getName()))) {
				branchRef = ref;
				break;
			}
		}

		// assume its setup correctly if its there
		if (branchRef != null) {
			return;
		}

		// assume the current branch is the correct one
		Ref headRef = git.getRepository().getRef(Constants.HEAD).getLeaf();
		try {
			// detach head so we don't modify any existing branches
			git.checkout()
					.setName(ObjectId.toString(headRef.getObjectId()))
					.call();

			removeFilesEndsWith(".class", COMMIT_MESSAGE_PREFIX + " Removing class files");
			RevCommit newRef = removeFilesEndsWith(".jar", COMMIT_MESSAGE_PREFIX + " Removing jar files");

			// now all automated setup routines have been accomplished
			git.branchCreate()
					.setName(WORK_BRANCH)
					.setStartPoint(newRef)
					.call();
		} finally {
			// failsafe
			git.getRepository().updateRef(Constants.HEAD)
					.link(headRef.getName());
		}
	}

	private RevCommit removeFilesEndsWith(final String endsWith, final String message) throws IOException, GitAPIException {
		boolean containsFiles = false;
		RmCommand removeCommand = git.rm();
		DirCache dirCache = git.getRepository().readDirCache();
		for (int i = 0; i < dirCache.getEntryCount(); ++i) {
			String path = dirCache.getEntry(i).getPathString();
			if (path.endsWith(endsWith)) {
				removeCommand.addFilepattern(path);
				containsFiles = true;
			}
		}

		if (containsFiles) {
			removeCommand.call();

			return git.commit()
					.setMessage(message)
					.call();
		}

		RevWalk walk = new RevWalk(git.getRepository());
		try {
			return walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
		} finally {
			walk.release();
		}
	}

	@Override
	public void clean() throws IOException {
		try {
			git.reset()
					.setMode(ResetType.HARD)
					.call();

			git.clean()
					.setDryRun(false)
					.setCleanDirectories(true)
					.call();
		} catch (GitAPIException e) {
			throw new IOException(e);
		}
	}
}
