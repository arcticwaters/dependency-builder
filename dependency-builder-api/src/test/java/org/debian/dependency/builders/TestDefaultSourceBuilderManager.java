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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.ReflectionUtils;
import org.debian.dependency.sources.Source;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Test case for {@link DefaultSourceBuilderManager} . */
@RunWith(MockitoJUnitRunner.class)
public class TestDefaultSourceBuilderManager {
	@InjectMocks
	private final DefaultSourceBuilderManager manager = new DefaultSourceBuilderManager();
	private static final int PRIORITY_INVALID = -1;
	private static final int PRIORITY_LOW = 1000;
	private static final int PRIORITY_MID = 500;
	private static final int PRIORITY_HIGH = 100;

	@Mock
	private Logger logger;
	@Mock
	private RepositorySystem repoSystem;
	@Mock
	private SourceBuilder invalidPriorityBuilder;
	@Mock
	private SourceBuilder lowPriorityBuilder;
	@Mock
	private SourceBuilder midPriorityBuilder;
	@Mock
	private SourceBuilder highPriorityBuilder;
	private SourceBuilder selectedBuilder;

	@Mock
	private Artifact artifact;
	@Mock
	private Source source;
	@Mock
	private MavenSession session;
	private File repository = new File("repo");

	@Before
	public void setUp() throws Exception {
		when(invalidPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_INVALID);
		when(lowPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_LOW);
		when(midPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_MID);
		when(highPriorityBuilder.getPriority(any(File.class)))
				.thenReturn(PRIORITY_HIGH);

		manager.addSourceBuilder(midPriorityBuilder);
		selectedBuilder = midPriorityBuilder;


		when(repoSystem.resolve(any(ArtifactResolutionRequest.class)))
				.then(new Answer<ArtifactResolutionResult>() {
					@Override
					public ArtifactResolutionResult answer(final InvocationOnMock invocation) throws Throwable {
						ArtifactResolutionRequest request = (ArtifactResolutionRequest) invocation.getArguments()[0];
						ArtifactResolutionResult result = new ArtifactResolutionResult();

						when(request.getArtifact().getFile())
								.thenReturn(new File("artifact"));

						result.addArtifact(request.getArtifact());
						return result;
					}
				});
	}

	/** Correct builder is selected when they are added in priority order. */
	@Test
	public void testPriorityOrder() throws Exception {
		manager.setBuilders(Arrays.asList(invalidPriorityBuilder, lowPriorityBuilder, midPriorityBuilder, highPriorityBuilder));

		SourceBuilder picked = manager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected when they are added in reverse priority order. */
	@Test
	public void testPriorityReverseOrder() throws Exception {
		manager.setBuilders(Arrays.asList(highPriorityBuilder, midPriorityBuilder, lowPriorityBuilder, invalidPriorityBuilder));

		SourceBuilder picked = manager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected when the highest priority not inserted first or last. */
	@Test
	public void testPriorityInRandomOrder() throws Exception {
		manager.setBuilders(Arrays.asList(midPriorityBuilder, highPriorityBuilder, invalidPriorityBuilder, lowPriorityBuilder));

		SourceBuilder picked = manager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected when there is no invalid priority. */
	@Test
	public void testPriorityEverythingButInvalid() throws Exception {
		manager.setBuilders(Arrays.asList(midPriorityBuilder, highPriorityBuilder, lowPriorityBuilder));

		SourceBuilder picked = manager.detect(new File("dir"));
		assertEquals(picked, highPriorityBuilder);
	}

	/** Correct builder is selected with different priorities. */
	@Test
	public void testPriorityDifferent() throws Exception {
		manager.setBuilders(Arrays.asList(midPriorityBuilder, lowPriorityBuilder));

		SourceBuilder picked = manager.detect(new File("dir"));
		assertEquals(picked, midPriorityBuilder);
	}

	/** We should bail if we cannot detect the build system used by the source. */
	@Test(expected = ArtifactBuildException.class)
	public void testCannotDetectSource() throws Exception {
		manager.setBuilders(Collections.singletonList(invalidPriorityBuilder));

		manager.build(artifact, source, repository, session);
	}

	/** We should bubble up build exceptions. */
	@Test(expected = ArtifactBuildException.class)
	public void testBuildException() throws Exception {
		when(selectedBuilder.build(artifact, source, repository))
				.thenThrow(new ArtifactBuildException());

		manager.build(artifact, source, repository, session);
	}

	/** Sources should only be built from a pristine copy. */
	@Test
	public void testSourceCleanedBeforeBuilt() throws Exception {
		manager.build(artifact, source, repository, session);

		InOrder order = inOrder(source, selectedBuilder);
		order.verify(source).clean();
		order.verify(selectedBuilder).build(artifact, source, repository);
	}

	/** When there is an error cleaning sources, we need to hiccup. */
	@Test(expected = ArtifactBuildException.class)
	public void testSourceCleanError() throws Exception {
		doThrow(new IOException())
				.when(source).clean();

		manager.build(artifact, source, repository, session);
	}

	/** All the artifacts that are built should be returned. */
	@Test
	public void testAllBuiltArtifactsReturned() throws Exception {
		Artifact fileArtifact1 = mock(Artifact.class);
		Artifact noFileArtifact = mock(Artifact.class, "noFile");
		Artifact fileArtifact2 = mock(Artifact.class);

		when(fileArtifact1.getFile())
				.thenReturn(mock(File.class));
		when(fileArtifact2.getFile())
				.thenReturn(mock(File.class));
		when(selectedBuilder.build(artifact, source, repository))
				.thenReturn(new HashSet<Artifact>(Arrays.asList(fileArtifact1, noFileArtifact, fileArtifact2)));

		Set<Artifact> results = manager.build(artifact, source, repository, session);
		assertThat(results, hasSize(2));
		assertThat(results, containsInAnyOrder(fileArtifact1, fileArtifact2));
		assertThat("Artifacts without files should be filtered out", results, not(hasItem(noFileArtifact)));
	}

	/** A builder which returns the artifact with the same file as the resolved one should bail if not configured. */
	@Test(expected = ArtifactBuildException.class)
	public void testReturnsPrebuiltSource() throws Exception {
		when(selectedBuilder.build(any(Artifact.class), any(Source.class), any(File.class)))
				.then(new Answer<Set<Artifact>>() {
					@Override
					public Set<Artifact> answer(final InvocationOnMock invocation) throws Throwable {
						return Collections.singleton((Artifact) invocation.getArguments()[0]);
					}
				});

		manager.build(artifact, source, repository, session);
	}

	/** We should not discriminate builders from returning the exact same artifact as long as it sets the file properly. */
	@Test
	public void testReturnsSameArtifactWithNewFile() throws Exception {
		when(selectedBuilder.build(any(Artifact.class), any(Source.class), any(File.class)))
				.then(new Answer<Set<Artifact>>() {
					@Override
					public Set<Artifact> answer(final InvocationOnMock invocation) throws Throwable {
						Artifact artifact = (Artifact) invocation.getArguments()[0];
						doReturn(new File("another file"))
								.when(artifact).getFile();
						return Collections.singleton(artifact);
					}
				});

		Set<Artifact> results = manager.build(artifact, source, repository, session);
		assertThat(results, hasItem(artifact));
	}

	/** If we allow prebuilt sources, we should allow builders returning artifacts with the same file. */
	@Test
	public void testAllowPrebuiltSource() throws Exception {
		ReflectionUtils.setVariableValueInObject(manager, "allowPrebuiltSources", true);

		when(selectedBuilder.build(any(Artifact.class), any(Source.class), any(File.class)))
				.then(new Answer<Set<Artifact>>() {
					@Override
					public Set<Artifact> answer(final InvocationOnMock invocation) throws Throwable {
						return Collections.singleton((Artifact) invocation.getArguments()[0]);
					}
				});

		Set<Artifact> results = manager.build(artifact, source, repository, session);
		assertThat(results, hasItem(artifact));
	}

	/** Some builders may find it helpful to use the original artifact file when building, i.e. comparing. */
	@Test
	public void testSendResolvedArtifact() throws Exception {
		Artifact resolvedArtifact = mock(Artifact.class);
		when(resolvedArtifact.getFile())
				.thenReturn(new File("artifact"));
		ArtifactResolutionResult result = mock(ArtifactResolutionResult.class);
		when(result.getArtifacts())
				.thenReturn(Collections.singleton(resolvedArtifact));

		doReturn(result)
				.when(repoSystem).resolve(any(ArtifactResolutionRequest.class));

		manager.build(artifact, source, repository, session);
		verify(selectedBuilder).build(eq(resolvedArtifact), any(Source.class), any(File.class));
	}
}
