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

import java.util.List;

import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;

/**
 * Provides a facility to collect dependencies against a project.
 */
public interface DependencyCollection {
	/**
	 * Attempts to resolve a projects runtime dependencies.
	 *
	 * @param groupId artifacts group ID
	 * @param artifactId artifacts artifact ID
	 * @param version artifacts version
	 * @param filter filter for artifacts (or {@code null})
	 * @param session session for resolving artifacts
	 * @return {@link DependencyNode} for the project
	 * @throws DependencyResolutionException in case of errors
	 * @see #resolveBuildDependencies(String, String, String, MavenSession)
	 */
	DependencyNode resolveProjectDependencies(String groupId, String artifactId, String version,
			ArtifactFilter filter, MavenSession session) throws DependencyResolutionException;

	/**
	 * Attempts to resolve project build dependencies. Project build dependencies include regular runtime, compile and test
	 * dependencies of the project, plugins used for the build, their dependencies as well as any build extensions. Although
	 * projects dependencies may merge (two artifacts with different versions of a dependency), plugin dependencies will not as
	 * they have an isolated classrealm during a build.
	 *
	 * @param groupId artifacts group ID
	 * @param artifactId artifacts artifact ID
	 * @param version artifacts version
	 * @param filter filter for dependencies (or {@code null})
	 * @param session session for resolving artifacts
	 * @return {@link DependencyNode} for the project
	 * @throws DependencyResolutionException in case of errors
	 * @see #resolveProjectDependencies(String, String, String, MavenSession)
	 */
	DependencyNode resolveBuildDependencies(String groupId, String artifactId, String version,
			ArtifactFilter filter, MavenSession session) throws DependencyResolutionException;

	/**
	 * Installs artifacts from the given artifact graphs in the {@link ArtifactRepository}. If selection is non-{@code null}, then
	 * only the selected artifacts will be installed. All graphs are returned with the installed nodes removed.
	 *
	 * @param graphs artifact graphs to walk
	 * @param selection filter of nodes to install or {@code null} to install everything
	 * @param repository where artifacts should be installed
	 * @param session session for resolving artifacts
	 * @return new artifact graphs with installed artifact and children removed (if any)
	 * @throws DependencyResolutionException in case of errors
	 * @throws ArtifactInstallationException in case of errors
	 */
	List<DependencyNode> installDependencies(List<DependencyNode> graphs, DependencyNodeFilter selection, ArtifactRepository repository,
			MavenSession session) throws DependencyResolutionException, ArtifactInstallationException;
}