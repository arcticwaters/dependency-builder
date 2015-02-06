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
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.debian.dependency.sources.Source;

/** Manages the different ways to build a project from source. */
public interface SourceBuilderManager {
	/**
	 * Attempts to detect which {@link SourceBuilder} would be best suitable to build the given directory. In general, this is
	 * determined based on the priority of a particular builder for a directory.
	 *
	 * @param directory where a source builder would build
	 * @return {@link SourceBuilder} that should be used for building
	 * @throws IOException in case of errors
	 * @see SourceBuilder#getPriority(File)
	 */
	SourceBuilder detect(File directory) throws IOException;

	/**
	 * Adds a {@link SourceBuilder} to the manager manually.
	 *
	 * @param sourceBuilder source builder to add
	 */
	void addSourceBuilder(SourceBuilder sourceBuilder);

	/**
	 * @return source builders which this manager knows about
	 */
	List<SourceBuilder> getSourceBuilders();

	/**
	 * Builds the given {@link Artifact} provided in {@link Source}. Returns a set of artifacts which were built as part of the
	 * build process. Artifacts are built using the {@link SourceBuilder} which was detected from {@link #detect(File)}.
	 *
	 * @param artifact artifact to build
	 * @param source source of the artifact
	 * @param localRepository repository that should be used for resolution
	 * @return artifacts that were built which can include attached and supplementary artifacts
	 * @throws ArtifactBuildException in case of errors
	 */
	Set<Artifact> build(Artifact artifact, Source source, File localRepository) throws ArtifactBuildException;
}
