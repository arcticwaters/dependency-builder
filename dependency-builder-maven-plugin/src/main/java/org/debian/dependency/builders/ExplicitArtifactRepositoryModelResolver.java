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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.RepositorySystem;

/** A {@link ModelResolver} for a particular {@link ArtifactRepository}. */
public class ExplicitArtifactRepositoryModelResolver implements ModelResolver {
	private RepositorySystem repositorySystem;
	private ArtifactRepository repository;

	public ExplicitArtifactRepositoryModelResolver(RepositorySystem repositorySystem, ArtifactRepository repository) {
		this.repositorySystem = repositorySystem;
		this.repository = repository;
	}

	@Override
	public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
		Artifact artifact = repositorySystem.createProjectArtifact(groupId, artifactId, version);
		artifact = repository.find(artifact);

		if (artifact.getFile() == null) {
			throw new UnresolvableModelException("Unable to find pom in repository", groupId, artifactId, version);
		}
		return new FileModelSource(artifact.getFile());
	}

	@Override
	public void addRepository(Repository repository) throws InvalidRepositoryException {
		// given repositories are not used
	}

	@Override
	public ModelResolver newCopy() {
		return new ExplicitArtifactRepositoryModelResolver(repositorySystem, repository);
	}
}
