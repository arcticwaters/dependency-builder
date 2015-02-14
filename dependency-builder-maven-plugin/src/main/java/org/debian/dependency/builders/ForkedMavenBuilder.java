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
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.debian.dependency.ProjectArtifactSpy;
import org.debian.dependency.sources.Source;

/**
 * Builds a Maven project using an embedded (forked) version of Maven.
 */
@Component(role = SourceBuilder.class, hint = "maven2")
public class ForkedMavenBuilder extends AbstractBuildFileSourceBuilder implements SourceBuilder {
	private static final String POM_INCLUDES = "**/pom.xml";
	private static final String POM_EXCLUDES = "**/src/**";

	@Requirement
	private ModelBuilder modelBuilder;
	@Requirement
	private Invoker invoker;
	@Requirement
	private RepositorySystem repositorySystem;

	@Override
	public Set<Artifact> build(final Artifact artifact, final Source source, final File localRepository) throws ArtifactBuildException {
		File reportFile;
		try {
			reportFile = File.createTempFile("report", ".txt");
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}

		try {
			Properties properties = new Properties();
			properties.setProperty("maven.ext.class.path", getOriginClassPath(ProjectArtifactSpy.class).toString());
			properties.setProperty(ProjectArtifactSpy.REPORT_FILE_PROPERTY, reportFile.toString());

			Model model = findProjectModel(artifact, source.getLocation());
			InvocationRequest request = new DefaultInvocationRequest()
					.setPomFile(model.getPomFile())
					.setGoals(Arrays.asList("verify"))
					.setOffline(true)
					.setRecursive(false) // in case we are dealing with a pom packaging
					.setProperties(properties)
					.setOutputHandler(new InvocationOutputHandler() {
						@Override
						public void consumeLine(final String line) {
							getLogger().info(line);
						}
					})
					.setErrorHandler(new InvocationOutputHandler() {
						@Override
						public void consumeLine(final String line) {
							getLogger().error(line);
						}
					})
					.setLocalRepositoryDirectory(localRepository);

			try {
				InvocationResult result = invoker.execute(request);
				if (result.getExecutionException() == null && result.getExitCode() == 0) {
					return getBuiltArtifacts(reportFile);
				} else if (result.getExecutionException() != null) {
					throw new ArtifactBuildException("Unable to build proejct", result.getExecutionException());
				}

				throw new ArtifactBuildException("Execution did not complete successfully");
			} catch (MavenInvocationException e) {
				throw new ArtifactBuildException("Unable to build project", e);
			} catch (IOException e) {
				throw new ArtifactBuildException(e);
			}
		} finally {
			// best effort, not really necessary to delete this
			reportFile.delete();
		}
	}

	@SuppressWarnings("PMD.PreserveStackTrace")
	private static File getOriginClassPath(final Class<?> clazz) throws ArtifactBuildException {
		try {
			URL sourceUrl;
			try {
				sourceUrl = clazz.getProtectionDomain().getCodeSource().getLocation();
			} catch (SecurityException e) {
				URL location = clazz.getResource(clazz.getSimpleName() + ".class");
				if ("file".equals(location.getProtocol())) {
					File file = new File(location.toURI()).getParentFile();
					for (int i = 0; i < StringUtils.countMatches(clazz.getPackage().getName(), ".") + 1; ++i) {
						file = file.getParentFile();
					}
					return file;
				} else if ("jar".equals(location.getProtocol())) {
					sourceUrl = new URL(location.toExternalForm().substring("jar".length(), location.toExternalForm().lastIndexOf('!')));
				} else {
					// this exception has nothing to do with the security exception
					throw new IllegalStateException("Unhandled location: " + location);
				}
			}

			if (!"file".equals(sourceUrl.getProtocol())) {
				throw new IllegalStateException("Not local location: " + sourceUrl);
			}
			return new File(sourceUrl.toURI().getPath());
		} catch (URISyntaxException e) {
			throw new ArtifactBuildException(e);
		} catch (MalformedURLException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private Set<Artifact> getBuiltArtifacts(final File reportFile) throws IOException {
		Properties artifacts = new Properties();

		FileInputStream stream = new FileInputStream(reportFile);
		try {
			artifacts.load(stream);
		} finally {
			IOUtil.close(stream);
		}

		Set<Artifact> result = new HashSet<Artifact>();
		for (String specifier : artifacts.stringPropertyNames()) {
			String[] pieces = specifier.split(":");

			final int groupIdIndex = 0;
			final int artifactIdIndex = 1;
			final int versionIndex = pieces.length - 1;
			final int typeIndex = 2;
			final int classifierIndex = 3;

			Artifact artifact;
			if (pieces.length > classifierIndex + 1) {
				artifact = repositorySystem.createArtifactWithClassifier(pieces[groupIdIndex], pieces[artifactIdIndex], pieces[versionIndex],
						pieces[typeIndex], pieces[classifierIndex]);
			} else {
				artifact = repositorySystem.createArtifact(pieces[groupIdIndex], pieces[artifactIdIndex], pieces[versionIndex], pieces[typeIndex]);
			}

			artifact.setFile(new File(artifacts.getProperty(specifier)));
			result.add(artifact);
		}

		return result;
	}

	private Model findProjectModel(final Artifact artifact, final File basedir) throws ArtifactBuildException {
		return findProjectModel(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), basedir);
	}

	private Model findProjectModel(final String groupId, final String artifactId, final String version, final File basedir)
			throws ArtifactBuildException {
		try {
			for (File pom : findBuildFiles(basedir)) {
				try {
					ModelBuildingRequest request = new DefaultModelBuildingRequest()
							.setPomFile(pom)
							.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

					Model model = modelBuilder.build(request).getEffectiveModel();
					if (model.getGroupId().equals(groupId)
							&& model.getArtifactId().equals(artifactId)
							&& model.getVersion().equals(version)) {
						return model;
					}
				} catch (ModelBuildingException e) {
					getLogger().debug("Ignoring unreadable pom file:" + pom, e);
				}
			}
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}
		throw new ArtifactBuildException("Unable to find reactor containing " + groupId + ":" + artifactId + ":" + version + " under "
				+ basedir);
	}

	@Override
	protected List<String> getIncludes() {
		return Arrays.asList(POM_INCLUDES);
	}

	@Override
	protected List<String> getExcludes() {
		List<String> result = new ArrayList<String>(super.getExcludes());
		result.add(POM_EXCLUDES);
		return result;
	}

	@Override
	protected int getPriorityOffset() {
		return PRIORITY_STEP / 2;
	}
}
