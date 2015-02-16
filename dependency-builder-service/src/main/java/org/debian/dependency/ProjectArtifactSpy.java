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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

/**
 * Reports all artifacts for successful projects.
 */
@Component(role = EventSpy.class, hint = "artifact-spy")
public class ProjectArtifactSpy implements EventSpy {
	@Requirement
	private RepositorySystem repoSystem;
	private Properties artifacts;
	private File outputFile;

	@Override
	public void init(final Context context) {
		artifacts = new Properties();
	}

	@Override
	public void onEvent(final Object event) {
		if (!(event instanceof ExecutionEvent)) {
			return;
		}
		ExecutionEvent execEvent = (ExecutionEvent) event;
		if (!Type.ProjectSucceeded.equals(execEvent.getType()) && !Type.ForkedProjectSucceeded.equals(execEvent.getType())) {
			return;
		}

		MavenProject project = execEvent.getProject();
		Artifact pomArtifact = repoSystem.createProjectArtifact(project.getGroupId(), project.getArtifactId(), project.getVersion());
		pomArtifact.setFile(project.getFile());

		// the first project should always be the top-level project
		if (outputFile == null) {
			outputFile = new File(project.getBuild().getDirectory(), ServicePackage.PROJECT_ARTIFACT_REPORT_NAME);
		}

		recordArtifact(pomArtifact);
		recordArtifact(project.getArtifact());
		for (Artifact artifact : project.getAttachedArtifacts()) {
			recordArtifact(artifact);
		}
	}

	private void recordArtifact(final Artifact artifact) {
		if (artifact.getFile() != null) {
			artifacts.setProperty(artifact.getId(), artifact.getFile().getAbsoluteFile().toString());
		}
	}

	@Override
	public void close() throws IOException {
		if (outputFile != null) {
			outputFile.getParentFile().mkdirs();
			OutputStream stream = new FileOutputStream(outputFile);
			try {
				artifacts.store(stream, "");
			} finally {
				stream.close();
			}
		}
	}
}