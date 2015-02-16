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
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Collections;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Scm;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
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
import org.codehaus.plexus.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Test case for {@link SCMSourceRetrieval}. */
@RunWith(MockitoJUnitRunner.class)
public class TestSCMSourceRetrieval {
	private static final String DEV_CONNECTION = "developer-connection";
	private static final String CONNECTION = "connection";

	@InjectMocks
	private SCMSourceRetrieval sourceRetrieval = new SCMSourceRetrieval();
	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private ScmManager scmManager;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private ProjectBuilder projectBuilder;
	@Mock(answer = Answers.RETURNS_MOCKS)
	private RepositorySystem repoSystem;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private SettingsDecrypter settingsDecrypter;
	@Mock
	private Logger logger;

	private File directory = new File("");
	private Artifact artifact = mock(Artifact.class, Answers.RETURNS_SMART_NULLS.get());
	private MavenProject resolvedProject = new MavenProject();
	@Mock(answer = Answers.RETURNS_MOCKS)
	private MavenSession session;

	@Before
	public void setUp() throws Exception {
		resolvedProject.setOriginalModel(resolvedProject.getModel());
		resolvedProject.setScm(new Scm());
		resolvedProject.getScm().setConnection(CONNECTION);
		resolvedProject.getScm().setDeveloperConnection(DEV_CONNECTION);
		resolvedProject.setArtifact(mock(Artifact.class, Answers.RETURNS_SMART_NULLS.get()));

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

		when(projectBuilder.build(any(Artifact.class), any(ProjectBuildingRequest.class)).getProject())
				.thenReturn(resolvedProject);

		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)).isSuccess())
				.thenReturn(true);
	}

	/** Settings decryption has some errors. */
	@Test(expected = SourceRetrievalException.class)
	public void testSettingsDecryptionFails() throws Exception {
		SettingsProblem problem = mock(SettingsProblem.class);
		Server server = new Server();

		when(settingsDecrypter.decrypt(any(SettingsDecryptionRequest.class)).getProblems())
				.thenReturn(Collections.singletonList(problem));
		// even failed servers are returned, but passwords may still be ciphered
		when(settingsDecrypter.decrypt(any(SettingsDecryptionRequest.class)).getServers())
				.thenReturn(Collections.singletonList(server));
		// in general, a failed decryption will provide a wrong password to scm
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)).isSuccess())
				.thenReturn(false);

		sourceRetrieval.retrieveSource(artifact, directory, session);
	}

	/** No decryption needed. */
	@Test
	public void testSettingsDecryptionWorks() throws Exception {
		Server server = new Server();
		when(settingsDecrypter.decrypt(any(SettingsDecryptionRequest.class)).getServers())
				.thenReturn(Collections.singletonList(server));

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, not(isEmptyOrNullString()));
	}

	/** No scm information is a failure. */
	@Test
	public void testNoScmInfo() throws Exception {
		resolvedProject.setScm(null);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat("Nothing can be retrieved with no scm info", result, isEmptyOrNullString());
	}

	/** No scm information in a parent (and none in the current) is a failure. */
	@Test
	public void testNoParentScmInfo() throws Exception {
		MavenProject parent = new MavenProject();
		parent.setScm(null);
		resolvedProject.setParent(parent);
		resolvedProject.setScm(null);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat("Nothing can be retrieved with no scm info", result, isEmptyOrNullString());
	}

	/** Scm contains developer information only. */
	@Test
	public void testScmDevInfoOnly() throws Exception {
		Scm scm = new Scm();
		scm.setDeveloperConnection(DEV_CONNECTION);
		resolvedProject.setScm(scm);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertEquals(DEV_CONNECTION, result);
	}

	/** Scm contains no developer information. */
	@Test
	public void testScmConnInfoOnly() throws Exception {
		Scm scm = new Scm();
		scm.setConnection(CONNECTION);
		resolvedProject.setScm(scm);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertEquals(CONNECTION, result);
	}

	/** Scm information contains both developer and regular connection -- developer preferred. */
	@Test
	public void testBothDevAndConn() throws Exception {
		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertEquals(DEV_CONNECTION, result);

		verify(scmManager).makeScmRepository(DEV_CONNECTION);
		verify(scmManager, never()).makeScmRepository(CONNECTION);
	}

	/** Checkout from developer information fails, should try connection next. */
	@Test
	public void testDevConnFails() throws Exception {
		ScmRepository repository = mock(ScmRepository.class);
		when(scmManager.makeScmRepository(DEV_CONNECTION))
				.thenReturn(repository);

		CheckOutScmResult checkoutResult = mock(CheckOutScmResult.class);
		when(scmManager.checkOut(eq(repository), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenReturn(checkoutResult);
		when(checkoutResult.isSuccess())
				.thenReturn(false);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertEquals(CONNECTION, result);
	}

	/** All scm information fails. */
	@Test(expected = SourceRetrievalException.class)
	public void testBothDevAndConnFails() throws Exception {
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)).isSuccess())
				.thenReturn(false);

		sourceRetrieval.retrieveSource(artifact, directory, session);
	}

	/** Scm developer throws exception, should try connection next. */
	@Test
	public void testDevConnThrowsException() throws Exception {
		ScmRepository repository = mock(ScmRepository.class);
		when(scmManager.makeScmRepository(DEV_CONNECTION))
				.thenReturn(repository);
		when(scmManager.checkOut(eq(repository), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenThrow(new ScmException("checkout failure"));

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertEquals(CONNECTION, result);
	}

	/** Both scm methods throw exceptions. */
	@Test(expected = SourceRetrievalException.class)
	public void testDevAndConnThrowsException() throws Exception {
		when(scmManager.checkOut(any(ScmRepository.class), any(ScmFileSet.class), any(ScmVersion.class)))
				.thenThrow(new ScmException("checkout failure"));

		sourceRetrieval.retrieveSource(artifact, directory, session);
	}

	/** Scm contains a <tag/>, we should try using it. */
	@Test
	public void testScmTagCheckedOut() throws Exception {
		final String tag = "someTag";
		resolvedProject.getScm().setTag(tag);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, not(isEmptyOrNullString()));

		ArgumentCaptor<ScmVersion> versionCaptor = ArgumentCaptor.forClass(ScmVersion.class);

		// times 2 is because we deep stubbed the mock and called it during setUp
		verify(scmManager, times(2)).checkOut(any(ScmRepository.class), any(ScmFileSet.class), versionCaptor.capture());
		assertEquals(tag, versionCaptor.getValue().getName());
	}

	/** Scm does not contain a <tag/>, we should use the default. */
	@Test
	public void testScmCheckoutAnyVersion() throws Exception {
		resolvedProject.getScm().setTag(null);

		String result = sourceRetrieval.retrieveSource(artifact, directory, session);
		assertThat(result, not(isEmptyOrNullString()));

		// times 2 is because we deep stubbed the mock and called it during setUp
		verify(scmManager, times(2)).checkOut(any(ScmRepository.class), any(ScmFileSet.class), isNull(ScmVersion.class));
	}

	/** Get source location of artifact which has no scm information. */
	@Test
	public void testGetLocationNoScmInfo() throws Exception {
		resolvedProject.setScm(null);

		String result = sourceRetrieval.getSourceLocation(artifact, session);
		assertThat("Nothing can be retrieved with no scm info", result, isEmptyOrNullString());
	}

	/** Get source location of artifact which has no scm information in a parent (and none in current). */
	@Test
	public void testGetLocationNoParentScmInfo() throws Exception {
		MavenProject parent = new MavenProject();
		parent.setScm(null);
		resolvedProject.setParent(parent);
		resolvedProject.setScm(null);

		String result = sourceRetrieval.getSourceLocation(artifact, session);
		assertThat("Nothing can be retrieved with no scm info", result, isEmptyOrNullString());
	}

	/** Get source location of artifact which has scm information. */
	@Test
	public void testGetLocationWithScmInfo() throws Exception {
		String result = sourceRetrieval.getSourceLocation(artifact, session);
		assertThat(result, not(isEmptyOrNullString()));
	}

	/** Get source directory name of artifact which has no scm information. */
	@Test
	public void testGetDirnameNoScmInfo() throws Exception {
		resolvedProject.setScm(null);

		String result = sourceRetrieval.getSourceDirname(artifact, session);
		assertThat("Should always be able to get source dirname", result, not(isEmptyOrNullString()));
	}

	/** Get source directory name of artifact which has no scm information in a parent (and none in current). */
	@Test
	public void testGetDirnameNoParentScmInfo() throws Exception {
		MavenProject parent = new MavenProject();
		parent.setScm(null);
		resolvedProject.setParent(parent);
		resolvedProject.setScm(null);

		String result = sourceRetrieval.getSourceDirname(artifact, session);
		assertThat("Should always be able to get source dirname", result, not(isEmptyOrNullString()));
	}

	/** Get source directory name of artifact which has scm information. */
	@Test
	public void testGetDirnameWithScmInfo() throws Exception {
		String result = sourceRetrieval.getSourceDirname(artifact, session);
		assertThat("Should always be able to get source dirname", result, not(isEmptyOrNullString()));
	}
}
