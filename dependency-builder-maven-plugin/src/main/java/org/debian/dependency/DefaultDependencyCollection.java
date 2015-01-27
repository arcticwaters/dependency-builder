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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
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
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.debian.dependency.filters.DependencyNodeAncestorOrSelfArtifactFilter;

/** Default implementation of {@link DependencyCollection}. */
@Component(role = DependencyCollection.class, hint = "default")
public class DefaultDependencyCollection extends AbstractLogEnabled implements DependencyCollection {
	@Requirement
	private ProjectBuilder projectBuilder;
	@Requirement
	private DependencyGraphBuilder dependencyGraphBuilder;
	@Requirement
	private RepositorySystem repositorySystem;
	@Requirement
	private ArtifactInstaller artifactInstaller;

	@Override
	public DependencyNode resolveProjectDependencies(final String groupId, final String artifactId, final String version,
			final ArtifactFilter filter, final MavenSession session) throws DependencyResolutionException {
		MavenProject project = buildProject(groupId, artifactId, version, session);
		try {
			AndArtifactFilter realFilter = new AndArtifactFilter();
			// this scope should really be runtime, but due to MNG-5197, we must resolve everything
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

			if (root.getChildren() != null) {
				for (DependencyNode child : root.getChildren()) {
					child.accept(dependencies);
				}
			}

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

	@Override
	public List<DependencyNode> installDependencies(final List<DependencyNode> graphs, final DependencyNodeFilter selection,
			final ArtifactRepository repository, final MavenSession session) throws DependencyResolutionException,
			ArtifactInstallationException {
		Set<Artifact> installing = new HashSet<Artifact>();
		List<DependencyNode> notInstalled = collectNodes(graphs, selection, installing);

		for (Artifact artifact : installing) {
			getLogger().debug("Installing " + artifact);

			// artifact first as you could potentially use it without pom, albeit not easily
			artifact = resolveArtifact(artifact, session);
			artifactInstaller.install(artifact.getFile(), artifact, repository);

			// now the parent poms as the project one is useless without them
			MavenProject project = buildProject(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), session);
			for (MavenProject parent = project.getParent(); parent != null; parent = parent.getParent()) {
				Artifact parentArtifact = resolveArtifact(parent.getArtifact(), session);
				artifactInstaller.install(parentArtifact.getFile(), parentArtifact, repository);
			}

			// finally the pom itself
			Artifact pomArtifact = repositorySystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(),
					artifact.getVersion());
			pomArtifact = resolveArtifact(pomArtifact, session);
			artifactInstaller.install(pomArtifact.getFile(), pomArtifact, repository);
		}

		return notInstalled;
	}

	private List<DependencyNode> collectNodes(final List<DependencyNode> graphs, final DependencyNodeFilter selection,
			final Set<Artifact> includes) {
		DependencyNodeFilter filter = selection;
		if (filter == null) {
			filter = new DependencyNodeFilter() {
				@Override
				public boolean accept(final DependencyNode node) {
					return true; // accept everything
				}
			};
		}

		CollectingDependencyNodeVisitor installing = new CollectingDependencyNodeVisitor();
		for (DependencyNode graph : graphs) {
			graph.accept(new FilteringDependencyNodeVisitor(installing, new DependencyNodeAncestorOrSelfArtifactFilter(filter)));
		}

		final Set<Artifact> artifacts = new HashSet<Artifact>();
		for (DependencyNode node : installing.getNodes()) {
			artifacts.add(node.getArtifact());
		}
		includes.addAll(artifacts);

		List<DependencyNode> result = new ArrayList<DependencyNode>();
		for (DependencyNode graph : graphs) {
			BuildingDependencyNodeVisitor visitor = new BuildingDependencyNodeVisitor();
			graph.accept(new FilteringDependencyNodeVisitor(visitor, new DependencyNodeFilter() {
				@Override
				public boolean accept(final DependencyNode node) {
					return !artifacts.contains(node.getArtifact());
				}
			}));

			if (visitor.getDependencyTree() != null) {
				result.add(visitor.getDependencyTree());
			}
		}

		return Collections.unmodifiableList(result);
	}

	private Artifact resolveArtifact(final Artifact toResolve, final MavenSession session) throws DependencyResolutionException {
		// otherwise resolve through the normal means
		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setLocalRepository(session.getLocalRepository())
				.setRemoteRepositories(session.getRequest().getRemoteRepositories())
				.setMirrors(session.getSettings().getMirrors())
				.setServers(session.getRequest().getServers())
				.setProxies(session.getRequest().getProxies())
				.setOffline(session.isOffline())
				.setForceUpdate(session.getRequest().isUpdateSnapshots())
				.setResolveRoot(true)
				.setArtifact(toResolve);

		ArtifactResolutionResult result = repositorySystem.resolve(request);
		if (!result.isSuccess()) {
			if (result.getExceptions() != null) {
				for (Exception exception : result.getExceptions()) {
					getLogger().error("Error resolving artifact " + toResolve, exception);
				}
			}
			throw new DependencyResolutionException("Unable to resolve artifact " + toResolve);
		}

		return result.getArtifacts().iterator().next();
	}
}
