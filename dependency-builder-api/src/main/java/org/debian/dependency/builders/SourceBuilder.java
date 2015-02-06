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
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.debian.dependency.sources.Source;

/** Defines a means of building a particular project. */
public interface SourceBuilder {
	/** It is recommended that builders use this distance between 2 closest priorities to enable extension between them. */
	int PRIORITY_STEP = 100;

	/**
	 * Builds the project {@link Artifact} located within the given {@link Source}. The returned set of artifacts should include
	 * any artifact built as part of building the given project artifact. Each <em>must</em> set the
	 * {@link Artifact#setFile(File) file} of the artifact to be valid.
	 *
	 * @param artifact artifact to build
	 * @param source where the artifact should be built from
	 * @param localRepository maven repository which can be used for local resolution
	 * @return original artifact with any attached artifacts
	 * @throws ArtifactBuildException in we are unable to build the artifact
	 */
	Set<Artifact> build(Artifact artifact, Source source, File localRepository) throws ArtifactBuildException;

	/**
	 * Gets the priority of this builder for the given directory. A small number (including zero) denotes a high priority. A
	 * negative number should be taken as a failure instead of a very high priority.
	 *
	 * @param directory directory that would be built
	 * @return priority of the builder with small numbers being a high priority (negative values a failure)
	 * @throws IOException in case of errors
	 */
	int getPriority(File directory) throws IOException;
}
