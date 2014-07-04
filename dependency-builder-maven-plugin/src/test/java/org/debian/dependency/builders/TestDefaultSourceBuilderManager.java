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

import org.junit.Test;

/** Test case for {@link DefaultSourceBuilderManager} . */
public class TestDefaultSourceBuilderManager {
	private final DefaultSourceBuilderManager sourceBuilderManager = new DefaultSourceBuilderManager();

	@Test
	public void testPriorityOrder() throws Exception {
		SourceBuilder noPriorityBuilder = mock(SourceBuilder.class);
		SourceBuilder lowPriorityBuilder = mock(SourceBuilder.class);
		SourceBuilder midPriorityBuilder = mock(SourceBuilder.class);
		SourceBuilder highPriorityBuilder = mock(SourceBuilder.class);

		final int noPriority = -1;
		final int lowPriority = 1000;
		final int midPriority = 500;
		final int highPriority = 100;

		when(noPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(noPriority);
		when(lowPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(highPriority);
		when(midPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(midPriority);
		when(highPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(lowPriority);

		sourceBuilderManager.addSourceBuilder(noPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(lowPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(midPriorityBuilder);
		sourceBuilderManager.addSourceBuilder(highPriorityBuilder);

		File directory = new File("test");
		SourceBuilder picked = sourceBuilderManager.detect(directory);
		assertEquals(picked, highPriorityBuilder);
	}
}
