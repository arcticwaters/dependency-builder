/*
 * Copyright 2014 Andrew Schurman
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
package org.debian.dependency.builders;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Builds an Ant project using an embedded version of Ant.
 */
@Component(role = SourceBuilder.class, hint = "ant")
public class EmbeddedAntBuilder extends AbstractBuildFileSourceBuilder implements SourceBuilder {
	private static final String COMMIT_MESSAGE_PREFIX = "[build-dependency-maven-plugin]";
	private static final String BUILD_INCLUDES = "**/build.xml";
	/** Minimum percentage as reported by git to denote the same file. */
	private static final int MIN_SIMILARITY = 95;
	private static final int SIMILARITY_SAME = 100;

	@Requirement
	private ArtifactInstaller installer;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public Set<Artifact> build(final MavenProject project, final Git repo, final File localRepository) throws ArtifactBuildException {
		try {
			ArtifactRepository targetRepository = repositorySystem.createLocalRepository(localRepository);

			removeClassFiles(repo);
			removeJarFiles(repo, project, targetRepository);

			FileWatcher jarWatcher = new FileWatcher(repo.getRepository().getWorkTree(), "**/*.jar", null);
			FileWatcher pomWatcher = new FileWatcher(repo.getRepository().getWorkTree(), "**/*.xml,**/*.pom", null);
			jarWatcher.watch();
			pomWatcher.watch();

			List<File> buildFiles = findBuildFiles(repo.getRepository().getWorkTree());
			Project antProject = new Project();
			ProjectHelper.configureProject(antProject, buildFiles.get(0));
			antProject.init();

			antProject.setBaseDir(buildFiles.get(0).getParentFile());
			antProject.executeTarget(antProject.getDefaultTarget());

			jarWatcher.watch();
			pomWatcher.watch();

			File builtArtifact = findArtifactFile(project, repo, jarWatcher);
			Artifact artifact = repositorySystem.createArtifact(project.getGroupId(), project.getArtifactId(),
					project.getVersion(), project.getPackaging());
			installer.install(builtArtifact, artifact, targetRepository);

			File pom = findPomFile(project, repo, pomWatcher);
			installer.install(pom, project.getArtifact(), targetRepository);

			artifact.setFile(null); // we've installed the artifact
			return Collections.singleton(artifact);
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		} catch (InvalidRepositoryException e) {
			throw new ArtifactBuildException(e);
		} catch (ArtifactInstallationException e) {
			throw new ArtifactBuildException("Unable to install artifact", e);
		} catch (GitAPIException e) {
			throw new ArtifactBuildException("Error processing local repository", e);
		}
	}

	private File findArtifactFile(final MavenProject project, final Git repo, final FileWatcher watcher) throws IOException, GitAPIException {
		for (String file : watcher) {
			File builtFile = new File(file);
			if (jarSimilarity(repo, project.getArtifact().getFile(), builtFile) > MIN_SIMILARITY) {
				return builtFile;
			}
		}
		throw new IllegalStateException("Cannot find built jar file for " + project);
	}

	private File findPomFile(final MavenProject project, final Git repo, final FileWatcher watcher) throws GitAPIException, IOException {
		for (String file : watcher) {
			repo.add().addFilepattern(file).call();
			try {
				// copying files is not ideal here, but allows leverage of the git diff engine
				File pomFile = new File(file);
				FileUtils.copyFile(project.getFile(), pomFile);
				List<DiffEntry> diff = repo.diff().setCached(true).call();

				if (diff.isEmpty() || diff.get(0).getScore() > MIN_SIMILARITY) {
					return pomFile;
				}
			} finally {
				repo.checkout().addPath(file).call();
				repo.reset().addPath(file).setMode(ResetType.SOFT).call();
			}
		}
		throw new IllegalStateException("Cannot find built pom file for " + project);
	}

	private List<String> findFilesToProcess(final Git repo, final String includes, final String excludes) throws IOException,
			GitAPIException {
		List<String> processFiles = FileUtils.getFileNames(repo.getRepository().getWorkTree(), includes, excludes, true);
		if (processFiles.isEmpty()) {
			return processFiles;
		}

		// check to see if we've done this before
		LogCommand logCommand = repo.log();
		for (String processFile : processFiles) {
			logCommand.addPath(processFile);
		}

		for (RevCommit commit : logCommand.call()) {
			if (commit.getShortMessage().startsWith(COMMIT_MESSAGE_PREFIX)) {
				return Collections.emptyList();
			}
		}

		return processFiles;
	}

	private void removeJarFiles(final Git repo, final MavenProject project, final ArtifactRepository artifactRepository) throws IOException,
			GitAPIException {
		List<String> jarFiles = findFilesToProcess(repo, "*.jar", null);
		if (jarFiles.isEmpty()) {
			return;
		}

		List<String> jarsToRemove = new ArrayList<String>();
		AddCommand addCommand = repo.add();
		for (String jarFile : jarFiles) {
			boolean foundDependency = false;
			for (Dependency dep : project.getDependencies()) {
				Artifact depArtifact = repositorySystem.createArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
						Artifact.SCOPE_RUNTIME, "jar");
				resolveArtifact(depArtifact, artifactRepository);

				File jarFileFile = new File(jarFile);
				if (jarSimilarity(repo, jarFileFile, depArtifact.getFile()) > MIN_SIMILARITY) {
					FileUtils.copyFile(depArtifact.getFile(), jarFileFile);
					addCommand.addFilepattern(jarFile);
					foundDependency = true;
					break;
				}
			}

			if (!foundDependency) {
				jarsToRemove.add(jarFile);
			}
		}

		if (jarsToRemove.size() < jarFiles.size()) {
			addCommand.call();

			repo.commit()
				.setMessage(COMMIT_MESSAGE_PREFIX + " replacing jar files with open source ones")
				.call();
		}

		if (jarsToRemove.isEmpty()) {
			return;
		}

		// now deal with jar files which we don't know where they came from
		RmCommand rmCommand = repo.rm();
		for (String jarFile : jarsToRemove) {
			rmCommand.addFilepattern(jarFile);
		}
		rmCommand.call();

		repo.commit()
			.setMessage(COMMIT_MESSAGE_PREFIX + " removing unknown jar files")
			.call();
	}

	private int jarSimilarity(final Git repo, final File jarFile1, final File jarFile2) throws IOException, GitAPIException {
		File fileList1 = createFileList(repo, jarFile1);
		File fileList2 = createFileList(repo, jarFile2);

		String relative1 = repo.getRepository().getWorkTree().toURI().relativize(fileList1.toURI()).getPath();
		repo.add().addFilepattern(relative1).call();
		try {
			FileUtils.copyFile(fileList2, fileList1);
			List<DiffEntry> diff = repo.diff().setCached(true).call();

			if (diff.isEmpty()) {
				return SIMILARITY_SAME;
			}
			return diff.get(0).getScore();
		} finally {
			repo.rm().addFilepattern(relative1).call();
		}
	}

	private File createFileList(final Git repo, final File file) throws IOException {
		File fileList = FileUtils.createTempFile("filelist", "txt", repo.getRepository().getWorkTree());
		PrintWriter writer = null;
		JarFile jarFile = null;
		try {
			jarFile = new JarFile(file);
			writer = new PrintWriter(fileList);

			List<String> entries = new ArrayList<String>();
			for (Enumeration<JarEntry> iter = jarFile.entries(); iter.hasMoreElements();) {
				JarEntry entry = iter.nextElement();
				entries.add(entry.getName());
			}

			Collections.sort(entries);
			for (String entry : entries) {
				writer.println(entry);
			}

			return fileList;
		} finally {
			try {
				IOUtil.close(writer);
				if (jarFile != null) {
					jarFile.close();
				}
			} catch (IOException e) {
				getLogger().debug("Ignoring exception closing file", e);
			}
		}
	}

	private Artifact resolveArtifact(final Artifact projectArtifact, final ArtifactRepository artifactRepository) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setArtifact(projectArtifact)
				.setResolveRoot(true)
				.setLocalRepository(artifactRepository)
				.setOffline(true); // if we got here, we must already have the artifact pom locally

		ArtifactResolutionResult result = repositorySystem.resolve(request);
		return result.getArtifacts().iterator().next();
	}

	private void removeClassFiles(final Git repo) throws IOException, GitAPIException {
		List<String> clazzFiles = findFilesToProcess(repo, "*.class", null);
		if (clazzFiles.isEmpty()) {
			return;
		}

		RmCommand rmCommand = repo.rm();
		for (String clazzFile : clazzFiles) {
			FileUtils.forceDelete(clazzFile);
			rmCommand.addFilepattern(clazzFile);
		}
		rmCommand.call();

		repo.commit()
			.setMessage(COMMIT_MESSAGE_PREFIX + " removing class files")
			.call();
	}

	@Override
	public boolean canBuild(final MavenProject project, final File directory) throws IOException {
		// there is no general rule for maven artifacts in ant projects (and this is just a hint)
		return false;
	}

	@Override
	protected List<String> getIncludes() {
		return Arrays.asList(BUILD_INCLUDES);
	}

	/** A way to keep track of new files without relying on file system timestamps. */
	private static class FileWatcher implements Iterable<String> {
		private Set<String> previousFiles = new HashSet<String>();
		private Set<String> files = new HashSet<String>();
		private final File basedir;
		private final String includes;
		private final String excludes;

		public FileWatcher(final File basedir, final String includes, final String excludes) {
			this.basedir = basedir;
			this.includes = includes;
			this.excludes = excludes;
		}

		public void watch() throws IOException {
			previousFiles = files;
			files = new HashSet<String>(FileUtils.getFileNames(basedir, includes, excludes, true));
		}

		public Set<String> getChanges() {
			Set<String> result = new HashSet<String>(files);
			result.removeAll(previousFiles);
			return result;
		}

		@Override
		public Iterator<String> iterator() {
			return new ChainedIterator<String>(getChanges().iterator(), files.iterator());
		}
	}

	private static class ChainedIterator<T> implements Iterator<T> {
		private final List<Iterator<T>> iters = new ArrayList<Iterator<T>>();
		private Iterator<T> current;

		public ChainedIterator(final Iterator<T> first, final Iterator<T> second) {
			this.current = first;
			iters.add(second);
		}

		@Override
		public boolean hasNext() {
			if (current.hasNext()) {
				return true;
			}

			while (!current.hasNext() && !iters.isEmpty()) {
				current = iters.get(0);
				iters.remove(0);
				if (current.hasNext()) {
					return true;
				}
			}

			return false;
		}

		@Override
		public T next() {
			hasNext(); // this will handle moving iterators up
			return current.next();
		}

		@Override
		public void remove() {
			current.remove();
		}
	}
}
