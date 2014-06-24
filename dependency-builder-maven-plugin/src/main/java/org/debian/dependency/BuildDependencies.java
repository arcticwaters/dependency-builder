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

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.InversionArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.debian.dependency.builders.ArtifactBuildException;
import org.debian.dependency.builders.BuildSession;
import org.debian.dependency.builders.BuildStrategy;
import org.debian.dependency.filters.DependencyNodeAncestorOrSelfArtifactFilter;
import org.debian.dependency.filters.IdentityArtifactFilter;
import org.debian.dependency.filters.InversionDependencyNodeFilter;

/** Builds the dependencies of a project which deploys Maven metadata. */
@Mojo(name = "build-dependencies")
public class BuildDependencies extends AbstractMojo {
	/** Artifact to try and build. */
	@Parameter(required = true)
	private String artifact;
	/** Where artifacts should be dumped after they are built. This can point to an existing repository or a new directory. */
	@Parameter(required = true, defaultValue = "${project.build.directory}/dependency-builder/repository")
	private File outputDirectory;
	/**
	 * Defines artifact dependencies which should be ignored. Ignored artifacts are not build, they are resolved in the usual
	 * Maven way and copied to {@link #outputDirectory}.
	 */
	@Parameter
	private final StrictPatternArtifactFilter ignoreArtifacts = new StrictPatternArtifactFilter(false);
	/** Directory where artifact sources will be built from. */
	@Parameter(defaultValue = "${project.build.directory}/dependency-builder/checkout")
	private File workDirectory;

	@Parameter(defaultValue = "${session}")
	private MavenSession session;
	@Parameter(defaultValue = "${mojoExecution}")
	private MojoExecution execution;

	@Component
	private RepositorySystem repositorySystem;
	@Component
	private ArtifactInstaller artifactInstaller;
	@Component(hint = "scm")
	private BuildStrategy scmBuildStrategy;
	@Component
	private DependencyCollector dependencyCollector;
	@Component
	private ProjectBuilder projectBuilder;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		setupDefaultIgnores();
		Artifact artifactToBuild = createArtifact();

		try {
			DependencyNode dependencies = dependencyCollector.resolveBuildDependencies(artifactToBuild.getGroupId(),
					artifactToBuild.getArtifactId(), artifactToBuild.getVersion(), null, session);

			List<DependencyNode> toInstall = collectArtifactRootsToInstall(dependencies);
			DependencyNode toBuild = collectArtifactsToBuild(dependencies);
			installArtifacts(toInstall);

			BuildSession buildSession = new BuildSession(session);
			buildSession.setExtensions(execution.getPlugin().getDependencies());
			buildSession.setWorkDirectory(workDirectory);
			buildSession.setTargetRepository(outputDirectory);

			Set<Artifact> builtArtifacts = scmBuildStrategy.build(toBuild, buildSession);

			List<DependencyNode> unmetDependencies = collectArtifactsToBuildNotBuilt(toBuild, builtArtifacts);
			if (!unmetDependencies.isEmpty()) {
				getLog().error("Some dependencies were not built, run again with the artifacts:");
				for (DependencyNode node : unmetDependencies) {
					getLog().error(" * " + node.getArtifact());
				}
				throw new MojoFailureException("Unable to build artifact, unmet dependencies: " + artifactToBuild);
			}
		} catch (DependencyResolutionException e) {
			throw new MojoExecutionException("Unable to resolve dependencies for " + artifactToBuild, e);
		} catch (ArtifactBuildException e) {
			throw new MojoExecutionException("Unable to build artifacts", e);
		}
	}

	private void setupDefaultIgnores() {
		List<String> includes = new ArrayList<String>(ignoreArtifacts.getIncludes());
		includes.add("org.apache.maven.plugins");
		ignoreArtifacts.setIncludes(includes);
	}

	private List<DependencyNode> collectArtifactsToBuildNotBuilt(final DependencyNode root, final Collection<Artifact> builtArtifacts) {
		CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
		DependencyNodeVisitor filter = new FilteringDependencyNodeVisitor(collector, new AndDependencyNodeFilter(
				// artifact to build and parent not ignored
				new InversionDependencyNodeFilter(new DependencyNodeAncestorOrSelfArtifactFilter(new ArtifactDependencyNodeFilter(
						ignoreArtifacts))),
				// artifact wasn't built
				new ArtifactDependencyNodeFilter(new InversionArtifactFilter(new IdentityArtifactFilter(builtArtifacts)))));
		root.accept(filter);
		return collector.getNodes();
	}

	private DependencyNode collectArtifactsToBuild(final DependencyNode root) {
		BuildingDependencyNodeVisitor collector = new BuildingDependencyNodeVisitor();
		DependencyNodeVisitor filter = new FilteringDependencyNodeVisitor(collector, new InversionDependencyNodeFilter(
				new DependencyNodeAncestorOrSelfArtifactFilter(new ArtifactDependencyNodeFilter(ignoreArtifacts))));
		root.accept(filter);
		return collector.getDependencyTree();
	}

	private List<DependencyNode> collectArtifactRootsToInstall(final DependencyNode root) throws MojoExecutionException {
		CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
		DependencyNodeVisitor filter = new FilteringDependencyNodeVisitor(collector, new ArtifactDependencyNodeFilter(ignoreArtifacts));
		root.accept(filter);

		// FIXME: this is a horrible hack, how can we handle implicit dependencies added to the build at runtime?
		List<DependencyNode> nodes = new ArrayList<DependencyNode>(collector.getNodes());
		for (int i = 0; i < nodes.size(); ++i) {
			DependencyNode node = nodes.get(i);
			if ("org.apache.maven.plugins".equals(node.getArtifact().getGroupId())
					&& "maven-surefire-plugin".equals(node.getArtifact().getArtifactId())) {
				BuildingDependencyNodeVisitor visitor = new BuildingDependencyNodeVisitor();

				// graft implicit surefire dependencies
				visitor.visit(node);
				for (DependencyNode child : node.getChildren()) {
					child.accept(visitor);
				}

				try {
					DependencyNode junit3Provider = dependencyCollector.resolveProjectDependencies("org.apache.maven.surefire",
							"surefire-junit3", node.getArtifact().getVersion(), null, session);
					junit3Provider.accept(visitor);

					DependencyNode junit4Provider = dependencyCollector.resolveProjectDependencies("org.apache.maven.surefire",
							"surefire-junit4", node.getArtifact().getVersion(), null, session);
					junit4Provider.accept(visitor);
					DependencyNode junit47Provider = dependencyCollector.resolveProjectDependencies("org.apache.maven.surefire",
							"surefire-junit47", node.getArtifact().getVersion(), null, session);
					junit47Provider.accept(visitor);

					DependencyNode testngProvider = dependencyCollector.resolveProjectDependencies("org.apache.maven.surefire",
							"surefire-testng", node.getArtifact().getVersion(), null, session);
					testngProvider.accept(visitor);
				} catch (DependencyResolutionException e) {
					throw new MojoExecutionException("Unable to resolve artifacts", e);
				}

				visitor.endVisit(node);

				nodes.set(i, visitor.getDependencyTree());
			}
		}

		return nodes;
	}

	private Artifact resolveArtifact(final Artifact toResolve) {
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
		return result.getArtifacts().iterator().next();
	}

	private void installArtifacts(final List<DependencyNode> toInstall) throws MojoExecutionException {
		try {
			ArtifactRepository targetRepository = repositorySystem.createLocalRepository(outputDirectory);

			// flatten tree into a single list
			CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
			for (DependencyNode node : toInstall) {
				getLog().debug("Artifact ignored, copying it and dependnecies from remote: " + node.getArtifact());
				node.accept(collector);
			}

			for (DependencyNode node : new LinkedHashSet<DependencyNode>(collector.getNodes())) {
				ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
				request.setActiveProfileIds(null);
				request.setInactiveProfileIds(null);
				request.setProfiles(null);
				request.setUserProperties(null);
				request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

				// artifact first as you could potentially use it without pom, albeit not easily
				Artifact artifactToInstall = node.getArtifact();
				artifactToInstall = resolveArtifact(artifactToInstall);
				artifactInstaller.install(artifactToInstall.getFile(), artifactToInstall, targetRepository);

				// now the parent poms as the project one is useless without them
				ProjectBuildingResult result = projectBuilder.build(artifactToInstall, request);
				for (MavenProject parent = result.getProject().getParent(); parent != null; parent = parent.getParent()) {
					Artifact parentArtifact = resolveArtifact(parent.getArtifact());
					artifactInstaller.install(parentArtifact.getFile(), parentArtifact, targetRepository);
				}

				// finally the pom itself
				Artifact pomArtifact = repositorySystem.createProjectArtifact(artifactToInstall.getGroupId(),
						artifactToInstall.getArtifactId(), artifactToInstall.getVersion());
				pomArtifact = resolveArtifact(pomArtifact);
				artifactInstaller.install(pomArtifact.getFile(), pomArtifact, targetRepository);
			}
		} catch (InvalidRepositoryException e) {
			throw new MojoExecutionException("Unable to create local repository", e);
		} catch (ArtifactInstallationException e) {
			throw new MojoExecutionException("Unable to install artifact", e);
		} catch (ProjectBuildingException e) {
			throw new MojoExecutionException("Unable to build project", e);
		}
	}

	@SuppressWarnings("checkstyle:magicnumber")
	private Artifact createArtifact() throws MojoExecutionException, MojoFailureException {
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

		return repositorySystem.createProjectArtifact(groupId, artifactId, version);
	}
}
