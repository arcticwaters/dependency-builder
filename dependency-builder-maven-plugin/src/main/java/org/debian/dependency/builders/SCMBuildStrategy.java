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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Configuration;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.twdata.maven.mojoexecutor.MojoExecutor;

/**
 * An attempt to build artifacts using their source control URL. This strategy attempts to use <scm/> information in an artifacts
 * pom. First using the developer connection and falling back to the regular connection if the developer connection is not
 * accessible.
 */
@Component(role = BuildStrategy.class, hint = "scm")
public class SCMBuildStrategy extends AbstractLogEnabled implements BuildStrategy {
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
	@Requirement(hint = "maven2")
	private SourceBuilder builder;

	@Override
	public Set<Artifact> build(final DependencyNode root, final BuildSession session) throws ArtifactBuildException {
		MavenProject project = constructProject(root.getArtifact(), session);
		Scm scm = project.getScm();
		if (scm == null) {
			return Collections.emptySet();
		}

		final File artifactDir = new File(session.getWorkDirectory(), root.getArtifact().toString());
		artifactDir.mkdirs();

		checkoutSource(project, artifactDir, session);
		final Set<Artifact> built = new HashSet<Artifact>();

		DependencyCollectingVisitor visitor = new DependencyCollectingVisitor();
		for (DependencyNode node : root.getChildren()) {
			node.accept(visitor);
		}
		try {
			for (Artifact artifact : visitor.artifacts) {
				if (builder.canBuild(artifact, artifactDir)) {
					built.addAll(builder.build(artifact, artifactDir, session.getTargetRepository()));
				}
			}
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}

		// we got scm info from the root not, do not gate
		built.addAll(builder.build(root.getArtifact(), artifactDir, session.getTargetRepository()));
		return built;
	}

	@SuppressWarnings("PMD.PreserveStackTrace")
	private void checkoutSource(final MavenProject resolvedProject, final File artifactDir, final BuildSession buildSession)
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
			ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getMavenSession().getProjectBuildingRequest());
			request.setActiveProfileIds(null);
			request.setInactiveProfileIds(null);
			request.setUserProperties(null);

			ProjectBuildingResult result = projectBuilder.build(artifact, request);
			return result.getProject();
		} catch (ProjectBuildingException e) {
			throw new ArtifactBuildException(e);
		}
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
