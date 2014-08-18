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

import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.shared.dependency.graph.DependencyNode;

/** Represents a means for building an artifact. */
public interface BuildStrategy {
	/**
	 * Attempts to build artifacts in the {@link DependencyNode} graph. The root artifact <em>needs</em> to be built. Some
	 * strategies may be able to build more than a single artifact, i.e., a multi-module project, although it is not expected to
	 * built the entire graph.
	 *
	 * @param graph dependency graph
	 * @param session session to use
	 * @return artifacts that were built
	 * @throws ArtifactBuildException in case of errors
	 */
	Set<Artifact> build(DependencyNode graph, BuildSession session) throws ArtifactBuildException;

	/**
	 * @return priority of the builder with small numbers being a high priority (negative values a failure)
	 */
	int getPriority();
}
