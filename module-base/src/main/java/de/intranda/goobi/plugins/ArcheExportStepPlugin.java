package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
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

    private SubnodeConfiguration projectConfig;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
        project = process.getProjekt();
        // read parameters from correct block in configuration file
        projectConfig = ConfigPlugins.getProjectAndStepConfig(title, step);

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
        return null; //NOSONAR
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
        DocStruct logical = null;
        DocStruct anchor = null;
        try {
            Fileformat fileformat = process.readMetadataFile();
            DigitalDocument dd = fileformat.getDigitalDocument();
            logical = dd.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                anchor = logical;
                logical = logical.getAllChildren().get(0);
            }
        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        if (logical == null) {
            return PluginReturnValue.ERROR;
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
            }
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

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        // retrieve metadata on top level, update if it exists
        //        String downloadUrl = topCollectionIdentifier + "/metadata";
        //
        //        if (false) {
        //            String inputFileName = "";
        //            InputStream in = RDFDataMgr.open(inputFileName);
        //            model.read(in, null);
        //
        //            // update hasCoverageStartDate + hasCoverageEndDate
        //
        //            //        hasCoverageStartDate -> update if current process has an older PublicationYear than the submitted one, otherwise keep it//TODO
        //
        //        } else {
        // if not, create metadata on project level
        Resource topCollection = createTopCollectionDocument(model, topCollectionIdentifier, languageCode);

        //        }

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

        Resource processResource = model.createResource(collectionIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "Collection"));

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
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), mainTitle, languageCode);

        //        hasAlternativeTitle 0-n     langString  2   TitleDocMain + " : " + TitleDocSub1 Add " : " only if TitleDocSub1 is present. Remove any characters &lt; or &gt; present at the beginning, used for denoting articles (like <<Der>>)
        String altTitle;
        if (StringUtils.isNotBlank(subtitle)) {
            altTitle = maintitle + " : " + subtitle;
        } else {
            altTitle = maintitle;
        }
        altTitle = altTitle.replace("<<", "").replace(">>", "");
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasAlternativeTitle"), altTitle, languageCode);

        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"), model.createResource(collectionIdentifier));

        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        if (StringUtils.isNotBlank(handle)) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasPid"), handle, XSDDatatype.XSDanyURI);
        }

        //        hasNonLinkedIdentifier  0-n     string  4   CatalogIDDigital    e.g. "AC02277063"
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNonLinkedIdentifier"), id);

        //        hasNonLinkedIdentifier  0-n     string  4   shelfmarksource e.g. "R-III: WE 379"
        if (StringUtils.isNotBlank(shelfmark)) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNonLinkedIdentifier"), shelfmark);
        }

        //        hasNonLinkedIdentifier  0-n     string  4   {Goobi ID}  e.g. "9470"
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNonLinkedIdentifier"), String.valueOf(process.getId()));

        //        hasUrl  0-n     anyURI  30  --- See note ---    Should be the URL of the object in Goobi Viewer, e.g. https://viewer.acdh.oeaw.ac.at/viewer/image/AC02277063
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"), "https://viewer.acdh.oeaw.ac.at/viewer/image/" + id,
                XSDDatatype.XSDanyURI);

        //        hasDescription  0-n     langString  40  --- See note ---    We would need a description such as "A collection of scans from: https://permalink.obvsg.at/AC02277063", where the AC identifier of the original publication is given (retrievable from field "CatalogIDDigital"). Maybe this description can be automatically generated?
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDescription"),
                "A collection of scans from: https://permalink.obvsg.at/" + id,
                "en");

        //        hasLanguage 0-n     Concept 41  DocLanguage Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/iso6393/
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLanguage"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/" + language));

        //        hasLifeCycleStatus  0-1     Concept 42  --- See note ---    "If the process has not been marked as completed yet, set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active
        //        Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        //        But most of the Processes that will be imported into ARCHE should be more or less completed."
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));

        //        hasExtent   0-1     langString  46  --- See note ---    We would need a string such as "544 files", where the total numer of master images is computed.
        // TODO count images in master folder instead?
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasExtent"), process.getSortHelperImages() + " images", "en");

        //        hasNote 0-1     langString  59  --- See note ---    "We would like to insert here a note about the uncertainty of the date provided in the ARCHE property ""hasDate"". Therefore, if the Goobi field ""DateOfOrigin"" presents square brackets (e.g. [1862]), then add acdh:hasNote ""Date is inferred.""@en, ""Datum ist abgeleitet.""@de
        //        If the Goobi field ""DateOfOrigin"" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote ""Date is inferred and uncertain.""@en, ""Datum abgeleitet und unsicher.""@de
        if (dateIsInferred && dateIsUncertain) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Date is inferred and uncertain.", "en");
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Datum abgeleitet und unsicher.", "de");
        } else if (dateIsInferred) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Date is inferred.", "en");
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Datum abgeleitet.", "de");
        } else if (dateIsUncertain) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Date is uncertain.", "en");
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Datum unsicher.", "de");
        }

        //        hasContact  0-n     Agent   71  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasContact property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasContact", "contact");

        //        hasDigitisingAgent  0-n     Agent   76  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasDigitisingAgent property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasDigitisingAgent", "digitisingAgent");
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasMetadataCreator property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasMetadataCreator", "metadataCreator");
        //        hasOwner    1-n     Agent   110 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasOwner property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasOwner", "owner");
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the Rights Holder property of the whole Project.
        createAgent(model, processResource, "hasRightsHolder", "rightsHolder");
        //        hasLicensor 1-n     Agent   112 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasLicensor property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasLicensor", "licensor");
        //        hasDepositor    1-n     Agent   170 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasDepositor property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasDepositor", "depositor");
        //        hasCurator  0-n     Agent   178 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasCurator property of the whole Project (if this property is added).
        createAgent(model, processResource, "hasCurator", "curator");

        //        hasSubject  0-n     langString  94  --- See note ---    Add label of topStruct here, e.g. "Band"@de and "volume"@en for topStruct "Volume". English labels should preferably have small initial letters.
        for (Entry<String, String> l : logical.getType().getAllLanguages().entrySet()) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasSubject"), l.getValue(), l.getKey());
        }

        //        hasLicense  0-1     Concept 113 AccessLicense   Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/archelicenses/
        if (StringUtils.isNotBlank(license)) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLicense"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelicenses/publicdomain-1-0"));
        }

        //        hasDate 0-n     date    130 PublicationYear
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDate"), publicationyear, XSDDatatype.XSDdate);
        //        relation    0-n     Thing   139 --- See note ---    Should be the URL of the object in the OBV catalog, e.g. https://permalink.obvsg.at/AC02277063 (it can be automatically created from CatalogIDDigital, I suppose)
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "relation"),
                model.createResource("https://permalink.obvsg.at/" + id));
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        if (handle != null) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasPid"), handle, XSDDatatype.XSDanyURI);
        }

        // folder level, master, media, ocr
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_master" or "RIIIWE3793_media" or "RIIIWE3793_ocr".
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Inherit value from the containing Process
        //        hasOwner    1-n     Agent   110 --- See note ---    Inherit value from the containing Process
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Inherit value from the containing Process
        //        hasLicensor 1-n     Agent   112 --- See note ---    Inherit value from the containing Process
        //        hasLicense  0-1     Concept 113 --- See note ---    Inherit value from the containing Process
        //        hasLicenseSummary   1   1   string  115 --- Will be automatically filled in.
        //        isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793)
        //        hasDepositor    1-n     Agent   170 --- See note ---    Inherit value from the containing Process
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        //        hasCurator  0-n     Agent   178 --- See note ---    Inherit value from the containing Process
        //        hasHosting  1-n 1   Agent   179 --- Will be automatically filled in.
        //        hasDate 0-n     date    130 --- See note ---    Inherit value from the containing Process
        //        hasOaiSet   0-n     Concept 153 --- See note ---    "Add property to Goobi at the Process level. Values should comply with controlled vocabulary https://vocabs.acdh.oeaw.ac.at/archeoaisets/
        //        In the specific case of Woldan, ONLY instances of acdh:Collection containing the ""media"" images (e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3791/RIIIWE3791_media) will have the value https://vocabs.acdh.oeaw.ac.at/archeoaisets/kulturpool"

        // meta.xml, meta_anchor.xml
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_meta.xml" or "RIIIWE3793_meta_anchor.xml"
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_meta.xml or https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_meta_anchor.xml
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        //        hasCategory 1-n     Concept 47  --- See note ---    Set to https://vocabs.acdh.oeaw.ac.at/archecategory/dataset
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Inherit value from the containing Process
        //        hasOwner    1-n     Agent   110 --- See note ---    Inherit value from the containing Process
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Inherit value from the containing Process
        //        hasLicensor 1-n     Agent   112 --- See note ---    Inherit value from the containing Process
        //        hasLicense  1       Concept 113 --- See note ---    Inherit value from the containing Process
        //        isMetadataFor   0-n     ContainerOrResource 146 --- See note ---    "For ID_meta.xml, set to identifier of relative Collection (= Process), e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793, and identifier of related Publication, e.g. https://id.acdh.oeaw.ac.at/pub-AC02277063
        //        For ID_meta_anchor.xml, set to identifier of related overarching Publication"
        //        isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793)
        //        hasDepositor    1-n     Agent   170 --- See note ---    Inherit value from the containing Process
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        //        hasCurator  0-n     Agent   178 --- See note ---    Inherit value from the containing Process
        //        hasHosting  1-n 1   Agent   179 --- Will be automatically filled in.

        // for each file within folder
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasCategory 1-n     Concept 47  --- See note ---    "For images: set to https://vocabs.acdh.oeaw.ac.at/archecategory/image
        //        For XML ALTO: set to https://vocabs.acdh.oeaw.ac.at/archecategory/dataset"
        //        hasCurator  0-n     Agent   178 --- See note ---    Inherit value from the containing Process
        //        hasDepositor    1-n     Agent   170 --- See note ---    Inherit value from the containing Process
        //        hasHosting  1-n 1   Agent   179 --- Will be automatically filled in.
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master/RIIIWE3793_master_0001.tif (where the last segment of the URI corresponds to the relative filename / title of the Resource).
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        //        hasLicense  1       Concept 113 --- See note ---    Inherit value from the containing Process
        //        hasLicensor 1-n     Agent   112 --- See note ---    Inherit value from the containing Process
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Inherit value from the containing Process
        //        hasOwner    1-n     Agent   110 --- See note ---    Inherit value from the containing Process
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Inherit value from the containing Process
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_master_0001.tif" or "RIIIWE3793_media_0001.tif" or "RIIIWE3793_0001.xml" (for XML ALTO files)
        //        isPartOf    1-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master)
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice

        // topstruct
        //        hasTitle    1       langString  1   TitleDocMain + " : " + TitleDocSub1
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Build identifier according to form https://id.acdh.oeaw.ac.at/pub-AC02277063, where the last segment of the URI includes the AC identifier from "CatalogIDDigital", prefixed with "pub-"
        //        hasNonLinkedIdentifier  0-n     string  4   CatalogIDDigital    e.g. "AC02277063"
        //        hasNonLinkedIdentifier  0-n     string  4   shelfmarksource e.g. "R-III: WE 379"
        //        hasCity 0-n     langString  24  PlaceOfPublication  The object of this property should always be a string.
        //        hasUrl  0-n     anyURI  30  --- See note ---    Should be the URL of the object in Goobi Viewer, e.g. https://viewer.acdh.oeaw.ac.at/viewer/image/AC02277063
        //        hasDescription  0-n     langString  40  Note    I see that the values for Note are often separated in different strings. Maybe we need to concatenate them, or at least discuss how to best approach them.
        //        hasDescription  0-n     langString  40  --- See note ---    """We would like to insert here a note about the uncertainty of the date provided in the ARCHE property """"hasDate"""". Therefore, if the Goobi field """"DateOfOrigin"""" presents square brackets (e.g. [1862]), then add acdh:hasNote """"Date is inferred.""""@en, """"Datum ist abgeleitet.""""@de
        //        If the Goobi field """"DateOfOrigin"""" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote """"Date is inferred and uncertain.""""@en, """"Datum abgeleitet und unsicher.""""@de
        //        If possible, it would be nice to have something like a checkbox in the Goobi interface, where one can specifiy if a date is uncertain and/or inferred from external source."""
        //        hasLanguage 0-n     Concept 41  DocLanguage Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/iso6393/
        //        hasExtent   0-1     langString  46  SizeSourcePrint
        //        hasSeriesInformation    0-1     langString  58  CurrentNo
        //        hasNote 0-1     langString  59  OnTheContent
        //        hasAuthor   0-n     Agent   73  Creator Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasAuthor   0-n     Agent   73  Cartographer    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasAuthor   0-n     Agent   73  Artist  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasAuthor   0-n     Agent   73  Author  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasEditor   0-n     Agent   74  Editor  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  OtherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Lithographer    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Engraver    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Contributor Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Printer Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  PublisherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasPublisher    0-n     string  77  PublisherName
        //        hasIssuedDate   0-1     date    129 PublicationYear
        //        isSourceOf  0-n     ContainerOrReMe 148 --- See note ---    Add here the ARCHE identifier of the related Process, e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793
        //        isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    In case the Process includes an anchor publication, set the value to the identifier of the anchor publication, which can be taken from field "CatalogIDDigital" with attribute anchorId="true"
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasUpdatedDate  0-1 1   dateTime    187 --- Will be automatically filled in.
        //        aclRead 0-n 1   string  911 --- Will be automatically filled in.
        //        aclUpdate   0-n 1   string  912 --- Will be automatically filled in.
        //        aclWrite    0-n 1   string  913 --- Will be automatically filled in.
        //        createdBy   0-n 1   string  914 --- Will be automatically filled in.
        //        hasBinaryUpdatedRole    0-n 1   string  940 --- Will be automatically filled in.
        //        hasUpdatedRole  0-n 1   string  945 --- Will be automatically filled in.

        // anchor
        //        hasTitle    1       langString  1   TitleDocMain + " : " + TitleDocSub1
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Build identifier according to form https://id.acdh.oeaw.ac.at/pub-AC00915891, where the last segment of the URI includes the AC identifier from "CatalogIDDigital", prefixed with "pub-"
        //        hasNonLinkedIdentifier  0-n     string  4   CatalogIDDigital    e.g. "AC00915891"
        //        hasNonLinkedIdentifier  0-n     string  4   shelfmarksource e.g. "R-III: WE 379"
        //        hasCity 0-n     langString  24  PlaceOfPublication  The object of this property should always be a string.
        //        hasUrl  0-n     anyURI  30  --- See note ---    Should be the URL of the object in Goobi Viewer, e.g. https://viewer.acdh.oeaw.ac.at/viewer/toc/AC00915891
        //        hasDescription  0-n     langString  40  OnTheContent
        //        hasDescription  0-n     langString  40  --- See note ---    """We would like to insert here a note about the uncertainty of the date provided in the ARCHE property """"hasDate"""". Therefore, if the Goobi field """"DateOfOrigin"""" presents square brackets (e.g. [1862]), then add acdh:hasNote """"Date is inferred.""""@en, """"Datum ist abgeleitet.""""@de
        //        If the Goobi field """"DateOfOrigin"""" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote """"Date is inferred and uncertain.""""@en, """"Datum abgeleitet und unsicher.""""@de
        //        If possible, it would be nice to have something like a checkbox in the Goobi interface, where one can specifiy if a date is uncertain and/or inferred from external source."""
        //        hasLanguage 0-n     Concept 41  DocLanguage Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/iso6393/
        //        hasExtent   0-1     langString  46  SizeSourcePrint
        //        hasSeriesInformation    0-1     langString  58  CurrentNo
        //        hasNote 0-1     langString  59  Note
        //        hasAuthor   0-n     Agent   73  Author  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasAuthor   0-n     Agent   73  Creator Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasAuthor   0-n     Agent   73  Cartographer    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasAuthor   0-n     Agent   73  Artist  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasEditor   0-n     Agent   74  Editor  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Contributor Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  OtherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Lithographer    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Engraver    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  Printer Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasContributor  0-n     Agent   75  PublisherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
        //        hasPublisher    0-n     string  77  PublisherName
        //        hasIssuedDate   0-1     date    129 PublicationYear
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasUpdatedDate  0-1 1   dateTime    187 --- Will be automatically filled in.
        //        aclRead 0-n 1   string  911 --- Will be automatically filled in.
        //        aclUpdate   0-n 1   string  912 --- Will be automatically filled in.
        //        aclWrite    0-n 1   string  913 --- Will be automatically filled in.
        //        createdBy   0-n 1   string  914 --- Will be automatically filled in.
        //        hasBinaryUpdatedRole    0-n 1   string  940 --- Will be automatically filled in.
        //        hasUpdatedRole  0-n 1   string  945 --- Will be automatically filled in.

        try (OutputStream out = new FileOutputStream("/tmp/process.ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }

        boolean successful = true;
        // your logic goes here

        log.info("ArcheExport step plugin executed");
        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    /**
     * Check for a process property with the given name. If the property does not exist, use configured project value
     * 
     * @param model
     * @param acdh
     * @param processResource
     * @param propertyName
     * @param fieldName
     */

    private void createAgent(Model model, Resource processResource, String propertyName, String fieldName) {
        Processproperty p = null;
        for (Processproperty prop : process.getEigenschaften()) {
            if (fieldName.equalsIgnoreCase(prop.getTitel())) {
                p = prop;
                break;
            }
        }
        if (p != null) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), propertyName), model.createResource(p.getWert()));
        } else {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), propertyName),
                    model.createResource(projectConfig.getString("/project/" + fieldName)));
        }
    }

    public Resource createTopCollectionDocument(Model model, String topCollectionIdentifier, String languageCode) {
        Resource projectResource =
                model.createResource(topCollectionIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "TopCollection"));
        //        hasTitle -> project name
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), project.getTitel(), languageCode);
        //        hasIdentifier -> topCollectionIdentifier
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                model.createResource(topCollectionIdentifier));
        //        hasPid  -> leave it free

        //        hasUrl -> viewer root url https://viewer.acdh.oeaw.ac.at/viewer
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"), "https://viewer.acdh.oeaw.ac.at/viewer",
                XSDDatatype.XSDanyURI);
        //        hasDescription -> project description (property, config, ...)
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDescription"),
                projectConfig.getString("/project/description"), languageCode);
        //        hasLifeCycleStatus -> project active: set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        if (project.getProjectIsArchived().booleanValue()) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active"));
        } else {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));
        }
        //        hasUsedSoftware -> Goobi
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUsedSoftware"), "Goobi");
        //        hasContact -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContact"),
                model.createResource(projectConfig.getString("/project/contact")));
        //        hasContributor -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContributor"),
                model.createResource(projectConfig.getString("/project/contributor")));
        //        hasDigitisingAgent -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDigitisingAgent"),
                model.createResource(projectConfig.getString("/project/digitisingAgent")));
        //        hasMetadataCreator -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasMetadataCreator"),
                model.createResource(projectConfig.getString("/project/metadataCreator")));
        //        hasRelatedDiscipline -> config, value from https://vocabs.acdh.oeaw.ac.at/oefosdisciplines/
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasRelatedDiscipline"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/oefosdisciplines/102"));
        //        hasSubject -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasSubject"), projectConfig.getString("/project/subject"),
                "en");

        String query =
                "select min(value), max(value) from metadata where name = 'PublicationYear' and processid in (select ProzesseId from prozesse where ProjekteID = (Select projekteid from projekte where titel='"
                        + project.getTitel() + "')) group by name;";
        @SuppressWarnings("unchecked")
        List<Object> results = ProcessManager.runSQL(query);
        if (!results.isEmpty()) {
            Object[] row = (Object[]) results.get(0);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageStartDate"), String.valueOf(row[0]),
                    XSDDatatype.XSDdate);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageEndDate"), String.valueOf(row[1]),
                    XSDDatatype.XSDdate);
        }
        //        hasOwner -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasOwner"),
                model.createResource(projectConfig.getString("/project/owner")));
        //        hasRightsHolder -> Project -> mets rights holder
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasRightsHolder"),
                model.createResource(projectConfig.getString("/project/rightsHolder")));
        //        hasLicensor -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLicensor"),
                model.createResource(projectConfig.getString("/project/licensor")));
        //        hasLicense -> config, use values from https://vocabs.acdh.oeaw.ac.at/archelicenses/
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLicense"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archelicenses/publicdomain-1-0"));
        //        hasCreatedStartDate -> project start date as YYYY-MM-DD
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedStartDate"), "2020-01-01", XSDDatatype.XSDdate);
        //        hasCreatedEndDate -> project end date as YYYY-MM-DD
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedEndDate"), "2023-12-31", XSDDatatype.XSDdate);
        //        hasDepositor -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDepositor"),
                model.createResource(projectConfig.getString("/project/depositor")));
        //        hasCurator -> config
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCurator"),
                model.createResource(projectConfig.getString("/project/curator")));

        return projectResource;
    }
}
