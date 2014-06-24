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
package org.debian.dependency.filters;

import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;

/**
 * A {@link DependencyNodeFilter} which accepts what a nested filter accepts on a given node or any of its ancestors.
 */
public class DependencyNodeAncestorOrSelfArtifactFilter implements DependencyNodeFilter {

	private DependencyNodeFilter filter;

	/**
	 * Creates a new {@link DependencyNodeAncestorOrSelfArtifactFilter}.
	 *
	 * @param filter filter to use
	 */
	public DependencyNodeAncestorOrSelfArtifactFilter(final DependencyNodeFilter filter) {
		this.filter = filter;
	}

	@Override
	public boolean accept(final DependencyNode node) {
		boolean result = false;
		DependencyNode work = node;

		while (!result && work != null) {
			result = filter.accept(work);
			work = work.getParent();
		}

		return result;
	}
}
