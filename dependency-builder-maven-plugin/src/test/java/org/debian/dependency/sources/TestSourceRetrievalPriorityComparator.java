/*
 * Copyright 2015 Andrew Schurman
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
package org.debian.dependency.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Tests for {@link SourceRetrievalPriorityComparator}. */
@RunWith(MockitoJUnitRunner.class)
public class TestSourceRetrievalPriorityComparator {
	@Mock(name = "low priority")
	private SourceRetrieval lowPriority;
	@Mock
	private SourceRetrieval lowPriority2;
	@Mock(name = "high priority")
	private SourceRetrieval highPriority;
	@Mock
	private SourceRetrieval highPriority2;
	private SourceRetrievalPriorityComparator comparator = new SourceRetrievalPriorityComparator();

	@Before
	public void setUp() throws Exception {
		when(lowPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_LOW);
		when(lowPriority2.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_LOW);

		when(highPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_HIGH);
		when(highPriority2.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_HIGH);
	}

	/** The same priority should be equal. */
	@Test
	public void testSamePriority() {
		assertEquals("Same objects should be equal", 0, comparator.compare(lowPriority, lowPriority));
		assertEquals("Same low priority should be equal", 0, comparator.compare(lowPriority, lowPriority2));
		assertEquals("Same high priority should be equal", 0, comparator.compare(highPriority, highPriority2));
	}

	/** Priorities of objects should be in ascending order. */
	@Test
	public void testPriorityAscending() {
		assertTrue("Low priority should be sorted last", comparator.compare(lowPriority, highPriority) > 0);
		assertTrue("Low priority should be sorted last", comparator.compare(highPriority, lowPriority) < 0);
	}
}
