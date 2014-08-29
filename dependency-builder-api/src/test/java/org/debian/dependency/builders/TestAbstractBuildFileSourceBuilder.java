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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.Git;
import org.junit.Test;

/** Test case for {@link AbstractBuildFileSourceBuilder}. */
public class TestAbstractBuildFileSourceBuilder {
	private BuildFileSourceBuilder buildFileSourceBuilder = new BuildFileSourceBuilder();

	private File locateProject(final String project) throws Exception {
		URL url = TestAbstractBuildFileSourceBuilder.class.getResource("/projects/marker.txt");
		return new File(new File(url.toURI()).getParentFile(), project);
	}

	/** Finding project files on different levels. */
	@Test
	public void testProjectOnDifferentLevels() throws Exception {
		File basedir = locateProject("different-levels");
		buildFileSourceBuilder.includes = Arrays.asList("build-file");
		int rootPriority = buildFileSourceBuilder.getPriority(basedir);
		buildFileSourceBuilder.includes = Arrays.asList("level1/build-file");
		int level1Priority = buildFileSourceBuilder.getPriority(basedir);
		assertTrue("Root should have a higher (numerically lowest) priority", rootPriority < level1Priority);
	}

	/** Finding projects with no project files. */
	@Test
	public void testNoProjectFiles() throws Exception {
		File basedir = locateProject("no-projects");
		int priority = buildFileSourceBuilder.getPriority(basedir);
		assertTrue("Project with no build file should have negative priority", priority < 0);
	}

	/** Mock class of {@link AbstractBuildFileSourceBuilder}. */
	private static class BuildFileSourceBuilder extends AbstractBuildFileSourceBuilder {
		private List<String> includes = new ArrayList<String>();

		@Override
		public Set<Artifact> build(final MavenProject project, final Git repository, final File localRepository)
				throws ArtifactBuildException {
			return null;
		}

		@Override
		public boolean canBuild(final MavenProject project, final File directory) throws IOException {
			return false;
		}

		@Override
		protected List<String> getIncludes() {
			return includes;
		}
	}
}
