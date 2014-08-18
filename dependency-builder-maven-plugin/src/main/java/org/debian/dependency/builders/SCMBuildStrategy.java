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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Configuration;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Ref;
import org.twdata.maven.mojoexecutor.MojoExecutor;

/**
 * An attempt to build artifacts using their source control URL. This strategy attempts to use <scm/> information in an artifacts
 * pom. First using the developer connection and falling back to the regular connection if the developer connection is not
 * accessible.
 */
@Component(role = BuildStrategy.class, hint = "scm")
public class SCMBuildStrategy extends AbstractLogEnabled implements BuildStrategy {
	private static final int PRIORITY = 100;
	private static final String WORK_BRANCH = "dependency-builder-maven-plugin";
	@Configuration(value = "org.apache.maven.plugins")
	private String scmPluginGroupId;
	@Configuration(value = "maven-scm-plugin")
	private String scmPluginArtifactId;
	@Configuration(value = "1.9")
	private String scmPluginVersion;
	@Configuration(value = "checkout")
	private String scmPluginGoalCheckout;
	@Configuration(value = "checkoutDirectory")
	private String scmPluginPropCheckoutDir;
	@Configuration(value = "connectionType")
	private String scmPluginPropConnectionType;
	@Configuration(value = "connection")
	private String scmPluginConnectionTypeCon;
	@Configuration(value = "developerConnection")
	private String scmPluginConnectionTypeDev;

	@Requirement
	private ProjectBuilder projectBuilder;
	@Requirement
	private BuildPluginManager buildPluginManager;
	@Requirement
	private SourceBuilderManager sourceBuilderManager;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public Set<Artifact> build(final DependencyNode root, final BuildSession session) throws ArtifactBuildException {
		MavenProject project = constructProject(root.getArtifact(), session);

		// ensures subtleties such as null/empty string are maintained
		root.getArtifact().setFile(project.getArtifact().getFile());
		project.setArtifact(root.getArtifact());

		MavenProject rootProject = findProjectRoot(project);
		if (rootProject == null) {
			return Collections.emptySet();
		}

		File checkoutDir = new File(session.getCheckoutDirectory(), rootProject.getId().toString());
		checkoutDir.mkdirs();
		File workDir = new File(session.getWorkDirectory(), rootProject.getId().toString());
		workDir.mkdirs();

		String scmUrl = checkoutSource(rootProject, checkoutDir, session);
		Git git = makeLocalCopy(checkoutDir, workDir, scmUrl);

		DependencyCollectingVisitor visitor = new DependencyCollectingVisitor();
		for (DependencyNode node : root.getChildren()) {
			node.accept(visitor);
		}

		try {
			SourceBuilder builder = sourceBuilderManager.detect(checkoutDir);
			if (builder == null) {
				return Collections.emptySet();
			}
			getLogger().debug("Resolved build strategy " + builder + " for " + project);

			final Set<Artifact> built = new HashSet<Artifact>();
			for (Artifact artifact : visitor.artifacts) {
				MavenProject depProject = constructProject(artifact, session);

				// ensures subtleties such as null/empty string are maintained
				artifact.setFile(depProject.getArtifact().getFile());
				depProject.setArtifact(artifact);

				if (builder.canBuild(depProject, checkoutDir)) {
					built.addAll(builder.build(depProject, git, session.getTargetRepository()));
				}
			}

			// we got scm info from root dependency node, it must be built
			built.addAll(builder.build(project, git, session.getTargetRepository()));
			return built;
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private Git makeLocalCopy(final File checkoutDir, final File workDir, final String scmUrl) throws ArtifactBuildException {
		try {
			// if the work directory is already a git repo, we assume its setup correctly
			try {
				Git git = Git.open(workDir);
				ensureOnCorrectBranch(git);
				return git;
			} catch (RepositoryNotFoundException e) {
				getLogger().debug("No repository found at " + workDir + ", creating from " + checkoutDir);
			}

			// otherwise we need to make a copy from the checkout
			Git git;
			try {
				// check to see if we checked out a git repo
				Git.open(checkoutDir);

				FileUtils.copyDirectoryStructure(checkoutDir, workDir);
				git = Git.open(workDir);
			} catch (RepositoryNotFoundException e) {
				FileUtils.copyDirectoryStructure(checkoutDir, workDir);

				git = Git.init().setDirectory(workDir).call();
				git.add().addFilepattern(".").call();

				// we don't want to track other SCM metadata files
				RmCommand removeAction = git.rm();
				for (String pattern : FileUtils.getDefaultExcludes()) {
					removeAction.addFilepattern(pattern);
				}
				removeAction.call();

				git.commit().setMessage("Import upstream from " + scmUrl).call();
			}

			ensureOnCorrectBranch(git);
			return git;
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		} catch (GitAPIException e) {
			throw new ArtifactBuildException(e);
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

	/*
	 * For multi-module projects, Maven appends the module name onto the scm url. Obviously this doesn't sit well with every VCS,
	 * so we look for the project root instead.
	 */
	private MavenProject findProjectRoot(final MavenProject project) {
		// if this project doesn't have one, then its parents won't have one either
		if (project.getScm() == null) {
			return null;
		}

		for (MavenProject parent = project; parent != null; parent = parent.getParent()) {
			if (parent.getOriginalModel().getScm() != null) {
				return parent;
			}
		}

		return null;
	}

	@SuppressWarnings("PMD.PreserveStackTrace")
	private String checkoutSource(final MavenProject resolvedProject, final File artifactDir, final BuildSession buildSession)
			throws ArtifactBuildException {
		MavenSession mavenSession = buildSession.getMavenSession();
		MavenProject currentProject = mavenSession.getCurrentProject();
		try {
			// we override the current project so that maven-scm-plugin can read properties directly from that project
			mavenSession.setCurrentProject(resolvedProject);

			try {
				// first try the developerConnection
				MojoExecutor.executeMojo(
						MojoExecutor.plugin(scmPluginGroupId, scmPluginArtifactId, scmPluginVersion, buildSession.getExtensions()),
						scmPluginGoalCheckout,
						MojoExecutor.configuration(
								MojoExecutor.element(scmPluginPropCheckoutDir, artifactDir.toString()),
								MojoExecutor.element(scmPluginPropConnectionType, scmPluginConnectionTypeDev)
								),
						MojoExecutor.executionEnvironment(currentProject, mavenSession, buildPluginManager)
						);
				return resolvedProject.getScm().getDeveloperConnection();
			} catch (MojoExecutionException e) {
				try {
					// now the regular connection
					MojoExecutor.executeMojo(
							MojoExecutor.plugin(scmPluginGroupId, scmPluginArtifactId, scmPluginVersion, buildSession.getExtensions()),
							scmPluginGoalCheckout,
							MojoExecutor.configuration(
									MojoExecutor.element(scmPluginPropCheckoutDir, artifactDir.toString()),
									MojoExecutor.element(scmPluginPropConnectionType, scmPluginConnectionTypeCon)
									),
							MojoExecutor.executionEnvironment(currentProject, mavenSession, buildPluginManager)
							);
					return resolvedProject.getScm().getConnection();
				} catch (MojoExecutionException f) {
					throw new ArtifactBuildException(f);
				}
			}
		} finally {
			mavenSession.setCurrentProject(currentProject);
		}
	}

	private MavenProject constructProject(final Artifact artifact, final BuildSession session) throws ArtifactBuildException {
		try {
			// pom files are not set up in projects which are not in the workspace, we add them in manually since they are needed
			Artifact pomArtifact = repositorySystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
			pomArtifact = resolveArtifact(pomArtifact, session);

			ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getMavenSession().getProjectBuildingRequest());
			request.setActiveProfileIds(null);
			request.setInactiveProfileIds(null);
			request.setUserProperties(null);

			ProjectBuildingResult result = projectBuilder.build(artifact, request);

			MavenProject mavenProject = result.getProject();
			mavenProject.setArtifact(resolveArtifact(mavenProject.getArtifact(), session));
			mavenProject.setFile(pomArtifact.getFile());
			return mavenProject;
		} catch (ProjectBuildingException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private Artifact resolveArtifact(final Artifact toResolve, final BuildSession session) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setLocalRepository(session.getMavenSession().getLocalRepository())
				.setOffline(true)
				.setResolveRoot(true)
				.setArtifact(toResolve);

		ArtifactResolutionResult result = repositorySystem.resolve(request);
		return result.getArtifacts().iterator().next();
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}

	/** Collects unique dependencies from a graph in a bottom up fashion, i.e. those without dependencies first. */
	private static class DependencyCollectingVisitor implements DependencyNodeVisitor {
		private final Set<Artifact> artifacts = new LinkedHashSet<Artifact>();

		@Override
		public boolean visit(final DependencyNode node) {
			return true;
		}

		@Override
		public boolean endVisit(final DependencyNode node) {
			artifacts.add(node.getArtifact());
			return true;
		}
	}
}
