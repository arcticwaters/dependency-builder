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

import org.apache.maven.execution.MavenSession;

/**
 * Tracks projects that need to be built.
 */
public class BuildSession {
	private final MavenSession session;
	private File checkoutDirectory;
	private File workDirectory;
	private File targetRepository;

	/**
	 * Creates a new session.
	 *
	 * @param session current session
	 */
	public BuildSession(final MavenSession session) {
		this.session = session;
	}

	/**
	 * @return where a local copy of files to use are
	 */
	public File getWorkDirectory() {
		return workDirectory;
	}

	/**
	 * Where checked out files are copied to so they can be modified.
	 *
	 * @param workDirectory where local files should live
	 */
	public void setWorkDirectory(final File workDirectory) {
		this.workDirectory = workDirectory;
	}

	/**
	 * @return folder where files are checked out
	 */
	public File getCheckoutDirectory() {
		return checkoutDirectory;
	}

	/**
	 * The folder where files are checked out from their SCM.
	 *
	 * @param checkoutDirectory where to checkout files
	 */
	public void setCheckoutDirectory(final File checkoutDirectory) {
		this.checkoutDirectory = checkoutDirectory;
	}

	/**
	 * @return current maven session
	 */
	public MavenSession getMavenSession() {
		return session;
	}

	/**
	 * @return Maven repository to reference existing artifacts
	 */
	public File getTargetRepository() {
		return targetRepository;
	}

	/**
	 * Sets where to reference existing Maven artifacts.
	 *
	 * @param targetRepository existing Maven artifacts
	 */
	public void setTargetRepository(final File targetRepository) {
		this.targetRepository = targetRepository;
	}
}
