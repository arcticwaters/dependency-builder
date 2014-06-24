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

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestStrictPatternArtifactFilterPatterns {
	private StrictPatternArtifactFilter matcher = new StrictPatternArtifactFilter();
	private Artifact artifact = new DefaultArtifact("com.example", "some-artifact-plugin", "", "", "", "", null);

	@Parameters(name = "{4}")
	public static Iterable<Object[]> parameters() {
		return Arrays.asList(new Object[][] {
				{ "com.*", "com.*", true, false, "Wildcard groupIds" },
				{ "com.example", "com.example", true, false, "Exact groupIds" },
				{ "com.example", null, true, true, "Exact match null exclude" },
				{ "com.example.foo", "", false, false, "Does not match" },
				{ "com.example:some-artifact-plugin", "com.example:some-artifact-plugin", true, false, "Exact artifactId" },
				{ "com:some-artifact-plugin", "com:some-artifact-plugin", false, false, "ArtifactId match, GroupId doesn't match" },
				{ "com*:some-artifact-plugin", "com*:some-artifact-plugin", true, false, "ArtifactId match, GroupId wildcard" },
				{ "*:*artifact-plugin", "*:*artifact-plugin", true, false, "Wildcard artifactIds" },
				{ "*:some-artifact-plugin*", "*:some-artifact-plugin*", true, false,
						"ArtifactIds match without wildcard, but have wildcard" },
		});
	}

	private String includePattern;
	private String excludePattern;
	private boolean includeShouldMatch;
	private boolean excludeShouldMatch;

	public TestStrictPatternArtifactFilterPatterns(final String includePattern, final String excludePattern,
			final boolean includeShouldMatch, final boolean excludeShouldMatch, final String name) {
		this.includePattern = includePattern;
		this.excludePattern = excludePattern;
		this.includeShouldMatch = includeShouldMatch;
		this.excludeShouldMatch = excludeShouldMatch;
	}

	@Test
	public void testMatch() {
		if (includePattern != null) {
			matcher.setIncludes(Arrays.asList(includePattern));
		}
		assertEquals(includeShouldMatch, matcher.include(artifact));
		if (excludePattern != null) {
			matcher.setExcludes(Arrays.asList(excludePattern));
		}
		assertEquals(excludeShouldMatch, matcher.include(artifact));
	}
}