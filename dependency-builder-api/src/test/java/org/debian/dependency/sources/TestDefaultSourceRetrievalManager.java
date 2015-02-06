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
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.logging.Logger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Test case for {@link DefaultSourceRetrievalManager}. */
@RunWith(MockitoJUnitRunner.class)
public class TestDefaultSourceRetrievalManager {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@InjectMocks
	private DefaultSourceRetrievalManager manager = new DefaultSourceRetrievalManager();

	@Mock
	private Logger logger;
	@Mock
	private PlexusContainer container;

	private File directory;
	@Mock
	private MavenSession session;
	@Mock
	private SourceRetrieval retrieval;
	@Mock
	private Artifact artifact;
	@Mock
	private Source source;

	@Before
	public void setUp() throws Exception {
		directory = tempFolder.newFolder();
		manager.setSourceRetrievals(Collections.singletonList(retrieval));

		when(container.lookup(eq(Source.class), anyString()))
				.thenReturn(source);

		when(retrieval.getSourceDirname(artifact, session))
				.thenReturn("dirname");
		when(retrieval.retrieveSource(eq(artifact), any(File.class), eq(session)))
				.thenReturn("location");
	}

	/** When there are multiple retrievals, we should check the highest priority one first. */
	@Test
	public void testSourceRetrievalsPriorityOrder() throws Exception {
		SourceRetrieval highPriority = mock(SourceRetrieval.class);
		SourceRetrieval midPriority = mock(SourceRetrieval.class);
		SourceRetrieval lowPriority = mock(SourceRetrieval.class);

		when(highPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_HIGH);
		when(midPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_MEDIUM);
		when(lowPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_LOW);
		manager.setSourceRetrievals(Arrays.asList(midPriority, highPriority, lowPriority));

		// this is only necessary so we don't throw an exception for not finding any retrievals to use
		doReturn("location")
				.when(lowPriority).retrieveSource(eq(artifact), any(File.class), eq(session));
		when(lowPriority.getSourceDirname(artifact, session))
				.thenReturn("dirname");

		manager.checkoutSource(artifact, directory, session);

		InOrder order = inOrder(highPriority, midPriority, lowPriority);
		order.verify(highPriority).retrieveSource(eq(artifact), any(File.class), eq(session));
		order.verify(midPriority).retrieveSource(eq(artifact), any(File.class), eq(session));
		order.verify(lowPriority).retrieveSource(eq(artifact), any(File.class), eq(session));
	}

	/** When there are multiple source retrievals, only the first successful one should be used. */
	@Test
	public void testFirstSourceRetrieval() throws Exception {
		SourceRetrieval highPriority = mock(SourceRetrieval.class);
		SourceRetrieval lowPriority = mock(SourceRetrieval.class);

		when(highPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_HIGH);
		when(lowPriority.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_LOW);
		when(highPriority.retrieveSource(eq(artifact), any(File.class), eq(session)))
				.thenReturn("location");
		when(highPriority.getSourceDirname(artifact, session))
				.thenReturn("dirname");
		manager.setSourceRetrievals(Arrays.asList(highPriority, lowPriority));

		manager.checkoutSource(artifact, directory, session);

		verify(lowPriority, never()).retrieveSource(any(Artifact.class), any(File.class), any(MavenSession.class));
	}

	/** When no source retrieval works, we should hiccup. */
	@Test(expected = SourceRetrievalException.class)
	public void testNoSourceRetrievalWorks() throws Exception {
		SourceRetrieval retrieval = mock(SourceRetrieval.class, Answers.RETURNS_SMART_NULLS.get());
		manager.setSourceRetrievals(Collections.singletonList(retrieval));

		manager.checkoutSource(artifact, directory, session);
	}

	/** When a source retrieval throws an exception, we should hiccup. */
	@Test(expected = SourceRetrievalException.class)
	public void testSourceRetrievalThrowsException() throws Exception {
		SourceRetrieval retrieval1 = mock(SourceRetrieval.class);
		SourceRetrieval retrieval2 = mock(SourceRetrieval.class);

		when(retrieval1.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_HIGH);
		when(retrieval2.getPriority())
				.thenReturn(SourceRetrieval.PRIORITY_LOW);
		when(retrieval2.retrieveSource(eq(artifact), any(File.class), eq(session)))
				.thenThrow(new SourceRetrievalException());
		manager.setSourceRetrievals(Arrays.asList(retrieval1, retrieval2));

		manager.checkoutSource(artifact, directory, session);
	}

	/** When getting the dirname for the source location throws an exception, we should hiccup. */
	@Test(expected = SourceRetrievalException.class)
	public void testSourceRetrievalDirNameThrowsException() throws Exception {
		when(retrieval.getSourceDirname(artifact, session))
				.thenThrow(new SourceRetrievalException());
		manager.checkoutSource(artifact, directory, session);
	}

	/** The source that is returned must be first initialized. */
	@Test
	public void testSourceInitialized() throws Exception {
		String dirname = "somedir";
		when(retrieval.getSourceDirname(artifact, session))
				.thenReturn(dirname);
		String location = "some-locatin";
		when(retrieval.retrieveSource(eq(artifact), any(File.class), eq(session)))
				.thenReturn(location);

		Source result = manager.checkoutSource(artifact, directory, session);

		verify(source).initialize(new File(directory, dirname), location);
		assertEquals(source, result);
	}
}
