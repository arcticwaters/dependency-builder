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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

/**
 * An attempt to build artifacts using an attached sources artifact.
 */
@Component(role = SourceRetrieval.class, hint = "java-sources")
public class JavaSourcesJarSourceRetrieval extends AbstractLogEnabled implements SourceRetrieval {
	private static final int PRIORITY = PRIORITY_LOW + PRIORITY_LOW / 2;

	@Requirement
	private RepositorySystem repoSystem;

	@Override
	public String getSourceLocation(final Artifact artifact, final MavenSession session) throws SourceRetrievalException {
		return "sources jar from " + artifact.getId();
	}

	@Override
	public String retrieveSource(final Artifact artifact, final File directory, final MavenSession session) throws SourceRetrievalException {
		Artifact sourcesArtifact = repoSystem.createArtifactWithClassifier(artifact.getGroupId(), artifact.getArtifactId(),
				artifact.getVersion(), "jar", "sources");
		sourcesArtifact = resolveArtifact(sourcesArtifact, session);

		if (sourcesArtifact == null || sourcesArtifact.getFile() == null || !sourcesArtifact.getFile().exists()) {
			return null;
		} else if (!"jar".equals(artifact.getType())) {
			throw new SourceRetrievalException("Can only retrieve sources for jar artifacts");
		}

		try {
			extractArtifactJar(sourcesArtifact, directory);

			Artifact pomArtifact = repoSystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
			pomArtifact = resolveArtifact(pomArtifact, session);
			FileUtils.copyFile(pomArtifact.getFile(), new File(directory, "pom.xml"));
			return getSourceLocation(artifact, session);
		} catch (IOException e) {
			throw new SourceRetrievalException(e);
		}
	}

	private JarFile extractArtifactJar(final Artifact artifact, final File parent) throws IOException {
		JarFile jarFile = null;
		try {
			// jar entries may be out of order, must create folders first
			File javaSourceDir = new File(parent, "src/main/java");
			jarFile = new JarFile(artifact.getFile());
			for (Enumeration<JarEntry> iter = jarFile.entries(); iter.hasMoreElements();) {
				JarEntry entry = iter.nextElement();
				if (entry.getName().endsWith("/")) {
					File file = new File(javaSourceDir, entry.getName());
					file.mkdirs();
				}
			}

			for (Enumeration<JarEntry> iter = jarFile.entries(); iter.hasMoreElements();) {
				JarEntry entry = iter.nextElement();
				File file = new File(javaSourceDir, entry.getName());
				if (!entry.getName().endsWith("/")) {
					FileOutputStream outputStream = null;
					try {
						outputStream = new FileOutputStream(file);
						IOUtil.copy(jarFile.getInputStream(entry), outputStream);
					} finally {
						IOUtil.close(outputStream);
					}
				}
			}
		} finally {
			try {
				if (jarFile != null) {
					jarFile.close();
				}
			} catch (IOException e) {
				getLogger().debug("Ignoring error when closing zip", e);
			}
		}
		return jarFile;
	}

	private Artifact resolveArtifact(final Artifact toResolve, final MavenSession session) {
		ArtifactResolutionRequest request = new ArtifactResolutionRequest()
				.setLocalRepository(session.getLocalRepository())
				.setOffline(true)
				.setResolveRoot(true)
				.setArtifact(toResolve);

		ArtifactResolutionResult result = repoSystem.resolve(request);
		if (result.getArtifacts().isEmpty()) {
			return null;
		}
		return result.getArtifacts().iterator().next();
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}

	@Override
	public String getSourceDirname(final Artifact artifact, final MavenSession session) throws SourceRetrievalException {
		return ArtifactUtils.key(artifact);
	}
}
