package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Person;
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

    private String exportFolder;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
        project = process.getProjekt();
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        // generate subfolder for each process
        Path exportPath = Paths.get(config.getString("/exportFolder"), process.getTitel());
        try {
            Files.createDirectories(exportPath);
        } catch (IOException e) {
            log.error(e);
        }

        exportFolder = exportPath.toString();
        if (!exportFolder.endsWith("/")) {
            exportFolder = exportFolder + "/";
        }

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

        DocStruct logical = null;
        DocStruct anchor = null;
        Map<Path, List<Path>> files = process.getAllFolderAndFiles();
        Path masterFolder = null;
        Path mediaFolder = null;
        Path altoFolder = null;

        for (Path p : files.keySet()) {
            if (p.getFileName().toString().endsWith("_alto")) {
                altoFolder = p;
            } else if (p.getFileName().toString().contains("master")) {
                masterFolder = p;
            } else if (p.getFileName().toString().endsWith("_media")) {
                mediaFolder = p;
            }
        }
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
            // metadata not readable
            return PluginReturnValue.ERROR;
        }

        // master folder is missing or empty
        if (masterFolder == null) {
            Helper.setFehlerMeldung("Master image folder not found");
            log.error("Master image folder not found");
            return PluginReturnValue.ERROR;
        }
        if (mediaFolder == null) {
            Helper.setFehlerMeldung("Media image folder not found");
            log.error("Media image folder not found");
            return PluginReturnValue.ERROR;
        }

        String language = null;
        String id = null;
        for (Metadata md : logical.getAllMetadata()) {
            if ("DocLanguage".equals(md.getType().getName())) {
                language = md.getValue();
            } else if ("CatalogIDDigital".equals(md.getType().getName())) {
                id = md.getValue();
            }
        }

        String languageCode = "de";
        if ("eng".equals(language)) {
            languageCode = "en";
        }

        Model model = ModelFactory.createDefaultModel();
        // collection name
        String topCollectionIdentifier = IDENTIFIER_PREFIX + project.getTitel();

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");

        // process level
        String collectionIdentifier = topCollectionIdentifier + "/" + process.getTitel();

        model = ModelFactory.createDefaultModel();

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        Resource processResource = createCollectionResource(language, logical, files, masterFolder, languageCode, model, collectionIdentifier);

        try (OutputStream out = new FileOutputStream(exportFolder + process.getTitel() + ".ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }

        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        createFolderResource(model, masterFolder, collectionIdentifier, processResource);
        try (OutputStream out = new FileOutputStream(exportFolder + "masterFolder.ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }
        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);
        createFolderResource(model, mediaFolder, collectionIdentifier, processResource);
        try (OutputStream out = new FileOutputStream(exportFolder + "mediaFolder.ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }
        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);
        if (altoFolder != null) {
            createFolderResource(model, altoFolder, collectionIdentifier, processResource);
            try (OutputStream out = new FileOutputStream(exportFolder + "altoFolder.ttl")) {
                RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
            } catch (IOException e) {
                log.error(e);
            }
        }
        if (anchor != null) {
            model = ModelFactory.createDefaultModel();
            model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
            model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
            model.setNsPrefix("top", topCollectionIdentifier);
            createMetadata(anchor, model, collectionIdentifier, processResource);
            try (OutputStream out = new FileOutputStream(exportFolder + "meta_anchor.ttl")) {
                RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
            } catch (IOException e) {
                log.error(e);
            }
        }
        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);
        createMetadata(logical, model, collectionIdentifier, processResource);
        try (OutputStream out = new FileOutputStream(exportFolder + "meta.ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }

        // for each file within folder

        for (Entry<Path, List<Path>> entry : files.entrySet()) {
            for (Path file : entry.getValue()) {
                createFileResource(id, topCollectionIdentifier, collectionIdentifier, processResource, entry, file);
            }
        }

        // topstruct

        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        Resource anchorResource = null;
        if (anchor != null) {
            model = ModelFactory.createDefaultModel();
            model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
            model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
            model.setNsPrefix("top", topCollectionIdentifier);

            createPublicationResource(anchor, languageCode, model, collectionIdentifier, null);
            try (OutputStream out = new FileOutputStream(exportFolder + "publication_anchor.ttl")) {
                RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
            } catch (IOException e) {
                log.error(e);
            }
        }

        createPublicationResource(logical, languageCode, model, collectionIdentifier, anchorResource);
        try (OutputStream out = new FileOutputStream(exportFolder + "publication.ttl")) {
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

    private Resource createPublicationResource(DocStruct docstruct, String languageCode, Model model, String collectionIdentifier,
            Resource anchorResource) {
        Resource resource = model.createResource(collectionIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "Publication"));

        String mainTitle = null;
        String subTitle = null;
        for (Metadata md : docstruct.getAllMetadata()) {
            switch (md.getType().getName()) {
                case "TitleDocMain": {
                    mainTitle = md.getValue().replace("<<", "").replace(">>", "");
                    break;
                }
                case "TitleDocSub1":
                    subTitle = md.getValue();
                    break;
                default:
            }
        }
        String altTitle;
        if (StringUtils.isNotBlank(subTitle)) {
            altTitle = mainTitle + " : " + subTitle;
        } else {
            altTitle = mainTitle;
        }
        //        hasTitle    1       langString  1   TitleDocMain + " : " + TitleDocSub1
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), altTitle, languageCode);

        for (Metadata md : docstruct.getAllMetadata()) {
            switch (md.getType().getName()) {
                case "CatalogIDDigital":
                    String catalogIdDigital = md.getValue();
                    //        hasIdentifier   1-n     Thing   3   --- See note ---    Build identifier according to form https://id.acdh.oeaw.ac.at/pub-AC02277063, where the last segment of the URI includes the AC identifier from "CatalogIDDigital", prefixed with "pub-"
                    String pubId = IDENTIFIER_PREFIX + "/pub-" + catalogIdDigital;
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isMetadataFor"), model.createResource(pubId));
                    //        hasNonLinkedIdentifier  0-n     string  4   CatalogIDDigital    e.g. "AC02277063"
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNonLinkedIdentifier"), catalogIdDigital);
                    //        hasUrl  0-n     anyURI  30  --- See note ---    Should be the URL of the object in Goobi Viewer, e.g. https://viewer.acdh.oeaw.ac.at/viewer/image/AC02277063
                    if (docstruct.getType().isAnchor()) {
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"),
                                "https://viewer.acdh.oeaw.ac.at/viewer/toc/" + catalogIdDigital,
                                XSDDatatype.XSDanyURI);
                    } else {
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"),
                                "https://viewer.acdh.oeaw.ac.at/viewer/image/" + catalogIdDigital,
                                XSDDatatype.XSDanyURI);
                    }
                    break;
                case "shelfmarksource":
                    //        hasNonLinkedIdentifier  0-n     string  4   shelfmarksource e.g. "R-III: WE 379"
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNonLinkedIdentifier"), md.getValue());
                    break;
                case "PlaceOfPublication":
                    //        hasCity 0-n     langString  24  PlaceOfPublication  The object of this property should always be a string.
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCity"), md.getValue(), languageCode);
                    break;
                case "Note":
                    //        hasDescription  0-n     langString  40  Note    I see that the values for Note are often separated in different strings. Maybe we need to concatenate them, or at least discuss how to best approach them.
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDescription"), md.getValue(), languageCode);
                    break;

                case "PublicationYear":
                    //        hasIssuedDate   0-1     date    129 PublicationYear
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDate"), md.getValue(), XSDDatatype.XSDdate);
                    break;
                case "DateOfOrigin":
                    //        hasDescription  0-n     langString  40  --- See note ---    """We would like to insert here a note about the uncertainty of the date provided in the ARCHE property """"hasDate"""". Therefore, if the Goobi field """"DateOfOrigin"""" presents square brackets (e.g. [1862]), then add acdh:hasNote """"Date is inferred.""""@en, """"Datum ist abgeleitet.""""@de
                    //        If the Goobi field """"DateOfOrigin"""" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote """"Date is inferred and uncertain.""""@en, """"Datum abgeleitet und unsicher.""""@de
                    //        If possible, it would be nice to have something like a checkbox in the Goobi interface, where one can specifiy if a date is uncertain and/or inferred from external source."""
                    createDateNote(model, md.getValue(), resource);
                    break;
                case "DocLanguage":
                    //        hasLanguage 0-n     Concept 41  DocLanguage Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/iso6393/
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLanguage"),
                            model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/" + md.getValue()));
                    break;
                case "SizeSourcePrint":
                    //        hasExtent   0-1     langString  46  SizeSourcePrint
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasExtent"), md.getValue(), languageCode);
                    break;

                case "CurrentNo":
                    //        hasSeriesInformation    0-1     langString  58  CurrentNo
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasSeriesInformation"), md.getValue(), languageCode);
                    break;
                case "OnTheContent":
                    //        hasNote 0-1     langString  59  OnTheContent
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), md.getValue(), languageCode);
                    break;
                case "PublisherName":
                    //        hasPublisher    0-n     string  77  PublisherName
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasPublisher"), md.getValue());
                    break;

                default:
                    // ignore other metadata
            }
        }
        //       isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    In case the Process includes an anchor publication, set the value to the identifier of the anchor publication, which can be taken from field "CatalogIDDigital" with attribute anchorId="true"
        if (anchorResource != null) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"), anchorResource);
        }
        //        isSourceOf  0-n     ContainerOrReMe 148 --- See note ---    Add here the ARCHE identifier of the related Process, e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793
        if (docstruct.getAllPersons() != null) {
            for (Person p : docstruct.getAllPersons()) {
                switch (p.getType().getName()) {
                    case "Cartographer", "Artist", "Author":
                        //        hasAuthor   0-n     Agent   73  Cartographer    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasAuthor   0-n     Agent   73  Artist  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasAuthor   0-n     Agent   73  Author  Object of this property should be the URI of the corresponding Agent (Person or Organisation)

                        Resource person = createPerson(languageCode, model, p);
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasAuthor"), person);

                        break;

                    case "Editor":
                        //        hasEditor   0-n     Agent   74  Editor  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasEditor"), createPerson(languageCode, model, p));
                        break;
                    case "OtherPerson", "Lithographer", "Engraver", "Contributor", "Printer", "PublisherPerson":
                        //        hasContributor  0-n     Agent   75  OtherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Lithographer  curator  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Engraver    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Contributor Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Printer Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  PublisherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContributor"),
                                createPerson(languageCode, model, p));
                        break;
                    default:
                        // ignore other roles
                }
            }
        }

        if (docstruct.getAllCorporates() != null) {
            for (Corporate c : docstruct.getAllCorporates()) {
                switch (c.getType().getName()) {
                    case "CorporateArtist":
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasAuthor"),
                                createOrganisation(languageCode, model, c));
                        break;
                    case "CorporateEditor":
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasEditor"),
                                createOrganisation(languageCode, model, c));
                        break;
                    case "CorporateOther", "CorporateEngraver", "CorporateContributor":
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContributor"),
                                createOrganisation(languageCode, model, c));
                        break;
                    default:
                        // ignore other roles
                }
            }
        }
        return resource;
    }

    private Resource createOrganisation(String languageCode, Model model, Corporate c) {
        Resource person = model.createResource();
        String name = c.getMainName();

        // hasTitle    1       langString  1   firstName + lastName    We cannot use the displayName because we prefer the direct form in ARCHE (i.e., "Friedrich Würthle" instead of "Würthle, Friedrich"). Therefore, it would be better to just have a concatenation of first and last name.
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), name, languageCode);

        if (StringUtils.isNotBlank(c.getAuthorityValue())) {

            //                        hasIdentifier   1-n     Thing   3   authorityURI + authorityValue
            if (StringUtils.isNotBlank(c.getAuthorityURI())) {
                person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                        model.createResource(c.getAuthorityURI() + c.getAuthorityValue()));
            } else {
                person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                        model.createResource(c.getAuthorityValue()));
            }
        }
        return person;
    }

    private Resource createPerson(String languageCode, Model model, Person p) {
        Resource person = model.createResource();
        String lastName = p.getLastname();
        String firstName = p.getFirstname();
        String displayName;
        if (StringUtils.isNotBlank(firstName) && StringUtils.isNotBlank(lastName)) {
            displayName = firstName + " " + lastName;
        } else if ((StringUtils.isNotBlank(firstName))) {
            displayName = firstName;
        } else {
            displayName = lastName;
        }

        // hasTitle    1       langString  1   firstName + lastName    We cannot use the displayName because we prefer the direct form in ARCHE (i.e., "Friedrich Würthle" instead of "Würthle, Friedrich"). Therefore, it would be better to just have a concatenation of first and last name.
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), displayName, languageCode);
        // hasFirstName    0-n     langString  11  firstName
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasFirstName"), firstName, languageCode);
        // hasLastName 0-n     langString  12  lastName
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLastName"), lastName, languageCode);
        if (StringUtils.isNotBlank(p.getAuthorityValue())) {

            //                        hasIdentifier   1-n     Thing   3   authorityURI + authorityValue
            if (StringUtils.isNotBlank(p.getAuthorityURI())) {
                person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                        model.createResource(p.getAuthorityURI() + p.getAuthorityValue()));
            } else {
                person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                        model.createResource(p.getAuthorityValue()));
            }
        }
        return person;
    }

    private void createFileResource(String id, String topCollectionIdentifier, String collectionIdentifier, Resource processResource,
            Entry<Path, List<Path>> entry, Path file) {
        Model model;
        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);
        String folderName = entry.getKey().getFileName().toString();
        String filename = file.getFileName().toString();
        Resource resource = model.createResource(id, model.createResource(model.getNsPrefixURI("acdh") + "Resources"));
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasCategory 1-n     Concept 47  --- See note ---    "For images: set to https://vocabs.acdh.oeaw.ac.at/archecategory/image
        //        For XML ALTO: set to https://vocabs.acdh.oeaw.ac.at/archecategory/dataset"
        if (filename.endsWith(".xml")) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCategory"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archecategory/dataset"));
        } else {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCategory"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archecategory/image"));
        }
        //        hasCurator  0-n     Agent   178 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasCurator");
        //        hasDepositor    1-n     Agent   170 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasDepositor");
        //        hasHosting  1-n 1   Agent   179 --- Will be automatically filled in.
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master/RIIIWE3793_master_0001.tif (where the last segment of the URI corresponds to the relative filename / title of the Resource).
        String fileId = collectionIdentifier + "/" + folderName + "/" + filename;
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"), model.createResource(fileId));
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        // TODO check for handles on image level, leave it blank if missing

        //        hasLicense  1       Concept 113 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasLicense");
        //        hasLicensor 1-n     Agent   112 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasLicensor");
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasMetadataCreator");
        //        hasOwner    1-n     Agent   110 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasOwner");
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasRightsHolder");
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_master_0001.tif" or "RIIIWE3793_media_0001.tif" or "RIIIWE3793_0001.xml" (for XML ALTO files)
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), filename, "en");
        //        isPartOf    1-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master)
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"),
                model.createResource(collectionIdentifier + "/" + folderName));

        try (OutputStream out = new FileOutputStream(exportFolder + folderName + "_" + filename + ".ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }
    }

    private Resource createCollectionResource(String language,
            DocStruct logical, Map<Path, List<Path>> files, Path masterFolder,
            String languageCode, Model model, String collectionIdentifier) {

        String sortTitle = null;
        String orderNumber = null;
        String maintitle = null;
        String subtitle = null;
        String id = null;
        String shelfmark = null;
        String license = null;
        String publicationyear = null;
        String handle = null;
        String dateOfOrigin = null;
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
        if (dateOfOrigin == null && StringUtils.isNotBlank(publicationyear)) {
            dateOfOrigin = publicationyear;
        }
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
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasExtent"), files.get(masterFolder).size() + " images",
                "en");

        //        hasNote 0-1     langString  59  --- See note ---    "We would like to insert here a note about the uncertainty of the date provided in the ARCHE property ""hasDate"". Therefore, if the Goobi field ""DateOfOrigin"" presents square brackets (e.g. [1862]), then add acdh:hasNote ""Date is inferred.""@en, ""Datum ist abgeleitet.""@de
        //        If the Goobi field ""DateOfOrigin"" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote ""Date is inferred and uncertain.""@en, ""Datum abgeleitet und unsicher.""@de

        createDateNote(model, dateOfOrigin, processResource);

        //        hasContact  0-n     Agent   71  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasContact property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasContact", "contact");

        //        hasDigitisingAgent  0-n     Agent   76  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasDigitisingAgent property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasDigitisingAgent", "digitisingAgent");
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasMetadataCreator property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasMetadataCreator", "metadataCreator");
        //        hasOwner    1-n     Agent   110 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasOwner property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasOwner", "owner");
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the Rights Holder property of the whole Project.
        createPropertyInResource(model, processResource, "hasRightsHolder", "rightsHolder");
        //        hasLicensor 1-n     Agent   112 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasLicensor property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasLicensor", "licensor");
        //        hasDepositor    1-n     Agent   170 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasDepositor property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasDepositor", "depositor");
        //        hasCurator  0-n     Agent   178 --- See note ---    Add property to Goobi at the Process level. If the property is not filled in, the value can be copied from the acdh:hasCurator property of the whole Project (if this property is added).
        createPropertyInResource(model, processResource, "hasCurator", "curator");

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

        return processResource;
    }

    private void createDateNote(Model model, String dateOfOrigin, Resource resource) {
        boolean dateIsInferred = false;
        boolean dateIsUncertain = false;
        if (dateOfOrigin != null) {
            if (dateOfOrigin.contains("?")) {
                dateIsUncertain = true;
            }
            if (dateOfOrigin.contains("[")) {
                dateIsInferred = true;
            }
        }

        if (dateIsInferred && dateIsUncertain) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Date is inferred and uncertain.", "en");
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Datum abgeleitet und unsicher.", "de");
        } else if (dateIsInferred) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Date is inferred.", "en");
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Datum abgeleitet.", "de");
        } else if (dateIsUncertain) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Date is uncertain.", "en");
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNote"), "Datum unsicher.", "de");
        }
    }

    private Resource createMetadata(DocStruct docstruct, Model model, String collectionIdentifier, Resource processResource) {

        String metadataId = null;

        if (docstruct.getType().isAnchor()) {
            metadataId = collectionIdentifier + "/" + process.getTitel() + "_meta_anchor.xml";
        } else {
            metadataId = collectionIdentifier + "/" + process.getTitel() + "_meta.xml";
        }
        Resource metaResource = model.createResource(metadataId, model.createResource(model.getNsPrefixURI("acdh") + "Metadata"));

        // meta.xml, meta_anchor.xml
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_meta.xml" or "RIIIWE3793_meta_anchor.xml"
        metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), process.getTitel() + "_meta_ancor.xml", "en");
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_meta.xml or https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_meta_anchor.xml
        metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"), model.createResource(metadataId));
        //        hasPid  0-n     anyURI  172 --- See note ---    Use as object the Handle reserved through the GWDG PID-Webservice
        for (Metadata md : docstruct.getAllMetadata()) {
            if ("Handle".equals(md.getType().getName())) {
                metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasPid"),
                        md.getValue(),
                        XSDDatatype.XSDanyURI);
                break;
            }
        }
        //        hasCategory 1-n     Concept 47  --- See note ---    Set to https://vocabs.acdh.oeaw.ac.at/archecategory/dataset
        metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCategory"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archecategory/dataset"));
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasMetadataCreator");
        //        hasOwner    1-n     Agent   110 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasOwner");
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasRightsHolder");
        //        hasLicensor 1-n     Agent   112 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasLicensor");
        //        hasLicense  1       Concept 113 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasLicense");
        //        isMetadataFor   0-n     ContainerOrResource 146 --- See note ---
        // "For ID_meta.xml, set to identifier of relative Collection (= Process), e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793, and identifier of
        //related Publication, e.g. https://id.acdh.oeaw.ac.at/pub-AC02277063
        // For ID_meta_anchor.xml, set to identifier of related overarching Publication"
        String catalogIdDigital = "";
        for (Metadata md : docstruct.getAllMetadata()) {
            if ("CatalogIDDigital".equals(md.getType().getName())) {
                catalogIdDigital = md.getValue();
            }
        }
        String pubId = IDENTIFIER_PREFIX + "/pub-" + catalogIdDigital;
        metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isMetadataFor"), model.createResource(pubId));
        if (docstruct.getType().isTopmost()) {
            metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isMetadataFor"), model.createResource(collectionIdentifier));
        }
        //        isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793)
        metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"), model.createResource(collectionIdentifier));
        //        hasDepositor    1-n     Agent   170 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasDepositor");
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasCurator  0-n     Agent   178 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, metaResource, "hasCurator");

        return metaResource;
    }

    private void createFolderResource(Model model, Path folder, String collectionIdentifier, Resource processResource) {
        String folderName = folder.getFileName().toString();
        String id = collectionIdentifier + "/" + folderName;
        Resource resource = model.createResource(id, model.createResource(model.getNsPrefixURI("acdh") + "Collections"));

        // folder level, master, media, ocr
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_master" or "RIIIWE3793_media" or "RIIIWE3793_ocr".
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), folder.getFileName().toString(), "en");
        //        hasIdentifier   1-n     Thing   3   --- See note ---    Use identifier in the form https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"), model.createResource(id));
        //        hasMetadataCreator  1-n     Agent   80  --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasMetadataCreator");
        //        hasOwner    1-n     Agent   110 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasOwner");
        //        hasRightsHolder 1-n     Agent   111 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasRightsHolder");
        //        hasLicensor 1-n     Agent   112 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasLicensor");
        //        hasLicense  0-1     Concept 113 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasLicense");
        //        isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793)
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"), model.createResource(collectionIdentifier));
        //        hasDepositor    1-n     Agent   170 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasDepositor");
        //        hasCurator  0-n     Agent   178 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasCurator");
        //        hasDate 0-n     date    130 --- See note ---    Inherit value from the containing Process
        inheritValue(model, processResource, resource, "hasDate");
        //        hasOaiSet   0-n     Concept 153 --- See note ---    "Add property to Goobi at the Process level. Values should comply with controlled vocabulary https://vocabs.acdh.oeaw.ac.at/archeoaisets/
        if (collectionIdentifier.contains("Woldan") && folder.getFileName().toString().endsWith("media")
                && !folder.getFileName().toString().contains("master")) {
            //        In the specific case of Woldan, ONLY instances of acdh:Collection containing the ""media"" images (e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3791/RIIIWE3791_media)
            // will have the value https://vocabs.acdh.oeaw.ac.at/archeoaisets/kulturpool"
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasOaiSet"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archeoaisets/kulturpool"));
        } else {
            createPropertyInResource(model, processResource, "hasOaiSet", "OAISet");
        }

    }

    private void inheritValue(Model model, Resource processResource, Resource resource, String localName) {
        StmtIterator it = processResource.listProperties();
        while (it.hasNext()) {
            Statement p = it.next();
            if (localName.equals(p.getPredicate().getLocalName())) {
                resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), localName), p.getObject());
            }
        }
    }

    /**
     * Check for a process property with the given name. If the property does not exist, use configured project value
     * 
     * @param model
     * @param acdh
     * @param processResource
     * @param propertyName
     * @param processPropertyName
     */

    private void createPropertyInResource(Model model, Resource processResource, String propertyName, String processPropertyName) {
        GoobiProperty p = null;

        for (GoobiProperty prop : process.getEigenschaften()) {
            if (processPropertyName.equalsIgnoreCase(prop.getPropertyName())) {
                p = prop;
                break;
            }
        }
        if (p == null) {
            for (GoobiProperty prop : project.getProperties()) {
                if (processPropertyName.equalsIgnoreCase(prop.getPropertyName())) {
                    p = prop;
                    break;
                }
            }
        }

        if (p != null) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), propertyName), model.createResource(p.getPropertyValue()));
        }
    }

}
