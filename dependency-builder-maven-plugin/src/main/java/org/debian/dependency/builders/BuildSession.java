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
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;

/**
 * Tracks projects that need to be built.
 */
public class BuildSession {
	private MavenSession session;
	private List<Dependency> extensions;
	private File workDirectory;
	private File targetRepository;

	/**
	 * Creates a new session.
	 */
	public BuildSession(final MavenSession session) {
		this.session = session;
	}

	/**
	 * Sets build extensions in case of nested executions.
	 *
	 * @param extensions build extensions
	 */
	public void setExtensions(final List<Dependency> extensions) {
		this.extensions = extensions;
	}

	/**
	 * @return extensions in case of nested executions
	 */
	public List<Dependency> getExtensions() {
		return extensions;
	}

	/**
	 * @return where to operate on files
	 */
	public File getWorkDirectory() {
		return workDirectory;
	}

	/**
	 * Sets where to operate on files.
	 *
	 * @param workDirectory where to operate on files
	 */
	public void setWorkDirectory(final File workDirectory) {
		this.workDirectory = workDirectory;
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
