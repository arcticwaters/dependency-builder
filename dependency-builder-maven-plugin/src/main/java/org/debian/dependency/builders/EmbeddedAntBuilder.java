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
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Configuration;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.FileUtils;
import org.debian.dependency.sources.Source;

import com.google.common.collect.Sets;

/**
 * Builds an Ant project using an embedded version of Ant.
 */
@Component(role = SourceBuilder.class, hint = "ant")
public class EmbeddedAntBuilder extends AbstractBuildFileSourceBuilder implements SourceBuilder {
	private static final String BUILD_INCLUDES = "**/build.xml";

	@Requirement
	private ModelBuilder modelBuilder;
	@Requirement
	private RepositorySystem repositorySystem;

	@Configuration(value = "0.9")
	private float minimumSimilarity;

	@Override
	public Set<Artifact> build(final Artifact artifact, final Source source, final File localRepository) throws ArtifactBuildException {
		try {
			List<File> buildFiles = findBuildFiles(source.getLocation());
			if (buildFiles == null || buildFiles.isEmpty()) {
				throw new ArtifactBuildException("No build files found for " + artifact);
			}

			Project antProject = new Project();
			ProjectHelper.configureProject(antProject, buildFiles.get(0));
			antProject.init();

			antProject.setBaseDir(buildFiles.get(0).getParentFile());
			antProject.executeTarget(antProject.getDefaultTarget());

			Set<Artifact> result = new HashSet<Artifact>();
			Artifact builtArtifact = ArtifactUtils.copyArtifact(artifact);
			builtArtifact.setFile(findFile(artifact, source, "**/*.jar"));
			result.add(builtArtifact);

			Artifact pomArtifact = repositorySystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(),
					artifact.getVersion());
			pomArtifact.setFile(findProjectFile(artifact, source));
			result.add(pomArtifact);

			return result;
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		} catch (BuildException e) {
			throw new ArtifactBuildException(e);
		}
	}

	private File findFile(final Artifact artifact, final Source source, final String pattern) throws IOException {
		for (File file : FileUtils.getFiles(source.getLocation(), pattern, null)) {
			if (jarSimilarity(artifact.getFile(), file) > minimumSimilarity) {
				return file;
			}
		}
		throw new IllegalStateException("Cannot find built jar file for " + artifact);
	}

	/*
	 * Differences of jar files are calculated via file lists. In general, this should be accurate with
	 * a high number of files and has the advantage of not hiccupping on differences in compilers. Since
	 * it doesn't actually diff contents, it can be easily fooled.
	 */
	private float jarSimilarity(final File jarFile1, final File jarFile2) throws IOException {
		Set<String> files1 = createFileList(jarFile1);
		Set<String> files2 = createFileList(jarFile2);

		Set<String> union = Sets.union(files1, files2);
		Set<String> difference = Sets.symmetricDifference(files1, files2);
		return (union.size() - difference.size()) / union.size();
	}

	private Set<String> createFileList(final File file) throws IOException {
		Set<String> result = new HashSet<String>();
		JarFile jarFile = null;
		try {
			jarFile = new JarFile(file);

			for (Enumeration<JarEntry> iter = jarFile.entries(); iter.hasMoreElements();) {
				JarEntry entry = iter.nextElement();
				result.add(entry.getName());
			}

			return result;
		} finally {
			try {
				if (jarFile != null) {
					jarFile.close();
				}
			} catch (IOException e) {
				getLogger().debug("Ignoring exception closing file", e);
			}
		}
	}

	private File findProjectFile(final Artifact artifact, final Source source) throws ArtifactBuildException {
		try {
			for (File pom : FileUtils.getFiles(source.getLocation(), "**/*.xml,**/*.pom", null)) {
				try {
					ModelBuildingRequest request = new DefaultModelBuildingRequest()
							.setModelSource(new FileModelSource(pom))
							.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

					Model model = modelBuilder.build(request).getEffectiveModel();
					if (model.getGroupId().equals(artifact.getGroupId())
							&& model.getArtifactId().equals(artifact.getArtifactId())
							&& model.getVersion().equals(artifact.getVersion())) {
						return pom;
					}
				} catch (ModelBuildingException e) {
					getLogger().debug("Ignoring unreadable pom file:" + pom, e);
				}
			}
		} catch (IOException e) {
			throw new ArtifactBuildException(e);
		}
		throw new ArtifactBuildException("Unable to find reactor containing " + artifact + " under "
				+ source.getLocation());
	}

	@Override
	protected List<String> getIncludes() {
		return Arrays.asList(BUILD_INCLUDES);
	}
}
