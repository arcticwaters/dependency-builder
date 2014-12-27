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
package org.debian.dependency.utils;

import static org.mockito.Mockito.mock;

import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;

/** Mocking utilities for tests dealing with plexus. */
public class PlexusMockingUtils {
	private PlexusContainer container;

	public PlexusMockingUtils(final PlexusContainer container) {
		this.container = container;
	}

	/**
	 * Creates a new mocked component for the given class. Components are also added to the container
	 *
	 * @param type type of component
	 * @return a mock of the given type
	 * @throws Exception in case of errors
	 */
	public <T> T mockComponent(final Class<T> type) throws Exception {
		T mockedComponent = mock(type);
		container.addComponent(mockedComponent, type, PlexusConstants.PLEXUS_DEFAULT_HINT);
		return mockedComponent;
	}
}
