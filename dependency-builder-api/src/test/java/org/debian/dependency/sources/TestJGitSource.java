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

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.Charset;

import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.google.common.io.Files;

/** Test case for {@link JGitSource}. */
@RunWith(MockitoJUnitRunner.class)
public class TestJGitSource {
	private static final String WORK_BRANCH = "dependency-builder-maven-plugin";

	private static final String ORIGIN = "origin";

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@InjectMocks
	private JGitSource source = new JGitSource();

	@Mock
	private Logger logger;

	private File directory;
	private Git git;

	@Before
	public void setUp() throws Exception {
		directory = tempFolder.getRoot();
		git = Git.init()
				.setDirectory(directory)
				.call();

		new File(git.getRepository().getWorkTree(), "a").createNewFile();
		git.add()
				.addFilepattern(".")
				.call();

		git.commit()
				.setMessage("Initial commit")
				.call();
	}

	/** If we haven't been initialized yet, we should barf. */
	@Test(expected = IllegalStateException.class)
	public void testGetLocationBeforeInit() throws Exception {
		source.getLocation();
	}

	/** Once we have initialized the source, we should be able to get the location. */
	@Test
	public void testGetLocationAfterInit() throws Exception {
		source.initialize(directory, ORIGIN);

		assertEquals(directory.getAbsoluteFile(), source.getLocation().getAbsoluteFile());
	}

	/** When initializing an existing git repository, we should preserve its history. */
	@Test
	public void testInitGit() throws Exception {
		assertNull("Branch should not exist before first init", git.getRepository().resolve(WORK_BRANCH));

		source.initialize(directory, ORIGIN);

		assertNotNull("Branch must exist after init", git.getRepository().resolve(WORK_BRANCH));
		Ref headRef = git.getRepository().getRef(Constants.HEAD).getTarget();
		assertEquals("Head should point to the work branch", WORK_BRANCH, Repository.shortenRefName(headRef.getName()));
	}

	/** The second time a repository is setup and its on the correct branch, no changes should have to be made. */
	@Test
	public void testInitGitSecondClean() throws Exception {
		Ref workRef = git.branchCreate()
				.setName(WORK_BRANCH)
				.call();

		source.initialize(directory, ORIGIN);

		assertNotNull("Branch must exist after init", git.getRepository().resolve(WORK_BRANCH));
		Ref headRef = git.getRepository().getRef(Constants.HEAD).getTarget();
		assertEquals("Head should point to the work branch", WORK_BRANCH, Repository.shortenRefName(headRef.getName()));
		assertEquals("Work branch should not have moved", workRef.getObjectId(), headRef.getObjectId());
	}

	/** If there are modified files when initializing, hiccup until the user does something with them. */
	@Test(expected = IllegalStateException.class)
	public void testInitGitWithModifiedFiles() throws Exception {
		File changed = File.createTempFile("changed", "file", directory);
		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Changes for test")
				.call();

		Files.write("changed-data", changed, Charset.defaultCharset());
		source.initialize(directory, ORIGIN);
	}

	/** If there are pending changes when initializing, hiccup until the user does something with them. */
	@Test(expected = IllegalStateException.class)
	public void testInitGitWithChangedFiles() throws Exception {
		File changed = File.createTempFile("changed", "file", directory);
		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Changes for test")
				.call();

		Files.write("changed-data", changed, Charset.defaultCharset());
		git.add()
				.addFilepattern(".")
				.call();

		source.initialize(directory, ORIGIN);
	}

	/** If there are pending removed files when initializing, hiccup until the user does something with them. */
	@Test(expected = IllegalStateException.class)
	public void testInitGitWithRemovedFiles() throws Exception {
		File changed = File.createTempFile("changed", "file", directory);
		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Changes for test")
				.call();

		git.rm()
				.addFilepattern(changed.getName())
				.call();

		source.initialize(directory, ORIGIN);
	}

	/** If there are pending files to be added when initializing, hiccup until the user does something with them. */
	@Test(expected = IllegalStateException.class)
	public void testInitGitWithAddedFiles() throws Exception {
		File.createTempFile("changed", "file", directory);
		git.add()
				.addFilepattern(".")
				.call();

		source.initialize(directory, ORIGIN);
	}

	/** On first initialization of a repository with bad files, they should be removed. */
	@Test
	public void testInitGitWithBadFiles() throws Exception {
		File.createTempFile("SomeClass", ".class", directory);
		File.createTempFile("some-jar", ".jar", directory);

		File subdir = new File(directory, "subdir");
		subdir.mkdirs();
		File.createTempFile("AnotherClass", ".class", subdir);
		File.createTempFile("another-jar", ".jar", subdir);

		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Add bad files for test")
				.call();

		Ref oldHead = git.getRepository().getRef(Constants.HEAD).getLeaf();

		source.initialize(directory, ORIGIN);

		DirCache dirCache = git.getRepository().readDirCache();
		for (int i = 0; i < dirCache.getEntryCount(); ++i) {
			assertThat(dirCache.getEntry(i).getPathString(), not(endsWith(".jar")));
			assertThat(dirCache.getEntry(i).getPathString(), not(endsWith(".class")));
		}

		Ref oldBranch = git.getRepository().getRef(Repository.shortenRefName(oldHead.getName()));
		assertEquals("Keep history that jars were there", oldHead.getObjectId(), oldBranch.getObjectId());
	}

	/**
	 * If there are bad files on another branch, but the repository has been setup, i.e. bad files removed on the work branch,
	 * then we should ignore them.
	 */
	@Test
	public void testInitBadFilesSecond() throws Exception {
		File.createTempFile("SomeClass", ".class", directory);
		File.createTempFile("some-jar", ".jar", directory);

		File subdir = new File(directory, "subdir");
		subdir.mkdirs();
		File.createTempFile("AnotherClass", ".class", subdir);
		File.createTempFile("another-jar", ".jar", subdir);

		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Add bad files for test")
				.call();

		Ref oldHead = git.getRepository().getRef(Constants.HEAD).getLeaf();

		// setup work branch with bad files
		git.checkout()
				.setCreateBranch(true)
				.setName(WORK_BRANCH)
				.call();

		RmCommand removeCommand = git.rm();
		DirCache dirCache = git.getRepository().readDirCache();
		for (int i = 0; i < dirCache.getEntryCount(); ++i) {
			String path = dirCache.getEntry(i).getPathString();
			if (path.endsWith(".jar") || path.endsWith(".class")) {
				removeCommand.addFilepattern(path);
			}
		}
		removeCommand.call();
		git.commit()
				.setMessage("Remove bad jars for testing")
				.call();

		// perform test
		source.initialize(directory, ORIGIN);

		dirCache = git.getRepository().readDirCache();
		for (int i = 0; i < dirCache.getEntryCount(); ++i) {
			assertThat(dirCache.getEntry(i).getPathString(), not(endsWith(".jar")));
			assertThat(dirCache.getEntry(i).getPathString(), not(endsWith(".class")));
		}

		Ref oldBranch = git.getRepository().getRef(Repository.shortenRefName(oldHead.getName()));
		assertEquals("Keep history that jars were there", oldHead.getObjectId(), oldBranch.getObjectId());
	}

	/**
	 * If we've initialized a work branch (removed bad files), and they are there again on second init, we assume the user knows
	 * what they are doing.
	 */
	@Test
	public void testBadFilesAfterInit() throws Exception {
		File clazz1 = File.createTempFile("SomeClass", ".class", directory);
		File jar1 = File.createTempFile("some-jar", ".jar", directory);

		File subdir = new File(directory, "subdir");
		subdir.mkdirs();
		File clazz2 = File.createTempFile("AnotherClass", ".class", subdir);
		File jar2 = File.createTempFile("another-jar", ".jar", subdir);

		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Add bad files for test")
				.call();

		git.checkout()
				.setCreateBranch(true)
				.setName(WORK_BRANCH)
				.call();

		// perform test
		source.initialize(directory, ORIGIN);

		DirCache dirCache = git.getRepository().readDirCache();
		assertTrue("Class file put there after setup is vaild", dirCache.findEntry(Repository.stripWorkDir(directory, clazz1)) >= 0);
		assertTrue("Class file put there after setup is vaild", dirCache.findEntry(Repository.stripWorkDir(directory, clazz2)) >= 0);
		assertTrue("Jar file put there after setup is vaild", dirCache.findEntry(Repository.stripWorkDir(directory, jar1)) >= 0);
		assertTrue("Jar file put there after setup is vaild", dirCache.findEntry(Repository.stripWorkDir(directory, jar2)) >= 0);
	}

	/** An existing empty git repository is rare, but we should treat it as an existing repository. */
	@Test(expected = IllegalArgumentException.class)
	public void testInitExistingEmptyGit() throws Exception {
		File emptyDir = tempFolder.newFolder();
		Git.init()
				.setDirectory(emptyDir)
				.call();

		source.initialize(emptyDir, ORIGIN);
	}

	/** We need a working tree in order to operate on the source. */
	@Test(expected = IllegalArgumentException.class)
	public void testInitExistingBareGit() throws Exception {
		File bareRepo = tempFolder.newFolder();
		Git.init()
				.setBare(true)
				.setDirectory(bareRepo)
				.call();

		source.initialize(bareRepo, ORIGIN);
	}

	/**
	 * We should be able to create a git repo over top of other version control systems, public void testInitOtherVcs() throws
	 * Exception { }
	 *
	 * /** When cleaning the source, we must take into account all types of dirty file states.
	 */
	@Test
	public void testClean() throws Exception {
		Git git = Git.open(directory);

		File.createTempFile("unchanged", "file", directory);

		File subChanged = new File(new File(directory, "directory"), "changed");
		subChanged.getParentFile().mkdirs();
		subChanged.createNewFile();
		File changed = File.createTempFile("changed", "file", directory);

		git.add()
				.addFilepattern(".")
				.call();
		git.commit()
				.setMessage("Prepare for test")
				.call();

		source.initialize(directory, ORIGIN);

		Files.write("some data", subChanged, Charset.defaultCharset());
		Files.write("more data", changed, Charset.defaultCharset());
		File.createTempFile("new", "file", directory);

		File untrackedDir = new File(directory, "untracked");
		untrackedDir.mkdirs();
		File.createTempFile("another", "file", untrackedDir);

		source.clean();

		Status status = git.status().call();
		assertThat(status.getUntracked(), hasSize(0));
		assertThat(status.getUntrackedFolders(), hasSize(0));
		assertThat(status.getIgnoredNotInIndex(), hasSize(0));
		assertTrue("No changes", status.isClean());
	}
}
