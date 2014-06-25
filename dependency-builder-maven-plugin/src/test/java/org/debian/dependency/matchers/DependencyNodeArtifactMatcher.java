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
package org.debian.dependency.matchers;

import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

/** Matches a {@link DependencyNode} graph via its artifacts. */
public class DependencyNodeArtifactMatcher extends TypeSafeMatcher<DependencyNode> {
	private final DependencyNode ref;

	/**
	 * Creates a new matcher.
	 *
	 * @param ref reference node
	 */
	public DependencyNodeArtifactMatcher(final DependencyNode ref) {
		this.ref = ref;
	}

	@Override
	public void describeTo(final Description description) {
		description.appendText("Dependency node rooted at ");
		description.appendValue(ref.getArtifact());
	}

	@Override
	protected boolean matchesSafely(final DependencyNode item) {
		return matches(ref, item);
	}

	private boolean matches(final DependencyNode node1, final DependencyNode node2) {
		if (node1 == null ^ node2 == null) {
			return false;
		} else if (node1 == null && node2 == null) {
			return true;
		}

		if (!new ArtifactMatcher(node1.getArtifact()).matches(node2.getArtifact())) {
			return false;
		} else if (node1.getChildren().size() != node2.getChildren().size()) {
			return false;
		}

		for (DependencyNode child1 : node1.getChildren()) {
			boolean matched = false;
			for (DependencyNode child2 : node2.getChildren()) {
				if (matches(child1, child2)) {
					matched = true;
					break;
				}
			}

			if (!matched) {
				return false;
			}
		}

		return true;
	}
}