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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.repository.RepositorySystem;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.jgit.api.Git;

/**
 * Builds an Ant project using an embedded version of Ant.
 */
@Component(role = SourceBuilder.class, hint = "ant")
public class EmbeddedAntBuilder extends AbstractBuildFileSourceBuilder implements SourceBuilder {
	private static final String BUILD_INCLUDES = "**/build.xml";

	@Requirement
	private ArtifactInstaller installer;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public Set<Artifact> build(final Artifact artifact, final Git repo, final File localRepository) throws ArtifactBuildException {
		try {
			List<File> buildFiles = findBuildFiles(repo.getRepository().getWorkTree());
			Project antProject = new Project();
			ProjectHelper.configureProject(antProject, buildFiles.get(0));
			antProject.init();

			antProject.setBaseDir(buildFiles.get(0).getParentFile());
			antProject.executeTarget(antProject.getDefaultTarget());

			ArtifactRepository targetRepository = repositorySystem.createLocalRepository(localRepository);

			File builtArtifact = null;
			installer.install(builtArtifact, artifact, targetRepository);

			File pom = null;
			Artifact pomArtifact = repositorySystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(),
					artifact.getVersion());
			installer.install(pom, pomArtifact, targetRepository);

			return Collections.singleton(artifact);
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		} catch (InvalidRepositoryException e) {
			throw new ArtifactBuildException(e);
		} catch (ArtifactInstallationException e) {
			throw new ArtifactBuildException("Unable to install artifact", e);
		}
	}

	@Override
	public boolean canBuild(final Artifact artifact, final File directory) throws IOException {
		// there is no general rule for maven artifacts in ant projects (and this is just a hint)
		return false;
	}

	@Override
	protected List<String> getIncludes() {
		return Arrays.asList(BUILD_INCLUDES);
	}
}
