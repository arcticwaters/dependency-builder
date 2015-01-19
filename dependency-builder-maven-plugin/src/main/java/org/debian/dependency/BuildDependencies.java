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
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
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
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.FileUtils;
import org.debian.dependency.builders.ArtifactBuildException;
import org.debian.dependency.builders.SourceBuilder;
import org.debian.dependency.builders.SourceBuilderManager;
import org.debian.dependency.filters.DependencyNodeAncestorOrSelfArtifactFilter;
import org.debian.dependency.filters.InversionDependencyNodeFilter;
import org.debian.dependency.sources.SourceRetrieval;
import org.debian.dependency.sources.SourceRetrievalException;
import org.debian.dependency.sources.SourceRetrievalPriorityComparator;
import org.eclipse.aether.util.StringUtils;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Ref;

import com.google.common.collect.Iterators;
import com.google.common.io.Files;

/** Builds the dependencies of a project which deploys Maven metadata. */
@SuppressWarnings("PMD.GodClass")
@Mojo(name = "build-dependencies")
public class BuildDependencies extends AbstractMojo {
	private static final String SUREFIRE_GROUPID = "org.apache.maven.surefire";
	private static final String SUREFIRE_PLUGIN_VERSION = "{org.apache.maven.plugins:maven-surefire-plugin}";
	private static final String WORK_BRANCH = "dependency-builder-maven-plugin";

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

	@Parameter(defaultValue = "${session}")
	private MavenSession session;

	@Component
	private RepositorySystem repositorySystem;
	@Component
	private ArtifactInstaller artifactInstaller;
	@Component(role = SourceRetrieval.class)
	private List<SourceRetrieval> sourceRetrievals;
	@Requirement
	private SourceBuilderManager sourceBuilderManager;
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

		List<DependencyNode> graphs = createArtifactGraph();
		installArtifacts(collectArtifactsToInstall(graphs));

		if (!checkoutDirectory.mkdirs()) {
			throw new MojoExecutionException("Unable to create directory: " + checkoutDirectory);
		}
		if (!workDirectory.mkdirs()) {
			throw new MojoExecutionException("Unable to create directory: " + workDirectory);
		}

		List<DependencyNode> toBuild = collectArtifactsToBuild(graphs);
		for (Iterator<DependencyNode> graphsIter = toBuild.iterator(); graphsIter.hasNext();) {
			DependencyNode graph = graphsIter.next();
			Set<Artifact> builtArtifacts = buildDependencyGraph(graph);
			checkSingleProjectFailure(graph, graphsIter, builtArtifacts);
		}
	}

	private void checkSingleProjectFailure(final DependencyNode first, final Iterator<DependencyNode> rest,
			final Set<Artifact> builtArtifacts) throws MojoFailureException {
		if (multiProject) {
			return;
		}

		CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
		first.accept(collector);
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
			iter = Iterators.singletonIterator(graph);
		} else {
			CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
			graph.accept(collector);

			// reversed ensures dependencies of dependencies are built first
			iter = new ReversedIterator<DependencyNode>(collector.getNodes());
		}

		Set<Artifact> result = new HashSet<Artifact>();
		while (iter.hasNext()) {
			DependencyNode node = iter.next();
			try {
				Git git = checkoutSource(node);
				SourceBuilder builder = sourceBuilderManager.detect(git.getRepository().getWorkTree());
				if (builder == null) {
					throw new MojoFailureException("Unknown build system for " + node.getArtifact());
				}

				Set<Artifact> built = builder.build(node.getArtifact(), git, outputDirectory);
				if (!built.contains(node.getArtifact())) {
					getLog().warn("Artifact not found in built artifacts: " + node.getArtifact());
				}
				result.addAll(built);
			} catch (IOException e) {
				throw new MojoExecutionException("Unable to build artifact: " + node.getArtifact(), e);
			} catch (SourceRetrievalException e) {
				throw new MojoExecutionException("Unable to retrieve source: " + node.getArtifact(), e);
			} catch (ArtifactBuildException e) {
				throw new MojoExecutionException("Unable to build artifact: " + node.getArtifact(), e);
			}
		}
		return result;
	}

	private Git checkoutSource(final DependencyNode node) throws SourceRetrievalException, IOException {
		// checkout source of artifact
		String location = null;
		File nodeDir = createNewDir(checkoutDirectory, "WORKING-");
		SourceRetrieval selected = null;
		for (SourceRetrieval sourceRetrieval : sourceRetrievals) {
			location = sourceRetrieval.retrieveSource(node.getArtifact(), nodeDir, session);
			if (!StringUtils.isEmpty(location)) {
				selected = sourceRetrieval;
				break;
			}
		}

		String dirname = selected.getSourceDirname(node.getArtifact(), session);
		dirname = dirname.replaceAll("[^a-zA-Z0-9.-]", "_");
		File destination = new File(checkoutDirectory, dirname);
		Files.move(nodeDir, destination);
		nodeDir = destination;

		Git git = makeLocalCopy(nodeDir, new File(workDirectory, nodeDir.getName()), location);
		return git;
	}

	/*
	 * This can be replaced by java.nio.file.Files#createTempDir when java 1.7+ becomes the lowest supported version
	 */
	private static File createNewDir(final File parent, final String prefix) {
		int index = 0;
		File file;
		do {
			file = new File(parent, prefix + ++index);
		} while (!file.mkdir());
		return file;
	}

	private Git makeLocalCopy(final File fromdir, final File todir, final String location) throws IOException {
		try {
			// if the work directory is already a git repo, we assume its setup correctly
			try {
				Git git = Git.open(todir);
				ensureOnCorrectBranch(git);
				return git;
			} catch (RepositoryNotFoundException e) {
				getLog().debug("No repository found at " + todir + ", creating from " + fromdir);
			}

			// otherwise we need to make a copy from the checkout
			Git git;
			try {
				// check to see if we checked out a git repo
				Git.open(fromdir);

				FileUtils.copyDirectoryStructure(fromdir, todir);
				git = Git.open(todir);
			} catch (RepositoryNotFoundException e) {
				FileUtils.copyDirectoryStructure(fromdir, todir);

				git = Git.init().setDirectory(todir).call();
				git.add().addFilepattern(".").call();

				// we don't want to track other SCM metadata files
				RmCommand removeAction = git.rm();
				for (String pattern : FileUtils.getDefaultExcludes()) {
					if (pattern.startsWith(".git")) {
						continue;
					}
					removeAction.addFilepattern(pattern);
				}
				removeAction.call();

				git.commit().setMessage("Import upstream from " + location).call();
			}

			ensureOnCorrectBranch(git);
			return git;
		} catch (GitAPIException e) {
			throw new IOException(e);
		}
	}

	private void ensureOnCorrectBranch(final Git git) throws GitAPIException {
		String workBranchRefName = "refs/heads/" + WORK_BRANCH;

		// make sure that we are on the right branch, again assume its setup correctly if there
		Ref branchRef = null;
		for (Ref ref : git.branchList().call()) {
			if (workBranchRefName.equals(ref.getName())) {
				branchRef = ref;
				break;
			}
		}

		CheckoutCommand command = git.checkout().setName(WORK_BRANCH);
		if (branchRef == null) {
			command.setCreateBranch(true);
		}
		command.call();

		git.clean().setCleanDirectories(true).call();
	}

	private List<DependencyNode> createArtifactGraph() throws MojoExecutionException {
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
			throw new MojoExecutionException("Must specify at least 1 (non-referencing) artifact to build");
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

	/**
	 * Sets the {@link SourceRetrieval}s to use.
	 *
	 * @param sourceRetrievals source retrievals to use
	 */
	public void setSourceRetrievals(final List<SourceRetrieval> sourceRetrievals) {
		List<SourceRetrieval> newRetrievals = new ArrayList<SourceRetrieval>(sourceRetrievals);
		Collections.sort(newRetrievals, new SourceRetrievalPriorityComparator());
		this.sourceRetrievals = newRetrievals;
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
