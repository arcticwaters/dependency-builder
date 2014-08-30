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
package org.debian.dependency;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.junit.Test;

/** Basic tests for {@link StrictPatternArtifactFilter}. */
public class TestStrictPatternArtifactFilter {
	private StrictPatternArtifactFilter matcher = new StrictPatternArtifactFilter();
	private final Artifact artifact = new DefaultArtifact("com.example", "some-artifact-plugin", "", "", "", "", null);

	/** An empty configuration should allow everything by default. */
	@Test
	public void testNoArtifacts() {
		assertTrue("should match by default", matcher.include(artifact));
	}

	/** Multiple include/exclude patterns should be allowed. */
	@Test
	public void testMultipleIncludesExcludes() {
		List<String> includes = Arrays.asList("z", "com.example");
		List<String> excludes = Arrays.asList("boo", "com.*");

		matcher.setIncludes(includes);
		assertTrue("Should match second pattern", matcher.include(artifact));
		matcher.setExcludes(excludes);
		assertFalse("Second exclude pattern should exclude it", matcher.include(artifact));

		matcher = new StrictPatternArtifactFilter(includes, null, true);
		matcher.setIncludes(includes);
		assertTrue("Should match second pattern", matcher.include(artifact));
		matcher.setExcludes(excludes);
		assertFalse("Second exclude pattern should exclude it", matcher.include(artifact));
	}

	/** Empty lists should be the same as no configuration. */
	@Test
	public void testEmptyLists() {
		List<String> emptyList = Collections.<String> emptyList();

		matcher.setIncludes(emptyList);
		assertTrue("should match eveything with no patterns", matcher.include(artifact));
		matcher.setExcludes(emptyList);
		assertTrue("empty exclude patterns should not affect include patterns", matcher.include(artifact));

		matcher = new StrictPatternArtifactFilter(emptyList, emptyList, true);
		matcher.setIncludes(emptyList);
		assertTrue("should match eveything with no patterns", matcher.include(artifact));
		matcher.setExcludes(emptyList);
		assertTrue("empty exclude patterns should not affect include patterns", matcher.include(artifact));
	}
}