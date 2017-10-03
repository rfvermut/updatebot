/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.updatebot.maven;

import io.fabric8.updatebot.model.Dependencies;
import io.fabric8.updatebot.model.GitRepositoryConfig;
import io.fabric8.updatebot.model.MavenDependencies;
import io.fabric8.updatebot.model.MavenDependency;
import io.fabric8.updatebot.model.MavenDependencyFilter;
import io.fabric8.updatebot.model.RepositoryConfig;
import io.fabric8.updatebot.model.RepositoryConfigs;
import io.fabric8.utils.Filter;
import io.fabric8.utils.Filters;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Exports the versions from the source code of the current project so that we can apply the versions
 * to other projects
 */
@Mojo(name = "export", aggregator = true, requiresProject = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ExportVersionsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    @Parameter(property = "updateBotYaml")
    protected String configYaml;

    @Parameter(property = "destFile", defaultValue = "${basedir}/target/updatebot-versions.properties")
    protected File destFile;

    private RepositoryConfig repositoryConfig;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Log log = getLog();
        File sourceDir = project.getBasedir();
        try {
            repositoryConfig = RepositoryConfigs.loadRepositoryConfig(configYaml, sourceDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load updatebot.yml file from " + configYaml + ". " + e, e);
        }
        GitRepositoryConfig config;
        try {
            config = RepositoryConfigs.getGitHubRepositoryDetails(repositoryConfig, sourceDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to find GitRepositoryConfig from updatebot.yml file " + configYaml + ". " + e, e);
        }
        log.debug("Loaded git config " + config + " from " + sourceDir);

        MavenDependencies mavenDependencies = null;
        if (config != null) {
            Dependencies push = config.getPush();
            if (push != null) {
                mavenDependencies = push.getMaven();
            }
        }
        Filter<MavenDependency> dependencyFilter = Filters.falseFilter();
        if (mavenDependencies != null) {
            List<MavenDependencyFilter> dependencies = mavenDependencies.getDependencies();
            if (dependencies != null) {
                dependencyFilter = MavenDependencyFilter.createFilter(dependencies);
            }
        }
        Map<MavenDependency, String> exportVersions = new TreeMap<>();

        List<MavenProject> projects = project.getCollectedProjects();
        for (MavenProject project : projects) {
            MavenDependency projectDependency = new MavenDependency(project.getGroupId(), project.getArtifactId());
            exportVersions.put(projectDependency, project.getVersion());

            log.debug("Collected project : " + project);
            List<Dependency> dependencies = project.getDependencies();
            for (Dependency dependency : dependencies) {
                MavenDependency mvnDependency = toMavenDependency(dependency);
                if (dependencyFilter.matches(mvnDependency)) {
                    log.debug("    dependency: " + dependency);
                    exportVersions.put(mvnDependency, dependency.getVersion());
                }
            }
        }

        destFile.getParentFile().mkdirs();

        Properties properties = new Properties();
        for (Map.Entry<MavenDependency, String> entry : exportVersions.entrySet()) {
            properties.setProperty(entry.getKey().toString(), entry.getValue());
        }
        try {
            properties.store(new FileWriter(destFile), "Generated by the updatebot-maven-plugin:export goal");

            log.info("Generated updatebot version export file " + destFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write to " + destFile + ". " + e, e);
        }
    }

    protected static MavenDependency toMavenDependency(Dependency dependency) {
        return new MavenDependency(dependency.getGroupId(), dependency.getArtifactId());
    }


}
