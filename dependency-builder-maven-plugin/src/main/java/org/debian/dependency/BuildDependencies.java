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
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
@SuppressWarnings("PMD.GodClass")
@Mojo(name = "build-dependencies")
public class BuildDependencies extends AbstractMojo {
	private static final String SUREFIRE_GROUPID = "org.apache.maven.surefire";
	private static final String SUREFIRE_PLUGIN_VERSION = "{org.apache.maven.plugins:maven-surefire-plugin}";

	/**
	 * A single artifact to build. This parameter will be merged with {@link #artifacts} and built first if both are specified.
	 *
	 * @see #artifacts
	 */
	@Parameter
	private String artifact;
	/**
	 * Defines all artifacts to build. This can be used to specify multiple artifacts to build, or implicit dependencies which are
	 * not represented in the pom.
	 * <p/>
	 * Artifacts are defined in one of the following formats:
	 * <ul>
	 * <li><code>&lt;groupId&gt;:&lt;artifactId&gt;</code></li>
	 * <li><code>&lt;groupId&gt;:&lt;artifactId&gt;:&lt;version&gt;</code></li>
	 * <li><code>&lt;groupId&gt;:&lt;artifactId&gt;:{&lt;refGroupId&gt;:&lt;refArtifactId&gt;}</code></li>
	 * </ul>
	 * If the version is not specified, the latest available version will be selected. If the version is specified in the
	 * <code>{*}</code> format, it can reference the version of another artifact which was resolved (the first one). It is
	 * ignored, if no artifact can be found.
	 */
	@Parameter
	private Set<String> artifacts = new LinkedHashSet<String>();
	/** Where artifacts should be dumped after they are built. This can point to an existing repository or a new directory. */
	@Parameter(required = true, defaultValue = "${project.build.directory}/dependency-builder/repository")
	private File outputDirectory;
	/**
	 * Defines artifact dependencies which should be ignored. Ignored artifacts are not build, they are resolved in the usual
	 * Maven way and copied to {@link #outputDirectory}.
	 */
	@Parameter
	private final StrictPatternArtifactFilter ignoreArtifacts = new StrictPatternArtifactFilter(false);
	/** Directory where artifact sources should be checked out to. */
	@Parameter(defaultValue = "${project.build.directory}/dependency-builder/checkout")
	private File checkoutDirectory;
	/** Directory where local git repositories should be made for potential modifications. */
	@Parameter(defaultValue = "${project.build.directory}/dependency-builder/work")
	private File workDirectory;
	/** Whether to allow more than a single project to be built. */
	@Parameter
	private boolean multiProject;
	/**
	 * Overrides for scm urls for specific projects. Keys should be in the format <code>&lt;groupId&gt;:&lt;artifactId&gt;:&lt;version&gt;</code>
	 * while values are scm connection strings, i.e. {@code scm:git:git://localhost/repository.git}.
	 */
	@Parameter
	private Properties artifactScmOverrides = new Properties();

	@Parameter(defaultValue = "${session}")
	private MavenSession session;
	@Parameter(defaultValue = "${mojoExecution}")
	private MojoExecution execution;

	@Component
	private RepositorySystem repositorySystem;
	@Component
	private ArtifactInstaller artifactInstaller;
	@Component(role = BuildStrategy.class)
	private List<BuildStrategy> buildStrategies;
	@Component
	private DependencyCollector dependencyCollector;
	@Component
	private ProjectBuilder projectBuilder;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (artifact != null) {
			Set<String> newArtifacts = new LinkedHashSet<String>();
			newArtifacts.add(artifact);
			newArtifacts.addAll(artifacts);
			artifacts = newArtifacts;
		}

		setupDefaultIgnores();
		validationConfiguration();

		buildStrategies = new ArrayList<BuildStrategy>(buildStrategies);
		Collections.sort(buildStrategies, new Comparator<BuildStrategy>() {
			@Override
			public int compare(final BuildStrategy strategy1, final BuildStrategy strategy2) {
				return strategy1.getPriority() - strategy2.getPriority();
			}
		});

		List<DependencyNode> graphs = createArtifactGraph();
		List<DependencyNode> toInstall = collectArtifactsToInstall(graphs);
		List<DependencyNode> toBuild = collectArtifactsToBuild(graphs);
		installArtifacts(toInstall);

		BuildSession buildSession = new BuildSession(session);
		buildSession.setExtensions(execution.getPlugin().getDependencies());
		buildSession.setWorkDirectory(workDirectory);
		buildSession.setTargetRepository(outputDirectory);
		buildSession.setArtifactScmOverrides(propertiesToMap(artifactScmOverrides));

		while (!toBuild.isEmpty()) {
			DependencyNode artifact = toBuild.get(0);

			// try and build it
			Set<Artifact> builtArtifacts = null;
			for (BuildStrategy buildStrategy : buildStrategies) {
				try {
					getLog().debug("Attempting to build with " + buildStrategy);
					builtArtifacts = buildStrategy.build(artifact, buildSession);
					if (builtArtifacts != null && !builtArtifacts.isEmpty()) {
						getLog().debug("Artifacts built with strategy!");
						break;
					}
				} catch (ArtifactBuildException e) {
					getLog().debug("Unable to build with strategy " + buildStrategy + ", trying next one", e);
				}
			}

			checkErrors(artifact, builtArtifacts);
			removeBuiltArtifacts(toBuild, builtArtifacts);
		}
	}

	private Map<String, String> propertiesToMap(final Properties properties) {
		Map<String, String> result = new HashMap<String, String>();
		for (Enumeration<?> iter = properties.propertyNames(); iter.hasMoreElements();) {
			Object key = iter.nextElement();
			if (!(key instanceof String)) {
				continue;
			}
			String property = (String) key;
			result.put(property, properties.getProperty(property));
		}
		return result;
	}

	private void validationConfiguration() throws MojoExecutionException {
		for (Entry<Object, Object> entry : artifactScmOverrides.entrySet()) {
			Artifact artifact = createArtifact((String) entry.getKey(), Collections.<DependencyNode> emptyList(), false);
			if (artifact == null) {
				throw new MojoExecutionException("Invalid artifact specifier for scm overrides: " + entry.getKey());
			}
		}
	}

	private void removeBuiltArtifacts(final List<DependencyNode> toBuild, final Set<Artifact> builtArtifacts) {
		for (Iterator<DependencyNode> iter = toBuild.iterator(); iter.hasNext();) {
			if (builtArtifacts.contains(iter.next().getArtifact())) {
				iter.remove();
			}
		}
	}

	private void checkErrors(final DependencyNode artifact, final Set<Artifact> builtArtifacts) throws MojoFailureException {
		if (!builtArtifacts.contains(artifact.getArtifact())) {
			throw new MojoFailureException("Aritfact " + artifact.getArtifact() + " was not built!");
		}

		if (!multiProject) {
			List<DependencyNode> unmetDependencies = findMissingDependencies(artifact, builtArtifacts);
			if (!unmetDependencies.isEmpty()) {
				getLog().error("Some dependencies were not built, run again with the artifacts:");
				for (DependencyNode node : unmetDependencies) {
					getLog().error(" * " + node.getArtifact());
				}
				throw new MojoFailureException("Unable to build artifact, unmet dependencies: " + artifact.getArtifact());
			}
		}
	}

	private List<DependencyNode> createArtifactGraph() throws MojoExecutionException {
		List<DependencyNode> result = new ArrayList<DependencyNode>();
		List<String> versionReferencingArtifacts = new ArrayList<String>();

		// stage 1 -- normal artifacts
		for (String specifier : artifacts) {
			Artifact artifact = createArtifact(specifier, Collections.<DependencyNode> emptyList(), true);
			if (artifact == null) {
				versionReferencingArtifacts.add(specifier);
				continue;
			}

			try {
				result.add(resolveDependencies(artifact));
			} catch (DependencyResolutionException e) {
				throw new MojoExecutionException("Unable to resolve dependencies for " + artifact, e);
			}
		}

		if (result.isEmpty()) {
			throw new MojoExecutionException("Must specify at least 1 (non-referencing) artifact to build");
		}

		// stage 2 -- referencing artifacts
		for (String specifier : versionReferencingArtifacts) {
			Artifact artifact = createArtifact(specifier, result, true);
			if (artifact != null) {
				try {
					result.add(resolveDependencies(artifact));
				} catch (DependencyResolutionException e) {
					getLog().warn("Unable to resolve dependencies for " + artifact, e);
				}
			}
		}

		return result;
	}

	private DependencyNode resolveDependencies(final Artifact artifact) throws DependencyResolutionException {
		// don't resolve build dependencies for artifacts that are going to be installed; they are not used
		if (ignoreArtifacts.include(artifact)) {
			return dependencyCollector.resolveProjectDependencies(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
					null, session);
		}

		return dependencyCollector.resolveBuildDependencies(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), null,
				session);
	}

	private void setupDefaultIgnores() {
		List<String> includes = new ArrayList<String>(ignoreArtifacts.getIncludes());
		includes.add("org.apache.maven.plugins");
		includes.add(SUREFIRE_GROUPID); // ensures dependencies below are installed
		ignoreArtifacts.setIncludes(includes);

		// implicit dependencies defined by the default plugins covered above in the most common packaging types
		artifacts.add(String.format("%s:surefire-junit3:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
		artifacts.add(String.format("%s:surefire-junit4:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
		artifacts.add(String.format("%s:surefire-junit47:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
		artifacts.add(String.format("%s:surefire-testng:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
	}

	private List<DependencyNode> findMissingDependencies(final DependencyNode root, final Collection<Artifact> builtArtifacts) {
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

	private List<DependencyNode> collectArtifactsToBuild(final List<DependencyNode> graphs) {
		List<DependencyNode> result = new ArrayList<DependencyNode>();
		for (DependencyNode graph : graphs) {
			BuildingDependencyNodeVisitor collector = new BuildingDependencyNodeVisitor();
			DependencyNodeVisitor filter = new FilteringDependencyNodeVisitor(collector, new InversionDependencyNodeFilter(
					new DependencyNodeAncestorOrSelfArtifactFilter(new ArtifactDependencyNodeFilter(ignoreArtifacts))));
			graph.accept(filter);

			DependencyNode node = collector.getDependencyTree();
			if (node != null) {
				result.add(node);
			}
		}
		return result;
	}

	private List<DependencyNode> collectArtifactsToInstall(final List<DependencyNode> graphs) throws MojoExecutionException {
		List<DependencyNode> result = new ArrayList<DependencyNode>();

		for (DependencyNode graph : graphs) {
			CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
			DependencyNodeVisitor filter = new FilteringDependencyNodeVisitor(collector, new ArtifactDependencyNodeFilter(ignoreArtifacts));
			graph.accept(filter);

			result.addAll(collector.getNodes());
		}

		return result;
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

	private Artifact createArtifact(final String specifier, final List<DependencyNode> graphs, final boolean allowVersionless)
			throws MojoExecutionException {
		Pattern artifactPattern = Pattern.compile("^([^:]+):([^:]+)(?::\\{([^:]+):([^:]+)\\}|:(.+))?$");
		final int refGroupIdGroup = 3; // NOPMD
		final int refArtifactGroup = 4; // NOPMD
		final int versionGroup = 5; // NOPMD

		Matcher matcher = artifactPattern.matcher(specifier);
		if (!matcher.find()) {
			throw new MojoExecutionException("Artifact `" + specifier
					+ "` is not valid, must `have format <groupId>:<artifactId> or <groupId>:<artifactId>:<version>");
		}

		String groupId = matcher.group(1);
		String artifactId = matcher.group(2);
		String version = "LATEST";

		if (matcher.group(versionGroup) != null) {
			version = matcher.group(versionGroup);
		} else if (!allowVersionless) {
			return null;
		}

		// specifier can be resolved now completely
		if (matcher.group(refGroupIdGroup) == null || matcher.group(refArtifactGroup) == null) {
			return repositorySystem.createProjectArtifact(groupId, artifactId, version);
		}

		// otherwise it must be a referencing another artifact
		String refGroupId = matcher.group(refGroupIdGroup);
		String refArtifactId = matcher.group(refArtifactGroup);
		for (DependencyNode graph : graphs) {
			DetectArtifactVisitor visitor = new DetectArtifactVisitor(refGroupId, refArtifactId);
			graph.accept(visitor);

			if (visitor.foundArtifact != null) {
				return repositorySystem.createProjectArtifact(groupId, artifactId, visitor.foundArtifact.getVersion());
			}
		}

		return null;
	}

	public void setBuildStrategies(final List<BuildStrategy> buildStrategies) {
		this.buildStrategies = buildStrategies;
	}

	/** Detects an artifact with a particular group and artifact id. */
	private static class DetectArtifactVisitor implements DependencyNodeVisitor {
		private final String groupId;
		private final String artifactId;
		private Artifact foundArtifact;
		private boolean stillLooking = true;

		public DetectArtifactVisitor(final String groupId, final String artifactId) {
			this.groupId = groupId;
			this.artifactId = artifactId;
		}

		@Override
		public boolean visit(final DependencyNode node) {
			Artifact artifact = node.getArtifact();
			if (stillLooking && artifact.getGroupId().equals(groupId) && artifact.getArtifactId().equals(artifactId)) {
				stillLooking = false;
				foundArtifact = artifact;
			}

			return stillLooking;
		}

		@Override
		public boolean endVisit(final DependencyNode node) {
			return stillLooking;
		}
	}
}
