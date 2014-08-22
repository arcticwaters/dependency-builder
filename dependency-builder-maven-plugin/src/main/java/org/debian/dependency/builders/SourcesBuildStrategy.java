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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * An attempt to build artifacts using an attached sources artifact.
 */
@Component(role = BuildStrategy.class, hint = "sources")
public class SourcesBuildStrategy extends AbstractLogEnabled implements BuildStrategy {
	private static final String WORK_BRANCH = "dependency-builder-maven-plugin";
	private static final String COMMIT_MESSAGE_PREFIX = "[build-dependency-maven-plugin]";
	private static final int PRIORITY = 5000;
	@Requirement
	private RepositorySystem repoSystem;
	@Requirement(hint = "maven2")
	private SourceBuilder embeddedMavenSourceBuilder;
	@Requirement
	private ProjectBuilder projectBuilder;

	@Override
	public Set<Artifact> build(final DependencyNode graph, final BuildSession session) throws ArtifactBuildException {
		Artifact artifact = graph.getArtifact();
		Artifact sourcesArtifact = repoSystem.createArtifactWithClassifier(artifact.getGroupId(), artifact.getArtifactId(),
				artifact.getVersion(), artifact.getType(), "sources");
		sourcesArtifact = resolveArtifact(sourcesArtifact, session);

		if (sourcesArtifact == null || sourcesArtifact.getFile() == null || !sourcesArtifact.getFile().exists()) {
			return Collections.emptySet();
		}

		// some build systems may not work well with colon in their name (and ntfs just can't handle it)
		String projectId = graph.getArtifact().getId().replace(':', '%');
		File workDir = new File(session.getWorkDirectory(), projectId);
		workDir.mkdirs();

		try {
			Git git = makeLocalCopy(workDir, artifact, session);
			MavenProject project = constructProject(artifact, session);
			embeddedMavenSourceBuilder.build(project, git, session.getTargetRepository());

			Artifact result = project.getArtifact();
			result.setFile(null); // should already be installed
			return Collections.singleton(result);
		} catch (GitAPIException e) {
			throw new ArtifactBuildException(e);
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private MavenProject constructProject(final Artifact artifact, final BuildSession session) throws ArtifactBuildException {
		try {
			// pom files are not set up in projects which are not in the workspace, we add them in manually since they are needed
			Artifact pomArtifact = repoSystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
			pomArtifact = resolveArtifact(pomArtifact, session);

			ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getMavenSession().getProjectBuildingRequest());
			request.setActiveProfileIds(null);
			request.setInactiveProfileIds(null);
			request.setUserProperties(null);

			ProjectBuildingResult result = projectBuilder.build(artifact, request);

			MavenProject mavenProject = result.getProject();
			mavenProject.setArtifact(resolveArtifact(mavenProject.getArtifact(), session));
			mavenProject.setFile(pomArtifact.getFile());
			return mavenProject;
		} catch (ProjectBuildingException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private Git makeLocalCopy(final File workDir, final Artifact artifact, final BuildSession session) throws IOException, GitAPIException {
		if (!"jar".equals(artifact.getType())) {
			throw new UnsupportedOperationException("Only able to build jar sources");
		}

		JarFile jarFile = null;
		try {
			jarFile = new JarFile(artifact.getFile());
			for (Enumeration<JarEntry> iter = jarFile.entries(); iter.hasMoreElements();) {
				JarEntry entry = iter.nextElement();
				if (entry.getName().endsWith("/")) {
					File file = new File(new File(workDir, "src/main/java/"), entry.getName());
					file.mkdirs();
				}
			}

			Git repo = Git.init().setDirectory(workDir).call();
			AddCommand addCommand = repo.add();

			for (Enumeration<JarEntry> iter = jarFile.entries(); iter.hasMoreElements();) {
				JarEntry entry = iter.nextElement();
				File file = new File(new File(workDir, "src/main/java/"), entry.getName());
				if (!entry.getName().endsWith("/")) {
					FileOutputStream outputStream = null;
					try {
						outputStream = new FileOutputStream(file);
						IOUtil.copy(jarFile.getInputStream(entry), outputStream);
						addCommand.addFilepattern(repositoryRelative(repo, file));
					} finally {
						IOUtil.close(outputStream);
					}
				}
			}


			Artifact pomArtifact = repoSystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
			pomArtifact = resolveArtifact(pomArtifact, session);
			FileUtils.copyFile(pomArtifact.getFile(), new File(workDir, "pom.xml"));
			addCommand.addFilepattern("pom.xml");

			addCommand.call();
			repo.commit()
				.setMessage(COMMIT_MESSAGE_PREFIX + " initial import from sources artifact of " + artifact)
				.call();

			repo.checkout()
				.setCreateBranch(true)
				.setName(WORK_BRANCH)
				.call();

			return repo;
		} finally {
			if (jarFile != null) {
				jarFile.close();
			}
		}
	}

	private String repositoryRelative(final Git repo, final File file) {
		return repo.getRepository().getWorkTree().toURI().relativize(file.toURI()).getPath();
	}

	private Artifact resolveArtifact(final Artifact toResolve, final BuildSession session) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setLocalRepository(session.getMavenSession().getLocalRepository())
				.setOffline(true)
				.setResolveRoot(true)
				.setArtifact(toResolve);

		ArtifactResolutionResult result = repoSystem.resolve(request);
		if (result.getArtifacts().isEmpty()) {
			return null;
		}
		return result.getArtifacts().iterator().next();
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}
}
