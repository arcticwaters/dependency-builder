/*
 * Copyright 2015 Andrew Schurman
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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;

/** Manages the different ways to retrieve source for a project. */
public interface SourceRetrievalManager {
	/**
	 * Retrieves the {@link Source} for a given {@link Artifact}. Sources should be created under the given parent directory.
	 *
	 * @param artifact which artifact to get sources for
	 * @param parentDir parent directory where sources should be placed
	 * @param session current session
	 * @return actual source
	 * @throws SourceRetrievalException in case of errors
	 */
	Source checkoutSource(Artifact artifact, File parentDir, MavenSession session) throws SourceRetrievalException;
}
