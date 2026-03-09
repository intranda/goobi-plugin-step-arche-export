package org.goobi.api;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;

import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;

@Getter
public class ArcheConfiguration {

    private String archeUserName;
    private String archePassword;
    private String archeApiUrl;

    private String placeholderImage;

    private String viewerUrl;
    private String exportFolder;

    private String identifierPrefix = "https://id.acdh.oeaw.ac.at/";
    private String archeNamespace = "https://vocabs.acdh.oeaw.ac.at/schema#";

    private boolean enableArcheIngestData = false;
    private boolean enableArcheIngestValidation = false;

    private XMLConfiguration config;

    public ArcheConfiguration(String configurationFile) {
        config = ConfigPlugins.getPluginConfig(configurationFile);
        config.setExpressionEngine(new XPathExpressionEngine());

        archeUserName = config.getString("/api/archeUserName");
        archePassword = config.getString("/api/archePassword");
        archeApiUrl = config.getString("/api/archeApiUrl");
        enableArcheIngestValidation = config.getBoolean("/api/@enableValidation", false);
        enableArcheIngestData = config.getBoolean("/api/@enableIngest", false);

        placeholderImage = config.getString("/project/placeholderImage");

        viewerUrl = config.getString("/viewerUrl");

        exportFolder = config.getString("/exportFolder");
    }

}
