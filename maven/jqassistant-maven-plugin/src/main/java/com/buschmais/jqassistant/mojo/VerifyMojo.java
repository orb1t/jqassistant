/**
 * Copyright (C) 2011 tdarby <tim.darby.uk@googlemail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.buschmais.jqassistant.mojo;

import com.buschmais.jqassistant.core.analysis.api.ConstraintAnalyzer;
import com.buschmais.jqassistant.core.analysis.api.RulesReader;
import com.buschmais.jqassistant.core.analysis.api.model.ConstraintGroup;
import com.buschmais.jqassistant.core.analysis.api.model.ConstraintViolations;
import com.buschmais.jqassistant.core.analysis.impl.ConstraintAnalyzerImpl;
import com.buschmais.jqassistant.core.analysis.impl.RulesReaderImpl;
import com.buschmais.jqassistant.store.api.Store;
import org.apache.commons.io.DirectoryWalker;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @goal verify
 * @phase verify
 * @requiresProject false
 */
public class VerifyMojo extends AbstractStoreMojo {

    public static final String DEFAULT_RULES_DIRECTORY = "src/jqassistant";
    /**
     * @parameter
     */
    protected Rules rules;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Map<String, ConstraintGroup> constraintGroups = readRules();

        List<ConstraintViolations> constraintViolations = execute(new StoreOperation<List<ConstraintViolations>>() {
            @Override
            public List<ConstraintViolations> run(Store store) throws MojoExecutionException {
                ConstraintAnalyzer analyzer = new ConstraintAnalyzerImpl(store);
                return analyzer.validateConstraints(constraintGroups.values());
            }
        });
        if (!constraintViolations.isEmpty()) {
            for (ConstraintViolations constraintViolation : constraintViolations) {
                getLog().error(constraintViolation.getConstraint().getId() + ": " + constraintViolation.getConstraint().getDescription());
                for (Map<String, Object> columns : constraintViolation.getViolations()) {
                    StringBuilder message = new StringBuilder();
                    for (Map.Entry<String, Object> entry : columns.entrySet()) {
                        if (message.length()>0) {
                            message.append(", ");
                        }
                        message.append(entry.getKey());
                        message.append('=');
                        message.append(entry.getValue());
                    }
                    getLog().error("  " + message.toString());
                }
            }
            throw new MojoFailureException(constraintViolations.size() + " constraints have been violated!");
        }
    }

    private Map<String, ConstraintGroup> readRules() throws MojoExecutionException {
        File rulesDirectory = null;
        List<URL> urls = null;
        if (rules != null) {
            rulesDirectory = rules.getDirectory();
            urls = rules.getUrls();
        }

        if (rulesDirectory == null) {
            rulesDirectory = new File(basedir.getAbsoluteFile() + File.separator + DEFAULT_RULES_DIRECTORY);
        }

        List<InputStream> ruleStreams = new ArrayList<InputStream>();
        try {
            List<File> ruleFiles = readRulesDirectory(rulesDirectory);
            for (File ruleFile : ruleFiles) {
                getLog().debug("Adding rules from file " + ruleFile.getAbsolutePath());
                try {
                    ruleStreams.add(new BufferedInputStream(new FileInputStream(ruleFile)));
                } catch (IOException e) {
                    throw new MojoExecutionException("Cannot open rule file: " + ruleFile.getAbsolutePath(), e);
                }
            }
            if (urls != null) {
                for (URL url : urls) {
                    getLog().debug("Adding rules from URL " + url.toString());
                    try {
                        InputStream ruleStream = url.openStream();
                    } catch (IOException e) {
                        throw new MojoExecutionException("Cannot open rule URL: " + url.toString(), e);
                    }
                }
            }
            List<Source> sources = new ArrayList<Source>();
            for (InputStream ruleStream : ruleStreams) {
                sources.add(new StreamSource(ruleStream));
            }
            RulesReader rulesReader = new RulesReaderImpl();
            return rulesReader.read(sources);
        } finally {
            for (InputStream ruleStream : ruleStreams) {
                IOUtils.closeQuietly(ruleStream);
            }
        }
    }

    private List<File> readRulesDirectory(File rulesDirectory) throws MojoExecutionException {
        if (rulesDirectory.exists() && !rulesDirectory.isDirectory()) {
            throw new MojoExecutionException(rulesDirectory.getAbsolutePath() + " does not exist or is not a directory.");
        }
        getLog().info("Reading rules from directory " + rulesDirectory.getAbsolutePath());
        final List<File> ruleFiles = new ArrayList<File>();
        try {
            new DirectoryWalker<File>() {

                @Override
                protected void handleFile(File file, int depth, Collection<File> results) throws IOException {
                    if (!file.isDirectory() && file.getName().endsWith(".xml")) {
                        results.add(file);
                    }
                }

                public void scan(File directory) throws IOException {
                    super.walk(directory, ruleFiles);
                }
            }.scan(rulesDirectory);
            return ruleFiles;
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read directory: " + rulesDirectory.getAbsolutePath(), e);
        }
    }
}