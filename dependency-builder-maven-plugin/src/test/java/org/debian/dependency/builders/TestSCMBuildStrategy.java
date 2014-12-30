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

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.plugin.testing.stubs.DefaultArtifactHandlerStub;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.util.FileUtils;
import org.debian.dependency.matchers.ArtifactMatcher;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.DepthWalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Tests for {@link SCMBuildStrategy}. */
@RunWith(MockitoJUnitRunner.class)
public class TestSCMBuildStrategy {
	private static final String CONNECTION = "connection";
	private static final String DEVELOPER_CONNECTION = "developerConnection";

	@Rule
	public MojoRule mojoRule = new MojoRule();

	@Mock
	private ScmManager scmManager;
	@Mock
	private SourceBuilderManager sourceBuilderManager;
	@Mock
	private SettingsDecryptionResult decryptionResult;
	@Mock
	private SourceBuilder sourceBuilder;

	private SCMBuildStrategy scmBuildStrategy;
	private BuildSession buildSession;
	private DependencyNode node;
	private MavenProject firstResolvedProject;

	private File findWorkDirectory() throws URISyntaxException, IOException {
		URL url = getClass().getResource("/work/marker.txt");
		return new File(url.toURI()).getParentFile();
	}

	@Before
	public void setUp() throws Exception {
		mojoRule.getContainer().addComponent(sourceBuilderManager, SourceBuilderManager.class, PlexusConstants.PLEXUS_DEFAULT_HINT);
		mojoRule.getContainer().addComponent(scmManager, ScmManager.class, PlexusConstants.PLEXUS_DEFAULT_HINT);

		when(sourceBuilderManager.detect(any(File.class)))
				.thenReturn(sourceBuilder);

		SettingsDecrypter settingsDecrypter = mock(SettingsDecrypter.class);
		mojoRule.getContainer().addComponent(settingsDecrypter, SettingsDecrypter.class, PlexusConstants.PLEXUS_DEFAULT_HINT);
		when(settingsDecrypter.decrypt(any(SettingsDecryptionRequest.class)))
				.thenReturn(decryptionResult);

		buildSession = new BuildSession(mojoRule.newMavenSession(new MavenProject()));
		File workDir = new File(findWorkDirectory(), "test");
		workDir.mkdirs();
		FileUtils.cleanDirectory(workDir);
		buildSession.setCheckoutDirectory(new File(workDir, "checkout"));
		buildSession.setWorkDirectory(new File(workDir, "work"));

		Artifact artifact1 = new DefaultArtifact("group", "artifact1", "1", Artifact.SCOPE_RUNTIME, "jar", "",
				new DefaultArtifactHandlerStub("jar"));
		Artifact artifact2 = new DefaultArtifact("group", "artifact2", "1", Artifact.SCOPE_RUNTIME, "jar", "",
				new DefaultArtifactHandlerStub("jar"));

		DefaultDependencyNode node1 = new DefaultDependencyNode(null, artifact1, null, null, null);
		DefaultDependencyNode node2 = new DefaultDependencyNode(node1, artifact2, null, null, null);
		node1.setChildren(Arrays.<DependencyNode> asList(node2));
		node2.setChildren(Collections.<DependencyNode> emptyList());
		this.node = node1;

		firstResolvedProject = createMavenProject(node1.getArtifact());
		firstResolvedProject.setOriginalModel(firstResolvedProject.getModel());
		firstResolvedProject.setScm(new Scm());
		firstResolvedProject.getScm().setConnection(CONNECTION);
		firstResolvedProject.getScm().setDeveloperConnection(DEVELOPER_CONNECTION);

		mockRepositorySystem();
		mockProjectBuilder();
		setupScmManagerMock();

		scmBuildStrategy = (SCMBuildStrategy) mojoRule.getContainer().lookup(BuildStrategy.class, "scm");
	}

	private void setupScmManagerMock() throws Exception {
		ScmRepository scmRepo = mock(ScmRepository.class);
		when(scmManager.makeScmRepository(anyString()))
				.thenReturn(scmRepo);
		CheckOutScmResult checkoutResult = mock(CheckOutScmResult.class);
		when(checkoutResult.isSuccess())
				.thenReturn(true);
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenReturn(checkoutResult);
	}

	private static MavenProject createMavenProject(final Artifact artifact) {
		MavenProject project = new MavenProject();
		project.setOriginalModel(project.getModel());
		project.setGroupId(artifact.getGroupId());
		project.setArtifactId(artifact.getArtifactId());
		project.setVersion(artifact.getVersion());
		project.setArtifact(artifact);
		return project;
	}

	private ProjectBuilder mockProjectBuilder() throws Exception {
		ProjectBuilder projectBuilder = mock(ProjectBuilder.class);
		mojoRule.getContainer().addComponent(projectBuilder, ProjectBuilder.class, PlexusConstants.PLEXUS_DEFAULT_HINT);

		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)))
				.then(new Answer<ProjectBuildingResult>() {
					private boolean firstProjectSet;

					@Override
					public ProjectBuildingResult answer(final InvocationOnMock invocation) throws Throwable {
						Artifact artifact = (Artifact) invocation.getArguments()[0];

						MavenProject project;
						if (firstProjectSet) {
							project = createMavenProject(artifact);
						} else {
							project = firstResolvedProject;
							firstProjectSet = true;
						}

						ProjectBuildingResult result = mock(ProjectBuildingResult.class);
						when(result.getProject())
								.thenReturn(project);
						return result;
					}
				});

		return projectBuilder;
	}

	private RepositorySystem mockRepositorySystem() throws Exception {
		RepositorySystem repoSystem = mock(RepositorySystem.class);
		mojoRule.getContainer().addComponent(repoSystem, RepositorySystem.class, PlexusConstants.PLEXUS_DEFAULT_HINT);

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

		when(repoSystem.createProjectArtifact(anyString(), anyString(), anyString()))
				.then(new Answer<Artifact>() {
					@Override
					public Artifact answer(final InvocationOnMock invocation) throws Throwable {
						String groupId = (String) invocation.getArguments()[0];
						String artifactId = (String) invocation.getArguments()[1];
						String version = (String) invocation.getArguments()[2];
						return new DefaultArtifact(groupId, artifactId, version, Artifact.SCOPE_RUNTIME, "pom", "",
								new DefaultArtifactHandlerStub("pom"));
					}
				});

		return repoSystem;
	}

	/** Settings decryption has some errors. */
	@Test
	public void testSettingsDecryptionFails() throws Exception {
		SettingsProblem problem1 = mock(SettingsProblem.class);
		Server server1 = new Server();

		when(decryptionResult.getProblems())
				.thenReturn(Collections.singletonList(problem1));
		// even failed servers are returned, but passwords may still be ciphered
		when(decryptionResult.getServers())
				.thenReturn(Collections.singletonList(server1));

		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		// in general, a failed decryption will provide a wrong password to scm, we ignore that case
		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** No decryption needed. */
	@Test
	public void testSettingsDecryptionWorks() throws Exception {
		Server server1 = new Server();
		when(decryptionResult.getServers())
				.thenReturn(Collections.singletonList(server1));

		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** No scm information. */
	@Test
	public void testNoScmInfo() throws Exception {
		firstResolvedProject.setScm(null);

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat("No artifacts should be build with no scm information", results, empty());
	}

	/** Scm contains developer information only. */
	@Test
	public void testScmDevInfoOnly() throws Exception {
		Scm scm = new Scm();
		scm.setDeveloperConnection(DEVELOPER_CONNECTION);
		firstResolvedProject.setScm(scm);

		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** Scm contains no developer information. */
	@Test
	public void testScmConnInfoOnly() throws Exception {
		Scm scm = new Scm();
		scm.setConnection(CONNECTION);
		firstResolvedProject.setScm(scm);

		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** Scm information contains both developer and regular connection -- developer preferred. */
	@Test
	public void testBothDevAndConn() throws Exception {
		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));

		verify(scmManager).makeScmRepository(DEVELOPER_CONNECTION);
		verify(scmManager, never()).makeScmRepository(CONNECTION);
	}

	/** Checkout from developer information fails, should try connection next. */
	@Test
	public void testDevConnFails() throws Exception {
		CheckOutScmResult failedCheckoutResult = mock(CheckOutScmResult.class);
		when(failedCheckoutResult.isSuccess())
				.thenReturn(false);

		ScmRepository repository = mock(ScmRepository.class);
		when(scmManager.makeScmRepository(DEVELOPER_CONNECTION))
				.thenReturn(repository);
		when(scmManager.checkOut(eq(repository), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenReturn(failedCheckoutResult);
		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** All scm information fails. */
	@Test(expected = ArtifactBuildException.class)
	public void testBothDevAndConnFails() throws Exception {
		CheckOutScmResult failedCheckoutResult = mock(CheckOutScmResult.class);
		when(failedCheckoutResult.isSuccess())
				.thenReturn(false);
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenReturn(failedCheckoutResult);

		scmBuildStrategy.build(node, buildSession);
	}

	/** Scm developer throws exception, should try connection next. */
	@Test
	public void testDevConnThrowsException() throws Exception {
		ScmRepository repository = mock(ScmRepository.class);
		when(scmManager.makeScmRepository(DEVELOPER_CONNECTION))
				.thenReturn(repository);
		when(scmManager.checkOut(eq(repository), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenThrow(new ScmException("checkout failure"));
		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** Both scm methods throw exceptions. */
	@Test(expected = ArtifactBuildException.class)
	public void testDevAndConnThrowsException() throws Exception {
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenThrow(new ScmException("checkout failure"));

		scmBuildStrategy.build(node, buildSession);
	}

	/** Scm contains a <tag/>. */
	@Test
	public void testScmTagCheckedOut() throws Exception {
		final String tag = "someTag";
		firstResolvedProject.getScm().setTag(tag);

		scmBuildStrategy.build(node, buildSession);

		verify(scmManager).checkOut(any(ScmRepository.class), any(ScmFileSet.class),
				argThat(new TypeSafeDiagnosingMatcher<ScmVersion>() {
					@Override
					public void describeTo(final Description description) {
						description.appendText("ScmTag with tag " + tag);
					}

					@Override
					protected boolean matchesSafely(final ScmVersion item, final Description mismatchDescription) {
						if (!tag.equals(item.getName())) {
							mismatchDescription.appendText("tag does not match");
							return false;
						}
						return true;
					}
				}));
	}

	/** Scm does not contain a <tag/>. */
	@Test
	public void testScmCheckoutAnyVersion() throws Exception {
		firstResolvedProject.getScm().setTag(null);

		scmBuildStrategy.build(node, buildSession);

		verify(scmManager).checkOut(any(ScmRepository.class), any(ScmFileSet.class), isNull(ScmVersion.class));
	}

	/** Git copy of the repository is created and committed under the proper branch. */
	@Test
	public void testGitCopyCreated() throws Exception {
		assertThat("Dirty  test--existing files in work directory", buildSession.getWorkDirectory().listFiles(),
				anyOf(emptyArray(), nullValue()));
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)))
				.then(new Answer<CheckOutScmResult>() {
					@Override
					public CheckOutScmResult answer(final InvocationOnMock invocation) throws Throwable {
						ScmFileSet fileSet = (ScmFileSet) invocation.getArguments()[1];
						new File(fileSet.getBasedir(), "file1").createNewFile();
						new File(fileSet.getBasedir(), "file2").createNewFile();

						CheckOutScmResult checkoutResult = mock(CheckOutScmResult.class);
						when(checkoutResult.isSuccess())
								.thenReturn(true);
						return checkoutResult;
					}
				});

		scmBuildStrategy.build(node, buildSession);

		int treeItems = 0;
		for (File file : buildSession.getWorkDirectory().listFiles()) {
			Git git = Git.open(file);
			for (Ref branch : git.branchList().call()) {
				if (branch.getName().endsWith("dependency-builder-maven-plugin")) {
					TreeWalk walk = new TreeWalk(git.getRepository());
					walk.addTree(new RevWalk(git.getRepository(), 1).parseCommit(branch.getObjectId()).getTree());
					walk.setFilter(PathFilterGroup.createFromStrings("file1", "file2"));
					try {
						while (walk.next()) {
							++treeItems;
						}
					} finally {
						walk.release();
					}
				}
			}
		}

		assertEquals("Must find git repository with dependency branch", 2, treeItems);
	}

	/** Local copies should be made via file copy rather than checking out again. */
	@Test
	public void testSourceCheckoutOnce() throws Exception {
		scmBuildStrategy.build(node, buildSession);

		verify(scmManager).checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class));
	}

	/** Source builder can't detect source. */
	@Test
	public void testBuilderCannotDetectSource() throws Exception {
		when(sourceBuilderManager.detect(any(File.class)))
				.thenReturn(null);

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat("No artifacts can be built if we can't detect the source version", results, empty());
	}

	/** Source builder detects source incorrectly or fails outright. */
	@Test(expected = ArtifactBuildException.class)
	public void testBuilderDetectIncorrectSource() throws Exception {
		when(sourceBuilder.build(any(MavenProject.class), any(Git.class), any(File.class)))
				.thenThrow(new ArtifactBuildException("builder fails"));

		scmBuildStrategy.build(node, buildSession);
	}

	/** Source builder cannot build all artifacts (which means there are child artifacts). */
	@Test
	public void testSomeArtifactsNotBuilt() throws Exception {
		MavenProject rootProject = createMavenProject(node.getArtifact());
		MavenProject childProject = createMavenProject(node.getChildren().get(0).getArtifact());

		when(sourceBuilder.canBuild(eq(childProject), any(File.class)))
				.thenReturn(false);
		when(sourceBuilder.build(eq(rootProject), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact())));
	}

	/** Source builder can build root and child artifacts. */
	@Test
	@SuppressWarnings("unchecked")
	public void testBuildOnlyRootProject() throws Exception {
		MavenProject rootProject = createMavenProject(node.getArtifact());
		Artifact childArtifact = node.getChildren().get(0).getArtifact();
		MavenProject childProject = createMavenProject(childArtifact);

		when(sourceBuilder.canBuild(eq(childProject), any(File.class)))
				.thenReturn(true);
		when(sourceBuilder.build(eq(rootProject), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(node.getArtifact()));
		when(sourceBuilder.build(eq(childProject), any(Git.class), any(File.class)))
				.thenReturn(Collections.singleton(childArtifact));

		Set<Artifact> results = scmBuildStrategy.build(node, buildSession);
		assertThat(results, contains(new ArtifactMatcher(node.getArtifact()), new ArtifactMatcher(childArtifact)));
	}
}
