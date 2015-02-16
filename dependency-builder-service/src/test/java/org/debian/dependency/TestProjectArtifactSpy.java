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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileReader;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.eventspy.EventSpy.Context;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Test case for {@link ProjectArtifactSpy}. */
@RunWith(MockitoJUnitRunner.class)
public class TestProjectArtifactSpy {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@InjectMocks
	private ProjectArtifactSpy artifactSpy = new ProjectArtifactSpy();

	@Mock
	private RepositorySystem repoSystem;

	@Mock
	private Context context;

	private ExecutionEvent mockExecutionEvent(final Type type, final MavenProject project) {
		ExecutionEvent event = mock(ExecutionEvent.class);
		when(event.getType())
				.thenReturn(type);
		when(event.getProject())
				.thenReturn(project);
		return event;
	}

	private Artifact mockArtifact(final String name, final File artifactFile) {
		Artifact artifact = mock(Artifact.class, name);
		when(artifact.getId())
				.thenReturn(name);
		when(artifact.getFile())
				.thenReturn(artifactFile);
		return artifact;
	}

	@Before
	public void setUp() throws Exception {
		when(repoSystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.then(new Answer<Artifact>() {
					@Override
					public Artifact answer(final InvocationOnMock invocation) throws Throwable {
						String groupId = (String) invocation.getArguments()[0];
						String artifactId = (String) invocation.getArguments()[1];
						String version = (String) invocation.getArguments()[2];

						Artifact artifact = mock(TestArtifact.class);
						doCallRealMethod()
								.when(artifact).setFile(any(File.class));
						when(artifact.getFile())
								.thenCallRealMethod();
						when(artifact.getId())
								.thenReturn(ArtifactUtils.key(groupId, artifactId, version));

						return artifact;
					}
				});
	}

	/** The spy should work correctly if there are no events. */
	@Test
	public void testNoEvents() throws Exception {
		artifactSpy.init(context);
		artifactSpy.close();
	}

	/** When all projects failed, we should not have any failures. */
	@Test
	public void testAllProjectsFailed() throws Exception {
		artifactSpy.init(context);
		artifactSpy.onEvent(mockExecutionEvent(Type.ProjectStarted, null));
		artifactSpy.onEvent(mockExecutionEvent(Type.ProjectFailed, null));
		artifactSpy.close();
	}

	/** All projects which were completed successfully should be logged to the output file. */
	@Test
	public void testEvents() throws Exception {
		File projectFolder = tempFolder.newFolder();
		File project1Pom = tempFolder.newFile();
		File project1Artifact = tempFolder.newFile();
		File project1AttachedArtifact = tempFolder.newFile();
		File project2Pom = tempFolder.newFile();
		File project2Artifact = tempFolder.newFile();

		MavenProject project1 = new MavenProject();
		project1.setGroupId("");
		project1.setArtifactId("project1");
		project1.setVersion("1");
		project1.getBuild().setDirectory(projectFolder.getPath());
		project1.setFile(project1Pom);
		project1.setArtifact(mockArtifact("project1", project1Artifact));
		project1.addAttachedArtifact(mockArtifact("project1-attached", project1AttachedArtifact));

		MavenProject project2 = new MavenProject();
		project2.setGroupId("");
		project2.setArtifactId("project2");
		project2.setVersion("1");
		project2.getBuild().setDirectory(tempFolder.newFolder().getPath());
		project2.setFile(project2Pom);
		project2.setArtifact(mockArtifact("project2", project2Artifact));

		artifactSpy.init(context);
		artifactSpy.onEvent(mockExecutionEvent(Type.ProjectStarted, project1));
		artifactSpy.onEvent(mockExecutionEvent(Type.ProjectSucceeded, project1));

		artifactSpy.onEvent(mockExecutionEvent(Type.ProjectStarted, project2));
		artifactSpy.onEvent(mockExecutionEvent(Type.ProjectSucceeded, project2));
		artifactSpy.close();

		File artifactFile = new File(projectFolder, ServicePackage.PROJECT_ARTIFACT_REPORT_NAME);
		assertTrue("Missing artifact file", artifactFile.exists());

		Properties props = new Properties();
		props.load(new FileReader(artifactFile));
		assertThat(props.keySet(),
				Matchers.<Object> containsInAnyOrder("project1", ":project1:1", "project1-attached", "project2", ":project2:1"));
		assertEquals(project1Artifact.getCanonicalPath(), props.getProperty("project1"));
		assertEquals(project1Pom.getCanonicalPath(), props.getProperty(":project1:1"));
		assertEquals(project1AttachedArtifact.getCanonicalPath(), props.getProperty("project1-attached"));
		assertEquals(project2Artifact.getCanonicalPath(), props.getProperty("project2"));
		assertEquals(project2Pom.getCanonicalPath(), props.getProperty(":project2:1"));
	}

	/** Test class for storing data in mocks. */
	private abstract static class TestArtifact implements Artifact {
		private File file;

		@Override
		public File getFile() {
			return file;
		}

		@Override
		public void setFile(final File file) {
			this.file = file;
		}
	}
}
