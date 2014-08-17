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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.StringUtils;

/**
 * A helper class for {@link SourceBuilder}s which depend on a build file for building.
 */
public abstract class AbstractBuildFileSourceBuilder extends AbstractLogEnabled implements SourceBuilder {
	private static final int PRIOTIRY_OFFSET = 0;

	/**
	 * @return offset for priorities in case one builder needs to distinguish itself from another (defaults to
	 *         {@value #PRIOTIRY_OFFSET})
	 */
	protected int getPriorityOffset() {
		return PRIOTIRY_OFFSET;
	}

	/**
	 * @return ant-style patterns for build files
	 * @see #getExcludes()
	 */
	protected abstract List<String> getIncludes();

	/**
	 * @return ant-style patterns for build files
	 * @see #getIncludes()
	 */
	protected List<String> getExcludes() {
		return new ArrayList<String>(Arrays.asList(DirectoryScanner.DEFAULTEXCLUDES));
	}

	private static String[] toArray(final Collection<String> collection) {
		return collection.toArray(new String[collection.size()]);
	}

	/**
	 * Retrieves a list of build files for the given directory using the include exclude rules. Files closer to the given
	 * directory will be first in the result list.
	 *
	 * @param directory directory to search
	 * @return all build files
	 * @throws IOException in case of errors
	 * @see #getIncludes()
	 * @see #getExcludes()
	 */
	protected List<File> findBuildFiles(final File directory) throws IOException {
		DirectoryScanner scanner = new DirectoryScanner();
		scanner.setBasedir(directory);
		scanner.setIncludes(toArray(getIncludes()));
		scanner.setExcludes(toArray(getExcludes()));
		scanner.setCaseSensitive(true);

		scanner.scan();

		String[] files = scanner.getIncludedFiles();
		if (files == null) {
			return Collections.emptyList();
		}

		List<File> result = new ArrayList<File>(files.length);
		for (String file : files) {
			result.add(new File(directory, file));
		}
		Collections.sort(result); // to simulate a breadth first search (useful in priority lookup)
		return result;
	}

	/**
	 * {@inheritDoc}
	 * <p/>
	 * A linearly decreasing priority (increasing number) of {@link #PRIOTIRY_OFFSET} based on the depth of the build file.
	 *
	 * @param directory {@inheritDoc}
	 * @return {@inheritDoc}
	 * @throws IOException {@inheritDoc}
	 */
	@Override
	public int getPriority(final File directory) throws IOException {
		List<File> poms = findBuildFiles(directory);
		if (poms == null || poms.isEmpty()) {
			return -1;
		}

		int depth = StringUtils.countMatches(poms.get(0).getCanonicalPath(), File.separator);
		depth -= StringUtils.countMatches(directory.getCanonicalPath(), File.separator);
		return depth * PRIORITY_STEP + getPriorityOffset();
	}
}
