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
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Builds an Ant project using an embedded version of Ant.
 */
@Component(role = SourceBuilder.class, hint = "ant")
public class EmbeddedAntBuilder extends AbstractBuildFileSourceBuilder implements SourceBuilder {
	private static final String COMMIT_MESSAGE_PREFIX = "[build-dependency-maven-plugin]";
	private static final String BUILD_INCLUDES = "**/build.xml";
	private static final float MIN_SIMILARITY = .9f;

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

			List<File> buildFiles = findBuildFiles(repo.getRepository().getWorkTree());
			Project antProject = new Project();
			ProjectHelper.configureProject(antProject, buildFiles.get(0));
			antProject.init();

			antProject.setBaseDir(buildFiles.get(0).getParentFile());
			antProject.executeTarget(antProject.getDefaultTarget());

			File builtArtifact = findArtifactFile(project, repo);
			installer.install(builtArtifact, project.getArtifact(), targetRepository);

			File pom = findPomFile(project, repo);
			Artifact pomArtifact = repositorySystem.createProjectArtifact(project.getGroupId(), project.getArtifactId(),
					project.getVersion());
			installer.install(pom, pomArtifact, targetRepository);

			project.getArtifact().setFile(null); // we've installed the artifact
			return Collections.singleton(project.getArtifact());
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

	private File findArtifactFile(final MavenProject project, final Git repo) throws IOException, GitAPIException {
		for (File file : FileUtils.getFiles(repo.getRepository().getWorkTree(), "**/*.jar", null)) {
			if (jarSimilarity(repo, project.getArtifact().getFile(), file) > MIN_SIMILARITY) {
				return file;
			}
		}
		throw new IllegalStateException("Cannot find built jar file for " + project);
	}

	private File findPomFile(final MavenProject project, final Git repo) throws IOException {
		for (File file : FileUtils.getFiles(repo.getRepository().getWorkTree(), "**/*.xml,**/*.pom", null)) {
			if (calculateSimilarity(project.getFile(), file) > MIN_SIMILARITY) {
				return file;
			}
		}
		throw new IllegalStateException("Cannot find built pom file for " + project);
	}

	private float calculateSimilarity(final File text1, final File text2) throws IOException {
		RawText rawText1 = new RawText(text1);
		RawText rawText2 = new RawText(text2);
		long fullSize = Math.max(rawText1.size(), rawText2.size());

		EditList diffList = new EditList();
		diffList.addAll(new HistogramDiff().diff(RawTextComparator.WS_IGNORE_ALL, rawText1, rawText2));

		long diffSize = 0;
		for (Edit edit : diffList) {
			diffSize += Math.max(edit.getLengthB(), edit.getLengthA());
		}

		return Math.max(fullSize - diffSize, 0f) / fullSize;
	}

	private String repositoryRelative(final Git repo, final String file) {
		return repositoryRelative(repo, new File(file));
	}

	private String repositoryRelative(final Git repo, final File file) {
		return repo.getRepository().getWorkTree().toURI().relativize(file.toURI()).getPath();
	}

	private List<String> findFilesToProcess(final Git repo, final String includes, final String excludes, final List<String> logMessages)
			throws IOException, GitAPIException {
		List<String> processFiles = FileUtils.getFileNames(repo.getRepository().getWorkTree(), includes, excludes, true);
		if (processFiles.isEmpty()) {
			return processFiles;
		}

		// check to see if we've done this before
		LogCommand logCommand = repo.log();
		for (String processFile : processFiles) {
			logCommand.addPath(repositoryRelative(repo, processFile));
		}

		for (RevCommit commit : logCommand.call()) {
			if (logMessages.contains(commit.getShortMessage())) {
				return Collections.emptyList();
			}
		}

		return processFiles;
	}

	private void removeJarFiles(final Git repo, final MavenProject project, final ArtifactRepository artifactRepository) throws IOException,
			GitAPIException {
		String replaceJarMessage = COMMIT_MESSAGE_PREFIX + " replacing jar files with open source ones";
		String removeJarMessage = COMMIT_MESSAGE_PREFIX + " removing unknown jar files";

		List<String> jarFiles = findFilesToProcess(repo, "**/*.jar", null, Arrays.asList(replaceJarMessage, removeJarMessage));
		if (jarFiles.isEmpty()) {
			return;
		}

		List<String> jarsToRemove = new ArrayList<String>();
		AddCommand addCommand = repo.add();
		for (String relativeJar : jarFiles) {
			File jarFile = new File(relativeJar);
			relativeJar = repositoryRelative(repo, relativeJar);

			boolean foundDependency = false;
			for (Dependency dep : project.getDependencies()) {
				Artifact depArtifact = repositorySystem.createDependencyArtifact(dep);
				resolveArtifact(depArtifact, artifactRepository);

				if (jarSimilarity(repo, jarFile, depArtifact.getFile()) > MIN_SIMILARITY) {
					FileUtils.copyFile(depArtifact.getFile(), jarFile);
					addCommand.addFilepattern(relativeJar);
					foundDependency = true;
					break;
				}
			}

			if (!foundDependency) {
				jarsToRemove.add(relativeJar);
			}
		}

		if (jarsToRemove.size() < jarFiles.size()) {
			addCommand.call();

			Status status = repo.status().call();
			if (!status.getChanged().isEmpty()) {
				repo.commit()
				.setMessage(replaceJarMessage)
				.call();
			}
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
			.setMessage(removeJarMessage)
			.call();
	}

	/*
	 * Differences of jar files are calculated via file lists. In general, this should be accurate with
	 * a high number of files and has the advantage of not hiccupping on differences in compilers. Since
	 * it doesn't actually diff contents, it can be easily fooled.
	 */
	private float jarSimilarity(final Git repo, final File jarFile1, final File jarFile2) throws IOException {
		File fileList1 = null;
		File fileList2 = null;
		try {
			fileList1 = createFileList(repo, jarFile1);
			fileList2 = createFileList(repo, jarFile2);
			return calculateSimilarity(fileList1, fileList2);
		} finally {
			if (fileList1 != null) {
				FileUtils.forceDelete(fileList1);
			}
			if (fileList2 != null) {
				FileUtils.forceDelete(fileList2);
			}
		}
	}

	private File createFileList(final Git repo, final File file) throws IOException {
		File fileList = FileUtils.createTempFile("filelist", ".txt", repo.getRepository().getWorkTree());
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
		String message = COMMIT_MESSAGE_PREFIX + " removing class files";
		List<String> clazzFiles = findFilesToProcess(repo, "*.class", null, Collections.singletonList(message));
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
			.setMessage(message)
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
}
