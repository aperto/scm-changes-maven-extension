package org.apache.maven.shared.scmupdates;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.update.UpdateScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Builds only projects containing files that contained updates (according to SCM).
 * <p/>
 * Based on {@link org.apache.maven.shared.scmchanges.MakeScmChanges} by
 * <a href="mailto:dfabulich@apache.org">Dan Fabulich</a>.
 *
 * @author Norman Wiechmann
 */
@Component(role = AbstractMavenLifecycleParticipant.class, hint = "scm-updates")
public class MakeScmUpdates extends AbstractMavenLifecycleParticipant {
    @Requirement
    Logger logger;

    @Requirement
    ScmManager scmManager;

    /**
     * SCM connection from root project
     */
    String scmConnection;

    /**
     * make.ignoreRootPom: Ignore updates in the root POM file, which would normally cause a full rebuild
     */
    boolean ignoreRootPom = false;

    /**
     * Disabled by default; activate via -Dmake.scmUpdates=true
     */
    boolean enabled = false;

    /**
     * make.baseDir: Search SCM for modified files in this directory.
     * Defaults to ${project.baseDir} for the root project.
     */
    File baseDir;

    public void afterProjectsRead(MavenSession session)
        throws MavenExecutionException {
        readParameters(session);

        if (!enabled) {
            logger.debug("make.scmUpdates = false, not modifying project list");
            return;
        }

        List<ScmFile> changedFiles = updateFilesFromScm(baseDir);

        List<String> includedProjects = new ArrayList<String>();

        MavenProject topLevelProject = session.getTopLevelProject();
        for (ScmFile changedScmFile : changedFiles) {
            logger.debug(changedScmFile.toString());

            File changedFile = new File(changedScmFile.getPath()).getAbsoluteFile();

            if (ignoreRootPom && topLevelProject.getFile().getAbsoluteFile().equals(changedFile)) {
                continue;
            }

            boolean found = false;
            // TODO There's a cleverer/faster way to code this, right? This is O(n^2)
            for (MavenProject project : session.getProjects()) {
                File projectDirectory = project.getFile().getParentFile();
                if (changedFile.getAbsolutePath().startsWith(projectDirectory.getAbsolutePath() + File.separator)) {
                    if (topLevelProject.equals(project)) {
                        // If we include the top level project, then we'll build everything.
                        // We have to be very careful before allowing that to happen.

                        // In particular, if the modified file is in a subdirectory X that is not itself
                        // a Maven project, we don't want that one file to cause a full build. 
                        // i.e. we ignore updates that are in a random subdirectory.

                        // Is the top level project actually in the baseDir?
                        // Sometimes people have sibling child projects, e.g.
                        // <module>../child-project</module>
                        // If the top level project isn't the baseDir, then running the whole build may be rational.
                        if (baseDir.equals(projectDirectory.getAbsoluteFile())) {

                            // is the changed file the baseDir or one of its immediate descendants?
                            // That should probably provoke a rebuild.
                            if (!(baseDir.equals(changedFile) || baseDir.equals(changedFile.getParentFile()))) {
                                // OK, so the changed file is in some random subdirectory of the baseDir.
                                // Skip it.
                                logger.debug("Not considering top level project for " + changedFile +
                                    " because that would trigger a full rebuild.");
                                continue;
                            }
                        }
                    }
                    if (!includedProjects.contains(project)) {
                        logger.debug("Including " + project);
                    }
                    includedProjects.add(project.getGroupId() + ":" + project.getArtifactId());
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.debug("Couldn't find file in any project root: " + changedFile.getAbsolutePath());
            }
        }

        if (includedProjects.isEmpty())
            throw new MavenExecutionException("No SCM updates detected; nothing to do!",
                topLevelProject.getFile());

        MavenExecutionRequest request = session.getRequest();
        String makeBehavior = request.getMakeBehavior();
        if (makeBehavior == null) {
            request.setMakeBehavior(MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM);
        }
        if (MavenExecutionRequest.REACTOR_MAKE_UPSTREAM.equals(makeBehavior)) {
            request.setMakeBehavior(MavenExecutionRequest.REACTOR_MAKE_BOTH);
        }

        request.setSelectedProjects(includedProjects);

    }

    void readParameters(MavenSession session) throws MavenExecutionException {
        Properties sessionProps = session.getUserProperties();
        scmConnection = sessionProps.getProperty("make.scmConnection");
        if (scmConnection == null) {
            try {
                scmConnection = session.getTopLevelProject().getScm().getConnection();
            } catch (NullPointerException e) {
                String message =
                    "No SCM connection specified.  You must specify an SCM "
                        + "connection by adding a <connection> element to your <scm> element in your POM";
                throw new MavenExecutionException(message, e);
            }
        }

        enabled = Boolean.parseBoolean(sessionProps.getProperty("make.scmUpdates", "false"));
        ignoreRootPom = Boolean.parseBoolean(sessionProps.getProperty("make.ignoreRootPom", "false"));

        String basePath = sessionProps.getProperty("make.baseDir");
        if (basePath != null) {
            baseDir = new File(basePath).getAbsoluteFile();
        } else {
            baseDir = session.getTopLevelProject().getBasedir().getAbsoluteFile();
        }
    }

    /**
     * Executes an SCM update operation and returns a list of affected files.
     */
    @SuppressWarnings("unchecked")
    List<ScmFile> updateFilesFromScm(File baseDir) throws MavenExecutionException {
        UpdateScmResult result;
        try {
            ScmRepository repository = scmManager.makeScmRepository(scmConnection);
            result = scmManager.update(repository, new ScmFileSet(baseDir), false);
        } catch (Exception e) {
            throw new MavenExecutionException("Couldn't configure SCM repository: " + e.getLocalizedMessage(), e);
        }
        return (List<ScmFile>) result.getUpdatedFiles();
    }
}