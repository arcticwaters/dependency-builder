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

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.StringUtils;
import org.debian.dependency.builders.ArtifactBuildException;
import org.debian.dependency.builders.SourceBuilderManager;
import org.debian.dependency.sources.Source;
import org.debian.dependency.sources.SourceRetrievalException;
import org.debian.dependency.sources.SourceRetrievalManager;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Test case for {@link BuildDependencies}. */
@RunWith(MockitoJUnitRunner.class)
public class TestBuildDependencies {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();
	@Rule
	public MojoRule mojoRule = new MojoRule();

	@InjectMocks
	private BuildDependencies configuredMojo = new BuildDependencies();
	@InjectMocks
	private BuildDependencies unconfiguredMojo = new BuildDependencies();

	@Mock
	private RepositorySystem repoSystem;
	@Mock(answer = Answers.RETURNS_MOCKS)
	private SourceRetrievalManager retrievalManager;
	@Mock
	private SourceBuilderManager builderManager;
	@Mock
	private DependencyCollection depCollection;

	@Before
	public void setUp() throws Exception {
		configureMojo(configuredMojo, defaultConfiguration());

		when(
				depCollection.resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class),
						any(MavenSession.class)))
				.then(new Answer<DependencyNode>() {
					@Override
					public DependencyNode answer(final InvocationOnMock invocation) throws Throwable {
						return createDependencyNode(null, (String) invocation.getArguments()[0], (String) invocation.getArguments()[1],
								(String) invocation.getArguments()[2]);
					}
				});
		when(
				depCollection.resolveProjectDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class),
						any(MavenSession.class)))
				.then(new Answer<DependencyNode>() {
					@Override
					public DependencyNode answer(final InvocationOnMock invocation) throws Throwable {
						return createDependencyNode(null, (String) invocation.getArguments()[0], (String) invocation.getArguments()[1],
								(String) invocation.getArguments()[2]);
					}
				});
		when(
				depCollection.installDependencies(anyListOf(DependencyNode.class), any(DependencyNodeFilter.class),
						any(ArtifactRepository.class),
						any(MavenSession.class))).then(returnsFirstArg());

		when(builderManager.build(any(Artifact.class), any(Source.class), any(File.class)))
				.then(new Answer<Set<Artifact>>() {
			@Override
					public Set<Artifact> answer(final InvocationOnMock invocation) throws Throwable {
						return Collections.singleton((Artifact) invocation.getArguments()[0]);
			}
		});

		when(repoSystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.then(new Answer<Artifact>() {
					@Override
					public Artifact answer(final InvocationOnMock invocation) throws Throwable {
						return mockArtifact((String) invocation.getArguments()[0], (String) invocation.getArguments()[1],
								(String) invocation.getArguments()[2]);
					}
				});
	}

	private void configureMojo(final Mojo mojo, final PlexusConfiguration config) throws Exception {
		ComponentConfigurator configurator = mojoRule.getContainer().lookup(ComponentConfigurator.class, "basic");

		MojoExecution execution = mojoRule.newMojoExecution("build-dependencies");
		merge(new XmlPlexusConfiguration(execution.getConfiguration()), config);

		MavenProject project = new MavenProject();
		// only required so tests resolve ${project.build.directory}
		project.getBuild().setDirectory(tempFolder.getRoot().getCanonicalPath());

		ExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(mojoRule.newMavenSession(project), execution);
		configurator.configureComponent(mojo, config, evaluator, mojoRule.getContainer().getContainerRealm());
	}

	private static Artifact mockArtifact(final String groupId, final String artifactId, final String version) {
		StringBuilder builder = new StringBuilder();
		if (groupId != null) {
			builder.append(groupId);
		}
		builder.append(':');
		if (artifactId != null) {
			builder.append(artifactId);
		}
		builder.append(':');
		if (version != null) {
			builder.append(version);
		}

		Artifact artifact = mock(Artifact.class, builder.toString());
		when(artifact.getGroupId())
				.thenReturn(groupId);
		when(artifact.getArtifactId())
				.thenReturn(artifactId);
		when(artifact.getVersion())
				.thenReturn(version);
		return artifact;
	}

	private static DependencyNode createDependencyNode(final DependencyNode parent, final String groupId, final String artifactId,
			final String version) {
		DefaultDependencyNode node = new DefaultDependencyNode(parent, mockArtifact(groupId, artifactId, version), null, null, null);
		node.setChildren(new ArrayList<DependencyNode>());
		if (parent != null) {
			parent.getChildren().add(node);
		}
		return node;
	}

	private static Matcher<Artifact> matchesArtifact(final String groupId, final String artifactId, final String version) {
		return new CustomTypeSafeMatcher<Artifact>("Artifact with specifier " + groupId + ":" + artifactId + ":" + version) {
			@Override
			protected boolean matchesSafely(final Artifact item) {
				if (!StringUtils.isEmpty(groupId) && !groupId.equals(item.getGroupId())) {
					return false;
				} else if (!StringUtils.isEmpty(artifactId) && !artifactId.equals(item.getArtifactId())) {
					return false;
				} else if (!StringUtils.isEmpty(version) && !version.equals(item.getVersion())) {
					return false;
				}
				return true;
			}
		};
	}

	private static PlexusConfiguration defaultConfiguration() {
		DefaultPlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact");
		config.addChild("multiProject", "true");
		return config;
	}

	private static void merge(final PlexusConfiguration from, final PlexusConfiguration to) {
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

	/** We should be able to configure just a single artifact. */
	@Test
	public void testSingleArtifact() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();

		verify(retrievalManager).checkoutSource(argThat(matchesArtifact("some", "artifact", null)), any(File.class), any(MavenSession.class));
		verify(builderManager).build(argThat(matchesArtifact("some", "artifact", null)), any(Source.class), any(File.class));
	}

	/** We should be able to configure multiple artifacts if necessary. */
	@Test
	@SuppressWarnings("unchecked")
	public void testArtifactSet() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.getChild("artifacts").addChild("artifact", "some:artifact1");
		config.getChild("artifacts").addChild("artifact", "some:artifact2");
		config.addChild("multiProject", "true");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();

		ArgumentCaptor<Artifact> retrievalArtifacts = ArgumentCaptor.forClass(Artifact.class);
		ArgumentCaptor<Artifact> buildArtifacts = ArgumentCaptor.forClass(Artifact.class);
		verify(retrievalManager, times(2)).checkoutSource(retrievalArtifacts.capture(), any(File.class), any(MavenSession.class));
		verify(builderManager, times(2)).build(buildArtifacts.capture(), any(Source.class), any(File.class));

		assertThat(buildArtifacts.getAllValues(), hasSize(2));
		assertThat(buildArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact1", null), matchesArtifact("some", "artifact2", null)));
		assertThat(retrievalArtifacts.getAllValues(), hasSize(2));
		assertThat(buildArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact1", null), matchesArtifact("some", "artifact2", null)));
	}

	/** If both a single artifact and the artifact set are specified, both are built. */
	@Test
	@SuppressWarnings("unchecked")
	public void testSingleArtifactAndAritfactSet() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact1");
		config.getChild("artifacts").addChild("artifact", "some:artifact2");
		config.addChild("multiProject", "true");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();

		ArgumentCaptor<Artifact> retrievalArtifacts = ArgumentCaptor.forClass(Artifact.class);
		ArgumentCaptor<Artifact> buildArtifacts = ArgumentCaptor.forClass(Artifact.class);
		verify(retrievalManager, times(2)).checkoutSource(retrievalArtifacts.capture(), any(File.class), any(MavenSession.class));
		verify(builderManager, times(2)).build(buildArtifacts.capture(), any(Source.class), any(File.class));

		assertThat(retrievalArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact1", null), matchesArtifact("some", "artifact2", null)));
		assertThat(buildArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact1", null), matchesArtifact("some", "artifact2", null)));
	}

	/** we must ensure at least 1 artifact is provided. */
	@Test(expected = MojoFailureException.class)
	public void testNoArtifacts() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();
	}

	/** We should be able to reference versions of other artifacts. Use case: implicit dependencies from surefire. */
	@Test
	@SuppressWarnings("unchecked")
	public void testVersionReferencingArtifacts() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.getChild("artifacts").addChild("artifact", "some:artifact:version");
		config.getChild("artifacts").addChild("artifact", "another:artifact:{some:artifact}");
		config.addChild("multiProject", "true");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();

		ArgumentCaptor<Artifact> retrievalArtifacts = ArgumentCaptor.forClass(Artifact.class);
		ArgumentCaptor<Artifact> buildArtifacts = ArgumentCaptor.forClass(Artifact.class);
		verify(retrievalManager, times(2)).checkoutSource(retrievalArtifacts.capture(), any(File.class), any(MavenSession.class));
		verify(builderManager, times(2)).build(buildArtifacts.capture(), any(Source.class), any(File.class));

		assertThat(retrievalArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact", "version"), matchesArtifact("another", "artifact", "version")));
		assertThat(buildArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact", "version"), matchesArtifact("another", "artifact", "version")));
	}

	/** When there are only version referencing artifacts, we should bail. */
	@Test(expected = MojoFailureException.class)
	public void testOnlyVersionReferencingArtifact() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact:{another:artifact}");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();
	}

	/** Maven plugins will generally be needed as build dependencies, unless explicitly required, they should be ignored. */
	@Test
	public void testMavenPluginIgnoredDefault() throws Exception {
		configuredMojo.execute();

		verify(depCollection).installDependencies(anyListOf(DependencyNode.class),
				argThat(new CustomTypeSafeMatcher<DependencyNodeFilter>("Maven plugins ignored") {
					@Override
					protected boolean matchesSafely(final DependencyNodeFilter item) {
						return item.accept(createDependencyNode(null, "org.apache.maven.plugins", "maven-compiler", "some-version"));
					}
				}), any(ArtifactRepository.class), any(MavenSession.class));
	}

	/**
	 * Surefire dynamically adds dependencies depending on which unit testing framework you have as a dependency. Since we ignore
	 * maven plugins by default, these implicit dependencies should be ignored as well.
	 */
	@Test
	public void testSurefireImplicitPluginsDefaultIgnored() throws Exception {
		configuredMojo.execute();

		verify(depCollection).installDependencies(anyListOf(DependencyNode.class),
				argThat(new CustomTypeSafeMatcher<DependencyNodeFilter>("Maven plugins ignored") {
					@Override
					protected boolean matchesSafely(final DependencyNodeFilter item) {
						return item.accept(createDependencyNode(null, "org.apache.maven.surefire", "surefire-junit4", "some-version"));
					}
				}), any(ArtifactRepository.class), any(MavenSession.class));
	}

	/**
	 * Surefire dynamically adds dependencies depending on which unit testing framework you have as a dependency. These artifacts
	 * should be dynamically added to the set of artifacts to be built.
	 */
	@Test
	public void testSurefireImplictPlugins() throws Exception {
		doAnswer(new Answer<DependencyNode>() {
			@Override
			public DependencyNode answer(final InvocationOnMock invocation) throws Throwable {
				DependencyNode root = createDependencyNode(null, (String) invocation.getArguments()[0], (String) invocation.getArguments()[1],
						(String) invocation.getArguments()[2]);
				createDependencyNode(root, "org.apache.maven.plugins", "maven-surefire-plugin", "some-version");
				return root;
			}
		}).when(depCollection).resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class), any(MavenSession.class));

		configuredMojo.execute();

		ArgumentCaptor<Artifact> captor = ArgumentCaptor.forClass(Artifact.class);
		verify(builderManager, atLeast(2)).build(captor.capture(), any(Source.class), any(File.class));

		assertThat(captor.getAllValues(), hasItem(matchesArtifact("org.apache.maven.surefire", "surefire-junit3", "some-version")));
		assertThat(captor.getAllValues(), hasItem(matchesArtifact("org.apache.maven.surefire", "surefire-junit4", "some-version")));
		assertThat(captor.getAllValues(), hasItem(matchesArtifact("org.apache.maven.surefire", "surefire-junit47", "some-version")));
		assertThat(captor.getAllValues(), hasItem(matchesArtifact("org.apache.maven.surefire", "surefire-testng", "some-version")));
	}

	/** We should be able to turn off ignores if we don't want them, especially default ignores. */
	@Test
	public void testIgnoreExclude() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "org.apache.maven.plugins:maven-compiler-plugin");
		config.getChild("ignores").getChild("includes").addChild("include", "org.apache.maven.plugins");
		config.getChild("ignores").getChild("excludes").addChild("exclude", "org.apache.maven.plugins");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();

		verify(retrievalManager).checkoutSource(argThat(matchesArtifact("org.apache.maven.plugins", "maven-compiler-plugin", null)), any(File.class),
				any(MavenSession.class));
		verify(builderManager).build(argThat(matchesArtifact("org.apache.maven.plugins", "maven-compiler-plugin", null)), any(Source.class),
				any(File.class));
	}

	/**
	 * We should be able to ignore some of a projects dependencies. This should include any dependency under the ignored one as
	 * well.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testIgnoredDependencies() throws Exception {
		doAnswer(new Answer<DependencyNode>() {
			@Override
			public DependencyNode answer(final InvocationOnMock invocation) throws Throwable {
				DependencyNode root = createDependencyNode(null, (String) invocation.getArguments()[0], (String) invocation.getArguments()[1],
						(String) invocation.getArguments()[2]);

				DependencyNode child1 = createDependencyNode(root, "ignored", "child", "version");
				createDependencyNode(child1, "ignored", "grandchild", "version");

				createDependencyNode(root, "another", "child", "version");
				return root;
			}
		}).when(depCollection).resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class),
				any(MavenSession.class));
		when(
				depCollection.installDependencies(anyListOf(DependencyNode.class), any(DependencyNodeFilter.class), any(ArtifactRepository.class),
						any(MavenSession.class)))
				.thenAnswer(new Answer<List<DependencyNode>>() {
					@Override
					public List<DependencyNode> answer(final InvocationOnMock invocation) throws Throwable {
						List<DependencyNode> result = new ArrayList<DependencyNode>();

						for (DependencyNode node : (List<DependencyNode>) invocation.getArguments()[0]) {
							BuildingDependencyNodeVisitor visitor = new BuildingDependencyNodeVisitor();
							FilteringDependencyNodeVisitor filter = new FilteringDependencyNodeVisitor(visitor, new DependencyNodeFilter() {
								@Override
								public boolean accept(final DependencyNode node) {
									return !"ignored".equals(node.getArtifact().getGroupId());
								}
							});
							node.accept(filter);
							result.add(visitor.getDependencyTree());
						}

						return result;
					}
				});

		configuredMojo.execute();

		ArgumentCaptor<Artifact> buildArtifacts = ArgumentCaptor.forClass(Artifact.class);
		verify(builderManager, times(2)).build(buildArtifacts.capture(), any(Source.class), any(File.class));

		assertThat(buildArtifacts.getAllValues(),
				containsInAnyOrder(matchesArtifact("some", "artifact", null), matchesArtifact("another", "child", null)));

		verify(builderManager, never()).build(argThat(matchesArtifact("group", "child", "version")), any(Source.class), any(File.class));
		verify(builderManager, never()).build(argThat(matchesArtifact("group", "grandchild", "version")), any(Source.class), any(File.class));
	}

	/** We should hiccup if a user inadvertently ignores all artifacts. */
	@Test(expected = MojoFailureException.class)
	public void testIgnoreAllArtifacts() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact");
		config.getChild("ignores").getChild("includes").addChild("include", "some:artifact");
		configureMojo(unconfiguredMojo, config);

		when(depCollection.installDependencies(anyListOf(DependencyNode.class), any(DependencyNodeFilter.class), any(ArtifactRepository.class),
				any(MavenSession.class)))
				.thenReturn(Collections.<DependencyNode> emptyList());

		unconfiguredMojo.execute();

		verify(depCollection).installDependencies(anyListOf(DependencyNode.class),
				argThat(new CustomTypeSafeMatcher<DependencyNodeFilter>("Ignore artifact") {
					@Override
					protected boolean matchesSafely(final DependencyNodeFilter item) {
						return item.accept(createDependencyNode(null, "some", "artifact", null));
					}
				}), any(ArtifactRepository.class),
				any(MavenSession.class));
	}

	/** The source that was checked out should be used for building. */
	@Test
	public void testCheckedoutSourceIsBuilt() throws Exception {
		File workDir = tempFolder.newFolder();
		File outputDir = tempFolder.newFolder();

		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact");
		config.addChild("workDirectory", workDir.getCanonicalPath());
		config.addChild("outputDirectory", outputDir.getCanonicalPath());
		configureMojo(unconfiguredMojo, config);

		Source source = mock(Source.class);
		when(
				retrievalManager.checkoutSource(argThat(matchesArtifact("some", "artifact", null)), eq(workDir.getAbsoluteFile()),
						any(MavenSession.class)))
				.thenReturn(source);
		when(builderManager.build(any(Artifact.class), any(Source.class), any(File.class)))
				.then(new Answer<Set<Artifact>>() {
					@Override
					public Set<Artifact> answer(final InvocationOnMock invocation) throws Throwable {
						return Collections.singleton((Artifact) invocation.getArguments()[0]);
					}
				});

		unconfiguredMojo.execute();

		verify(retrievalManager).checkoutSource(argThat(matchesArtifact("some", "artifact", null)), any(File.class), any(MavenSession.class));
		verify(builderManager).build(argThat(matchesArtifact("some", "artifact", null)), eq(source), eq(outputDir.getCanonicalFile()));
	}

	/** When multiproject is set, we should still be able to build a single artifact. */
	@Test
	public void testMultiprojectWithSingleArtifact() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("artifact", "some:artifact");
		config.addChild("multiProject", "true");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();

		verify(retrievalManager).checkoutSource(argThat(matchesArtifact("some", "artifact", null)), any(File.class), any(MavenSession.class));
		verify(builderManager).build(argThat(matchesArtifact("some", "artifact", null)), any(Source.class), any(File.class));
	}

	/** If there are multiple artifacts to be built, but without multiproject set, we should bail. */
	@Test(expected = MojoFailureException.class)
	public void testNotMultipleWithMultipleArtifacts() throws Exception {
		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.addChild("multiProject", "false");
		config.getChild("artifacts").addChild("artifact", "first:artifact");
		config.getChild("artifacts").addChild("artifact", "second:artifact");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();
	}

	/** Artifacts with dependencies are treated the same as multiple artifacts: if we don't want to build all projects, we bail. */
	@Test(expected = MojoFailureException.class)
	public void testNotMultipleWithArtifactDependencies() throws Exception {
		doAnswer(new Answer<DependencyNode>() {
			@Override
			public DependencyNode answer(final InvocationOnMock invocation) throws Throwable {
				DependencyNode root = createDependencyNode(null, (String) invocation.getArguments()[0], (String) invocation.getArguments()[1],
						(String) invocation.getArguments()[2]);
				createDependencyNode(root, "group", "child", "version");
				return root;
			}
		}).when(depCollection).resolveBuildDependencies(anyString(), anyString(), anyString(), any(ArtifactFilter.class),
				any(MavenSession.class));

		PlexusConfiguration config = new DefaultPlexusConfiguration("configuration");
		config.getChild("multiProject").setValue("false");
		config.getChild("artifact").setValue("some:artifact");
		configureMojo(unconfiguredMojo, config);

		unconfiguredMojo.execute();
	}

	/** We should bubble up errors if we cannot create an output maven repository. */
	@SuppressWarnings("unchecked")
	@Test(expected = MojoExecutionException.class)
	public void testCannotCreateLocalRepository() throws Exception {
		when(repoSystem.createLocalRepository(any(File.class)))
				.thenThrow(InvalidRepositoryException.class);

		configuredMojo.execute();
	}

	/** If we cannot resolve an artifact when installing ignored artifacts, errors should bubble up. */
	@Test(expected = MojoExecutionException.class)
	public void testInstallIgnoreResolutionErrors() throws Exception {
		when(
				depCollection.installDependencies(anyListOf(DependencyNode.class), any(DependencyNodeFilter.class),
						any(ArtifactRepository.class), any(MavenSession.class)))
				.thenThrow(new DependencyResolutionException());

		configuredMojo.execute();
	}

	/** Errors when installing ignored artifacts should bubble up. */
	@SuppressWarnings("unchecked")
	@Test(expected = MojoExecutionException.class)
	public void testInstallIgnoreInstallErrors() throws Exception {
		when(
				depCollection.installDependencies(anyListOf(DependencyNode.class), any(DependencyNodeFilter.class),
						any(ArtifactRepository.class), any(MavenSession.class)))
				.thenThrow(ArtifactInstallationException.class);

		configuredMojo.execute();
	}

	/** Errors from checking out source should bubble up. */
	@Test(expected = MojoExecutionException.class)
	public void testCheckoutSourceError() throws Exception {
		when(retrievalManager.checkoutSource(any(Artifact.class), any(File.class), any(MavenSession.class)))
				.thenThrow(new SourceRetrievalException());

		configuredMojo.execute();
	}

	/** Errors from building source should bubble up. */
	@Test(expected = MojoExecutionException.class)
	public void testBuildErrors() throws Exception {
		when(builderManager.build(any(Artifact.class), any(Source.class), any(File.class)))
				.thenThrow(new ArtifactBuildException());

		configuredMojo.execute();
	}
}
