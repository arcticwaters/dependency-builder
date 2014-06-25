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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;

/**
 * An {@link ArtifactFilter} which matches precisely the input artifacts.
 */
public class IdentityArtifactFilter implements ArtifactFilter {
	private final Set<Artifact> artifacts;

	/**
	 * Creates a new filter with no artifacts.
	 */
	public IdentityArtifactFilter() {
		this(Collections.<Artifact> emptySet());
	}

	/**
	 * Creates a new filter with the given artifacts to match.
	 *
	 * @param artifacts artifacts to match
	 */
	public IdentityArtifactFilter(final Collection<Artifact> artifacts) {
		this.artifacts = new HashSet<Artifact>(artifacts);
	}

	/**
	 * @return artifacts which match this filter
	 */
	public Set<Artifact> getArtifacts() {
		return Collections.unmodifiableSet(artifacts);
	}

	/**
	 * Adds the given {@link Artifact} to the ones to match.
	 *
	 * @param artifact artifact to match
	 */
	public void addArtifact(final Artifact artifact) {
		artifacts.add(artifact);
	}

	@Override
	public boolean include(final Artifact artifact) {
		return artifacts.contains(artifact);
	}
}
