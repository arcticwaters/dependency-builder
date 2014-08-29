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

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.Before;
import org.junit.Test;

/** Test case for {@link DefaultSourceBuilderManager} . */
public class TestDefaultSourceBuilderManager {
	private final DefaultSourceBuilderManager sourceBuilderManager = new DefaultSourceBuilderManager();
	private static final int PRIORITY_INVALID = -1;
	private static final int PRIORITY_LOW = 1000;
	private static final int PRIORITY_MID = 500;
	private static final int PRIORITY_HIGH = 100;

	private SourceBuilder invalidPriorityBuilder;
	private SourceBuilder lowPriorityBuilder;
	private SourceBuilder midPriorityBuilder;
	private SourceBuilder highPriorityBuilder;

	@Before
	public void setUp() throws Exception {
		invalidPriorityBuilder = mock(SourceBuilder.class);
		lowPriorityBuilder = mock(SourceBuilder.class);
		midPriorityBuilder = mock(SourceBuilder.class);
		highPriorityBuilder = mock(SourceBuilder.class);

		when(invalidPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_INVALID);
		when(lowPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_LOW);
		when(midPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_MID);
		when(highPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_HIGH);
	}

	/** Correct builder is selected when they are added in priority order. */
	@Test
	public void testPriorityOrder() throws Exception {
		sourceBuilderManager.addSourceBuilder(invalidPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(lowPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(midPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(highPriorityBuilder);

		SourceBuilder picked = sourceBuilderManager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected when they are added in reverse priority order. */
	@Test
	public void testPriorityReverseOrder() throws Exception {
		sourceBuilderManager.addSourceBuilder(highPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(midPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(lowPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(invalidPriorityBuilder);

		SourceBuilder picked = sourceBuilderManager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected when the highest priority not inserted first or last. */
	@Test
	public void testPriorityInRandomOrder() throws Exception {
		sourceBuilderManager.addSourceBuilder(midPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(highPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(invalidPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(lowPriorityBuilder);

		SourceBuilder picked = sourceBuilderManager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected when there is no invalid priority. */
	@Test
	public void testPriorityEverythingButInvalid() throws Exception {
		sourceBuilderManager.addSourceBuilder(midPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(highPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(lowPriorityBuilder);

		SourceBuilder picked = sourceBuilderManager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected with different priorities. */
	@Test
	public void testPriorityDifferent() throws Exception {
		sourceBuilderManager.addSourceBuilder(midPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(lowPriorityBuilder);

		SourceBuilder picked = sourceBuilderManager.detect(new File("dir"));
		assertEquals(picked, midPriorityBuilder);
	}
}
