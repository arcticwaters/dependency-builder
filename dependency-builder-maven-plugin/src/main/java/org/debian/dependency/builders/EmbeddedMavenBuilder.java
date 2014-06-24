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
import org.apache.maven.model.Model;
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
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

/**
 * Builds a Maven project using an embedded version of Maven.
 */
@Component(role = SourceBuilder.class, hint = "maven2")
public class EmbeddedMavenBuilder extends AbstractLogEnabled implements SourceBuilder {
	private static final String POM_INCLUDES = "**/pom.xml";
	private static final String POM_EXCLUDES = "**/src/**";

	@Requirement
	private ModelBuilder modelBuilder;
	@Requirement
	private Invoker invoker;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public Set<Artifact> build(final Artifact artifact, final File basedir, final File localRepository) throws ArtifactBuildException {
		File project = findReactor(artifact, basedir);

		InvocationRequest request = new DefaultInvocationRequest()
				.setBaseDirectory(project)
				.setGoals(Arrays.asList("install"))
				.setOffline(true)
				.setRecursive(false) // in case we are dealing with a pom packaging
				.setLocalRepositoryDirectory(localRepository);

		try {
			InvocationResult result = invoker.execute(request);
			if (result.getExecutionException() != null) {
				throw new ArtifactBuildException("Unable to build proejct", result.getExecutionException());
			} else if (result.getExitCode() != 0) {
				throw new ArtifactBuildException("Execution did not complete successfully");
			}
		} catch (MavenInvocationException e) {
			throw new ArtifactBuildException("Unable to build project", e);
		}

		return Collections.singleton(artifact);
	}

	private File findReactor(final Artifact artifact, final File basedir) throws ArtifactBuildException {
		try {
			List<File> poms = FileUtils.getFiles(basedir, POM_INCLUDES, FileUtils.getDefaultExcludesAsString());

			for (File pom : poms) {
				try {
					ModelBuildingRequest request = new DefaultModelBuildingRequest()
							.setModelSource(new FileModelSource(pom))
							.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

					Model model = modelBuilder.build(request).getEffectiveModel();
					if (artifact.getGroupId().equals(model.getGroupId())
							&& artifact.getArtifactId().equals(model.getArtifactId())
							&& artifact.getVersion().equals(model.getVersion())) {
						return pom.getParentFile();
					}
				} catch (ModelBuildingException e) {
					getLogger().debug("Ignoring unreadable pom file:" + pom, e);
				}
			}
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}
		throw new ArtifactBuildException("Unable to find reactor containing " + artifact + " under " + basedir);
	}

	@Override
	public boolean canBuild(final Artifact artifact, final File directory) throws IOException {
		List<String> excludes = new ArrayList<String>(FileUtils.getDefaultExcludesAsList());
		excludes.add(POM_EXCLUDES);
		List<File> poms = FileUtils.getFiles(directory, POM_INCLUDES, StringUtils.join(excludes.iterator(), ","));

		for (File pom : poms) {
			try {
				ModelBuildingRequest request = new DefaultModelBuildingRequest()
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
}
