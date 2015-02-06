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

/** Represents a method for retrieving sources for a particular artifact. */
public interface SourceRetrieval {
	/** A default value for high priority methods. Users should add a small random value to distinguish between other methods. */
	int PRIORITY_HIGH = 1000;
	/** A default value for medium priority methods. Users should add a small random value to distinguish between other methods. */
	int PRIORITY_MEDIUM = 500;
	/** A default value for low priority methods. Users should add a small random value to distinguish between other methods. */
	int PRIORITY_LOW = 100;

	/**
	 * Gets where the source is located for a particular artifact.
	 * <p/>
	 * Two different artifacts which come from the same source repository should return the same string, except when a
	 * {@code null} or empty string are returned. In general, the return value is used to determine whether we need to perform a
	 * full check out.
	 *
	 * @param artifact which artifact to get sources for
	 * @param session current session
	 * @return location of the source or empty if it cannot be found
	 * @throws SourceRetrievalException in case of errors
	 */
	String getSourceLocation(Artifact artifact, MavenSession session) throws SourceRetrievalException;

	/**
	 * Retrieves the source code for the given {@link Artifact} and places it in the given directory.
	 *
	 * @param artifact which artifact to get sources for
	 * @param directory where sources should be placed
	 * @param session current session
	 * @return description of real source location or empty if sources could not be retrieved
	 * @throws SourceRetrievalException in case of errors
	 */
	String retrieveSource(Artifact artifact, File directory, MavenSession session) throws SourceRetrievalException;

	/**
	 * Gets a possible directory name for the source of the given {@link Artifact}. This is only used as a hint for the actual
	 * directory name.
	 *
	 * @param artifact artifact to get a directory name for
	 * @param session current session
	 * @return directory name for the given artifact
	 * @throws SourceRetrievalException in case of errors
	 */
	String getSourceDirname(Artifact artifact, MavenSession session) throws SourceRetrievalException;

	/**
	 * @return priority of the method with small numbers being a high priority (negative values a failure)
	 */
	int getPriority();
}
