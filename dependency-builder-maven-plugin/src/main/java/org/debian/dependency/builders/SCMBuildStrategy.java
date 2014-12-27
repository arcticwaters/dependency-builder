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
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmTag;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProviderRepositoryWithHost;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Ref;

/**
 * An attempt to build artifacts using their source control URL. This strategy attempts to use <scm/> information in an artifacts
 * pom. First using the developer connection and falling back to the regular connection if the developer connection is not
 * accessible.
 */
@Component(role = BuildStrategy.class, hint = "scm")
public class SCMBuildStrategy extends AbstractLogEnabled implements BuildStrategy {
	private static final int PRIORITY = 100;
	private static final String WORK_BRANCH = "dependency-builder-maven-plugin";

	@Requirement
	private ProjectBuilder projectBuilder;
	@Requirement
	private SourceBuilderManager sourceBuilderManager;
	@Requirement
	private RepositorySystem repositorySystem;
	@Requirement
	private ScmManager scmManager;
	@Requirement
	private SettingsDecrypter settingsDecrypter;

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

		// some build systems may not work well with colon in their name (and ntfs just can't handle it)
		String projectId = rootProject.getId().replace(':', '%');
		File checkoutDir = new File(session.getCheckoutDirectory(), projectId);
		checkoutDir.mkdirs();
		File workDir = new File(session.getWorkDirectory(), projectId);
		workDir.mkdirs();

		String scmUrl = checkoutSource(rootProject, checkoutDir, session.getMavenSession());
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

	private String checkoutSource(final MavenProject project, final File checkoutDirectory, final MavenSession session)
			throws ArtifactBuildException {
		Scm scm = project.getScm();
		if (scm == null) {
			scm = new Scm();
		}

		SettingsDecryptionResult decryptionResult = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(session.getSettings()));
		for (SettingsProblem problem : decryptionResult.getProblems()) {
			getLogger().warn("Error decrypting settings (" + problem.getLocation() + ") : " + problem.getMessage(), problem.getException());
		}

		try {
			// first we check developer connection
			CheckOutScmResult checkoutResult = null;
			String connection = scm.getDeveloperConnection();
			try {
				checkoutResult = performCheckout(connection, determineVersion(scm), checkoutDirectory, decryptionResult.getServers());
			} catch (ScmException e) {
				// we don't really care about the exception here because we will try the regular connection next
				getLogger().debug("Unable to checkout sources using developer connection, trying standard connection", e);
			}

			// now the regular connection if it wasn't successful
			if (checkoutResult == null || !checkoutResult.isSuccess()) {
				connection = scm.getConnection();
				checkoutResult = performCheckout(connection, determineVersion(scm), checkoutDirectory, decryptionResult.getServers());
			}

			if (checkoutResult == null) {
				throw new ArtifactBuildException("No scm information available");
			} else if (!checkoutResult.isSuccess()) {
				getLogger().error("Provider Message:");
				getLogger().error(StringUtils.defaultString(checkoutResult.getProviderMessage()));
				getLogger().error("Commandline:");
				getLogger().error(StringUtils.defaultString(checkoutResult.getCommandOutput()));
				throw new ArtifactBuildException("Unable to checkout files: "
						+ StringUtils.defaultString(checkoutResult.getProviderMessage()));
			}
			return connection;
		} catch (ScmException e) {
			throw new ArtifactBuildException("Unable to checkout project", e);
		}
	}

	private CheckOutScmResult performCheckout(final String connection, final ScmVersion version, final File directory,
			final List<Server> servers) throws ScmException {
		if (StringUtils.isEmpty(connection)) {
			return null;
		}

		ScmRepository repository = scmManager.makeScmRepository(connection);
		if (repository.getProviderRepository() instanceof ScmProviderRepositoryWithHost) {
			ScmProviderRepositoryWithHost repo = (ScmProviderRepositoryWithHost) repository.getProviderRepository();
			StringBuilder builder = new StringBuilder(repo.getHost());
			int port = repo.getPort();
			if (port > 0) {
				builder.append(':').append(port);
			}

			String host = builder.toString();
			for (Server server : servers) {
				if (server.getId().equals(host)) {
					repo.setPassphrase(server.getPassphrase());
					repo.setPassword(server.getPassword());
					repo.setPrivateKey(server.getPrivateKey());
					repo.setUser(server.getUsername());
					break;
				}
			}
		}
		return scmManager.checkOut(repository, new ScmFileSet(directory), version);
	}

	private ScmVersion determineVersion(final Scm scm) {
		/*
		 * Some scm providers don't work with tags (even the default "HEAD"), i.e. local scm provider. Null will use the default
		 * branch for most scms.
		 */
		if (StringUtils.isEmpty(scm.getTag()) || "HEAD".equals(scm.getTag())) {
			return null;
		}
		return new ScmTag(scm.getTag());
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
