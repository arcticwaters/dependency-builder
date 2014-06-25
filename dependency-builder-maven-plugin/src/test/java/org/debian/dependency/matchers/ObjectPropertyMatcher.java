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

import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.plexus.util.reflection.Reflector;
import org.codehaus.plexus.util.reflection.ReflectorException;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

/**
 * Reflectively matches properties on a particular object.
 *
 * @param <T> type of object to match
 */
public class ObjectPropertyMatcher<T> extends TypeSafeMatcher<T> {
	private final Map<String, Object> properties;

	public ObjectPropertyMatcher(final Map<String, Object> properties) {
		this.properties = properties;
	}

	public ObjectPropertyMatcher(final String name, final Object value) {
		this.properties = Collections.singletonMap(name, value);
	}

	@Override
	public void describeTo(final Description description) {
		description.appendText("invocation result with properties: ");
		for (Entry<String, Object> entry : properties.entrySet()) {
			description.appendText(entry.getKey());
			description.appendText("=");
			description.appendValue(entry.getValue());
			description.appendText(" ");
		}
	}

	@Override
	protected boolean matchesSafely(final T item) {
		Reflector reflector = new Reflector();
		for (Entry<String, Object> entry : properties.entrySet()) {
			try {
				Object actual = reflector.getObjectProperty(item, entry.getKey());
				Object expected = entry.getValue();

				if (actual == null ^ expected == null) {
					return false;
				} else if (expected != null && !expected.equals(actual)) {
					return false;
				}
			} catch (ReflectorException e) {
				return false;
			}
		}
		return true;
	}
}