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
package org.debian.dependency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Test;

/** Test class for {@link ReversedIterator}. */
public class TestReversedIterator {
	private static final String ITEM1 = "item1";
	private static final String ITEM2 = "item2";
	private static final String ITEM3 = "item3";

	/** We should have valid iteration for a single item. */
	@Test
	public void testSingleItem() {
		Iterator<String> iterator = new ReversedIterator<String>(Arrays.asList(ITEM1));
		assertTrue("One item remains", iterator.hasNext());
		assertEquals(ITEM1, iterator.next());
		assertFalse("No items remain", iterator.hasNext());
	}

	/** We should not have any values if the list if empty. */
	@Test
	public void testEmpty() {
		Iterator<String> iterator = new ReversedIterator<String>(Collections.<String> emptyList());
		assertFalse("No items", iterator.hasNext());
	}

	/** We should throw an exception if requesting an element that doesn't exist. */
	@Test(expected = NoSuchElementException.class)
	public void testEmptyNoSuchElement() {
		Iterator<String> iterator = new ReversedIterator<String>(Collections.<String> emptyList());
		iterator.next();
	}

	/** Valid iteration for multiple items. */
	@Test
	public void testMultipleItems() {
		Iterator<String> iterator = new ReversedIterator<String>(Arrays.asList(ITEM1, ITEM2, ITEM3));
		assertTrue("3 items remain", iterator.hasNext());
		assertEquals(ITEM3, iterator.next());
		assertTrue("2 items remain", iterator.hasNext());
		assertEquals(ITEM2, iterator.next());
		assertTrue("1 item remain", iterator.hasNext());
		assertEquals(ITEM1, iterator.next());
		assertFalse("No more items", iterator.hasNext());
	}

	/** We should be able to remove items in the usual fashion. */
	@Test
	public void testRemove() {
		List<String> result = new ArrayList<String>(Arrays.asList(ITEM1, ITEM2, ITEM3));
		Iterator<String> iterator = new ReversedIterator<String>(result);
		iterator.next();
		iterator.remove();
		iterator.next();
		iterator.next();
		iterator.remove();
		assertFalse("No items left", iterator.hasNext());
		assertEquals(Arrays.asList(ITEM2), result);
	}

	/** We should barf if we haven't retrieved an item yet. */
	@Test(expected = IllegalStateException.class)
	public void testRemoveBeforeNext() {
		Iterator<String> iterator = new ReversedIterator<String>(Collections.singletonList(ITEM1));
		iterator.remove();
	}
}
