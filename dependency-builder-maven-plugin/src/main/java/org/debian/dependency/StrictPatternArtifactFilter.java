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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;

/**
 * An {@link ArtifactFilter} which allows both include and exclude patterns to be specified. By default, if there are no include
 * patterns, this filter will match everything not specified by the exclude patterns. An artifact will not be matched if it
 * matches an exclude pattern regardless if its matched by an include pattern.
 * <p/>
 * Available patterns are described in {@link StrictPatternArtifactFilter}.
 */
public class StrictPatternArtifactFilter implements ArtifactFilter {
	private List<String> includes;
	private List<String> excludes;
	private StrictPatternIncludesArtifactFilter includeFilter;
	private StrictPatternIncludesArtifactFilter excludeFilter;

	/**
	 * Creates a new filter with no defaults.
	 *
	 * @param defaults whether to add defaults
	 */
	public StrictPatternArtifactFilter(final boolean defaults) {
		this(Collections.<String> emptyList(), Collections.<String> emptyList(), defaults);
	}

	/**
	 * Creates a new filter which will match everything.
	 */
	public StrictPatternArtifactFilter() {
		this(true);
	}

	/**
	 * Creates a new filter which will match the given patterns.
	 *
	 * @param includes patterns to include ({@code null} or an empty array to match everything)
	 * @param excludes patterns to exclude ({@code null} or an empty array to not exclude anything)
	 * @param defaults whether to add defaults
	 */
	public StrictPatternArtifactFilter(final String[] includes, final String[] excludes, final boolean defaults) {
		this(Arrays.asList(includes), Arrays.asList(excludes), defaults);
	}

	/**
	 * Creates a new filter which will match the given patterns.
	 *
	 * @param includes patterns to include ({@code null} or an empty list to match everything)
	 * @param excludes patterns to exclude ({@code null} or an empty list to not exclude anything)
	 * @param defaults whether to add defaults
	 */
	public StrictPatternArtifactFilter(final List<String> includes, final List<String> excludes, final boolean defaults) {
		setIncludes(includes, defaults);
		setExcludes(excludes);
	}

	/**
	 * Sets up new include patterns. Use {@code null} or an empty list to remove all patterns and match everything not excluded.
	 *
	 * @param includes patterns to include
	 * @param defaults whether it include defaults
	 */
	public void setIncludes(final List<String> includes, final boolean defaults) {
		List<String> list = includes;
		if (includes == null || includes.isEmpty()) {
			if (defaults) {
				list = Arrays.asList("*");
			} else {
				list = Collections.emptyList();
			}
		}

		this.includes = list;
		includeFilter = new StrictPatternIncludesArtifactFilter(list);
	}

	/**
	 * Sets up new include patterns. Use {@code null} or an empty list to remove all patterns and match everything not excluded.
	 *
	 * @param includes patterns to include
	 */
	public void setIncludes(final List<String> includes) {
		setIncludes(includes, true);
	}

	/**
	 * @return patterns which are included
	 */
	public List<String> getIncludes() {
		return includes;
	}

	/**
	 * Sets up new exclude patterns. Use {@code null} or an empty list to remove all patterns that were previously excluded.
	 *
	 * @param excludes patterns to exclude
	 */
	public void setExcludes(final List<String> excludes) {
		List<String> list = excludes;
		if (excludes == null) {
			list = Collections.emptyList();
		}

		this.excludes = list;
		excludeFilter = new StrictPatternIncludesArtifactFilter(list);
	}

	/**
	 * @return patterns which are excluded
	 */
	public List<String> getExcludes() {
		return excludes;
	}

	@Override
	public boolean include(final Artifact artifact) {
		return includeFilter.include(artifact) && !excludeFilter.include(artifact);
	}
}
