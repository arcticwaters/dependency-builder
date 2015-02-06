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

import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.debian.dependency.sources.Source;
import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import com.google.common.io.Files;

/** Tests for {@link EmbeddedAntBuilder}. */
@RunWith(MockitoJUnitRunner.class)
public class TestEmbeddedAntBuilder {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Spy
	@InjectMocks
	private EmbeddedAntBuilder builder = new EmbeddedAntBuilder();

	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private ModelBuilder modelBuilder;
	@Mock(answer = Answers.RETURNS_MOCKS)
	private RepositorySystem repoSystem;

	private XMLOutputFactory factory = XMLOutputFactory.newInstance();
	private File pomFile;
	private File buildFile;
	private File repository = new File("repository");
	private Artifact artifact = new DefaultArtifact("groupId", "artifactId", "version", Artifact.SCOPE_RUNTIME, "type", "classifier", null);
	@Mock
	private Source source;

	@Before
	public void setUp() throws Exception {
		File sourceLocation = tempFolder.newFolder();
		when(source.getLocation())
				.thenReturn(sourceLocation);

		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getGroupId())
				.thenReturn(artifact.getGroupId());
		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getArtifactId())
				.thenReturn(artifact.getArtifactId());
		when(modelBuilder.build(any(ModelBuildingRequest.class)).getEffectiveModel().getVersion())
				.thenReturn(artifact.getVersion());

		buildFile = File.createTempFile("build", ".xml", sourceLocation);
		writeEmptyBuildFile(buildFile);
		doReturn(Collections.singletonList(buildFile))
				.when(builder).findBuildFiles(sourceLocation);


		pomFile = new File(sourceLocation, "pom.xml");
		pomFile.createNewFile();

		File jarFile = new File(tempFolder.getRoot(), "artifact.jar");
		writeSimpleJar(jarFile);
		artifact.setFile(jarFile);
	}

	private void writeEmptyBuildFile(final File buildFile) throws Exception {
		XMLStreamWriter writer = factory.createXMLStreamWriter(new BufferedWriter(new FileWriter(buildFile)));
		writer.writeStartDocument();
		writer.writeStartElement("project");
		writer.writeAttribute("default", "default");

		writer.writeStartElement("target");
		writer.writeAttribute("name", "default");
		writer.writeEndElement();

		writer.writeEndElement();
		writer.writeEndDocument();

		writer.flush();
		writer.close();
	}

	private void writeSimpleJar(final File jarFile) throws Exception {
		JarOutputStream stream = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)));
		stream.putNextEntry(new ZipEntry("entry1"));
		stream.putNextEntry(new ZipEntry("entry2"));
		stream.putNextEntry(new ZipEntry("entry3"));
		stream.putNextEntry(new ZipEntry("entry4"));

		stream.flush();
		stream.close();
	}

	/** When there are no build files to be found, we hiccup. */
	@Test(expected = ArtifactBuildException.class)
	public void testNoBuildFiles() throws Exception {
		doReturn(Collections.emptyList())
				.when(builder).findBuildFiles(any(File.class));

		builder.build(artifact, source, repository);
	}

	/** We should only use the first, its the highest in the hierarchy tree (alpha sorted). */
	@Test
	public void testMultipleBuildFiles() throws Exception {
		File otherBuildFile = new File("other-file");
		doReturn(Arrays.asList(buildFile, otherBuildFile))
				.when(builder).findBuildFiles(any(File.class));

		writeSimpleJar(new File(source.getLocation(), "some-artifact.jar"));

		assertFalse("Ensures that ant will fail if this is used", otherBuildFile.exists());
		builder.build(artifact, source, repository);
	}

	/** Ant doesn't know how to handle an empty build file, we should hiccup. */
	@Test(expected = ArtifactBuildException.class)
	public void testEmptyBuildFile() throws Exception {
		Files.write(new byte[0], buildFile);

		builder.build(artifact, source, repository);
	}

	/** If ant can't parse the build file, we should hiccup. */
	@Test(expected = ArtifactBuildException.class)
	public void testInvalidBuildFile() throws Exception {
		Files.write("some invalid build file", buildFile, Charset.defaultCharset());

		builder.build(artifact, source, repository);
	}

	/** When the ant build fails, we need to fail. */
	@Test(expected = ArtifactBuildException.class)
	public void testAntBuildFails() throws Exception {
		XMLStreamWriter writer = factory.createXMLStreamWriter(new BufferedWriter(new FileWriter(buildFile)));
		writer.writeStartDocument();
		writer.writeStartElement("project");
		writer.writeAttribute("default", "default");

		writer.writeStartElement("target");
		writer.writeAttribute("name", "default");
		writer.writeEmptyElement("fail");
		writer.writeEndElement();

		writer.writeEndElement();
		writer.writeEndDocument();

		writer.flush();
		writer.close();

		builder.build(artifact, source, repository);
	}

	/** We expect all ant projects to create at least 1 jar file. */
	@Test(expected = ArtifactBuildException.class)
	public void testNoJarFileCreated() throws Exception {
		File folder = tempFolder.newFolder();
		when(source.getLocation())
				.thenReturn(folder);

		builder.build(artifact, source, repository);
	}

	/** When there are multiple jar files, make sure the one we pick is most "similar" to the downloaded one. */
	@Test
	@SuppressWarnings("unchecked")
	public void testMultipleJarFiles() throws Exception {
		JarOutputStream stream = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(new File(source.getLocation(),
				"sources.jar"))));
		stream.putNextEntry(new ZipEntry("one"));
		stream.putNextEntry(new ZipEntry("two"));
		stream.flush();
		stream.close();

		Artifact pomArtifact = new DefaultArtifact("groupId", "artifactId", "version", Artifact.SCOPE_RUNTIME, "pom", "", null);
		when(repoSystem.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()))
				.thenReturn(pomArtifact);

		final File jarFile = new File(source.getLocation(), "some-artifact.jar");
		writeSimpleJar(jarFile);

		Set<Artifact> results = builder.build(artifact, source, repository);
		assertThat(results, hasSize(2));
		assertThat(results, containsInAnyOrder(artifact, pomArtifact));
		assertThat(results, containsInAnyOrder(new CustomTypeSafeMatcher<Artifact>("Artifact with file") {
			@Override
			protected boolean matchesSafely(final Artifact item) {
				return jarFile.getAbsoluteFile().equals(item.getFile().getAbsoluteFile());
			}
		}, anything()));

		assertEquals(pomFile.getAbsoluteFile(), pomArtifact.getFile().getAbsoluteFile());
	}
}