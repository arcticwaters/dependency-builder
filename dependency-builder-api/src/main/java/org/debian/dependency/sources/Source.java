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

/**
 * Defines a type of source for a particular project. Sources may contain many different projects or just a single one depending
 * on the what type of source it is.
 */
public interface Source {
	/**
	 * @return location of the source
	 */
	File getLocation();

	/**
	 * Initialization routine for the source. This method should only be called once.
	 *
	 * @param location location of the source
	 * @param origin where the source is from
	 * @throws IOException in case of errors
	 */
	void initialize(File location, String origin) throws IOException;

	/**
	 * Cleans any stale files and returns the source to a pristine copy.
	 *
	 * @throws IOException in case of errors
	 */
	void clean() throws IOException;
}
