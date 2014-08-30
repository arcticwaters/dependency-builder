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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.Collections;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.codehaus.plexus.util.StringUtils;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.FromDataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

/** Tests pattern matching of {@link StrictPatternArtifactFilter}. */
@RunWith(Theories.class)
public class TestStrictPatternArtifactFilterPatterns {
	private final StrictPatternArtifactFilter matcher = new StrictPatternArtifactFilter();
	private final Artifact artifact = new DefaultArtifact("com.example", "some-artifact-plugin", "1.0.0", "runtime", "maven-plugin", "",
			null);

	@DataPoints({ "groupId", "validGroupId" })
	public static final String[] VALID_GROUP_IDS = { null, "", "com.*", "com.example", "com.example*" };
	@DataPoints({ "groupId", "invalidGroupId" })
	public static final String[] INVALID_GROUP_IDS = { "com", "com.example.*", "com.example.foo" };
	@DataPoints({ "artifactId", "validArtifactId" })
	public static final String[] VALID_ARTIFACT_IDS = { null, "", "some-artifact-plugin", "*artifact-plugin", "some-artifact-*",
			"*-artifact-*", "some-artifact-plugin*" };
	@DataPoints({ "artifactId", "invalidArtifactId" })
	public static final String[] INVALID_ARTIFACT_IDS = { "another-plugin", "some-artifact-plugin1" };
	@DataPoints({ "version", "validVersion" })
	public static final String[] VALID_VERSIONS = { null, "", "1.0.0", "1.*", "1*", "1.0.0*", "[1,2]", "[1.0,2]" };
	@DataPoints({ "version", "invalidVersion" })
	public static final String[] INVALID_VERSIONS = { "2", "2.0", "[2,3]", "(1,2]" };
	@DataPoints({ "type", "validType" })
	public static final String[] VALID_TYPES = { null, "", "maven-plugin", "*-plugin" };
	@DataPoints({ "type", "invalidType" })
	public static final String[] INVALID_TYPES = { "jar" };

	private static void addIfAnyNonBlank(final StringBuilder builder, final String value, final String... tests) {
		if (tests == null) {
			return;
		}

		boolean blank = true;
		for (String test : tests) {
			blank &= StringUtils.isBlank(test);
		}

		if (!blank) {
			builder.append(value);
		}
	}

	private String createArtifact(final String groupId, final String artifactId, final String version, final String type) {
		if (groupId == null && artifactId == null && version == null && type == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		addIfAnyNonBlank(builder, groupId, groupId);
		addIfAnyNonBlank(builder, ":", artifactId, type, version);
		addIfAnyNonBlank(builder, artifactId, artifactId);
		addIfAnyNonBlank(builder, ":", type, version);
		addIfAnyNonBlank(builder, type, type);
		addIfAnyNonBlank(builder, ":", version);
		addIfAnyNonBlank(builder, version, version);
		return builder.toString();
	}

	/** Artifacts should be able to be whitelisted by specifying them explicitly. */
	@Theory
	public void testWhitelistValid(
			@FromDataPoints("validGroupId") final String groupId,
			@FromDataPoints("validArtifactId") final String artifactId,
			@FromDataPoints("validVersion") final String version,
			@FromDataPoints("validType") final String type
			) {
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(pattern));
		assertTrue(matcher.include(artifact));

		matcher.setExcludes(Collections.singletonList(pattern));
		assertFalse("blacklist overrides whitelist", matcher.include(artifact));
	}

	/** Whitelist patterns which don't specify anything valid should not be matched. */
	@Theory
	public void testWhitelistInvalid(
			@FromDataPoints("invalidGroupId") final String groupId,
			@FromDataPoints("invalidArtifactId") final String artifactId,
			@FromDataPoints("invalidVersion") final String version,
			@FromDataPoints("invalidType") final String type
			) {
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(pattern));
		assertFalse(matcher.include(artifact));
	}

	/** Whitelist patterns which don't match the groupId should fail regardless of other values. */
	@Theory
	public void testWhitelistInvalidGroupId(
			@FromDataPoints("invalidGroupId") final String groupId,
			@FromDataPoints("artifactId") final String artifactId,
			@FromDataPoints("version") final String version,
			@FromDataPoints("type") final String type
			) {
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(pattern));
		assertFalse(matcher.include(artifact));
	}

	/** Whitelist patterns which don't match the artifactId should fail regardless of other values. */
	@Theory
	public void testWhitelistInvalidArtifactId(
			@FromDataPoints("groupId") final String groupId,
			@FromDataPoints("invalidArtifactId") final String artifactId,
			@FromDataPoints("version") final String version,
			@FromDataPoints("type") final String type
			) {
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(pattern));
		assertFalse(matcher.include(artifact));
	}

	/** Whitelist patterns which don't match the version should fail regardless of other values. */
	@Theory
	public void testWhitelistInvalidVersion(
			@FromDataPoints("groupId") final String groupId,
			@FromDataPoints("artifactId") final String artifactId,
			@FromDataPoints("invalidVersion") final String version,
			@FromDataPoints("type") final String type
			) {
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(pattern));
		assertFalse(matcher.include(artifact));
	}

	/** Whitelist patterns which don't match the type should fail regardless of other values. */
	@Theory
	public void testWhitelistInvalidType(
			@FromDataPoints("groupId") final String groupId,
			@FromDataPoints("artifactId") final String artifactId,
			@FromDataPoints("version") final String version,
			@FromDataPoints("invalidType") final String type
			) {
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(pattern));
		assertFalse(matcher.include(artifact));
	}

	/** Artifacts should be able to be blacklsited by specifying them explicitly. */
	@Theory
	public void testBlacklistValid(
			@FromDataPoints("validGroupId") final String groupId,
			@FromDataPoints("validArtifactId") final String artifactId,
			@FromDataPoints("validVersion") final String version,
			@FromDataPoints("validType") final String type
			) {
		assumeTrue(matcher.include(artifact));
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setExcludes(Collections.singletonList(pattern));
		assertFalse(matcher.include(artifact));

		matcher.setIncludes(Collections.singletonList(pattern));
		assertFalse("blacklist overrides whitelist", matcher.include(artifact));
	}

	/** Blacklist patterns which don't specify anything valid should not be matched. */
	@Theory
	public void testBlacklistInvalid(
			@FromDataPoints("invalidGroupId") final String groupId,
			@FromDataPoints("invalidArtifactId") final String artifactId,
			@FromDataPoints("invalidVersion") final String version,
			@FromDataPoints("invalidType") final String type
			) {
		assumeTrue(matcher.include(artifact));
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setExcludes(Collections.singletonList(pattern));
		assertTrue(matcher.include(artifact));
	}

	/** Blacklist patterns which don't match the artifactId should fail regardless of other values. */
	@Theory
	public void testBlacklistInvalidArtifactId(
			@FromDataPoints("groupId") final String groupId,
			@FromDataPoints("invalidArtifactId") final String artifactId,
			@FromDataPoints("version") final String version,
			@FromDataPoints("type") final String type
			) {
		assumeTrue(matcher.include(artifact));
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setExcludes(Collections.singletonList(pattern));
		assertTrue(matcher.include(artifact));
	}

	/** Blacklist patterns which don't match the version should fail regardless of other values. */
	@Theory
	public void testBlacklistInvalidVersion(
			@FromDataPoints("groupId") final String groupId,
			@FromDataPoints("artifactId") final String artifactId,
			@FromDataPoints("invalidVersion") final String version,
			@FromDataPoints("type") final String type
			) {
		assumeTrue(matcher.include(artifact));
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setExcludes(Collections.singletonList(pattern));
		assertTrue(matcher.include(artifact));
	}

	/** Blacklist patterns which don't match the type should fail regardless of other values. */
	@Theory
	public void testBlacklistInvalidType(
			@FromDataPoints("groupId") final String groupId,
			@FromDataPoints("artifactId") final String artifactId,
			@FromDataPoints("version") final String version,
			@FromDataPoints("invalidType") final String type
			) {
		assumeTrue(matcher.include(artifact));
		String pattern = createArtifact(groupId, artifactId, version, type);
		matcher.setExcludes(Collections.singletonList(pattern));
		assertTrue(matcher.include(artifact));
	}

	/** Specifying invalid artifacts in the blacklist should nto affect matching of the whitelist. */
	@Theory
	@SuppressWarnings("checkstyle:parameternumber")
	public void testValidWhitelistInvalidBlacklist(
			@FromDataPoints("validGroupId") final String groupId,
			@FromDataPoints("validArtifactId") final String artifactId,
			@FromDataPoints("validVersion") final String version,
			@FromDataPoints("validType") final String type,
			@FromDataPoints("invalidGroupId") final String invalidGroupId,
			@FromDataPoints("invalidArtifactId") final String invalidArtifactId,
			@FromDataPoints("invalidVersion") final String invalidVersion,
			@FromDataPoints("invalidType") final String invalidType
			) {
		String whitelistPattern = createArtifact(groupId, artifactId, version, type);
		String blacklistPattern = createArtifact(invalidGroupId, invalidArtifactId, invalidVersion, invalidType);
		matcher.setIncludes(Collections.singletonList(whitelistPattern));
		matcher.setExcludes(Collections.singletonList(blacklistPattern));
		assertTrue(matcher.include(artifact));
	}

	/** Specifying the valid artifact in the blacklist should not affect matching of the whitelist. */
	@Theory
	@SuppressWarnings("checkstyle:parameternumber")
	public void testInvalidWhitelistValidBlacklist(
			@FromDataPoints("validGroupId") final String groupId,
			@FromDataPoints("validArtifactId") final String artifactId,
			@FromDataPoints("validVersion") final String version,
			@FromDataPoints("validType") final String type,
			@FromDataPoints("invalidGroupId") final String invalidGroupId,
			@FromDataPoints("invalidArtifactId") final String invalidArtifactId,
			@FromDataPoints("invalidVersion") final String invalidVersion,
			@FromDataPoints("invalidType") final String invalidType
			) {
		String whitelistPattern = createArtifact(invalidGroupId, invalidArtifactId, invalidVersion, invalidType);
		String blacklistPattern = createArtifact(groupId, artifactId, version, type);
		matcher.setIncludes(Collections.singletonList(whitelistPattern));
		matcher.setExcludes(Collections.singletonList(blacklistPattern));
		assertFalse(matcher.include(artifact));

	}
}