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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.plugin.testing.stubs.ArtifactStub;
import org.apache.maven.plugin.testing.stubs.MavenProjectStub;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.debian.dependency.matchers.ObjectPropertyMatcher;
import org.eclipse.jgit.api.Git;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Tests for {@link EmbeddedMavenBuilder}. */
@RunWith(MockitoJUnitRunner.class)
public class TestEmbeddedMavenBuilder {
	@Rule
	public MojoRule mojoRule = new MojoRule();

	@Mock
	private Invoker invoker;
	@Mock
	private ArtifactInstaller artifactInstaller;

	@Before
	public void setUp() throws Exception {
		mojoRule.getContainer().addComponent(invoker, Invoker.class, PlexusConstants.PLEXUS_DEFAULT_HINT);
		mojoRule.getContainer().addComponent(artifactInstaller, ArtifactInstaller.class, PlexusConstants.PLEXUS_DEFAULT_HINT);

		when(invoker.execute(any(InvocationRequest.class)))
				.thenReturn(mock(InvocationResult.class));
	}

	private EmbeddedMavenBuilder lookupBuilder() throws Exception {
		return (EmbeddedMavenBuilder) mojoRule.getContainer().lookup(SourceBuilder.class, "maven2");
	}

	private File findProjectDirectory() throws URISyntaxException {
		URL url = getClass().getResource("/projects/marker.txt");
		return new File(url.toURI()).getParentFile();
	}

	private MavenProject createProject(final String groupId, final String artifactId, final String version) {
		MavenProject stub = new MavenProjectStub();
		stub.setGroupId(groupId);
		stub.setArtifactId(artifactId);
		stub.setVersion(version);

		Artifact artifactStub = new ArtifactStub();
		artifactStub.setGroupId(groupId);
		artifactStub.setArtifactId(artifactId);
		artifactStub.setVersion(version);
		stub.setArtifact(artifactStub);

		return stub;
	}

	@Test
	public void testProjectInRoot() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "simple");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", basedir);

		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
	}

	@Test
	public void testMultiModuleSingleBuilt() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "simple");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", basedir);

		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
	}

	@Test
	public void testProjectNotInRoot() throws Exception {
		MavenProject project = createProject("com.example", "module2", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "multi-module");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", new File(basedir, "module2"));

		// even though there are inter-project dependencies, they should not be built unless requested
		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
	}

	@Test
	public void testFindNonRootedSingleProject() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "non-rooted-simple");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", new File(basedir, "folder"));

		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
	}

	@Test
	public void testFindNonRootedMultiModuleProject() throws Exception {
		MavenProject project = createProject("com.example", "module1", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "non-rooted-multi-module");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", new File(basedir, "folder/module1"));

		Map<String, Object> parentProperties = new HashMap<String, Object>();
		parentProperties.put("groupId", "com.example");
		parentProperties.put("artifactId", "test-parent");
		parentProperties.put("version", "0.0.1-SNAPSHOT");

		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
		verify(artifactInstaller).install(eq(new File(basedir, "folder/pom.xml")),
				argThat(new ObjectPropertyMatcher<Artifact>(parentProperties)), any(ArtifactRepository.class));
	}

	@Test
	public void testBuildPomProject() throws Exception {
		MavenProject project = createProject("com.example", "test-parent", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "multi-module");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", basedir);
		properties.put("recursive", false);

		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
	}

	@Test
	public void testOffline() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "simple");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("basedir", basedir);
		properties.put("offline", true);

		verify(invoker).execute(argThat(new ObjectPropertyMatcher<InvocationRequest>(properties)));
	}

	@Test(expected = ArtifactBuildException.class)
	public void testArtifactNotExists() throws Exception {
		MavenProject project = createProject("artifact", "does-not-exist", "55");
		File basedir = new File(findProjectDirectory(), "multi-module");
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());
	}

	@Test(expected = ArtifactBuildException.class)
	public void testExecutionThrowsException() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "simple");

		when(invoker.execute(any(InvocationRequest.class)))
				.thenThrow(new MavenInvocationException("exception"));

		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());
	}

	@Test(expected = ArtifactBuildException.class)
	public void testExecutionCommandlineException() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "simple");

		InvocationResult result = mock(InvocationResult.class);
		when(invoker.execute(any(InvocationRequest.class)))
				.thenReturn(result);
		when(result.getExecutionException())
				.thenReturn(new CommandLineException("exception"));

		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());
	}

	@Test(expected = ArtifactBuildException.class)
	public void testExecutionCommandlineFailed() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "simple");

		InvocationResult result = mock(InvocationResult.class);
		when(invoker.execute(any(InvocationRequest.class)))
				.thenReturn(result);
		when(result.getExitCode())
				.thenReturn(-1);

		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());
	}

	@Test
	public void testCanBuild() throws Exception {
		MavenProject project = createProject("com.example", "test", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "non-rooted-multi-module");
		assertFalse("artifact not in directory", lookupBuilder().canBuild(project, basedir));

		project = createProject("com.example", "test-parent", "0.0.1-SNAPSHOT");
		assertTrue("should find artifacts outside of the root", lookupBuilder().canBuild(project, basedir));

		project = createProject("com.example", "module1", "0.0.1-SNAPSHOT");
		assertTrue("should find modules of the main project", lookupBuilder().canBuild(project, basedir));
	}

	@Test(expected = ArtifactBuildException.class)
	public void testUnableToInstallParent() throws Exception {
		MavenProject project = createProject("com.example", "module1", "0.0.1-SNAPSHOT");
		File basedir = new File(findProjectDirectory(), "non-rooted-multi-module");

		doThrow(ArtifactBuildException.class).when(artifactInstaller).install(any(File.class), any(Artifact.class),
				any(ArtifactRepository.class));
		lookupBuilder().build(project, Git.init().setDirectory(basedir).call(), findProjectDirectory());
	}
}