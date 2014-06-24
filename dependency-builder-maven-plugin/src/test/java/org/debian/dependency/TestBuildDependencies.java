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
package org.debian.dependency;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.plugin.testing.ResolverExpressionEvaluatorStub;
import org.apache.maven.plugin.testing.stubs.ArtifactStub;
import org.apache.maven.plugin.testing.stubs.DefaultArtifactHandlerStub;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.debian.dependency.builders.BuildSession;
import org.debian.dependency.builders.BuildStrategy;
import org.debian.dependency.matchers.ArtifactMatcher;
import org.debian.dependency.matchers.DependencyNodeArtifactMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestBuildDependencies {
	@Rule
	public MojoRule mojoRule = new MojoRule();

	private DependencyCollector collector;
	private BuildStrategy buildStrategy;
	private ArtifactInstaller installer;
	private RepositorySystem repositorySystem;
	private ComponentConfigurator configurator;
	private ProjectBuilder projectBuilder;

	private BuildDependencies lookupConfiguredMojo() throws Exception {
		return lookupConfiguredMojo(defaultConfiguration());
	}

	private PlexusConfiguration defaultConfiguration() {
		DefaultPlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact");
		return config;
	}

	private void merge(PlexusConfiguration from, PlexusConfiguration to) {
		PlexusConfiguration[] children = from.getChildren();
		if (children == null) {
			return;
		}
		for (PlexusConfiguration child : children) {
			if (to.getChild(child.getName(), false) == null) {
				to.addChild(child);
			}
		}
	}

	private BuildDependencies lookupConfiguredMojo(PlexusConfiguration config) throws Exception {
		merge(defaultConfiguration(), config);

		MavenProject project = new MavenProject();
		MavenSession session = mojoRule.newMavenSession(project);
		MojoExecution execution = mojoRule.newMojoExecution("build-dependencies");

		// need to set this up for plugin dependencies
		execution.getMojoDescriptor().getPluginDescriptor().setPlugin(new Plugin());

		Mojo mojo = mojoRule.lookupConfiguredMojo(session, execution);

		ExpressionEvaluator evaluator = new ResolverExpressionEvaluatorStub();
		configurator.configureComponent(mojo, config, evaluator, mojoRule.getContainer().getContainerRealm());

		return (BuildDependencies) mojo;
	}

	private <T> T mockComponent(final Class<T> type) throws Exception {
		T mockedComponent = mock(type);
		for (Entry<String, T> entry : mojoRule.getContainer().lookupMap(type).entrySet()) {
			mojoRule.getContainer().addComponent(mockedComponent, type, entry.getKey());
		}
		return mockedComponent;
	}

	@Before
	public void setUp() throws Exception {
		configurator = mojoRule.getContainer().lookup(ComponentConfigurator.class, "basic");

		collector = mockComponent(DependencyCollector.class);
		buildStrategy = mockComponent(BuildStrategy.class);
		installer = mockComponent(ArtifactInstaller.class);
		repositorySystem = mockComponent(RepositorySystem.class);
		projectBuilder = mockComponent(ProjectBuilder.class);

		when(repositorySystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.then(new Answer<Artifact>() {
					@Override
					public Artifact answer(InvocationOnMock invocation) throws Throwable {
						Object[] args = invocation.getArguments();
						Artifact result = new ArtifactStub();
						result.setGroupId((String) args[0]);
						result.setArtifactId((String) args[1]);
						result.setVersion((String) args[2]);
						return result;
					}
				});
		when(repositorySystem.resolve(any(ArtifactResolutionRequest.class)))
				.then(new Answer<ArtifactResolutionResult>() {
					@Override
					public ArtifactResolutionResult answer(InvocationOnMock invocation) throws Throwable {
						ArtifactResolutionRequest request = (ArtifactResolutionRequest) invocation.getArguments()[0];
						ArtifactResolutionResult result = new ArtifactResolutionResult();
						result.setArtifacts(Collections.singleton(request.getArtifact()));
						return result;
					}
				});

		ProjectBuildingResult projectBuilingResult = mock(ProjectBuildingResult.class);
		when(projectBuilingResult.getProject()).thenReturn(new MavenProject());
		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)))
				.thenReturn(projectBuilingResult);
	}

	private DependencyNode createNode(final DependencyNode parent, final String groupId, final String artifactId, final String version) {
		Artifact artifact = new DefaultArtifact(groupId, artifactId, version, Artifact.SCOPE_RUNTIME, "jar", "",
				new DefaultArtifactHandlerStub("jar"));

		DefaultDependencyNode graph = new DefaultDependencyNode(parent, artifact, null, null, null);
		graph.setChildren(new ArrayList<DependencyNode>());
		if (parent != null) {
			parent.getChildren().add(graph);
		}

		return graph;
	}

	@Test(expected = MojoFailureException.class)
	public void testStrategyBuildsNothing() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "children", "child1", "1");
		createNode(child1, "grandchildren", "child1child", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		createNode(child2, "grandchildren", "child2child", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(Collections.singleton(root.getArtifact()));

		lookupConfiguredMojo().execute();
	}

	@Test(expected = MojoFailureException.class)
	public void testStrategyBuildsSomeArtifacts() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "children", "child1", "1");
		createNode(child1, "grandchildren", "child1child", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		createNode(child2, "grandchildren", "child2child", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(Collections.singleton(root.getArtifact()));

		lookupConfiguredMojo().execute();
	}

	@Test
	public void testStrategyBuildsAllArtifacts() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "children", "child1", "1");
		createNode(child1, "grandchildren", "child1child", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		createNode(child2, "grandchildren", "child2child", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(root));

		lookupConfiguredMojo().execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(root)), any(BuildSession.class));
	}

	@Test
	public void testArtifactsDirectIgnored() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "children", "child1", "1");
		DependencyNode child1child = createNode(child1, "grandchildren", "child1child", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		createNode(child2, "grandchildren", "child2child", "1");

		DependencyNode toBuild = createNode(null, "root", "root", "1");
		DependencyNode toBuildChild = createNode(toBuild, "children", "child2", "1");
		createNode(toBuildChild, "grandchildren", "child2child", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(toBuild));

		PlexusConfiguration ignoreArtifactsIncludes = new DefaultPlexusConfiguration("includes");
		ignoreArtifactsIncludes.addChild("include", "*:child1");
		PlexusConfiguration ignoreArtifacts = new DefaultPlexusConfiguration("ignoreArtifacts");
		ignoreArtifacts.addChild(ignoreArtifactsIncludes);
		PlexusConfiguration config = new DefaultPlexusConfiguration(null);
		config.addChild(ignoreArtifacts);

		lookupConfiguredMojo(config).execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(toBuild)), any(BuildSession.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1child.getArtifact())), any(ArtifactRepository.class));
	}

	@Test
	public void testArtifactsDirectAndIndirectIgnored() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "children", "child1", "1");
		DependencyNode child1child = createNode(child1, "grandchildren", "child1child", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		DependencyNode child2child = createNode(child2, "grandchildren", "child1", "1");
		DependencyNode child2childchild = createNode(child2child, "greatgrandchildren", "child2", "1");

		DependencyNode toBuild = createNode(null, "root", "root", "1");
		createNode(toBuild, "children", "child2", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(toBuild));

		PlexusConfiguration ignoreArtifactsIncludes = new DefaultPlexusConfiguration("includes");
		ignoreArtifactsIncludes.addChild("include", "*:child1");
		PlexusConfiguration ignoreArtifacts = new DefaultPlexusConfiguration("ignoreArtifacts");
		ignoreArtifacts.addChild(ignoreArtifactsIncludes);
		PlexusConfiguration config = new DefaultPlexusConfiguration(null);
		config.addChild(ignoreArtifacts);

		lookupConfiguredMojo(config).execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(toBuild)), any(BuildSession.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1child.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child2child.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child2childchild.getArtifact())),
				any(ArtifactRepository.class));
	}

	@Test
	public void testMavenPluginsIgnoredByDefault() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "org.apache.maven.plugins", "maven-compiler-plugin", "1");
		DependencyNode child1child = createNode(child1, "org.apache.maven", "maven-plugin-api", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		createNode(child2, "grandchildren", "child2child", "1");

		DependencyNode toBuild = createNode(null, "root", "root", "1");
		DependencyNode toBuildChild = createNode(toBuild, "children", "child2", "1");
		createNode(toBuildChild, "grandchildren", "child2child", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(root));

		lookupConfiguredMojo().execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(toBuild)), any(BuildSession.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1child.getArtifact())), any(ArtifactRepository.class));
	}

	@Test
	public void testMavenPluginsIgnoredWithCustomIgnore() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");
		DependencyNode child1 = createNode(root, "org.apache.maven.plugins", "maven-compiler-plugin", "1");
		DependencyNode child1child = createNode(child1, "org.apache.maven", "maven-plugin-api", "1");
		DependencyNode child2 = createNode(root, "children", "child2", "1");
		DependencyNode child2child = createNode(child2, "grandchildren", "child2child", "1");

		DependencyNode toBuild = createNode(null, "root", "root", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(root));

		PlexusConfiguration ignoreArtifactsIncludes = new DefaultPlexusConfiguration("includes");
		ignoreArtifactsIncludes.addChild("include", "*:*child*");
		PlexusConfiguration ignoreArtifacts = new DefaultPlexusConfiguration("ignoreArtifacts");
		ignoreArtifacts.addChild(ignoreArtifactsIncludes);
		PlexusConfiguration config = new DefaultPlexusConfiguration(null);
		config.addChild(ignoreArtifacts);

		lookupConfiguredMojo(config).execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(toBuild)), any(BuildSession.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child1child.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child2.getArtifact())), any(ArtifactRepository.class));
		verify(installer).install(any(File.class), argThat(new ArtifactMatcher(child2child.getArtifact())), any(ArtifactRepository.class));
	}

	@Test
	public void testGivenArtifactBuilt() throws Exception {
		DependencyNode root = createNode(null, "some", "artifact", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(root));

		PlexusConfiguration config = new DefaultPlexusConfiguration(null);
		config.addChild("artifact", "some:artifact");

		lookupConfiguredMojo(config).execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(root)), any(BuildSession.class));
		verify(collector).resolveBuildDependencies(eq("some"), eq("artifact"), anyString(), any(ArtifactFilter.class),
				any(MavenSession.class));
	}

	@Test
	public void testGivenArtifactWithVersionBuilt() throws Exception {
		DependencyNode root = createNode(null, "some", "artifact", "4.0");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(root));

		PlexusConfiguration config = new DefaultPlexusConfiguration(null);
		config.addChild("artifact", "some:artifact:4.0");

		lookupConfiguredMojo(config).execute();

		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(root)), any(BuildSession.class));
		verify(collector).resolveBuildDependencies(eq("some"), eq("artifact"), eq("4.0"), any(ArtifactFilter.class),
				any(MavenSession.class));
	}

	@Test
	public void testArtifactToBuildIgnored() throws Exception {
		DependencyNode root = createNode(null, "root", "root", "1");

		when(collector.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class)))
				.thenReturn(root);
		when(buildStrategy.build(any(DependencyNode.class), any(BuildSession.class)))
				.thenReturn(toArtifactSet(root));

		PlexusConfiguration ignoreArtifactsIncludes = new DefaultPlexusConfiguration("includes");
		ignoreArtifactsIncludes.addChild("include", "some:artifact");
		PlexusConfiguration ignoreArtifacts = new DefaultPlexusConfiguration("ignoreArtifacts");
		ignoreArtifacts.addChild(ignoreArtifactsIncludes);
		PlexusConfiguration config = new DefaultPlexusConfiguration(null);
		config.addChild(ignoreArtifacts);
		config.addChild("artifact", "some:artifact");

		lookupConfiguredMojo(config).execute();

		// we should always build the given artifact even if its ignored
		verify(buildStrategy).build(argThat(new DependencyNodeArtifactMatcher(root)), any(BuildSession.class));
	}

	private Set<Artifact> toArtifactSet(final DependencyNode node) {
		final Set<Artifact> results = new HashSet<Artifact>();
		node.accept(new DependencyNodeVisitor() {
			@Override
			public boolean visit(DependencyNode node) {
				results.add(node.getArtifact());
				return true;
			}

			@Override
			public boolean endVisit(DependencyNode node) {
				return true;
			}
		});
		return results;
	}
}
