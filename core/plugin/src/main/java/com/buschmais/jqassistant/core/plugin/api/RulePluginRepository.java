package com.buschmais.jqassistant.core.plugin.api;

import com.buschmais.jqassistant.core.analysis.api.rule.RuleSource;

import java.util.List;

import javax.xml.transform.Source;

/**
 * Defines the interface for the scanner plugin repository.
 */
public interface RulePluginRepository {

    /**
     * Get a list of sources providing rules.
     * 
     * @return The list of sources providing rules.
     */
    List<RuleSource> getRuleSources();

}
