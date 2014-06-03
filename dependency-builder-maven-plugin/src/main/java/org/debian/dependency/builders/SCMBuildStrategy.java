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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Configuration;
import org.codehaus.plexus.component.annotations.Requirement;
import org.twdata.maven.mojoexecutor.MojoExecutor;

/**
 * An attempt to build artifacts using their source control URL. This strategy attempts to use <scm/> information in an artifacts
 * pom. First using the developer connection and falling back to the regular connection if the developer connection is not
 * accessible.
 */
@Component(role = BuildStrategy.class, hint = "scm")
public class SCMBuildStrategy implements BuildStrategy {
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

	@Override
	public File buildArtifact(final Artifact artifact, final MavenSession mavenSession, final MojoExecution mojoExecution)
			throws ArtifactBuildException {
		MavenProject resolvedProject;

		try {
			ProjectBuildingResult request = projectBuilder.build(artifact, mavenSession.getProjectBuildingRequest());
			resolvedProject = request.getProject();
		} catch (ProjectBuildingException e) {
			throw new ArtifactBuildException(e);
		}

		Scm scm = resolvedProject.getScm();
		if (scm == null) {
			throw new ArtifactBuildException("No scm information for " + artifact);
		}

		File buildDirectory = new File(mavenSession.getCurrentProject().getBuild().getDirectory());
		File checkoutBasedir = new File(new File(buildDirectory, "dependency-builder"), "checkout");
		File artifactDir = new File(checkoutBasedir, artifact.toString());
		artifactDir.mkdirs();

		checkoutSource(mavenSession, mojoExecution, resolvedProject, artifactDir);

		return null;
	}

	@SuppressWarnings("PMD.PreserveStackTrace")
	private void checkoutSource(final MavenSession mavenSession, final MojoExecution mojoExecution, final MavenProject resolvedProject,
			final File artifactDir) throws ArtifactBuildException {
		MavenProject currentProject = mavenSession.getCurrentProject();
		try {
			// we override the current project so that maven-scm-plugin can read properties directly from that project
			mavenSession.setCurrentProject(resolvedProject);

			try {
				// first try the developerConnection
				MojoExecutor.executeMojo(
						MojoExecutor.plugin(scmPluginGroupId, scmPluginArtifactId, scmPluginVersion, mojoExecution.getPlugin()
								.getDependencies()),
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
							MojoExecutor.plugin(scmPluginGroupId, scmPluginArtifactId, scmPluginVersion, mojoExecution.getPlugin()
									.getDependencies()),
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
}
