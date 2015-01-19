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

public class SourceRetrievalException extends Exception {
	private static final long serialVersionUID = 1L;

	/** Constructs a new exception. */
	public SourceRetrievalException() {
		super();
	}

	/**
	 * Constructs a new exception with the given message and nested.
	 *
	 * @param message explanation of the problem
	 * @param cause nested exception
	 */
	public SourceRetrievalException(final String message, final Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new exception with the given message.
	 *
	 * @param message explanation of the problem
	 */
	public SourceRetrievalException(final String message) {
		super(message);
	}

	/**
	 * Constructs a new exception with the given nested.
	 *
	 * @param cause nested exception
	 */
	public SourceRetrievalException(final Throwable cause) {
		super(cause);
	}
}
