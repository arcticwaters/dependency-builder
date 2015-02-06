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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.util.FileUtils;
import org.debian.dependency.builders.ArtifactBuildException;
import org.debian.dependency.builders.SourceBuilderManager;
import org.debian.dependency.sources.Source;
import org.debian.dependency.sources.SourceRetrievalException;
import org.debian.dependency.sources.SourceRetrievalManager;

import com.google.common.collect.Iterators;
import com.google.common.io.Files;

/** Builds the dependencies of a project which deploys Maven metadata. */
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
	private final StrictPatternArtifactFilter ignores = new StrictPatternArtifactFilter(false);
	/** Directory where local git repositories should be made for potential modifications. */
	@Parameter(defaultValue = "${project.build.directory}/dependency-builder/work")
	private File workDirectory;
	/** Whether to allow more than a single project to be built. */
	@Parameter
	private boolean multiProject;

	@Parameter(defaultValue = "${session}")
	private MavenSession session;

	@Component
	private RepositorySystem repositorySystem;
	@Component
	private SourceRetrievalManager sourceRetrievalManager;
	@Component
	private SourceBuilderManager sourceBuilderManager;
	@Component
	private DependencyCollection dependencyCollection;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (artifact != null) {
			Set<String> newArtifacts = new LinkedHashSet<String>();
			newArtifacts.add(artifact);
			newArtifacts.addAll(artifacts);
			artifacts = newArtifacts;
		}

		setupDefaultIgnores();

		try {
			Files.createParentDirs(outputDirectory);
			FileUtils.forceMkdir(workDirectory);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write file system", e);
		}

		try {
			ArtifactRepository repository = repositorySystem.createLocalRepository(outputDirectory);
			List<DependencyNode> graphs = createArtifactGraph();

			getLog().debug("Installing ignored artifacts");
			List<DependencyNode> toBuild = dependencyCollection.installDependencies(graphs,
					new ArtifactDependencyNodeFilter(ignores), repository, session);
			if (toBuild.isEmpty()) {
				throw new MojoFailureException("All artifacts were ignored and installed, nothing to build!");
			}

			for (Iterator<DependencyNode> iter = toBuild.iterator(); iter.hasNext();) {
				DependencyNode node = iter.next();
				Set<Artifact> builtArtifacts = buildDependencyGraph(node);
				checkSingleProjectFailure(node, iter, builtArtifacts);
			}
		} catch (InvalidRepositoryException e) {
			throw new MojoExecutionException("Unable to create local repository", e);
		} catch (DependencyResolutionException e) {
			throw new MojoExecutionException("Unable to resolve dependencies", e);
		} catch (ArtifactInstallationException e) {
			throw new MojoExecutionException("Unable to install ignored artifact", e);
		}
	}

	private void checkSingleProjectFailure(final DependencyNode current, final Iterator<DependencyNode> rest, final Set<Artifact> builtArtifacts)
			throws MojoFailureException {
		if (multiProject) {
			return;
		}

		// collect artifacts to report for failure
		CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
		current.accept(collector);
		while (rest.hasNext()) {
			rest.next().accept(collector);
		}

		Set<Artifact> artifacts = new HashSet<Artifact>(collector.getNodes().size());
		for (DependencyNode node : collector.getNodes()) {
			artifacts.add(node.getArtifact());
		}
		artifacts.removeAll(builtArtifacts);

		if (!artifacts.isEmpty()) {
			getLog().error("Some dependencies were not built, run again with the artifacts:");
			for (Artifact artifact : artifacts) {
				getLog().error(" * " + artifact);
			}
			throw new MojoFailureException("Unable to build artifact, unmet dependencies: "
					+ collector.getNodes().get(0).getArtifact());
		}
	}

	private Set<Artifact> buildDependencyGraph(final DependencyNode graph) throws MojoFailureException, MojoExecutionException {
		Iterator<DependencyNode> iter;
		if (multiProject) {
			CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
			graph.accept(collector);

			// reversed ensures dependencies of dependencies are built first
			iter = new ReversedIterator<DependencyNode>(collector.getNodes());
		} else {
			iter = Iterators.singletonIterator(graph);
		}

		Set<Artifact> result = new HashSet<Artifact>();
		while (iter.hasNext()) {
			DependencyNode node = iter.next();
			try {
				Source source = sourceRetrievalManager.checkoutSource(node.getArtifact(), workDirectory, session);
				Set<Artifact> built = sourceBuilderManager.build(node.getArtifact(), source, outputDirectory);
				if (!built.contains(node.getArtifact())) {
					getLog().warn("Artifact not found in built artifacts: " + node.getArtifact());
				}
				result.addAll(built);
			} catch (SourceRetrievalException e) {
				throw new MojoExecutionException("Unable to retrieve source: " + node.getArtifact(), e);
			} catch (ArtifactBuildException e) {
				throw new MojoExecutionException("Unable to build artifact: " + node.getArtifact(), e);
			}
		}
		return result;
	}

	private List<DependencyNode> createArtifactGraph() throws MojoFailureException, MojoExecutionException {
		List<DependencyNode> result = new ArrayList<DependencyNode>();
		List<String> versionReferencingArtifacts = new ArrayList<String>();

		// stage 1 -- normal artifacts
		for (String specifier : artifacts) {
			Artifact artifact = createArtifact(specifier, Collections.<DependencyNode> emptyList());
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
			throw new MojoFailureException("Must specify at least 1 (non-referencing) artifact to build");
		}

		// stage 2 -- referencing artifacts
		for (String specifier : versionReferencingArtifacts) {
			Artifact artifact = createArtifact(specifier, result);
			if (artifact != null) {
				try {
					result.add(resolveDependencies(artifact));
				} catch (DependencyResolutionException e) {
					throw new MojoExecutionException("Unable to resolve dependencies for " + artifact, e);
				}
			}
		}

		return result;
	}

	private DependencyNode resolveDependencies(final Artifact artifact) throws DependencyResolutionException {
		// don't resolve build dependencies for artifacts that are going to be installed; they are not used
		if (ignores.include(artifact)) {
			return dependencyCollection.resolveProjectDependencies(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
					null, session);
		}

		return dependencyCollection.resolveBuildDependencies(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), null,
				session);
	}

	private void setupDefaultIgnores() {
		List<String> includes = new ArrayList<String>(ignores.getIncludes());
		includes.add("org.apache.maven.plugins");
		includes.add(SUREFIRE_GROUPID); // ensures dependencies below are installed
		ignores.setIncludes(includes);

		// implicit dependencies defined by the default plugins covered above in the most common packaging types
		artifacts.add(String.format("%s:surefire-junit3:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
		artifacts.add(String.format("%s:surefire-junit4:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
		artifacts.add(String.format("%s:surefire-junit47:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
		artifacts.add(String.format("%s:surefire-testng:%s", SUREFIRE_GROUPID, SUREFIRE_PLUGIN_VERSION));
	}

	private Artifact createArtifact(final String specifier, final List<DependencyNode> graphs) throws MojoExecutionException {
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
