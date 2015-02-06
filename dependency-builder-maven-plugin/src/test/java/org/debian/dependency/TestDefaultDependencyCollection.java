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

import static org.debian.dependency.matchers.DependencyNodeArtifactMatcher.eqArtifactGraph;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.codehaus.plexus.logging.Logger;
import org.hamcrest.CustomMatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Test case for {@link DefaultDependencyCollection}. */
@RunWith(MockitoJUnitRunner.class)
public class TestDefaultDependencyCollection {
	@InjectMocks
	private DefaultDependencyCollection collector = new DefaultDependencyCollection();
	@Mock
	private Logger logger;
	@Mock
	private ArtifactInstaller installer;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private ProjectBuilder projectBuilder;
	@Mock(answer = Answers.RETURNS_MOCKS)
	private RepositorySystem repositorySystem;

	private DependencyNode singleNodeGraph = createNode(null);
	@Mock(answer = Answers.RETURNS_MOCKS)
	private MavenSession session;
	@Mock
	private ArtifactRepository repository;

	@Before
	public void setUp() throws Exception {
		when(repositorySystem.resolve(any(ArtifactResolutionRequest.class)))
				.then(new Answer<ArtifactResolutionResult>() {
					@Override
					public ArtifactResolutionResult answer(final InvocationOnMock invocation) throws Throwable {
						ArtifactResolutionRequest request = (ArtifactResolutionRequest) invocation.getArguments()[0];
						ArtifactResolutionResult result = new ArtifactResolutionResult();
						result.setArtifacts(Collections.singleton(request.getArtifact()));
						return result;
					}
				});

		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)).getProject())
				.thenReturn(new MavenProject());
	}

	private DependencyNode createNode(final DependencyNode parent, final Artifact artifact) {
		DefaultDependencyNode graph = new DefaultDependencyNode(parent, artifact, null, null, null);
		graph.setChildren(new ArrayList<DependencyNode>());
		if (parent != null) {
			parent.getChildren().add(graph);
		}

		return graph;
	}

	private DependencyNode createNode(final DependencyNode parent) {
		return createNode(parent, mock(Artifact.class));
	}

	/** We should be able to install all artifact graphs with no filter. */
	@Test
	public void testInstallNoFilter() throws Exception {
		List<DependencyNode> graphs = new ArrayList<DependencyNode>();
		graphs.add(createNode(null));
		createNode(graphs.get(0));

		graphs.add(createNode(null));

		List<DependencyNode> result = collector.installDependencies(graphs, null, repository, session);
		assertThat("All artifacts installed, nothing left", result, hasSize(0));

		verify(installer).install(any(File.class), eq(graphs.get(0).getArtifact()), eq(repository));
		verify(installer).install(any(File.class), eq(graphs.get(0).getChildren().get(0).getArtifact()), eq(repository));
		verify(installer).install(any(File.class), eq(graphs.get(1).getArtifact()), eq(repository));
	}

	/** Installed artifacts should be eligible for being filtered from installation. */
	@Test
	public void testInstallFiltered() throws Exception {
		final List<DependencyNode> graphs = new ArrayList<DependencyNode>();
		graphs.add(createNode(null));
		createNode(graphs.get(0));

		graphs.add(createNode(null));
		createNode(graphs.get(1));

		DependencyNodeFilter filter = new DependencyNodeFilter() {
			@Override
			public boolean accept(final DependencyNode node) {
				return node.getArtifact().equals(graphs.get(1).getArtifact());
			}
		};

		List<DependencyNode> result = collector.installDependencies(graphs, filter, repository, session);

		assertThat(result, contains(eqArtifactGraph(graphs.get(0))));
		assertThat(result, hasSize(1));

		verify(installer).install(any(File.class), eq(graphs.get(1).getArtifact()), eq(repository));
		verify(installer).install(any(File.class), eq(graphs.get(1).getChildren().get(0).getArtifact()), eq(repository));
	}

	/**
	 * Any artifact that is installed must not be returned. This tests for inconsistent input filters which could both include and
	 * exclude the same artifact. An example of such a filter is this test case: it has a filter that depends on the graph level
	 * with an artifact that is represented in both a level that is included as well as excluded.
	 */
	@Test
	public void testWeirdChildFilter() throws Exception {
		DependencyNodeFilter filter = new DependencyNodeFilter() {
			@Override
			public boolean accept(final DependencyNode node) {
				int parents = 0;
				for (DependencyNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
					++parents;
				}
				return parents >= 2;
			}
		};

		/* @formatter:off
		 * root
		 * |-- child1
		 *     |-- grandchild1
		 *         |-- grandgrandchild1
		 *     |-- grandchild2
		 * |-- child2
		 * |-- grandchild1
		 *     |-- grandgrandchild1
		 */
		// @formatter:on
		DependencyNode root = createNode(null);
		DependencyNode child1 = createNode(root);
		DependencyNode grandchild1 = createNode(child1);
		DependencyNode grandgrandchild1 = createNode(grandchild1);

		DependencyNode grandchild2 = createNode(child1);

		DependencyNode child2 = createNode(root);

		DependencyNode grandchild1Clone = createNode(root, grandchild1.getArtifact());
		createNode(grandchild1Clone, grandgrandchild1.getArtifact());

		List<DependencyNode> result = collector.installDependencies(Collections.singletonList(root), filter, repository, session);

		DependencyNode resultRoot = createNode(null, root.getArtifact());
		createNode(resultRoot, child1.getArtifact());
		createNode(resultRoot, child2.getArtifact());
		assertThat(result, contains(eqArtifactGraph(resultRoot)));
		assertThat(result, hasSize(1));

		verify(installer).install(any(File.class), eq(grandchild1.getArtifact()), eq(repository));
		verify(installer).install(any(File.class), eq(grandgrandchild1.getArtifact()), eq(repository));
		verify(installer).install(any(File.class), eq(grandchild2.getArtifact()), eq(repository));

	}

	/** We should fail if we cannot install artifacts. */
	@Test(expected = ArtifactInstallationException.class)
	public void testCannotInstallArtifact() throws Exception {
		doThrow(ArtifactInstallationException.class)
				.when(installer).install(any(File.class), eq(singleNodeGraph.getArtifact()), eq(repository));

		collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
	}

	/** We should fail if we cannot install any parent poms. */
	@Test(expected = ArtifactInstallationException.class)
	public void testCannotInstallParentPoms() throws Exception {
		MavenProject project = new MavenProject();
		project.setParent(new MavenProject());
		Artifact parentArtifact = mock(Artifact.class);
		project.getParent().setArtifact(parentArtifact);

		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)).getProject())
				.thenReturn(project);
		doThrow(ArtifactInstallationException.class)
				.when(installer).install(any(File.class), eq(parentArtifact), eq(repository));

		collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
	}

	/** We should fail if we cannot install a pom. */
	@Test(expected = ArtifactInstallationException.class)
	public void testCannotInstallPom() throws Exception {
		Artifact pomArtifact = mock(Artifact.class);

		when(repositorySystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.thenReturn(pomArtifact);
		doThrow(ArtifactInstallationException.class)
				.when(installer).install(any(File.class), eq(pomArtifact), eq(repository));

		collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
	}

	/** We should fail if we cannot install artifacts. */
	@Test(expected = DependencyResolutionException.class)
	public void testCannotResolveArtifact() throws Exception {
		ArtifactResolutionResult badResult = mock(ArtifactResolutionResult.class);
		when(badResult.isSuccess())
				.thenReturn(false);
		doReturn(badResult)
				.when(repositorySystem).resolve(any(ArtifactResolutionRequest.class));

		collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
	}

	/** We should fail if we cannot install any parent poms. */
	@Test(expected = DependencyResolutionException.class)
	public void testCannotResolveParentPoms() throws Exception {
		MavenProject project = new MavenProject();
		project.setParent(new MavenProject());
		final Artifact parentArtifact = mock(Artifact.class);
		project.getParent().setArtifact(parentArtifact);

		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)).getProject())
				.thenReturn(project);
		ArtifactResolutionResult badResult = mock(ArtifactResolutionResult.class);
		when(badResult.isSuccess())
				.thenReturn(false);
		doReturn(badResult)
				.when(repositorySystem).resolve(argThat(new CustomMatcher<ArtifactResolutionRequest>("parent artifact") {
					@Override
					public boolean matches(final Object item) {
						ArtifactResolutionRequest request = (ArtifactResolutionRequest) item;
						return request.getArtifact().equals(parentArtifact);
					}
				}));

		collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
	}

	/** We should fail if we cannot install a pom. */
	@Test(expected = DependencyResolutionException.class)
	public void testCannotResolvePom() throws Exception {
		final Artifact pomArtifact = mock(Artifact.class);
		when(repositorySystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.thenReturn(pomArtifact);

		ArtifactResolutionResult badResult = mock(ArtifactResolutionResult.class);
		when(badResult.isSuccess())
				.thenReturn(false);
		doReturn(badResult)
				.when(repositorySystem).resolve(argThat(new CustomMatcher<ArtifactResolutionRequest>("parent artifact") {
					@Override
					public boolean matches(final Object item) {
						ArtifactResolutionRequest request = (ArtifactResolutionRequest) item;
						return request.getArtifact().equals(pomArtifact);
					}
				}));

		collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
	}

	/** Happy path. */
	@Test
	public void testInstall() throws Exception {
		MavenProject project = new MavenProject();
		project.setParent(new MavenProject());
		final Artifact parentArtifact = mock(Artifact.class);
		File parentFile = mock(File.class);
		when(parentArtifact.getFile())
				.thenReturn(parentFile);
		project.getParent().setArtifact(parentArtifact);
		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)).getProject())
				.thenReturn(project);

		File pomFile = mock(File.class);
		final Artifact pomArtifact = mock(Artifact.class);
		when(repositorySystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.thenReturn(pomArtifact);
		when(pomArtifact.getFile())
				.thenReturn(pomFile);

		File graphFile = mock(File.class);
		when(singleNodeGraph.getArtifact().getFile())
				.thenReturn(graphFile);

		List<DependencyNode> result = collector.installDependencies(Collections.singletonList(singleNodeGraph), null, repository, session);
		assertThat(result, hasSize(0));

		// we require a specific install sequence here to ensure the repository is in the best state if there is an error
		InOrder order = inOrder(installer);
		order.verify(installer).install(graphFile, singleNodeGraph.getArtifact(), repository);
		order.verify(installer).install(parentFile, parentArtifact, repository);
		order.verify(installer).install(pomFile, pomArtifact, repository);
	}
}
