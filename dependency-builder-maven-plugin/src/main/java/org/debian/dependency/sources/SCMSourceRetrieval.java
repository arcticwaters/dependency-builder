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
package org.debian.dependency.sources;

import java.io.File;
import java.util.List;

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
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.StringUtils;

/**
 * Artifact sources are retrieved from the <scm/> information in an artifacts pom. First using the developer connection and
 * falling back to the regular connection if the developer connection is not accessible.
 */
@Component(role = SourceRetrieval.class, hint = "scm")
public class SCMSourceRetrieval extends AbstractLogEnabled implements SourceRetrieval {
	private static final int PRIORITY = PRIORITY_HIGH + PRIORITY_LOW / 2;

	@Requirement
	private ProjectBuilder projectBuilder;
	@Requirement
	private RepositorySystem repositorySystem;
	@Requirement
	private ScmManager scmManager;
	@Requirement
	private SettingsDecrypter settingsDecrypter;

	@Override
	public String getSourceLocation(final Artifact artifact, final MavenSession session) throws SourceRetrievalException {
		MavenProject project = findProjectRoot(constructProject(artifact, session));
		Scm scm = project.getScm();
		if (scm == null) {
			return null;
		}

		if (!StringUtils.isEmpty(scm.getConnection())) {
			return scm.getConnection();
		}
		return scm.getDeveloperConnection();
	}

	/*
	 * For multi-module projects, Maven appends the module name onto the scm url. Obviously this doesn't sit well with every VCS,
	 * so we look for the project root instead.
	 */
	private MavenProject findProjectRoot(final MavenProject project) {
		// if this project doesn't have one, then its parents won't have one either
		if (project.getScm() == null) {
			return project;
		}

		for (MavenProject parent = project; parent != null; parent = parent.getParent()) {
			if (parent.getOriginalModel().getScm() != null) {
				return parent;
			}
		}

		return project;
	}

	@Override
	public String retrieveSource(final Artifact artifact, final File directory, final MavenSession session)
			throws SourceRetrievalException {
		MavenProject project = findProjectRoot(constructProject(artifact, session));
		Scm scm = project.getScm();
		if (scm == null) {
			return null;
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
				checkoutResult = performCheckout(connection, determineVersion(scm), directory, decryptionResult.getServers());
			} catch (ScmException e) {
				// we don't really care about the exception here because we will try the regular connection next
				getLogger().debug("Unable to checkout sources using developer connection, trying standard connection", e);
			}

			// now the regular connection if it wasn't successful
			if (checkoutResult == null || !checkoutResult.isSuccess()) {
				connection = scm.getConnection();
				checkoutResult = performCheckout(connection, determineVersion(scm), directory, decryptionResult.getServers());
			}

			if (checkoutResult == null) {
				throw new SourceRetrievalException("No scm information available");
			} else if (!checkoutResult.isSuccess()) {
				getLogger().error("Provider Message:");
				getLogger().error(StringUtils.defaultString(checkoutResult.getProviderMessage()));
				getLogger().error("Commandline:");
				getLogger().error(StringUtils.defaultString(checkoutResult.getCommandOutput()));
				throw new SourceRetrievalException("Unable to checkout files: "
						+ StringUtils.defaultString(checkoutResult.getProviderMessage()));
			}
			return connection;
		} catch (ScmException e) {
			throw new SourceRetrievalException("Unable to checkout project", e);
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

	private MavenProject constructProject(final Artifact artifact, final MavenSession session) throws SourceRetrievalException {
		try {
			// pom files are not set up in projects which are not in the workspace, we add them in manually since they are needed
			Artifact pomArtifact = repositorySystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
			pomArtifact = resolveArtifact(pomArtifact, session);

			ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			request.setActiveProfileIds(null);
			request.setInactiveProfileIds(null);
			request.setUserProperties(null);

			ProjectBuildingResult result = projectBuilder.build(artifact, request);

			MavenProject mavenProject = result.getProject();
			mavenProject.setArtifact(resolveArtifact(mavenProject.getArtifact(), session));
			mavenProject.setFile(pomArtifact.getFile());
			return mavenProject;
		} catch (ProjectBuildingException e) {
			throw new SourceRetrievalException(e);
		}
	}

	private Artifact resolveArtifact(final Artifact toResolve, final MavenSession session) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setLocalRepository(session.getLocalRepository())
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

	@Override
	public String getSourceDirname(final Artifact artifact, final MavenSession session) throws SourceRetrievalException {
		MavenProject project = findProjectRoot(constructProject(artifact, session));
		return project.getId();
	}
}
