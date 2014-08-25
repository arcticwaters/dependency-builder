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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.plexus.util.reflection.Reflector;
import org.codehaus.plexus.util.reflection.ReflectorException;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;

/**
 * Reflectively matches properties on a particular object.
 *
 * @param <T> type of object to match
 */
public class ObjectPropertyMatcher<T> extends TypeSafeMatcher<T> {
	private final Map<String, Matcher<?>> properties;

	public ObjectPropertyMatcher() {
		properties = new HashMap<String, Matcher<?>>();
	}

	public ObjectPropertyMatcher(final Map<String, Matcher<?>> properties) {
		this();
		this.properties.putAll(properties);
	}

	public ObjectPropertyMatcher(final String name, final Matcher<?> matcher) {
		this(Collections.<String, Matcher<?>> singletonMap(name, matcher));
	}

	public ObjectPropertyMatcher(final String name, final Object value) {
		this(name, Matchers.equalTo(value));
	}

	/**
	 * Adds the given object to the set of matchers. The value is matched for equality through {@link Object#equals(Object)}).
	 *
	 * @param name name of the property
	 * @param value value to be matched
	 */
	public void addMatcher(final String name, Object value) {
		addMatcher(name, Matchers.equalTo(value));
	}

	/**
	 * Adds the given object to the set of matchers.
	 *
	 * @param name name of the property
	 * @param matcher matcher to use
	 */
	public void addMatcher(final String name, Matcher<?> matcher) {
		this.properties.put(name, matcher);
	}

	@Override
	public void describeTo(final Description description) {
		description.appendText("invocation result with properties: ");
		for (Entry<String, Matcher<?>> entry : properties.entrySet()) {
			description.appendText(entry.getKey());
			description.appendText("=");
			entry.getValue().describeTo(description);
			description.appendText(" ");
		}
	}

	@Override
	protected boolean matchesSafely(final T item) {
		if (properties.isEmpty()) {
			throw new IllegalStateException("No properties to match!");
		}

		final Reflector reflector = new Reflector();
		for (final Entry<String, Matcher<?>> entry : properties.entrySet()) {
			try {
				Object actual = reflector.getObjectProperty(item, entry.getKey());
				Matcher<?> expected = entry.getValue();

				if (!expected.matches(actual)) {
					return false;
				}
			} catch (ReflectorException e) {
				return false;
			}
		}
		return true;
	}
}