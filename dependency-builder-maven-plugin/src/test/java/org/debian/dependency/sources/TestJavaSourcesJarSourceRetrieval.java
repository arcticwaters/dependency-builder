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

import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.logging.Logger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import com.google.common.io.Files;
import com.google.common.io.LineProcessor;

/** Test case for {@link JavaSourcesJarSourceRetrieval}. */
@RunWith(MockitoJUnitRunner.class)
public class TestJavaSourcesJarSourceRetrieval {
	private static final String DATA1 = "some-data";
	private static final String DATA2 = "more-data";

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@InjectMocks
	private JavaSourcesJarSourceRetrieval sourceRetrieval = new JavaSourcesJarSourceRetrieval();
	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private RepositorySystem repoSystem;
	@Mock
	private Logger logger;

	private File directory;
	private Artifact artifact = mock(Artifact.class, Answers.RETURNS_SMART_NULLS.get());
	@Mock
	private MavenSession session;

	@Before
	public void setUp() throws Exception {
		directory = tempFolder.newFolder();

		when(repoSystem.resolve(any(ArtifactResolutionRequest.class)))
				.then(new Answer<ArtifactResolutionResult>() {
					@Override
					public ArtifactResolutionResult answer(final InvocationOnMock invocation) throws Throwable {
						ArtifactResolutionRequest request = (ArtifactResolutionRequest) invocation.getArguments()[0];
						ArtifactResolutionResult result = new ArtifactResolutionResult();
						result.addArtifact(request.getArtifact());
						return result;
					}
				});
		when(repoSystem.createArtifactWithClassifier(anyString(), anyString(), anyString(), anyString(), anyString()))
				.thenReturn(artifact);

		when(artifact.getType())
				.thenReturn("jar");
	}

	/** Get location should never return an empty value. */
	@Test
	public void testGetLocation() throws Exception {
		String result = sourceRetrieval.getSourceLocation(artifact, session);
		assertThat(result, not(isEmptyOrNullString()));
	}

	/** Get directory name should never return an empty value. */
	@Test
	public void testGetDirname() throws Exception {
		when(artifact.getId())
				.thenReturn("some-id");

		String result = sourceRetrieval.getSourceDirname(artifact, session);
		assertThat(result, not(isEmptyOrNullString()));
	}

	/** When there are no attached sources. */
	@Test
	public void testNoAttachedSources() throws Exception {
		when(artifact.getFile())
				.thenReturn(null);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, isEmptyOrNullString());
	}

	/** Ensure that we can only work with jar artifacts. */
	@Test(expected = SourceRetrievalException.class)
	public void testRetrieveNonJar() throws Exception {
		when(artifact.getType())
				.thenReturn("bz2");
		when(artifact.getFile())
				.thenReturn(tempFolder.newFile("sources.bz2"));

		sourceRetrieval.retrieveSource(artifact, directory, session);
	}

	/** Jar with files before folders. */
	@Test
	public void testJarFilesBeforeFolders() throws Exception {
		File jarFile = tempFolder.newFile();
		JarOutputStream stream = new JarOutputStream(new FileOutputStream(jarFile));

		stream.putNextEntry(new JarEntry("/folder/entry1"));
		stream.write(DATA1.getBytes());
		stream.closeEntry();

		stream.putNextEntry(new JarEntry("entry2"));
		stream.write(DATA2.getBytes());
		stream.closeEntry();

		stream.putNextEntry(new JarEntry("/folder/"));
		stream.close();

		when(artifact.getFile())
				.thenReturn(jarFile);
		when(repoSystem.createProjectArtifact(anyString(), anyString(), anyString()).getFile())
				.thenReturn(tempFolder.newFile());

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, not(isEmptyOrNullString()));

		assertEquals(DATA1, Files.readLines(new File(directory, "src/main/java/folder/entry1"), Charset.defaultCharset(), new LineJoiner()));
		assertEquals(DATA2, Files.readLines(new File(directory, "src/main/java/entry2"), Charset.defaultCharset(), new LineJoiner()));
	}

	/** Jar with folders before files. */
	@Test
	public void testJarFoldersBeforeFiles() throws Exception {
		File jarFile = tempFolder.newFile();
		JarOutputStream stream = new JarOutputStream(new FileOutputStream(jarFile));

		stream.putNextEntry(new JarEntry("/folder/"));
		stream.closeEntry();

		stream.putNextEntry(new JarEntry("entry2"));
		stream.write(DATA2.getBytes());
		stream.closeEntry();

		stream.putNextEntry(new JarEntry("/folder/entry1"));
		stream.write(DATA1.getBytes());
		stream.close();

		when(artifact.getFile())
				.thenReturn(jarFile);
		when(repoSystem.createProjectArtifact(anyString(), anyString(), anyString()).getFile())
				.thenReturn(tempFolder.newFile());

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, not(isEmptyOrNullString()));

		assertEquals(DATA1, Files.readLines(new File(directory, "src/main/java/folder/entry1"), Charset.defaultCharset(), new LineJoiner()));
		assertEquals(DATA2, Files.readLines(new File(directory, "src/main/java/entry2"), Charset.defaultCharset(), new LineJoiner()));
	}

	/** The pom file should also be copied to the output directory. */
	@Test
	public void testPomFileCopied() throws Exception {
		File jarFile = tempFolder.newFile();
		JarOutputStream stream = new JarOutputStream(new FileOutputStream(jarFile));
		stream.close();

		File pomFile = tempFolder.newFile();
		Files.write(DATA1.getBytes(), pomFile);

		when(artifact.getFile())
				.thenReturn(jarFile);
		when(repoSystem.createProjectArtifact(anyString(), anyString(), anyString()).getFile())
				.thenReturn(pomFile);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, not(isEmptyOrNullString()));

		assertEquals(DATA1, Files.readLines(new File(directory, "pom.xml"), Charset.defaultCharset(), new LineJoiner()));
	}

	/** We cannot do anything if we can't read the file. */
	@Test(expected = SourceRetrievalException.class)
	public void testCannotReadJarFile() throws Exception {
		File jarFile = tempFolder.newFile();
		Files.write("not a jar file".getBytes(), jarFile);

		when(artifact.getFile())
				.thenReturn(jarFile);

		sourceRetrieval.retrieveSource(artifact, directory, session);
	}

	private static class LineJoiner implements LineProcessor<String> {
		private StringBuilder builder = new StringBuilder();

		@Override
		public boolean processLine(final String line) throws IOException {
			builder.append(line);
			return true;
		}

		@Override
		public String getResult() {
			return builder.toString();
		}
	}
}
