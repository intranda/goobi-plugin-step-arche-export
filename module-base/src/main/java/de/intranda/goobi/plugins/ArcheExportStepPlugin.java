package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.exceptions.UGHException;

@PluginImplementation
@Log4j2
public class ArcheExportStepPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = 2508360403026767509L;
    @Getter
    private String title = "intranda_step_arche_export";
    @Getter
    private Step step;

    private Process process;

    private Project project;

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    @Getter
    private PluginType type = PluginType.Step;

    private static final String IDENTIFIER_PREFIX = "https://id.acdh.oeaw.ac.at/";

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
        project = process.getProjekt();
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        log.info("ArcheExport step plugin initialized");
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_arche_export.xhtml";
    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        String sortTitle = null;
        String orderNumber = null;
        String maintitle = null;
        String subtitle = null;
        String id = null;
        String shelfmark = null;
        String language = null;
        String license = null;
        String publicationyear = null;
        String handle = null;
        String dateOfOrigin = null;

        try {
            Fileformat fileformat = process.readMetadataFile();
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct logical = dd.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                logical = logical.getAllChildren().get(0);
            }

            for (Metadata md : logical.getAllMetadata()) {
                switch (md.getType().getName()) {
                    case "TitleDocMainShort":
                        sortTitle = md.getValue();
                        break;
                    case "CurrentNo":
                        orderNumber = md.getValue();
                        break;
                    case "TitleDocMain":
                        maintitle = md.getValue();
                        break;
                    case "TitleDocSub1":
                        subtitle = md.getValue();
                        break;
                    case "CatalogIDDigital":
                        id = md.getValue();
                        break;
                    case "shelfmarksource":
                        shelfmark = md.getValue();
                        break;
                    case "DocLanguage":
                        language = md.getValue();
                        break;
                    case "AccessLicense":
                        license = md.getValue();
                        break;
                    case "PublicationYear":
                        publicationyear = md.getValue();
                        break;
                    case "DateOfOrigin":
                        dateOfOrigin = md.getValue();
                        break;
                    default:
                        throw new IllegalArgumentException("Unexpected value: " + md.getType().getName());
                }
            }

        } catch (UGHException | IOException |

                SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        String languageCode = "de";
        if ("eng".equals(language)) {
            languageCode = "en";
        }

        boolean dateIsInferred = false;
        boolean dateIsUncertain = false;
        if (dateOfOrigin == null && StringUtils.isNotBlank(publicationyear)) {
            dateOfOrigin = publicationyear;
        }
        if (dateOfOrigin != null) {
            if (dateOfOrigin.contains("?")) {
                dateIsUncertain = true;
            }
            if (dateOfOrigin.contains("[")) {
                dateIsInferred = true;
            }
        }

        Model model = ModelFactory.createDefaultModel();
        // collection name
        String topCollectionIdentifier = IDENTIFIER_PREFIX + project.getTitel();

        String api = "https://arche.acdh.oeaw.ac.at/api/";
        String acdh =
                "https://vocabs.acdh.oeaw.ac.at/schema#";
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        // retrieve metadata on top level, update if it exists
        String downloadUrl = topCollectionIdentifier + "/metadata";

        if (false) {
            String inputFileName = "";
            InputStream in = RDFDataMgr.open(inputFileName);
            model.read(in, null);

            // update hasCoverageStartDate + hasCoverageEndDate

        } else {
            // if not, create metadata on project level
            Resource topCollection = createTopCollectionDocument(model, topCollectionIdentifier, api, acdh, publicationyear, languageCode);

        }

        try (
                OutputStream out = new FileOutputStream("/tmp/project.ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }

        // process level
        String collectionIdentifier = topCollectionIdentifier + "/" + id;

        model = ModelFactory.createDefaultModel();

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        Resource processResource = model.createResource(collectionIdentifier, model.createResource(acdh + "Collection"));

        if (StringUtils.isBlank(sortTitle)) {
            sortTitle = maintitle;
        }

        //        hasTitle    1       langString  1   TitleDocMainShort + CurrentNo   Remove any characters &lt; or &gt; present at the beginning, used for denoting articles according to library science conventions (like <<Der>>)
        String mainTitle;
        if (StringUtils.isNotBlank(orderNumber)) {
            mainTitle = sortTitle + orderNumber;
        } else {
            mainTitle = sortTitle;
        }
        mainTitle = mainTitle.replace("<<", "").replace(">>", "");
        processResource.addProperty(model.createProperty(acdh, "hasTitle"), mainTitle, languageCode);

        //        hasAlternativeTitle 0-n     langString  2   TitleDocMain + " : " + TitleDocSub1 Add " : " only if TitleDocSub1 is present. Remove any characters &lt; or &gt; present at the beginning, used for denoting articles (like <<Der>>)
        String altTitle;
        if (StringUtils.isNotBlank(subtitle)) {
            altTitle = maintitle + " : " + subtitle;
        } else {
            altTitle = maintitle;
        }
        altTitle = altTitle.replace("<<", "").replace(">>", "");
        processResource.addProperty(model.createProperty(acdh, "hasAlternativeTitle"), altTitle, languageCode);

        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793
        processResource.addProperty(model.createProperty(acdh, "hasIdentifier"), model.createResource(collectionIdentifier));

        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        if (StringUtils.isNotBlank(handle)) {
            processResource.addProperty(model.createProperty(acdh, "hasPid"), handle, XSDDatatype.XSDanyURI);
        }

        //        hasNonLinkedIdentifier  0-n     string  4   CatalogIDDigital    e.g. "AC02277063"
        processResource.addProperty(model.createProperty(acdh, "hasNonLinkedIdentifier"), id);

        //        hasNonLinkedIdentifier  0-n     string  4   shelfmarksource e.g. "R-III: WE 379"
        if (StringUtils.isNotBlank(shelfmark)) {
            processResource.addProperty(model.createProperty(acdh, "hasNonLinkedIdentifier"), shelfmark);
        }

        //        hasNonLinkedIdentifier  0-n     string  4   {Goobi ID}  e.g. "9470"
        processResource.addProperty(model.createProperty(acdh, "hasNonLinkedIdentifier"), String.valueOf(process.getId()));

        //        hasUrl  0-n     anyURI  30  --- See note ---    Should be the URL of the object in Goobi Viewer, e.g. https://viewer.acdh.oeaw.ac.at/viewer/image/AC02277063
        processResource.addProperty(model.createProperty(acdh, "hasUrl"), "https://viewer.acdh.oeaw.ac.at/viewer/image/" + id);

        //        hasDescription  0-n     langString  40  --- See note ---    We would need a description such as "A collection of scans from: https://permalink.obvsg.at/AC02277063", where the AC identifier of the original publication is given (retrievable from field "CatalogIDDigital"). Maybe this description can be automatically generated?
        processResource.addProperty(model.createProperty(acdh, "hasDescription"), "A collection of scans from: https://permalink.obvsg.at/" + id,
                "en");

        //        hasLanguage 0-n     Concept 41  DocLanguage Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/iso6393/
        processResource.addProperty(model.createProperty(acdh, "hasLanguage"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/" + language));

        //        hasLifeCycleStatus  0-1     Concept 42  --- See note ---    "If the process has not been marked as completed yet, set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active
        //        Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        //        But most of the Processes that will be imported into ARCHE should be more or less completed."
        processResource.addProperty(model.createProperty(acdh, "hasLifeCycleStatus"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));

        //        hasExtent   0-1     langString  46  --- See note ---    We would need a string such as "544 files", where the total numer of master images is computed.
        processResource.addProperty(model.createProperty(acdh, "hasExtent"), process.getSortHelperImages() + " images", "en"); // TODO count images in master folder instead?

        //        hasNote 0-1     langString  59  --- See note ---    "We would like to insert here a note about the uncertainty of the date provided in the ARCHE property ""hasDate"". Therefore, if the Goobi field ""DateOfOrigin"" presents square brackets (e.g. [1862]), then add acdh:hasNote ""Date is inferred.""@en, ""Datum ist abgeleitet.""@de
        //        If the Goobi field ""DateOfOrigin"" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote ""Date is inferred and uncertain.""@en, ""Datum abgeleitet und unsicher.""@de
        if (dateIsInferred && dateIsUncertain) {
            processResource.addProperty(model.createProperty(acdh, "hasNote"), "Date is inferred and uncertain.", "en");
            processResource.addProperty(model.createProperty(acdh, "hasNote"), "Datum abgeleitet und unsicher.", "de");
        } else if (dateIsInferred) {
            processResource.addProperty(model.createProperty(acdh, "hasNote"), "Date is inferred.", "en");
            processResource.addProperty(model.createProperty(acdh, "hasNote"), "Datum abgeleitet.", "de");
        } else if (dateIsUncertain) {
            processResource.addProperty(model.createProperty(acdh, "hasNote"), "Date is uncertain.", "en");
            processResource.addProperty(model.createProperty(acdh, "hasNote"), "Datum unsicher.", "de");
        }

        //        hasContact  0-n     Agent   71  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasContact property of the whole Project (if this property is added).

        //        hasDigitisingAgent  0-n     Agent   76  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasDigitisingAgent property of the whole Project (if this property is added).
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasMetadataCreator property of the whole Project (if this property is added).
        //        hasSubject  0-n     langString  94  --- See note ---    Add label of topStruct here, e.g. "Band"@de and "volume"@en for topStruct "Volume". English labels should preferably have small initial letters.
        //        hasOwner    1-n     Agent   110 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasOwner property of the whole Project (if this property is added).
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the Rights Holder property of the whole Project.
        //        hasLicensor 1-n     Agent   112 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasLicensor property of the whole Project (if this property is added).
        //        hasLicense  0-1     Concept 113 AccessLicense   Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/archelicenses/
        //        hasLicenseSummary   1   1   string  115 --- Will be automatically filled in.
        //        hasDate 0-n     date    130 PublicationYear
        //        relation    0-n     Thing   139 --- See note ---    Should be the URL of the object in the OBV catalog, e.g. https://permalink.obvsg.at/AC02277063 (it can be automatically created from CatalogIDDigital, I suppose)
        //        hasDepositor    1-n     Agent   170 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasDepositor property of the whole Project (if this property is added).
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        //        hasCurator  0-n     Agent   178 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasCurator property of the whole Project (if this property is added).
        //        hasHosting  1-n 1   Agent   179 --- Will be automatically filled in.

        // on file level
        String resourceIdentifier = collectionIdentifier + "meta.xml";

        String metadataIdentifier;

        boolean successful = true;
        // your logic goes here

        log.info("ArcheExport step plugin executed");
        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    public Resource createTopCollectionDocument(Model model, String topCollectionIdentifier, String api, String acdh, String publicationYear,
            String languageCode) {
        Resource projectResource = model.createResource(topCollectionIdentifier, model.createResource(acdh + "TopCollection"));
        //        hasTitle -> project name
        projectResource.addProperty(model.createProperty(acdh, "hasTitle"), project.getTitel(), languageCode);
        //        hasIdentifier -> topCollectionIdentifier
        projectResource.addProperty(model.createProperty(acdh, "hasIdentifier"), model.createResource(topCollectionIdentifier));
        //        hasPid  -> leave it free, will be generated by ARCHE

        //        hasUrl -> viewer root url https://viewer.acdh.oeaw.ac.at/viewer
        projectResource.addProperty(model.createProperty(acdh, "hasUrl"), model.createLiteral("https://viewer.acdh.oeaw.ac.at/viewer"));
        //        hasDescription -> project description (property, config, ...)
        projectResource.addProperty(model.createProperty(acdh, "hasDescription"),
                "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum.",
                languageCode);
        //        hasLifeCycleStatus -> project active: set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        if (project.getProjectIsArchived().booleanValue()) {
            projectResource.addProperty(model.createProperty(acdh, "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active"));
        } else {
            projectResource.addProperty(model.createProperty(acdh, "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));
        }
        //        hasUsedSoftware -> Goobi
        projectResource.addProperty(model.createProperty(acdh, "hasUsedSoftware"), model.createLiteral("Goobi"));
        //        hasContact -> config
        projectResource.addProperty(model.createProperty(acdh, "hasContact"), model.createResource(api + "TODO"));
        //        hasContributor -> config
        projectResource.addProperty(model.createProperty(acdh, "hasContributor"), model.createResource(api + "TODO"));
        //        hasDigitisingAgent -> config
        projectResource.addProperty(model.createProperty(acdh, "hasDigitisingAgent"), model.createResource(api + "TODO"));
        //        hasMetadataCreator -> config
        projectResource.addProperty(model.createProperty(acdh, "hasMetadataCreator"), model.createResource(api + "TODO"));
        //        hasRelatedDiscipline -> config, value from https://vocabs.acdh.oeaw.ac.at/oefosdisciplines/
        projectResource.addProperty(model.createProperty(acdh, "hasRelatedDiscipline"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/oefosdisciplines/102"));
        //        hasSubject -> config
        projectResource.addProperty(model.createProperty(acdh, "hasSubject"), "subject", "en");
        //        hasCoverageStartDate -> update if current process has an older PublicationYear than the submitted one, otherwise keep it//TODO
        projectResource.addProperty(model.createProperty(acdh, "hasCoverageStartDate"), "1900-01-01", XSDDatatype.XSDdate);
        //        hasCoverageEndDate -> update if current process has a newer PublicationYear than the submitted one, otherwise keep it
        projectResource.addProperty(model.createProperty(acdh, "hasCoverageStartDate"), "1900-01-01", XSDDatatype.XSDdate);
        //        hasOwner -> config
        projectResource.addProperty(model.createProperty(acdh, "hasOwner"), model.createResource(api + "TODO"));
        //        hasRightsHolder -> Project -> mets rights holder
        projectResource.addProperty(model.createProperty(acdh, "hasRightsHolder"), model.createResource(api + "TODO"));
        //        hasLicensor -> config
        projectResource.addProperty(model.createProperty(acdh, "hasLicensor"), model.createResource(api + "TODO"));
        //        hasLicense -> config, use values from https://vocabs.acdh.oeaw.ac.at/archelicenses/
        projectResource.addProperty(model.createProperty(acdh, "hasLicense"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archelicenses/publicdomain-1-0"));
        //        hasCreatedStartDate -> project start date as YYYY-MM-DD
        projectResource.addProperty(model.createProperty(acdh, "hasCreatedStartDate"), "2020-01-01", XSDDatatype.XSDdate);
        //        hasCreatedEndDate -> project end date as YYYY-MM-DD
        projectResource.addProperty(model.createProperty(acdh, "hasCreatedEndDate"), "2023-12-31", XSDDatatype.XSDdate);
        //        hasDepositor -> config
        projectResource.addProperty(model.createProperty(acdh, "hasDepositor"), model.createResource(api + "TODO"));
        //        hasAvailableDate -> leave it blank
        //        hasPid -> leave it blank
        //        hasHosting -> leave it blank
        //        hasCurator -> config
        projectResource.addProperty(model.createProperty(acdh, "hasCurator"), model.createResource(api + "TODO"));

        return projectResource;
    }
}
