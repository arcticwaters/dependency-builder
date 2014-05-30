package org.debian.dependency;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/** Builds the dependencies of a project which deploys Maven metadata. */
@Mojo(name = "build-dependencies")
public class BuildDependencies extends AbstractMojo {

	@Override
	public void execute() throws MojoExecutionException {
		// TODO Auto-generated method stub
	}
}
