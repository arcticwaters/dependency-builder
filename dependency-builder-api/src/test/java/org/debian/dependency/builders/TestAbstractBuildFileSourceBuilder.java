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
import static org.mockito.Mockito.doReturn;

import java.io.File;
import java.net.URL;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Test case for {@link AbstractBuildFileSourceBuilder}. */
@RunWith(MockitoJUnitRunner.class)
public class TestAbstractBuildFileSourceBuilder {
	@Mock(answer = Answers.CALLS_REAL_METHODS)
	private AbstractBuildFileSourceBuilder buildFileSourceBuilder;

	private File locateProject(final String project) throws Exception {
		URL url = TestAbstractBuildFileSourceBuilder.class.getResource("/projects/marker.txt");
		return new File(new File(url.toURI()).getParentFile(), project);
	}

	/** Finding project files on different levels. */
	@Test
	public void testProjectOnDifferentLevels() throws Exception {
		File basedir = locateProject("different-levels");
		doReturn(Collections.singletonList("build-file"))
				.when(buildFileSourceBuilder).getIncludes();
		int rootPriority = buildFileSourceBuilder.getPriority(basedir);

		doReturn(Collections.singletonList("level1/build-file"))
				.when(buildFileSourceBuilder).getIncludes();
		int level1Priority = buildFileSourceBuilder.getPriority(basedir);
		assertTrue("Root should have a higher (numerically lowest) priority", rootPriority < level1Priority);
	}

	/** Finding projects with no project files. */
	@Test
	public void testNoProjectFiles() throws Exception {
		File basedir = locateProject("no-projects");

		doReturn(Collections.emptyList())
				.when(buildFileSourceBuilder).getIncludes();
		int priority = buildFileSourceBuilder.getPriority(basedir);
		assertTrue("Project with no build file should have negative priority", priority < 0);
	}
}
