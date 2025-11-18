package org.goobi.api;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;

import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;

@Getter
public class ArcheConfiguration {

    private String archeUserNameProd;
    private String archePasswordProd;
    private String archeApiUrlProd;

    private String archeUserNameTest;
    private String archePasswordTest;
    private String archeApiUrlTest;

    private String archeUrlPropertyNameProd;
    private String archeUrlPropertyNameTest;
    private String placeholderImage;

    private String viewerUrl;
    private String exportFolder;

    private String identifierPrefix = "https://id.acdh.oeaw.ac.at/";
    private String archeNamespace = "https://vocabs.acdh.oeaw.ac.at/schema#";

    private boolean enableArcheIngestProd = false;
    private boolean enableArcheIngestTest = false;

    private XMLConfiguration config;

    public ArcheConfiguration(String configurationFile) {
        config = ConfigPlugins.getPluginConfig(configurationFile);
        config.setExpressionEngine(new XPathExpressionEngine());

        archeUserNameProd = config.getString("/api[@type='prod']/archeUserName");
        archePasswordProd = config.getString("/api[@type='prod']/archePassword");
        archeApiUrlProd = config.getString("/api[@type='prod']/archeApiUrl");
        enableArcheIngestProd = config.getBoolean("/api[@type='prod']/@enabled", false);

        archeUserNameTest = config.getString("/api[@type!='prod']/archeUserName");
        archePasswordTest = config.getString("/api[@type!='prod']/archePassword");
        archeApiUrlTest = config.getString("/api[@type!='prod']/archeApiUrl");
        enableArcheIngestTest = config.getBoolean("/api[@type!='prod']/@enabled", false);

        archeUrlPropertyNameProd = config.getString("/project/archeUrlPropertyName[@type='prod']");

        archeUrlPropertyNameTest = config.getString("/project/archeUrlPropertyName[@type!='prod']");
        placeholderImage = config.getString("/project/placeholderImage");

        viewerUrl = config.getString("/viewerUrl");

        exportFolder = config.getString("/exportFolder");
    }

    public boolean isEnableArcheIngest(boolean prod) {
        return prod ? enableArcheIngestProd : enableArcheIngestTest;
    }

    public String getArcheApiUrl(boolean prod) {
        return prod ? archeApiUrlProd : archeApiUrlTest;
    }

    public String getArcheUserName(boolean prod) {
        return prod ? archeUserNameProd : archeUserNameTest;
    }

    public String getArchePassword(boolean prod) {
        return prod ? archePasswordProd : archePasswordTest;
    }

    public String getArcheUrlPropertyName(boolean prod) {
        return prod ? archeUrlPropertyNameProd : archeUrlPropertyNameTest;
    }

}
