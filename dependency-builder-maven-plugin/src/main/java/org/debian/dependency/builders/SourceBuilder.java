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

/** Defines a means of building a particular project. */
public interface SourceBuilder {
	/**
	 * Builds the {@link Artifact} located under the given directory. It is not guaranteed that the artifact is in the root of the
	 * given directory. Each returned artifact must set the artifact file appropriately: if given it is up to the caller to
	 * install into the repository otherwise it is assumed to have been installed already.
	 *
	 * @param artifact artifact to build
	 * @param basedir directory where the artifact source resides
	 * @param localRepository repository that should be used for resolution
	 * @return original artifact with any attached artifacts
	 * @throws ArtifactBuildException in we are unable to build the artifact
	 */
	Set<Artifact> build(Artifact artifact, File basedir, File localRepository) throws ArtifactBuildException;

	/**
	 * A hint to whether this builder can build an artifact from the given directory. The given artifact is not guaranteed to
	 * exist in the directory. Since this is only meant as a hint, returning {@code false} does not mean its impossible to build
	 * the artifact from the directory.
	 *
	 * @param artifact artifact to build
	 * @param directory directory to search
	 * @return whether the artifact can be built from the directory
	 * @throws IOException in case of errors
	 */
	boolean canBuild(Artifact artifact, File directory) throws IOException;

	/**
	 * Determines whether this builder can build any artifact in the given directory.
	 *
	 * @param directory directory to search
	 * @return whether its possible to build any artifact from the directory
	 * @throws IOException in case of errors
	 */
	boolean canBuild(File directory) throws IOException;

	/**
	 * @return priority to be used if multiple builders can build a directory (higher is better)
	 */
	int getPriority();
}
