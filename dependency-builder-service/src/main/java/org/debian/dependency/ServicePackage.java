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

/** A bare class that can be used for lookup and classloading to find the jar. */
public final class ServicePackage {
	/**
	 * File name where the project artifact spy report lives. This will be under the project build directory of the first built
	 * project (generally the top-level one).
	 */
	public static final String PROJECT_ARTIFACT_REPORT_NAME = "project-artifact-spy.xml";

	private ServicePackage() {
		// empty
	}
}
