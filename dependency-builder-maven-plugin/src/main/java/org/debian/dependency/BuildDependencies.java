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
package org.debian.dependency;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.debian.dependency.builders.ArtifactBuildException;
import org.debian.dependency.builders.BuildStrategy;

/** Builds the dependencies of a project which deploys Maven metadata. */
@Mojo(name = "build-dependencies")
public class BuildDependencies extends AbstractMojo {
	/** Artifact to try and build. */
	@Parameter(required = true)
	private String artifact;

	@Parameter(defaultValue = "${session}")
	private MavenSession session;
	@Parameter(defaultValue = "${mojoExecution}")
	private MojoExecution execution;

	@Component
	private RepositorySystem repositorySystem;
	@Component(hint = "scm")
	private BuildStrategy scmBuildStrategy;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		Artifact resolvedArtifact = resolveArtifact();

		try {
			scmBuildStrategy.buildArtifact(resolvedArtifact, session, execution);
		} catch (ArtifactBuildException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	@SuppressWarnings("checkstyle:magicnumber")
	private Artifact resolveArtifact() throws MojoExecutionException, MojoFailureException {
		String[] parts = artifact.split(":");

		String groupId;
		String artifactId;
		String version = "LATEST";

		switch (parts.length) {
		case 3:
			groupId = parts[0];
			artifactId = parts[1];
			version = parts[2];
			break;
		case 2:
			groupId = parts[0];
			artifactId = parts[1];
			break;
		default:
			throw new MojoFailureException("Artifact parameter must conform to 'groupId:artifactId[:version]'.");
		}

		Artifact newArtifact = repositorySystem.createArtifact(groupId, artifactId, version, Artifact.SCOPE_RUNTIME, "pom");

		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setLocalRepository(session.getLocalRepository())
				.setRemoteRepositories(session.getRequest().getRemoteRepositories())
				.setMirrors(session.getSettings().getMirrors())
				.setServers(session.getRequest().getServers())
				.setProxies(session.getRequest().getProxies())
				.setOffline(session.isOffline())
				.setForceUpdate(session.getRequest().isUpdateSnapshots())
				.setArtifact(newArtifact);

		ArtifactResolutionResult result = repositorySystem.resolve(request);
		if (result.hasExceptions()) {
			throw new MojoExecutionException("Unable to retrieve artifact " + artifact + '\n' + result.getExceptions());
		} else if (result.hasMissingArtifacts()) {
			throw new MojoExecutionException("Unable to find artifacts: " + result.getMissingArtifacts());
		}

		return result.getArtifacts().iterator().next();
	}
}
