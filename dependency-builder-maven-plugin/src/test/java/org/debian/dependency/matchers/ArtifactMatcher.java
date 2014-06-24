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

import org.apache.maven.artifact.Artifact;
import org.codehaus.plexus.util.StringUtils;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

/** Matches an {@link Artifact}. */
public class ArtifactMatcher extends TypeSafeMatcher<Artifact> {
	private Artifact ref;

	/**
	 * Creates a new matcher.
	 *
	 * @param ref reference node
	 */
	public ArtifactMatcher(final Artifact ref) {
		this.ref = ref;
	}

	@Override
	public void describeTo(final Description description) {
		description.appendValue(ref);
	}

	@Override
	protected boolean matchesSafely(final Artifact item) {
		return matches(ref, item);
	}

	private boolean matches(final Artifact artifact1, final Artifact artifact2) {
		return StringUtils.equals(artifact1.getGroupId(), artifact2.getGroupId())
				&& StringUtils.equals(artifact1.getArtifactId(), artifact2.getArtifactId())
				&& StringUtils.equals(artifact1.getVersion(), artifact2.getVersion())
				&& StringUtils.equals(artifact1.getType(), artifact2.getType())
				&& StringUtils.equals(artifact1.getClassifier(), artifact2.getClassifier());
	}
}
