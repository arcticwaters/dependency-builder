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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;

/** Default implementation of {@link SourceBuilderManager}. */
@Component(role = SourceBuilderManager.class, hint = "default")
public class DefaultSourceBuilderManager extends AbstractLogEnabled implements SourceBuilderManager {
	@Requirement(role = SourceBuilder.class)
	private List<SourceBuilder> builders = new ArrayList<SourceBuilder>();

	public void setBuilders(final List<SourceBuilder> builders) {
		// new list so that we can modify it
		this.builders = new ArrayList<SourceBuilder>(builders);
	}

	@Override
	public SourceBuilder detect(final File directory) throws IOException {
		SourceBuilder priorityBuilder = null;
		int priority = Integer.MAX_VALUE;
		for (SourceBuilder builder : builders) {
			int builderPriority = builder.getPriority(directory);
			if (builderPriority >= 0 && builderPriority <= priority) {
				priority = builderPriority;
				priorityBuilder = builder;
			}
		}
		return priorityBuilder;
	}

	@Override
	public void addSourceBuilder(final SourceBuilder sourceBuilder) {
		builders.add(sourceBuilder);
	}

	@Override
	public List<SourceBuilder> getSourceBuilders() {
		return Collections.unmodifiableList(builders);
	}
}
