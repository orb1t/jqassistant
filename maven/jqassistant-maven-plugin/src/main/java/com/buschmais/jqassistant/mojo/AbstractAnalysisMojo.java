package com.buschmais.jqassistant.mojo;

import com.buschmais.jqassistant.core.analysis.api.CatalogReader;
import com.buschmais.jqassistant.core.analysis.api.RuleSelector;
import com.buschmais.jqassistant.core.analysis.api.RuleSetReader;
import com.buschmais.jqassistant.core.analysis.api.RuleSetResolverException;
import com.buschmais.jqassistant.core.analysis.impl.CatalogReaderImpl;
import com.buschmais.jqassistant.core.analysis.impl.RuleSelectorImpl;
import com.buschmais.jqassistant.core.analysis.impl.RuleSetReaderImpl;
import com.buschmais.jqassistant.core.model.api.rule.Concept;
import com.buschmais.jqassistant.core.model.api.rule.Constraint;
import com.buschmais.jqassistant.core.model.api.rule.Group;
import com.buschmais.jqassistant.core.model.api.rule.RuleSet;
import com.buschmais.jqassistant.core.store.api.Store;
import org.apache.commons.io.DirectoryWalker;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Abstract base implementation for analysis mojos.
 */
public abstract class AbstractAnalysisMojo extends org.apache.maven.plugin.AbstractMojo {

    public static final String RULES_DIRECTORY = "jqassistant";

    public static final String REPORT_XML = "/jqassistant/jqassistant-report.xml";

    public static final String LOG_LINE_PREFIX = "  \"";

    /**
     * The directory to scan for rules.
     *
     * @parameter expression="${jqassistant.rules.directory}"
     */
    protected File rulesDirectory;

    /**
     * The list of concept names to be applied.
     *
     * @parameter expression="${jqassistant.concepts}"
     */
    protected List<String> concepts;

    /**
     * The list of constraint names to be validated.
     *
     * @parameter expression="${jqassistant.constraints}"
     */
    protected List<String> constraints;

    /**
     * The list of group names to be executed.
     *
     * @parameter expression="${jqassistant.groups}"
     */
    protected List<String> groups;

    /**
     * The file to write the XML report to.
     *
     * @parameter expression="${jqassistant.report.xml}"
     */
    protected File xmlReportFile;


    /**
     * The catalog reader instance.
     */
    private CatalogReader catalogReader = new CatalogReaderImpl();

    /**
     * The rules reader instance.
     */
    private RuleSetReader ruleSetReader = new RuleSetReaderImpl();

    /**
     * The rule selector.
     */
    private RuleSelector ruleSelector = new RuleSelectorImpl();

    protected static interface StoreOperation<T> {
        public T run(Store store) throws MojoExecutionException, MojoFailureException;
    }

    /**
     * The artifactId.
     *
     * @parameter expression="${project.artifactId}"
     * @readonly
     */
    protected String artifactId;


    /**
     * The project rulesDirectory.
     *
     * @parameter expression="${basedir}"
     * @readonly
     */
    protected File basedir;

    /**
     * The build rulesDirectory.
     *
     * @parameter expression="${project.build.rulesDirectory}"
     * @readonly
     */
    protected File buildDirectory;

    /**
     * The classes rulesDirectory.
     *
     * @parameter expression="${project.build.outputDirectory}"
     * @readonly
     */
    protected File classesDirectory;

    /**
     * The classes rulesDirectory.
     *
     * @parameter expression="${project.build.testOutputDirectory}"
     * @readonly
     */
    protected File testClassesDirectory;

    /**
     * The store directory.
     *
     * @parameter expression="${jqassistant.store.directory}" default-value="${project.build.directory}/jqassistant/store"
     * @readonly
     */
    protected File storeDirectory;

    /**
     * The Maven project.
     *
     * @parameter expression="${project}"
     */
    protected MavenProject project;

    /**
     * The Maven Session Object
     *
     * @parameter expression="${session}"
     * @readonly
     */
    protected MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     *
     * @parameter expression = "${reactorProjects}"
     */
    protected List<MavenProject> reactorProjects;

    /**
     * @component
     */
    protected StoreProvider storeProvider;

    protected <T> T executeInTransaction(StoreOperation<T> operation) throws MojoExecutionException, MojoFailureException {
        final Store store = getStore();
        store.beginTransaction();
        try {
            return operation.run(store);
        } finally {
            store.commitTransaction();
        }
    }

    protected <T> T execute(StoreOperation<T> operation) throws MojoExecutionException, MojoFailureException {
        return operation.run(getStore());
    }

    private Store getStore() throws MojoExecutionException {
        getBaseProject();
        storeDirectory.getParentFile().mkdirs();
        return storeProvider.getStore(storeDirectory);
    }

    /**
     * Reads the available rules from the rules directory and deployed catalogs.
     *
     * @return A {@link java.util.Map} containing {@link com.buschmais.jqassistant.core.model.api.rule.Group}s identified by their id.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          If the rules cannot be read.
     */
    protected RuleSet readRules() throws MojoExecutionException {
        File selectedDirectory = null;
        if (rulesDirectory != null) {
            selectedDirectory = rulesDirectory;
        } else {
            MavenProject baseProject = getBaseProject();
            if (baseProject != null) {
                selectedDirectory = new File(baseProject.getBasedir(), RULES_DIRECTORY);
            }
        }
        List<Source> sources = new ArrayList<>();
        // read rules from rules directory
        if (selectedDirectory != null) {
            List<File> ruleFiles = readRulesDirectory(selectedDirectory);
            for (File ruleFile : ruleFiles) {
                getLog().debug("Adding rules from file " + ruleFile.getAbsolutePath());
                sources.add(new StreamSource(ruleFile));
            }
        }
        sources.addAll(catalogReader.readCatalogs());
        return ruleSetReader.read(sources);
    }

    /**
     * Return the {@link MavenProject} containing a rules directory.
     *
     * @return The {@link MavenProject} containing a rules directory.
     * @throws MojoExecutionException If the directory cannot be resolved.
     */
    protected MavenProject getBaseProject() throws MojoExecutionException {
        MavenProject currentProject = project;
        if (project != null) {
            do {
                File directory = new File(currentProject.getBasedir(), RULES_DIRECTORY);
                if (directory.exists() && directory.isDirectory()) {
                    return currentProject;
                }
                MavenProject parent = currentProject.getParent();
                if (parent == null || parent.getBasedir() == null) {
                    return currentProject;
                }
                currentProject = parent;
            } while (currentProject != null);
        }
        throw new MojoExecutionException("Cannot resolve base directory.");
    }

    /**
     * Retrieves the list of available rules from the rules directory.
     *
     * @param rulesDirectory The rules directory.
     * @return The {@link java.util.List} of available rule {@link java.io.File}s.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          If the rules directory cannot be read.
     */
    private List<File> readRulesDirectory(File rulesDirectory) throws MojoExecutionException {
        if (rulesDirectory.exists() && !rulesDirectory.isDirectory()) {
            throw new MojoExecutionException(rulesDirectory.getAbsolutePath() + " does not exist or is not a rulesDirectory.");
        }
        getLog().info("Reading rules from rulesDirectory " + rulesDirectory.getAbsolutePath());
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
            throw new MojoExecutionException("Cannot read rulesDirectory: " + rulesDirectory.getAbsolutePath(), e);
        }
    }

    /**
     * Resolves the effective rules.
     *
     * @return The resolved rule set.
     * @throws MojoExecutionException If resolving fails.
     */
    protected RuleSet resolveEffectiveRules() throws MojoExecutionException {
        RuleSet ruleSet = readRules();
        validateRuleSet(ruleSet);
        try {
            return ruleSelector.getEffectiveRuleSet(ruleSet, concepts, constraints, groups);
        } catch (RuleSetResolverException e) {
            throw new MojoExecutionException("Cannot resolve rules.", e);
        }
    }

    /**
     * Validates the given rule set for unresolved concepts, constraints or groups.
     *
     * @param ruleSet The rule set.
     * @throws MojoExecutionException If there are unresolved concepts, constraints or groups.
     */
    private void validateRuleSet(RuleSet ruleSet) throws MojoExecutionException {
        StringBuffer message = new StringBuffer();
        if (!ruleSet.getMissingConcepts().isEmpty()) {
            message.append("\n  Concepts: ");
            message.append(ruleSet.getMissingConcepts());
        }
        if (!ruleSet.getMissingConstraints().isEmpty()) {
            message.append("\n  Constraints: ");
            message.append(ruleSet.getMissingConstraints());
        }
        if (!ruleSet.getMissingGroups().isEmpty()) {
            message.append("\n  Groups: ");
            message.append(ruleSet.getMissingGroups());
        }
        if (message.length() > 0) {
            throw new MojoExecutionException("The following rules are referenced but are not available;" + message);
        }
    }

    /**
     * Logs the given {@link RuleSet} on level info.
     *
     * @param ruleSet The {@link RuleSet}.
     */
    protected void logRuleSet(RuleSet ruleSet) {
        getLog().info("Groups [" + ruleSet.getGroups().size() + "]");
        for (Group group : ruleSet.getGroups().values()) {
            getLog().info(LOG_LINE_PREFIX + group.getId() + "\"");
        }
        getLog().info("Constraints [" + ruleSet.getConstraints().size() + "]");
        for (Constraint constraint : ruleSet.getConstraints().values()) {
            getLog().info(LOG_LINE_PREFIX + constraint.getId() + "\" - " + constraint.getDescription());
        }
        getLog().info("Concepts [" + ruleSet.getConcepts().size() + "]");
        for (Concept concept : ruleSet.getConcepts().values()) {
            getLog().info(LOG_LINE_PREFIX + concept.getId() + "\" - " + concept.getDescription());
        }
        if (!ruleSet.getMissingConcepts().isEmpty()) {
            getLog().warn("Missing concepts [" + ruleSet.getMissingConcepts().size() + "]");
            for (String missingConcept : ruleSet.getMissingConcepts()) {
                getLog().warn(LOG_LINE_PREFIX + missingConcept);
            }
        }
        if (!ruleSet.getMissingConstraints().isEmpty()) {
            getLog().warn("Missing constraints [" + ruleSet.getMissingConstraints().size() + "]");
            for (String missingConstraint : ruleSet.getMissingConstraints()) {
                getLog().warn(LOG_LINE_PREFIX + missingConstraint);
            }
        }
        if (!ruleSet.getMissingGroups().isEmpty()) {
            getLog().warn("Missing groups [" + ruleSet.getMissingGroups().size() + "]");
            for (String missingGroup : ruleSet.getMissingGroups()) {
                getLog().warn(LOG_LINE_PREFIX + missingGroup);
            }
        }
    }

    /**
     * Determines a report file name.
     *
     * @param reportFile The report file as specified in the pom.xml file or on the command line.
     * @return The resolved {@link File}.
     * @throws MojoExecutionException If the file cannot be determined.
     */
    protected File getReportFile(File reportFile, String defaultFile) throws MojoExecutionException {
        MavenProject baseProject = getBaseProject();
        File selectedXmlReportFile;
        if (reportFile != null) {
            selectedXmlReportFile = reportFile;
        } else if (baseProject != null) {
            String rulesProjectOutputDirectory = baseProject.getBuild().getDirectory();
            selectedXmlReportFile = new File(rulesProjectOutputDirectory + defaultFile);
        } else if (project != null) {
            String outputDirectory = project.getBuild().getDirectory();
            selectedXmlReportFile = new File(outputDirectory + defaultFile);
        } else {
            throw new MojoExecutionException("Cannot determine report file.");
        }
        return selectedXmlReportFile;
    }
}
