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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Configuration;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.StringUtils;

import com.google.common.io.Files;

/** Default implementation of {@link SourceRetrievalManager}. */
@Component(role = SourceRetrievalManager.class, hint = "default")
public class DefaultSourceRetrievalManager extends AbstractLogEnabled implements SourceRetrievalManager, Contextualizable {
	@Requirement(role = SourceRetrieval.class)
	private List<SourceRetrieval> sourceRetrievals;
	@Configuration(name = "source-type", value = "default")
	private String sourceType;
	private PlexusContainer container;

	@Override
	public Source checkoutSource(final Artifact artifact, final File parentDir, final MavenSession session) throws SourceRetrievalException {
		// checkout source of artifact
		String location = null;
		File checkoutDir = createNewDir(parentDir, "CHECKOUT-");
		SourceRetrieval selected = null;
		for (SourceRetrieval sourceRetrieval : sourceRetrievals) {
			location = sourceRetrieval.retrieveSource(artifact, checkoutDir, session);
			if (!StringUtils.isEmpty(location)) {
				selected = sourceRetrieval;
				getLogger().debug("Selected source retrieval " + sourceRetrieval);
				break;
			}
		}

		if (StringUtils.isEmpty(location)) {
			checkoutDir.delete();
			throw new SourceRetrievalException("Unable to retrieve source for " + artifact);
		}

		String dirname = selected.getSourceDirname(artifact, session);
		dirname = dirname.replaceAll("[^a-zA-Z0-9.-]", "_");
		File destination = new File(parentDir, dirname);

		try {
			Files.move(checkoutDir, destination);
			checkoutDir = destination;

			Source source = container.lookup(Source.class, sourceType);
			source.initialize(destination, location);
			return source;
		} catch (IOException e) {
			throw new SourceRetrievalException("Cannot initialize source", e);
		} catch (ComponentLookupException e) {
			throw new SourceRetrievalException("Unable to create source", e);
		}
	}

	/*
	 * This can be replaced by java.nio.file.Files#createTempDir when java 1.7+ becomes the lowest supported version
	 */
	private static File createNewDir(final File parent, final String prefix) {
		int index = 0;
		File file;
		do {
			file = new File(parent, prefix + ++index);
		} while (!file.mkdir());
		return file;
	}

	/**
	 * Sets the {@link SourceRetrieval}s to use.
	 *
	 * @param sourceRetrievals source retrievals to use
	 */
	public void setSourceRetrievals(final List<SourceRetrieval> sourceRetrievals) {
		List<SourceRetrieval> newRetrievals = new ArrayList<SourceRetrieval>(sourceRetrievals);
		Collections.sort(newRetrievals, new SourceRetrievalPriorityComparator());
		this.sourceRetrievals = newRetrievals;
	}

	@Override
	public void contextualize(final Context context) throws ContextException {
		container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
	}
}
