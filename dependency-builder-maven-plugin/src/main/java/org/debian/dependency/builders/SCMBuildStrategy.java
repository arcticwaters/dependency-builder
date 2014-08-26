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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.model.building.ModelBuildingRequest;
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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.twdata.maven.mojoexecutor.MojoExecutor;

/**
 * An attempt to build artifacts using their source control URL. This strategy attempts to use <scm/> information in an artifacts
 * pom. First using the developer connection and falling back to the regular connection if the developer connection is not
 * accessible.
 */
@Component(role = BuildStrategy.class, hint = "scm")
public class SCMBuildStrategy extends AbstractLogEnabled implements BuildStrategy {
	private static final int PRIORITY = 100;
	private static final String PLUGIN_NAME = "dependency-builder-maven-plugin";
	private static final String TRACKING_REF = "refs/tracking/dependency-builder-maven-plugin";
	private static final String TRACKING_MESSAGE = "Dependency builder tracking commit";

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

		MavenProject rootProject = findProjectRoot(project, session);
		if (rootProject == null) {
			return Collections.emptySet();
		}

		// some build systems may not work well with colon in their name (and ntfs just can't handle it)
		String projectId = rootProject.getId().replace(':', '#');
		File checkoutDir = new File(session.getWorkDirectory(), projectId);
		checkoutDir.mkdirs();
		getLogger().debug("Source location for " + root.getArtifact() + " is " + checkoutDir);

		BottomUpDependencyCollectingVisitor visitor = new BottomUpDependencyCollectingVisitor();
		for (DependencyNode node : root.getChildren()) {
			node.accept(visitor);
		}

		try {
			getLogger().debug("Checking for tracked repository to continue from");
			if (needsCheckout(checkoutDir)) {
				getLogger().debug("No tracked repository at location, checking out new sources: " + checkoutDir);
				String scmUrl = checkoutSource(rootProject, checkoutDir, session);
				setupGitRepo(checkoutDir, scmUrl);
			}
			Git git = Git.open(checkoutDir);

			SourceBuilder builder = sourceBuilderManager.detect(checkoutDir);
			if (builder == null) {
				return Collections.emptySet();
			}
			getLogger().debug("Resolved build strategy " + builder + " for " + project);

			getLogger().debug("Checking if any dependencies are in the same project");
			Set<Artifact> built = new HashSet<Artifact>();
			for (Artifact artifact : visitor.artifacts) {
				MavenProject depProject = constructProject(artifact, session);

				// ensures subtleties such as null/empty string are maintained
				artifact.setFile(depProject.getArtifact().getFile());
				depProject.setArtifact(artifact);

				if (builder.canBuild(depProject, checkoutDir)) {
					getLogger().debug("Aritfact reported in the same repository: " + artifact);
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

	private boolean needsCheckout(final File checkoutDir) throws IOException {
		try {
			Git git = Git.open(checkoutDir);

			Ref trackingRef = null;
			Map<String, Ref> references = git.getRepository().getAllRefs();
			for (Entry<String, Ref> reference : references.entrySet()) {
				if (TRACKING_REF.equals(reference.getKey())) {
					trackingRef = reference.getValue();
					break;
				}
			}

			if (trackingRef != null) {
				for (RevCommit commit : git.log().add(trackingRef.getObjectId()).call()) {
					if (commit.getShortMessage().equals(TRACKING_MESSAGE)) {
						return false;
					}
				}
			}
			return true;
		} catch (RepositoryNotFoundException e) {
			return true;
		} catch (GitAPIException e) {
			return true;
		}
	}

	private Git setupGitRepo(final File checkoutDir, final String scmUrl) throws ArtifactBuildException {
		try {
			Git git;
			try {
				// check to see if we checked out a git repo
				git = Git.open(checkoutDir);
			} catch (RepositoryNotFoundException e) {
				git = Git.init().setDirectory(checkoutDir).call();

				git.add()
						.addFilepattern(".")
						.call();

				RmCommand rmCommand = git.rm();
				List<String> excludes = FileUtils.getFileNames(checkoutDir, FileUtils.getDefaultExcludesAsString(), null, false);
				for (String exclude : excludes) {
					rmCommand.addFilepattern(exclude);
				}
				if (!excludes.isEmpty()) {
					rmCommand.call();
				}

				git.commit()
						.setMessage("Importing upstream from " + scmUrl)
						.call();
			}

			git.checkout()
					.setOrphan(true)
					.setName(TRACKING_REF)
					.setCreateBranch(true)
					.setForce(true)
					.call();

			StringBuilder message = new StringBuilder(TRACKING_MESSAGE);
			message.append("\n\n");
			message.append("This is a tracking commit to denote that this repository has been tracked by the\n");
			message.append(PLUGIN_NAME + ". It should not be deleted unless you intend to\n");
			message.append("recreate the repository from scratch.");

			git.commit()
					.setMessage(message.toString())
					.call();

			ensureOnCorrectBranch(git);
			return git;
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		} catch (GitAPIException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private void ensureOnCorrectBranch(final Git git) throws IOException, GitAPIException {
		Ref branchRef = git.getRepository().getRef(PLUGIN_NAME);
		if (branchRef != null) {
			return;
		}

		git.checkout()
				.setName(PLUGIN_NAME)
				.setCreateBranch(true)
				.setForce(true)
				.call();

		git.clean().setCleanDirectories(true).call();
	}

	/*
	 * For multi-module projects, Maven appends the module name onto the scm url. Obviously this doesn't sit well with every VCS,
	 * so we look for the project root instead.
	 */
	private MavenProject findProjectRoot(final MavenProject project, final BuildSession session) {
		for (MavenProject parent = project; parent != null; parent = parent.getParent()) {
			// has the scm information been overridden for the project
			String artifactKey = ArtifactUtils.key(project.getArtifact());
			if (session.getArtifactScmOverrides().containsKey(artifactKey)) {
				Scm scm = new Scm();
				scm.setConnection(session.getArtifactScmOverrides().get(artifactKey));
				project.setScm(scm);
				return project;
			}

			// is scm information explicitly provided
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
			request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

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

		/*
		 * Projects with custom packaging may not resolve completely, but they appear to set the file of the artifact accurately.
		 * This is really all we're interested in.
		 */
		if (toResolve.getFile() != null) {
			return toResolve;
		}
		return result.getArtifacts().iterator().next();
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}

	/** Collects unique dependencies from a graph in a bottom up fashion, i.e. those without dependencies first. */
	private static class BottomUpDependencyCollectingVisitor implements DependencyNodeVisitor {
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
