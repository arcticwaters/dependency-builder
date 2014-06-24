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

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;

/** Default implementation of {@link DependencyCollector}. */
@Component(role = DependencyCollector.class, hint = "default")
public class DefaultDependencyCollector extends AbstractLogEnabled implements DependencyCollector {
	@Requirement
	private ProjectBuilder projectBuilder;
	@Requirement
	private DependencyGraphBuilder dependencyGraphBuilder;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public DependencyNode resolveProjectDependencies(final String groupId, final String artifactId, final String version,
			final ArtifactFilter filter, final MavenSession session) throws DependencyResolutionException {
		MavenProject project = buildProject(groupId, artifactId, version, session);
		try {
			AndArtifactFilter realFilter = new AndArtifactFilter();
			/*
			 * This scope should really be runtime, but due to MNG-5197, we must resolve everything. Note that
			 * m2e is able to work around but they appear to use a different version of aether than the one packaged
			 * with Maven 3.0.5 (the embedded version in m2e).
			 */
			realFilter.add(new ScopeArtifactFilter(Artifact.SCOPE_TEST));
			if (filter != null) {
				realFilter.add(filter);
			}

			return dependencyGraphBuilder.buildDependencyGraph(project, realFilter);
		} catch (DependencyGraphBuilderException e) {
			throw new DependencyResolutionException(e);
		}
	}

	@Override
	public DependencyNode resolveBuildDependencies(final String groupId, final String artifactId, final String version,
			final ArtifactFilter filter, final MavenSession session) throws DependencyResolutionException {
		MavenProject project = buildProject(groupId, artifactId, version, session);

		AndArtifactFilter testScoped = new AndArtifactFilter();
		testScoped.add(new ScopeArtifactFilter(Artifact.SCOPE_TEST));
		if (filter != null) {
			testScoped.add(filter);
		}

		BuildingDependencyNodeVisitor dependencies = new BuildingDependencyNodeVisitor();
		try {
			DependencyNode root = dependencyGraphBuilder.buildDependencyGraph(project, testScoped);

			dependencies.visit(root);
			visitBuildExtensions(dependencies, project, filter, session);
			visitPluginDependencies(dependencies, project, filter, session);
			dependencies.endVisit(root);

			return dependencies.getDependencyTree();
		} catch (DependencyGraphBuilderException e) {
			throw new DependencyResolutionException(e);
		}
	}

	private void visitPluginDependencies(final DependencyNodeVisitor visitor, final MavenProject project, final ArtifactFilter filter,
			final MavenSession session) throws DependencyResolutionException, DependencyGraphBuilderException {
		for (Plugin plugin : project.getBuildPlugins()) {
			if (filter == null || filter.include(repositorySystem.createPluginArtifact(plugin))) {
				DependencyNode pluginDependencies = resolveProjectDependencies(plugin.getGroupId(), plugin.getArtifactId(),
						plugin.getVersion(), filter, session);
				pluginDependencies.accept(visitor);
			}

			for (Dependency dep : plugin.getDependencies()) {
				if (filter != null && !filter.include(repositorySystem.createDependencyArtifact(dep))) {
					continue;
				}

				MavenProject depProject = buildProject(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), session);
				List<String> exclusions = new ArrayList<String>(dep.getExclusions().size());
				for (Exclusion exclusion : dep.getExclusions()) {
					exclusions.add(exclusion.getGroupId() + ":" + exclusion.getArtifactId());
				}

				AndArtifactFilter depFilter = new AndArtifactFilter();
				depFilter.add(new ExcludesArtifactFilter(exclusions));
				depFilter.add(filter);

				DependencyNode dependencies = dependencyGraphBuilder.buildDependencyGraph(depProject, depFilter);
				dependencies.accept(visitor);
			}
		}
	}

	private void visitBuildExtensions(final DependencyNodeVisitor visitor, final MavenProject project,
			final ArtifactFilter filter, final MavenSession session) throws DependencyResolutionException {
		for (Extension extension : project.getBuildExtensions()) {
			Artifact artifact = repositorySystem.createProjectArtifact(extension.getGroupId(), extension.getArtifactId(),
					extension.getVersion());
			if (filter != null && !filter.include(artifact)) {
				continue;
			}

			DependencyNode extensionDependencies = resolveProjectDependencies(extension.getGroupId(), extension.getArtifactId(),
					extension.getVersion(), filter, session);
			extensionDependencies.accept(visitor);
		}
	}

	private MavenProject buildProject(final String groupId, final String artifactId, final String version, final MavenSession session)
			throws DependencyResolutionException {
		Artifact artifact = repositorySystem.createProjectArtifact(groupId, artifactId, version);

		ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
		request.setActiveProfileIds(null);
		request.setInactiveProfileIds(null);
		request.setProfiles(null);
		request.setResolveDependencies(false); // we may filter them out
		request.setUserProperties(null);
		request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

		try {
			ProjectBuildingResult result = projectBuilder.build(artifact, request);
			return result.getProject();
		} catch (ProjectBuildingException e) {
			throw new DependencyResolutionException(e);
		}
	}
}
