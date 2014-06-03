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
import org.apache.maven.plugin.MojoExecution;

/** Represents a means for building an artifact. */
public interface BuildStrategy {
	/**
	 * Attempts to build the given {@link Artifact}.
	 *
	 * @param artifact artifact to build
	 * @param mavenSession current session to build in
	 * @param mojoExecution execution used call this strategy to pass configuration
	 * @throws ArtifactBuildException if we cannot build the artifact
	 * @return location of the built artifact
	 */
	File buildArtifact(Artifact artifact, MavenSession mavenSession, MojoExecution mojoExecution) throws ArtifactBuildException;
}
