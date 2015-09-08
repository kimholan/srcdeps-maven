/**
 * Copyright 2015 Maven Source Dependencies
 * Plugin contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.l2x6.maven.srcdeps;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.shared.release.ReleaseResult;
import org.apache.maven.shared.release.env.DefaultReleaseEnvironment;
import org.apache.maven.shared.release.env.ReleaseEnvironment;
import org.apache.maven.shared.release.exec.MavenExecutor;
import org.apache.maven.shared.release.exec.MavenExecutorException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.l2x6.maven.srcdeps.config.Repository;
import org.l2x6.maven.srcdeps.config.SrcdepsConfiguration;

public class SrcdepsInstaller {

    private static class ArgsBuilder {
        private final StringBuilder builder = new StringBuilder();

        public ArgsBuilder nonDefaultProp(String key, boolean value, boolean defaultValue) {
            if (defaultValue != value) {
                prop(key, String.valueOf(value));
            }
            return this;
        }

        public ArgsBuilder prop(String key, String value) {
            if (builder.length() != 0) {
                builder.append(' ');
            }
            // FIXME: we should check and eventually quote and/or escape the the
            // whole param
            builder.append("-D").append(key).append('=').append(value);
            return this;
        }

        public String build() {
            return builder.toString();
        }
    }

    private final Logger logger;

    private final SrcdepsConfiguration configuration;
    private final Map<Dependency, ScmVersion> revisions;
    private final MavenSession session;
    private final MavenExecutor mavenExecutor;
    private final ReleaseEnvironment releaseEnvironment;
    private final ArtifactHandlerManager artifactHandlerManager;

    public SrcdepsInstaller(MavenSession session, Logger logger, ArtifactHandlerManager artifactHandlerManager,
            SrcdepsConfiguration configuration, Map<Dependency, ScmVersion> revisions) {
        super();
        this.session = session;
        this.logger = logger;
        this.artifactHandlerManager = artifactHandlerManager;
        this.configuration = configuration;
        this.revisions = revisions;
        try {
            this.mavenExecutor = (MavenExecutor) session.lookup(MavenExecutor.ROLE, "forked-path");
            logger.debug("srcdeps-maven-plugin looked up a mavenExecutor [" + mavenExecutor +"]");
        } catch (ComponentLookupException e) {
            throw new RuntimeException(e);
        }
        this.releaseEnvironment = new DefaultReleaseEnvironment().setJavaHome(configuration.getJavaHome())
                .setMavenHome(configuration.getMavenHome()).setSettings(session.getSettings());

    }

    protected void build(DependencyBuild depBuild) throws MavenExecutorException {
        logger.info("srcdeps-maven-plugin is setting version [" + depBuild.getId() + "] version ["
                + depBuild.getVersion() + "] from [" + depBuild.getUrl() + "]");

        mavenExecutor.executeGoals(depBuild.getWorkingDirectory(), "versions:set", releaseEnvironment, false,
                "-DnewVersion=" + depBuild.getVersion() + " -DgenerateBackupPoms=false", "pom.xml",
                new ReleaseResult());

        logger.info("srcdeps-maven-plugin is building [" + depBuild.getId() + "] version [" + depBuild.getVersion()
                + "] from [" + depBuild.getUrl() + "]");
        mavenExecutor.executeGoals(depBuild.getWorkingDirectory(), "clean install", releaseEnvironment, false, null,
                "pom.xml", new ReleaseResult());
    }

    protected void checkout(DependencyBuild depBuild) throws MavenExecutorException {

        logger.info("srcdeps-maven-plugin is checking out [" + depBuild.getId() + "] version [" + depBuild.getVersion()
                + "] from [" + depBuild.getUrl() + "]");

        File checkoutDir = depBuild.getWorkingDirectory();
        if (!checkoutDir.exists()) {
            checkoutDir.mkdirs();
        }

        final ScmVersion scmVersion = depBuild.getScmVersion();

        final String args = new ArgsBuilder().prop("checkoutDirectory", checkoutDir.getAbsolutePath())
                .prop("connectionUrl", depBuild.getUrl()).prop("scmVersionType", scmVersion.getVersionType())
                .prop("scmVersion", scmVersion.getVersion()).nonDefaultProp("skipTests", depBuild.isSkipTests(), false)
                .nonDefaultProp("maven.test.skip", depBuild.isMavenTestSkip(), false).build();

        // logger.info("Using args ["+ args +"]");

        mavenExecutor.executeGoals(new File(session.getExecutionRootDirectory()),
                "org.apache.maven.plugins:maven-scm-plugin:" + configuration.getScmPluginVersion() + ":checkout",
                releaseEnvironment, false, args, "pom.xml", new ReleaseResult());

    }

    protected Repository findRepository(Dependency dep) {
        for (Repository repository : configuration.getRepositories()) {
            for (String selector : repository.getSelectors()) {
                if (SrcdepsUtils.matches(dep, selector)) {
                    return repository;
                }
            }
        }
        return null;
    }

    public void install() {
        logger.info("About to build srcdeps with " + configuration);
        Map<String, DependencyBuild> depBuilds = new HashMap<String, DependencyBuild>(revisions.size());

        ArtifactRepository localRepo = session.getLocalRepository();

        for (Map.Entry<Dependency, ScmVersion> revisionEntry : revisions.entrySet()) {
            Dependency dep = revisionEntry.getKey();

            Artifact artifact = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
                    dep.getScope(), dep.getType(), dep.getClassifier(),
                    artifactHandlerManager.getArtifactHandler(dep.getType()));

            File artifactFile = new File(localRepo.getBasedir(), localRepo.pathOf(artifact));
            if (artifactFile.exists()) {
                logger.info("Source dependency available in local repository: " + artifactFile.getAbsolutePath());
            } else {
                logger.info("Source dependency missing in local repository: " + artifactFile.getAbsolutePath());
                ScmVersion scmVersion = revisionEntry.getValue();
                Repository repo = findRepository(dep);
                if (repo == null) {
                    if (configuration.isFailOnMissingRepository()) {
                        throw new RuntimeException("Could not find repository for " + dep);
                    } else {
                        /*
                         * ignore and assume that the user knows what he is
                         * doing
                         */
                    }
                } else {
                    String url = repo.getUrl();
                    DependencyBuild depBuild = depBuilds.get(url);
                    if (depBuild != null) {
                        ScmVersion foundScmVersion = depBuild.getScmVersion();
                        if (foundScmVersion != null && !foundScmVersion.equals(scmVersion)) {
                            throw new RuntimeException("Cannot handle two revisions for the same repository URL '" + url
                                    + "': '" + foundScmVersion + "' and '" + scmVersion + "' of " + dep);
                        }
                    } else {
                        /* checkout == null */
                        depBuild = new DependencyBuild(configuration.getSourcesDirectory(), repo.getId(), url,
                                dep.getVersion(), scmVersion, repo.isSkipTests(), repo.isMavenTestSkip());
                        depBuilds.put(url, depBuild);
                    }
                }
            }

        }

        /* this could eventually be done in parallel */
        for (DependencyBuild depBuild : depBuilds.values()) {
            try {
                checkout(depBuild);
                build(depBuild);
            } catch (MavenExecutorException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
