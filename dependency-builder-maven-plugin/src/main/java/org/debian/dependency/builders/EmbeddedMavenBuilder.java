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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.jgit.api.Git;

/**
 * Builds a Maven project using an embedded version of Maven.
 */
@Component(role = SourceBuilder.class, hint = "maven2")
public class EmbeddedMavenBuilder extends AbstractBuildFileSourceBuilder implements SourceBuilder {
	private static final String POM_INCLUDES = "**/pom.xml";
	private static final String POM_EXCLUDES = "**/src/**";

	@Requirement
	private ModelBuilder modelBuilder;
	@Requirement
	private Invoker invoker;
	@Requirement
	private ArtifactInstaller installer;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public Set<Artifact> build(final Artifact artifact, final Git repository, final File localRepository) throws ArtifactBuildException {
		File basedir = repository.getRepository().getWorkTree();
		Model model = findProjectModel(artifact, basedir);

		/*
		 * Although using the install phase will catch attached artifacts for the project, it won't handle parent poms which are
		 * in the same source repository. We choose to install manually instead of forking off another Maven process since you
		 * won't have custom build steps for poms in general.
		 */
		try {
			Model parentModel = model;
			ArtifactRepository targetRepository = repositorySystem.createLocalRepository(localRepository);
			for (Parent parent = parentModel.getParent(); parent != null; parent = parentModel.getParent()) {
				parentModel = findProjectModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), basedir);
				if (parentModel == null) {
					break; // it's not in this repository
				}

				Artifact parentArtifact = repositorySystem.createProjectArtifact(parentModel.getGroupId(), parentModel.getArtifactId(),
						parentModel.getVersion());
				installer.install(parentModel.getPomFile(), parentArtifact, targetRepository);
			}
		} catch (ArtifactInstallationException e) {
			throw new ArtifactBuildException("Unable to install parent", e);
		} catch (InvalidRepositoryException e) {
			throw new ArtifactBuildException(e);
		}

		InvocationRequest request = new DefaultInvocationRequest()
				.setBaseDirectory(model.getPomFile().getParentFile())
				.setGoals(Arrays.asList("install"))
				.setOffline(true)
				.setRecursive(false) // in case we are dealing with a pom packaging
				.setLocalRepositoryDirectory(localRepository);

		try {
			InvocationResult result = invoker.execute(request);
			if (result.getExitCode() == 0 && result.getExecutionException() == null) {
				return Collections.singleton(artifact);
			}

			if (result.getExecutionException() != null) {
				throw new ArtifactBuildException("Unable to build proejct", result.getExecutionException());
			}

			throw new ArtifactBuildException("Execution did not complete successfully");
		} catch (MavenInvocationException e) {
			throw new ArtifactBuildException("Unable to build project", e);
		}
	}

	private Model findProjectModel(final Artifact artifact, final File basedir) throws ArtifactBuildException {
		return findProjectModel(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), basedir);
	}

	private Model findProjectModel(final String groupId, final String artifactId, final String version, final File basedir)
			throws ArtifactBuildException {
		try {
			for (File pom : findBuildFiles(basedir)) {
				try {
					ModelBuildingRequest request = new DefaultModelBuildingRequest()
							.setPomFile(pom)
							.setModelSource(new FileModelSource(pom))
							.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

					Model model = modelBuilder.build(request).getEffectiveModel();
					if (model.getGroupId().equals(groupId)
							&& model.getArtifactId().equals(artifactId)
							&& model.getVersion().equals(version)) {
						return model;
					}
				} catch (ModelBuildingException e) {
					getLogger().debug("Ignoring unreadable pom file:" + pom, e);
				}
			}
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}
		throw new ArtifactBuildException("Unable to find reactor containing " + groupId + ":" + artifactId + ":" + version + " under "
				+ basedir);
	}

	@Override
	public boolean canBuild(final Artifact artifact, final File directory) throws IOException {
		for (File pom : findBuildFiles(directory)) {
			try {
				ModelBuildingRequest request = new DefaultModelBuildingRequest()
						.setPomFile(pom)
						.setModelSource(new FileModelSource(pom))
						.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

				ModelBuildingResult result = modelBuilder.build(request);
				Model model = result.getEffectiveModel();

				if (model.getGroupId().equals(artifact.getGroupId()) && model.getArtifactId().equals(artifact.getArtifactId())
						&& model.getVersion().equals(artifact.getVersion())) {
					return true;
				}
			} catch (ModelBuildingException e) {
				getLogger().debug("Ignoring unresolvable model", e);
			}
		}
		return false;
	}

	@Override
	protected List<String> getIncludes() {
		return Arrays.asList(POM_INCLUDES);
	}

	@Override
	protected List<String> getExcludes() {
		List<String> result = new ArrayList<String>(super.getExcludes());
		result.add(POM_EXCLUDES);
		return result;
	}
}
