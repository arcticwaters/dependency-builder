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
package org.debian.dependency.builders;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.utils.io.IOUtil;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.debian.dependency.ProjectArtifactSpy;
import org.debian.dependency.sources.Source;
import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Tests for {@link ForkedMavenBuilder}. */
@RunWith(MockitoJUnitRunner.class)
public class TestForkedMavenBuilder {
	private static final String VERSION = "version";
	private static final String ARTIFACT_ID = "artifactId";
	private static final String GROUP_ID = "groupId";

	@Spy
	@InjectMocks
	private ForkedMavenBuilder builder = new ForkedMavenBuilder();

	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private ModelBuilder modelBuilder;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private Invoker invoker;
	@Mock
	private ArtifactInstaller artifactInstaller;
	@Mock(answer = Answers.RETURNS_MOCKS)
	private RepositorySystem repoSystem;
	@Mock
	private Logger logger;

	private File buildFile = new File("build-file");
	private File repository = new File("repository");
	@Mock
	private Artifact artifact;
	@Mock
	private Source source;

	@Before
	public void setUp() throws Exception {
		File sourceLocation = new File("source");
		when(source.getLocation())
				.thenReturn(sourceLocation);

		doReturn(Collections.singletonList(buildFile))
				.when(builder).findBuildFiles(sourceLocation);

		when(artifact.getGroupId())
				.thenReturn(GROUP_ID);
		when(artifact.getArtifactId())
				.thenReturn(ARTIFACT_ID);
		when(artifact.getVersion())
				.thenReturn(VERSION);

		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getGroupId())
				.thenReturn(GROUP_ID);
		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getArtifactId())
				.thenReturn(ARTIFACT_ID);
		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getVersion())
				.thenReturn(VERSION);

		when(invoker.execute(any(InvocationRequest.class)))
				.thenReturn(mock(InvocationResult.class));
	}

	/** When the only build file produces errors trying to read it, we hiccup. */
	@Test(expected = ArtifactBuildException.class)
	public void testUnableToReadBuildFile() throws Exception {
		when(modelBuilder.build(any(ModelBuildingRequest.class)))
				.thenThrow(new ModelBuildingException(null));

		builder.build(artifact, source, repository);
	}

	/** A failed build file should not prevent other build files from being checked. */
	@Test
	public void testOneGoodOneFailedBuildFile() throws Exception {
		final File badBuildFile = new File("bad-file");
		doReturn(Arrays.asList(badBuildFile, buildFile))
				.when(builder).findBuildFiles(any(File.class));
		when(modelBuilder.build(argThat(new CustomTypeSafeMatcher<ModelBuildingRequest>("Bad build file") {
			@Override
			protected boolean matchesSafely(final ModelBuildingRequest item) {
				return badBuildFile.getAbsoluteFile().equals(item.getPomFile().getAbsoluteFile());
			}
		})))
				.thenThrow(new ModelBuildingException(null));

		Model model = mock(Model.class);
		when(model.getGroupId())
				.thenReturn(GROUP_ID);
		when(model.getArtifactId())
				.thenReturn(ARTIFACT_ID);
		when(model.getVersion())
				.thenReturn(VERSION);
		when(model.getPomFile())
				.thenReturn(buildFile);
		ModelBuildingResult modelBuildingResult = mock(ModelBuildingResult.class);
		when(modelBuildingResult.getEffectiveModel())
				.thenReturn(model);

		when(modelBuilder.build(argThat(new CustomTypeSafeMatcher<ModelBuildingRequest>("Good build file") {
			@Override
			protected boolean matchesSafely(final ModelBuildingRequest item) {
				return buildFile.getAbsoluteFile().equals(item.getPomFile().getAbsoluteFile());
			}
		}))).thenReturn(modelBuildingResult);

		builder.build(artifact, source, repository);

		ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(invoker).execute(request.capture());

		assertEquals(buildFile, request.getValue().getPomFile());
	}

	/** We cannot continue if there are no build files. */
	@Test(expected = ArtifactBuildException.class)
	public void testNoBuildFilesFound() throws Exception {
		doReturn(Collections.emptyList())
				.when(builder).findBuildFiles(any(File.class));

		builder.build(artifact, source, repository);
	}

	/** We cannot continue if there are problems locating build files. */
	@Test(expected = ArtifactBuildException.class)
	public void testErrorFindingBuildFiles() throws Exception {
		doThrow(new IOException())
				.when(builder).findBuildFiles(any(File.class));

		builder.build(artifact, source, repository);
	}

	/** A given artifact must exist in the provided source location. */
	@Test(expected = ArtifactBuildException.class)
	public void testBuildFilesButNoMatchingArtifact() throws Exception {
		doReturn(Arrays.asList(mock(File.class), mock(File.class)))
				.when(builder).findBuildFiles(any(File.class));
		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getGroupId())
				.thenReturn(GROUP_ID + "different");

		builder.build(artifact, source, repository);
	}

	/** We should fail if the invoker fails. */
	@Test(expected = ArtifactBuildException.class)
	public void testInvokerException() throws Exception {
		when(invoker.execute(any(InvocationRequest.class)))
				.thenThrow(new MavenInvocationException(""));

		builder.build(artifact, source, repository);
	}

	/** We should fail if the command line of the invoker fails. This can signify a valid, but failed maven build. */
	@Test(expected = ArtifactBuildException.class)
	public void testInvokerCommandlineFails() throws Exception {
		when(invoker.execute(any(InvocationRequest.class)).getExitCode())
				.thenReturn(-1);

		builder.build(artifact, source, repository);
	}

	/** Exceptions from the commandline should bubble up and fail the build process. */
	@Test(expected = ArtifactBuildException.class)
	public void testInvokerCommandlineException() throws Exception {
		when(invoker.execute(any(InvocationRequest.class)).getExecutionException())
				.thenReturn(new CommandLineException(""));

		builder.build(artifact, source, repository);
	}

	/**
	 * Although unlikely, it may occur that a build does not produce any detectable artifacts (wrong build perhaps?).
	 */
	@Test
	public void testReportFileEmpty() throws Exception {
		Set<Artifact> results = builder.build(artifact, source, repository);
		assertThat(results, hasSize(0));
	}

	/** All artifacts from the report file should be returned. */
	@Test
	public void testArtifactsFromReportReturned() throws Exception {
		final String sourcesFile = "sources-jar";
		final String jarFile = "jar-file";
		final String pomFile = "pom-file";

		when(invoker.execute(any(InvocationRequest.class)))
				.then(new Answer<InvocationResult>() {
					@Override
					public InvocationResult answer(final InvocationOnMock invocation) throws Throwable {
						InvocationRequest request = (InvocationRequest) invocation.getArguments()[0];
						File file = new File(request.getProperties().getProperty(ProjectArtifactSpy.REPORT_FILE_PROPERTY));

						Properties properties = new Properties();
						properties.setProperty(GROUP_ID + ":" + ARTIFACT_ID + ":jar:" + VERSION, jarFile);
						properties.setProperty(GROUP_ID + ":" + ARTIFACT_ID + ":pom:" + VERSION, pomFile);
						properties.setProperty(GROUP_ID + ":" + ARTIFACT_ID + ":jar:sources:" + VERSION, sourcesFile);

						OutputStream stream = new BufferedOutputStream(new FileOutputStream(file));
						try {
							properties.store(stream, "");
						} finally {
							IOUtil.close(stream);
						}

						return mock(InvocationResult.class);
					}
				});

		Artifact mainArtifact = mock(Artifact.class, "mainArtifact");
		Artifact pomArtifact = mock(Artifact.class, "pomArtifact");
		Artifact sourcesArtifact = mock(Artifact.class, "sourcesArtifact");

		when(repoSystem.createArtifact(GROUP_ID, ARTIFACT_ID, VERSION, "jar"))
				.thenReturn(mainArtifact);
		when(repoSystem.createArtifact(GROUP_ID, ARTIFACT_ID, VERSION, "pom"))
				.thenReturn(pomArtifact);
		when(repoSystem.createArtifactWithClassifier(GROUP_ID, ARTIFACT_ID, VERSION, "jar", "sources"))
				.thenReturn(sourcesArtifact);

		Set<Artifact> results = builder.build(artifact, source, repository);
		assertThat(results, hasSize(3));
		assertThat(results, containsInAnyOrder(mainArtifact, pomArtifact, sourcesArtifact));

		// these should be setup by interface contract
		verify(mainArtifact).setFile(new File(jarFile));
		verify(pomArtifact).setFile(new File(pomFile));
		verify(sourcesArtifact).setFile(new File(sourcesFile));
	}

	/** Ensure that the invoker is run with the correct project arguments. */
	@Test
	public void testInvocationArguments() throws Exception {
		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getPomFile())
				.thenReturn(buildFile);

		builder.build(artifact, source, repository);

		ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
		verify(invoker).execute(request.capture());

		assertTrue("Fetching online artifacts defeats the purpose of building them", request.getValue().isOffline());
		assertEquals("Use previously build artifacts for dependencies", repository.getAbsoluteFile(), request.getValue()
				.getLocalRepositoryDirectory(null).getAbsoluteFile());
		assertEquals(buildFile.getAbsoluteFile(), request.getValue().getPomFile().getAbsoluteFile());
	}
}