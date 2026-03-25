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

package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.goobi.api.ArcheConfiguration;
import org.goobi.api.rest.ArcheAPI;
import org.goobi.api.rest.TransactionInfo;
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
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.SwapException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Person;
import ugh.exceptions.DocStructHasNoTypeException;
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
    private boolean exportFolderEnabled;

    private ArcheConfiguration archeConfiguration;

    private Map<String, String> languageCodes;

    private Map<String, String> licenseMapping;

    private DecimalFormat counterFormat = new DecimalFormat("0000");

    private Map<String, String> doctypes;

    private String viewerUrl;
    private String permalinkUrl;
    private List<MetadataFieldMapping> metadataMappings;
    private List<String[]> propertyMappings;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
        project = process.getProjekt();

        archeConfiguration = new ArcheConfiguration("intranda_administration_arche_project_export");

        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        languageCodes = new HashMap<>();
        for (HierarchicalConfiguration hc : config.configurationsAt("/language/code")) {
            languageCodes.put(hc.getString("@iso639-2"), hc.getString("@iso639-1"));
        }

        licenseMapping = new HashMap<>();
        for (HierarchicalConfiguration hc : config.configurationsAt("/licenses/license")) {
            licenseMapping.put(hc.getString("@internalName"), hc.getString("@archeField"));
        }

        doctypes = new HashMap<>();
        for (HierarchicalConfiguration hc : config.configurationsAt("/tags/tag")) {
            doctypes.put(hc.getString("@doctype"), hc.getString("@code"));
        }

        viewerUrl = config.getString("/viewerUrl", "https://viewer.acdh.oeaw.ac.at/viewer");
        if (viewerUrl.endsWith("/")) {
            viewerUrl = viewerUrl.substring(0, viewerUrl.length() - 1);
        }
        permalinkUrl = config.getString("/permalinkUrl", "https://permalink.obvsg.at/");
        if (!permalinkUrl.endsWith("/")) {
            permalinkUrl = permalinkUrl + "/";
        }

        metadataMappings = new ArrayList<>();
        for (HierarchicalConfiguration hc : config.configurationsAt("/metadataMappings/metadataMapping")) {
            metadataMappings.add(new MetadataFieldMapping(
                    hc.getString("@metadataName"),
                    hc.getString("@archeField"),
                    hc.getString("@language", "NO_LANGUAGE")));
        }

        propertyMappings = new ArrayList<>();
        for (HierarchicalConfiguration hc : config.configurationsAt("/propertyMappings/propertyMapping")) {
            propertyMappings.add(new String[] {
                    hc.getString("@goobiProperty"),
                    hc.getString("@archeField") });
        }

        String destination = config.getString("/exportFolder");
        // prepare export folder, if enabled
        if (StringUtils.isNotBlank(destination)) {
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
            exportFolderEnabled = true;
        } else {
            exportFolder = null;
            exportFolderEnabled = false;
        }
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
        Fileformat fileformat = null;
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

        try {
            fileformat = process.readMetadataFile();
            DigitalDocument dd = fileformat.getDigitalDocument();
            logical = dd.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                anchor = logical;
                logical = logical.getAllChildren().get(0);
            }
            int sizeInFolder = files.get(mediaFolder).size();
            int paginationSize = dd.getPhysicalDocStruct().getAllChildren().size();
            //  pagination length = number of files in media folder
            if (sizeInFolder != paginationSize) {
                Helper.setFehlerMeldung("Image size in file system and metadata file differs.");
                log.error("Image size in file system and metadata file differs.");
                return PluginReturnValue.ERROR;
            }

        } catch (UGHException | IOException | SwapException | DocStructHasNoTypeException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        if (logical == null) {
            // metadata not readable
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
        if (language == null) {
            language = "und";
        }

        String docTypeCode = doctypes.get(logical.getType().getName());
        if (StringUtils.isBlank(docTypeCode)) {
            docTypeCode = "TEXT";
        }
        // read metadata default language from project property
        String languagePropertyName = archeConfiguration.getConfig().getString("/project/languagePropertyName", "DefaultProjectLanguage");
        String metadataDefaultLanguage = "und"; // default language, if nothing else was defined
        for (GoobiProperty gp : project.getProperties()) {
            if (languagePropertyName.equals(gp.getPropertyName())) {
                if (StringUtils.isNotBlank(gp.getPropertyValue())) {
                    metadataDefaultLanguage = gp.getPropertyValue();
                }
                break;
            }
        }

        // get language codes from configuration file
        String languageCode = languageCodes.get(language);

        // top collection name
        String topCollectionIdentifier = null;
        if (StringUtils.isNotBlank(project.getProjectIdentifier())) {
            topCollectionIdentifier = IDENTIFIER_PREFIX + project.getProjectIdentifier().replace(" ", "_");
        } else {
            topCollectionIdentifier = IDENTIFIER_PREFIX + archeConfiguration.getArcheApiUrl().replace(" ", "_");
        }

        // process level
        String collectionIdentifier = topCollectionIdentifier + "/" + process.getTitel();

        Model model = ModelFactory.createDefaultModel();

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        Resource processResource =
                createCollectionResource(language, logical, files, masterFolder, languageCode, model, topCollectionIdentifier, collectionIdentifier,
                        collectionIdentifier);
        String filename = createImageFilename(process.getTitel() + "_master", 1,
                FilenameUtils.getExtension(files.get(masterFolder).get(0).getFileName().toString()));
        Resource masterFolderResource = createFolderResource(model, process.getTitel() + "_master", collectionIdentifier, processResource,
                filename, false, docTypeCode);

        filename = createImageFilename(process.getTitel() + "_media", 1,
                FilenameUtils.getExtension(files.get(mediaFolder).get(0).getFileName().toString()));
        Resource mediaFolderResource = createFolderResource(model, process.getTitel() + "_media", collectionIdentifier, processResource,
                filename, false, docTypeCode);

        Resource altoFolderResource = null;
        if (altoFolder != null) {
            filename = createImageFilename(process.getTitel() + "_ocr", 1,
                    FilenameUtils.getExtension(files.get(altoFolder).get(0).getFileName().toString()));
            altoFolderResource = createFolderResource(model, process.getTitel() + "_ocr", collectionIdentifier, processResource,
                    filename, false, docTypeCode);
        }

        Resource metaAnchorResource = null;
        if (anchor != null) {
            metaAnchorResource = createMetadata(anchor, model, collectionIdentifier, processResource, false);
        }
        Resource metaResource = createMetadata(logical, model, collectionIdentifier, processResource, false);

        List<Resource> anchorMetsResources = null;
        String anchorUri = null;
        if (anchor != null) {
            anchorMetsResources = createPublicationResource(anchor, languageCode, model, collectionIdentifier, null, null, metadataDefaultLanguage);
            anchorUri = anchorMetsResources.get(0).getProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isMetadataFor")).getString();

        }

        // topstruct
        List<Resource> metsResources = createPublicationResource(logical, languageCode, model, collectionIdentifier,
                anchorUri, null, metadataDefaultLanguage);

        if (exportFolderEnabled) {

            // folder
            Model union1 = ModelFactory.createUnion(processResource.getModel(), masterFolderResource.getModel());
            Model union = null;
            if (altoFolderResource != null) {
                Model union2 = ModelFactory.createUnion(mediaFolderResource.getModel(), altoFolderResource.getModel());
                union = ModelFactory.createUnion(union1, union2);
            } else {
                union = ModelFactory.createUnion(union1, mediaFolderResource.getModel());
            }

            // internal meta.xml
            if (metaAnchorResource != null) {
                Model union2 = ModelFactory.createUnion(metaAnchorResource.getModel(), metaResource.getModel());
                union = ModelFactory.createUnion(union, union2);
            } else {
                union = ModelFactory.createUnion(union, metaResource.getModel());
            }

            // mets files
            if (anchorMetsResources != null) {
                for (Resource mets : anchorMetsResources) {
                    union = ModelFactory.createUnion(union, mets.getModel());
                }
            }
            for (Resource mets : metsResources) {
                union = ModelFactory.createUnion(union, mets.getModel());
            }

            // files
            List<Path> fileList = files.get(masterFolder);

            Path masterDestination = Paths.get(exportFolder, process.getTitel() + "_master");
            if (!StorageProvider.getInstance().isFileExists(masterDestination)) {
                try {
                    StorageProvider.getInstance().createDirectories(masterDestination);
                } catch (IOException e) {
                    log.error(e);
                }
            }

            for (int i = 0; i < fileList.size(); i++) {
                Path current = fileList.get(i);
                Path next = null;
                if (i + 1 < fileList.size()) {
                    next = fileList.get(i + 1);
                }

                String currentFilename =
                        createImageFilename(process.getTitel() + "_master", i + 1, FilenameUtils.getExtension(current.getFileName().toString()));

                String nextFilename = null;
                if (next != null) {
                    nextFilename =
                            createImageFilename(process.getTitel() + "_master", i + 2, FilenameUtils.getExtension(next.getFileName().toString()));
                }

                Resource fileResource = createFileResource(id, topCollectionIdentifier, collectionIdentifier, processResource,
                        process.getTitel() + "_master", currentFilename, nextFilename, false);
                union = ModelFactory.createUnion(union, fileResource.getModel());

                // copy file to destination
                try {
                    StorageProvider.getInstance().copyFile(current, Paths.get(masterDestination.toString(), currentFilename));
                } catch (IOException e) {
                    log.error(e);
                }
            }

            Path mediaDestination = Paths.get(exportFolder, process.getTitel() + "_media");
            if (!StorageProvider.getInstance().isFileExists(mediaDestination)) {
                try {
                    StorageProvider.getInstance().createDirectories(mediaDestination);
                } catch (IOException e) {
                    log.error(e);
                }
            }

            fileList = files.get(mediaFolder);
            for (int i = 0; i < fileList.size(); i++) {
                Path current = fileList.get(i);
                Path next = null;
                if (i + 1 < fileList.size()) {
                    next = fileList.get(i + 1);
                }

                String currentFilename =
                        createImageFilename(process.getTitel() + "_media", i + 1, FilenameUtils.getExtension(current.getFileName().toString()));

                String nextFilename = null;
                if (next != null) {
                    nextFilename =
                            createImageFilename(process.getTitel() + "_media", i + 2, FilenameUtils.getExtension(next.getFileName().toString()));
                }
                Resource fileResource = createFileResource(id, topCollectionIdentifier, collectionIdentifier, processResource,
                        process.getTitel() + "_media", currentFilename, nextFilename, false);
                union = ModelFactory.createUnion(union, fileResource.getModel());

                // copy file to destination
                try {
                    StorageProvider.getInstance().copyFile(current, Paths.get(mediaDestination.toString(), currentFilename));
                } catch (IOException e) {
                    log.error(e);
                }
            }

            if (altoFolder != null) {

                Path altoDestination = Paths.get(exportFolder, process.getTitel() + "_ocr");
                if (!StorageProvider.getInstance().isFileExists(altoDestination)) {
                    try {
                        StorageProvider.getInstance().createDirectories(altoDestination);
                    } catch (IOException e) {
                        log.error(e);
                    }
                }
                fileList = files.get(altoFolder);
                for (int i = 0; i < fileList.size(); i++) {
                    Path current = fileList.get(i);
                    Path next = null;
                    if (i + 1 < fileList.size()) {
                        next = fileList.get(i + 1);
                    }
                    String currentFilename =
                            createImageFilename(process.getTitel() + "_ocr", i + 1, FilenameUtils.getExtension(current.getFileName().toString()));

                    String nextFilename = null;
                    if (next != null) {
                        nextFilename =
                                createImageFilename(process.getTitel() + "_ocr", i + 2, FilenameUtils.getExtension(next.getFileName().toString()));
                    }
                    Resource fileResource = createFileResource(id, topCollectionIdentifier, collectionIdentifier, processResource,
                            process.getTitel() + "_ocr", currentFilename, nextFilename, false);
                    union = ModelFactory.createUnion(union, fileResource.getModel());

                    // copy file to destination
                    try {
                        StorageProvider.getInstance().copyFile(current, Paths.get(altoDestination.toString(), currentFilename));
                    } catch (IOException e) {
                        log.error(e);
                    }
                }
            }

            try (OutputStream out = new FileOutputStream(exportFolder + process.getTitel() + ".ttl")) {
                RDFDataMgr.write(out, union, RDFFormat.TURTLE_PRETTY);
            } catch (IOException e) {
                log.error(e);
            }

            // meta.xml, meta_anchor.xml
            try {
                Path metaDestination = Paths.get(exportFolder, process.getTitel() + "_meta.xml");
                StorageProvider.getInstance().copyFile(Paths.get(process.getMetadataFilePath()), metaDestination);

                Path anchorSource = Paths.get(process.getMetadataFilePath().replace("meta.xml", "meta_anchor.xml"));
                if (StorageProvider.getInstance().isFileExists(anchorSource)) {
                    Path anchorDestination = Paths.get(exportFolder, process.getTitel() + "_meta_anchor.xml");
                    StorageProvider.getInstance().copyFile(anchorSource, anchorDestination);
                }

            } catch (IOException | SwapException e) {
                log.error(e);
            }
        }

        if (archeConfiguration.isEnableArcheIngestValidation() || archeConfiguration.isEnableArcheIngestData()) {
            Path metaFile = null;
            Path metaAnchorFile = null;
            try {
                metaFile = Paths.get(process.getMetadataFilePath());
                metaAnchorFile = Paths.get(process.getMetadataFilePath().replace(".xml", "_anchor.xml"));

            } catch (IOException | SwapException e) {
                log.error(e);
            }

            try (Client client =
                    ArcheAPI.getClient(archeConfiguration.getArcheUserName(), archeConfiguration.getArchePassword())) {
                TransactionInfo ti = ArcheAPI.startTransaction(client, archeConfiguration.getArcheApiUrl());
                model = resetModel(topCollectionIdentifier);
                Resource validationResource = createCollectionResource(language, logical,
                        files, masterFolder, languageCode, model, topCollectionIdentifier, collectionIdentifier,
                        archeConfiguration.getArcheApiUrl());
                model = resetModel(topCollectionIdentifier);

                boolean success = true;

                String location = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, validationResource);
                if (location == null) {
                    // ingest failed, abort
                    return PluginReturnValue.ERROR;
                }

                // ingest publication resources
                if (anchor != null) {
                    model = resetModel(topCollectionIdentifier);
                    anchorMetsResources =
                            createPublicationResource(anchor, languageCode, model, collectionIdentifier, null, archeConfiguration.getArcheApiUrl(),
                                    metadataDefaultLanguage);
                    for (Resource r : anchorMetsResources) {
                        location = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, r);
                        if (location == null) {
                            // ingest failed, abort
                            return PluginReturnValue.ERROR;
                        }
                    }
                }
                model = resetModel(topCollectionIdentifier);
                // topstruct
                metsResources = createPublicationResource(logical, languageCode, model, collectionIdentifier,
                        anchorUri, archeConfiguration.getArcheApiUrl(), metadataDefaultLanguage);
                for (Resource r : metsResources) {
                    location = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, r);
                    if (location == null) {
                        // ingest failed, abort
                        return PluginReturnValue.ERROR;
                    }
                }

                if (archeConfiguration.isEnableArcheIngestData()) {
                    // upload meta_anchor.xml
                    if (metaAnchorResource != null) {
                        model = resetModel(topCollectionIdentifier);
                        metaAnchorResource = createMetadata(anchor, model, collectionIdentifier, processResource, true);
                        location = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, metaAnchorResource);
                        if (location == null) {
                            // ingest failed, abort
                            return PluginReturnValue.ERROR;
                        }
                        success = ArcheAPI.uploadBinary(client, location, ti, metaAnchorFile);
                        if (!success) {
                            // file upload failed, abort
                            return PluginReturnValue.ERROR;
                        }
                    }
                    model = resetModel(topCollectionIdentifier);
                    metaResource = createMetadata(logical, model, collectionIdentifier, processResource, true);
                    location = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, metaResource);
                    if (location == null) {
                        // ingest failed, abort
                        return PluginReturnValue.ERROR;
                    }
                    success = ArcheAPI.uploadBinary(client, location, ti, metaFile);
                    if (!success) {
                        // file upload failed, abort
                        return PluginReturnValue.ERROR;
                    }

                    model = resetModel(topCollectionIdentifier);
                    filename = createImageFilename(process.getTitel() + "_master", 1,
                            FilenameUtils.getExtension(files.get(masterFolder).get(0).getFileName().toString()));
                    masterFolderResource = createFolderResource(model, process.getTitel() + "_master", collectionIdentifier, processResource,
                            filename, true, docTypeCode);

                    ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, masterFolderResource);
                    List<Path> fileList = files.get(masterFolder);
                    success = ingestFiles(fileList, "_master", id, topCollectionIdentifier, collectionIdentifier, processResource, client, ti);
                    if (!success) {
                        // file upload failed, abort
                        return PluginReturnValue.ERROR;
                    }

                    model = resetModel(topCollectionIdentifier);
                    filename = createImageFilename(process.getTitel() + "_media", 1,
                            FilenameUtils.getExtension(files.get(mediaFolder).get(0).getFileName().toString()));
                    masterFolderResource = createFolderResource(model, process.getTitel() + "_media", collectionIdentifier, processResource,
                            filename, true, docTypeCode);

                    ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, masterFolderResource);
                    fileList = files.get(mediaFolder);
                    success = ingestFiles(fileList, "_media", id, topCollectionIdentifier, collectionIdentifier, processResource, client, ti);
                    if (!success) {
                        // file upload failed, abort
                        return PluginReturnValue.ERROR;
                    }

                    if (altoFolder != null) {
                        model = resetModel(topCollectionIdentifier);
                        filename = createImageFilename(process.getTitel() + "_ocr", 1,
                                FilenameUtils.getExtension(files.get(altoFolder).get(0).getFileName().toString()));
                        masterFolderResource = createFolderResource(model, process.getTitel() + "_ocr", collectionIdentifier, processResource,
                                filename, true, docTypeCode);

                        ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, masterFolderResource);
                        fileList = files.get(altoFolder);
                        success = ingestFiles(fileList, "_ocr", id, topCollectionIdentifier, collectionIdentifier, processResource, client, ti);
                        if (!success) {
                            // file upload failed, abort
                            return PluginReturnValue.ERROR;
                        }

                    }

                    ArcheAPI.finishTransaction(client, archeConfiguration.getArcheApiUrl(), ti);
                    Helper.setMeldung("Arche ingest successful");
                } else {

                    ArcheAPI.cancelTransaction(client, archeConfiguration.getArcheApiUrl(), ti);
                    Helper.setMeldung("Arche validation successful");
                }
            } catch (ProcessingException e) {
                Helper.setFehlerMeldung("Cannot reach arche API");
                return PluginReturnValue.ERROR;
            }
        }

        return PluginReturnValue.FINISH;
    }

    private Model resetModel(String topCollectionIdentifier) {
        Model model;
        model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);
        return model;
    }

    private boolean ingestFiles(List<Path> fileList, String folderPrefix, String id, String topCollectionIdentifier,
            String collectionIdentifier, Resource processResource, Client client, TransactionInfo ti) {
        boolean success;
        for (int i = 0; i < fileList.size(); i++) {
            Path current = fileList.get(i);
            Path next = null;
            if (i + 1 < fileList.size()) {
                next = fileList.get(i + 1);
            }

            String currentFilename =
                    createImageFilename(process.getTitel() + folderPrefix, i + 1, FilenameUtils.getExtension(current.getFileName().toString()));

            String nextFilename = null;
            if (next != null) {
                nextFilename =
                        createImageFilename(process.getTitel() + folderPrefix, i + 2, FilenameUtils.getExtension(next.getFileName().toString()));
            }

            Resource fileResource = createFileResource(id, topCollectionIdentifier, collectionIdentifier, processResource,
                    process.getTitel() + folderPrefix, currentFilename, nextFilename, true);
            String fileUri = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, fileResource);
            if (archeConfiguration.isEnableArcheIngestData()) {
                success = ArcheAPI.uploadBinary(client, fileUri, ti, current);
                if (!success) {
                    // file upload failed, abort
                    return false;
                }
            }
        }
        return true;
    }

    private List<Resource> createPublicationResource(DocStruct docstruct, String languageCode, Model model, String collectionIdentifier,
            String anchorResourceId, String resourceIdentifier, String defaultLanguageCode) {

        String pubId = null;
        for (Metadata md : docstruct.getAllMetadata()) {
            switch (md.getType().getName()) {
                case "CatalogIDDigital":
                    String catalogIdDigital = md.getValue();
                    //        hasIdentifier   1-n     Thing   3   --- See note ---    Build identifier according to form https://id.acdh.oeaw.ac.at/pub-AC02277063, where the last segment of the URI includes the AC identifier from "CatalogIDDigital", prefixed with "pub-"
                    pubId = IDENTIFIER_PREFIX + "pub-" + catalogIdDigital;
            }
        }

        if (StringUtils.isBlank(resourceIdentifier)) {
            resourceIdentifier = pubId;
        }

        Resource resource = model.createResource(resourceIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "Publication"));

        List<Resource> resources = new ArrayList<>();
        resources.add(resource);

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
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"), model.createResource(pubId));
                    //        hasNonLinkedIdentifier  0-n     string  4   CatalogIDDigital    e.g. "AC02277063"
                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNonLinkedIdentifier"), catalogIdDigital);
                    //        hasUrl  0-n     anyURI  30  --- See note ---    Should be the URL of the object in Goobi Viewer, e.g. https://viewer.acdh.oeaw.ac.at/viewer/image/AC02277063
                    if (docstruct.getType().isAnchor()) {
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"),
                                viewerUrl + "/toc/" + catalogIdDigital,
                                XSDDatatype.XSDanyURI);
                    } else {
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"),
                                viewerUrl + "/image/" + catalogIdDigital,
                                XSDDatatype.XSDanyURI);
                    }
                    break;
                case "DateOfOrigin":
                    //        hasDescription  0-n     langString  40  --- See note ---
                    // TODO: disabled in current arche schema                   createDateNote(model, md.getValue(), resource);
                    break;
                case "DocLanguage":
                    //        hasLanguage 0-n     Concept 41  DocLanguage
                    if ("ger".equals(md.getValue())) {
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLanguage"),
                                model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/deu"));
                    } else {
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLanguage"),
                                model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/" + md.getValue()));
                    }
                    break;
                default:
                    // check configurable metadata mappings
                    for (MetadataFieldMapping mapping : metadataMappings) {
                        if (mapping.metadataName.equals(md.getType().getName())) {
                            switch (mapping.languageMode) {
                                case "DOC_LANGUAGE":
                                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), mapping.archeField),
                                            md.getValue(), languageCode);
                                    break;
                                case "DEFAULT_LANGUAGE":
                                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), mapping.archeField),
                                            md.getValue(), defaultLanguageCode);
                                    break;
                                case "DATE":
                                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), mapping.archeField),
                                            md.getValue(), XSDDatatype.XSDdate);
                                    break;
                                case "NO_LANGUAGE":
                                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), mapping.archeField), md.getValue());
                                    break;
                                default:
                                    // explicit language tag, e.g. "und", "en"
                                    resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), mapping.archeField),
                                            md.getValue(), mapping.languageMode);
                                    break;
                            }
                        }
                    }
            }
        }
        //       isPartOf    0-n     CollectionOrPlaceOrPublication  151 --- See note ---    In case the Process includes an anchor publication, set the value to the identifier of the anchor publication, which can be taken from field "CatalogIDDigital" with attribute anchorId="true"
        if (anchorResourceId != null) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"), anchorResourceId);
        }
        //        isSourceOf  0-n     ContainerOrReMe 148 --- See note ---    Add here the ARCHE identifier of the related Process, e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793

        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isSourceOf"), model.createResource(collectionIdentifier));

        if (docstruct.getAllPersons() != null) {
            for (Person p : docstruct.getAllPersons()) {
                switch (p.getType().getName()) {
                    case "Cartographer", "Artist", "Author":
                        //        hasAuthor   0-n     Agent   73  Cartographer    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasAuthor   0-n     Agent   73  Artist  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasAuthor   0-n     Agent   73  Author  Object of this property should be the URI of the corresponding Agent (Person or Organisation)

                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasAuthor"),
                                model.createResource(createPerson(languageCode, p, collectionIdentifier, resources)));

                        break;

                    case "Editor":
                        //        hasEditor   0-n     Agent   74  Editor  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasEditor"),
                                model.createResource(createPerson(languageCode, p, collectionIdentifier, resources)));
                        break;
                    case "OtherPerson", "Lithographer", "Engraver", "Contributor", "Printer", "PublisherPerson":
                        //        hasContributor  0-n     Agent   75  OtherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Lithographer  curator  Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Engraver    Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Contributor Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  Printer Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        //        hasContributor  0-n     Agent   75  PublisherPerson Object of this property should be the URI of the corresponding Agent (Person or Organisation)
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContributor"),
                                model.createResource(createPerson(languageCode, p, collectionIdentifier, resources)));
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
                                model.createResource(createOrganisation(languageCode, c, collectionIdentifier, resources)));
                        break;
                    case "CorporateEditor":
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasEditor"),
                                model.createResource(createOrganisation(languageCode, c, collectionIdentifier, resources)));
                        break;
                    case "CorporateOther", "CorporateEngraver", "CorporateContributor":
                        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContributor"),
                                model.createResource(createOrganisation(languageCode, c, collectionIdentifier, resources)));
                        break;
                    default:
                        // ignore other roles
                }
            }
        }
        return resources;
    }

    private String createOrganisation(String languageCode, Corporate c, String collectionIdentifier, List<Resource> resources) {
        String identifier = null;
        if (StringUtils.isNotBlank(c.getAuthorityValue())) {

            //                        hasIdentifier   1-n     Thing   3   authorityURI + authorityValue
            if (StringUtils.isNotBlank(c.getAuthorityURI())) {
                identifier = c.getAuthorityURI() + c.getAuthorityValue();
            } else {
                identifier = c.getAuthorityValue();
            }
            return identifier;
        }

        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        String name = c.getMainName();
        identifier = collectionIdentifier + name.replaceAll("[\\W]", "_");

        Resource person =
                model.createResource(identifier, model.createResource(model.getNsPrefixURI("acdh") + "Person"));
        // hasTitle    1       langString  1   firstName + lastName    We cannot use the displayName because we prefer the direct form in ARCHE (i.e., "Friedrich Würthle" instead of "Würthle, Friedrich"). Therefore, it would be better to just have a concatenation of first and last name.
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), name, languageCode);

        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                model.createResource(identifier));

        resources.add(person);

        return identifier;
    }

    private String createPerson(String languageCode, Person p, String collectionIdentifier, List<Resource> resources) {
        String identifier = null;

        if (StringUtils.isNotBlank(p.getAuthorityValue())) {
            //                        hasIdentifier   1-n     Thing   3   authorityURI + authorityValue
            if (StringUtils.isNotBlank(p.getAuthorityURI())) {
                identifier = p.getAuthorityURI() + p.getAuthorityValue();
            } else {
                identifier = p.getAuthorityValue();
            }
            return identifier;
        }

        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");

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

        identifier = collectionIdentifier + displayName.replaceAll("[\\W]", "_");
        Resource person =
                model.createResource(identifier, model.createResource(model.getNsPrefixURI("acdh") + "Person"));
        // hasTitle    1       langString  1   firstName + lastName    We cannot use the displayName because we prefer the direct form in ARCHE (i.e., "Friedrich Würthle" instead of "Würthle, Friedrich"). Therefore, it would be better to just have a concatenation of first and last name.
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), displayName, languageCode);
        // hasFirstName    0-n     langString  11  firstName
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasFirstName"), firstName, languageCode);
        // hasLastName 0-n     langString  12  lastName
        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLastName"), lastName, languageCode);

        person.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                model.createResource(identifier));

        resources.add(person);

        return identifier;
    }

    private Resource createFileResource(String id, String topCollectionIdentifier, String collectionIdentifier, Resource processResource,
            String folderName, String currentFile, String nextFile, boolean ingest) {

        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        String fileId = collectionIdentifier + "/" + folderName + "/" + currentFile;
        String resourceId;
        if (ingest) {
            resourceId = archeConfiguration.getArcheApiUrl();
        } else {
            resourceId = fileId;
        }

        Resource resource =
                model.createResource(resourceId,
                        model.createResource(model.getNsPrefixURI("acdh") + "Resource"));
        //        hasAvailableDate    1   1   dateTime    171 --- Will be automatically filled in.
        //        hasCategory 1-n     Concept 47  --- See note ---    "For images: set to https://vocabs.acdh.oeaw.ac.at/archecategory/image
        //        For XML ALTO: set to https://vocabs.acdh.oeaw.ac.at/archecategory/dataset"
        if (currentFile.endsWith(".xml")) {
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
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"), model.createResource(fileId));

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

        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), currentFile, "und");
        //        isPartOf    1-n     CollectionOrPlaceOrPublication  151 --- See note ---    Should have as object the containing collection (e.g., https://id.acdh.oeaw.ac.at/woldan/RIIIWE3793/RIIIWE3793_master)
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"),
                model.createResource(collectionIdentifier + "/" + folderName));

        if (nextFile != null) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNextItem"),
                    model.createResource(collectionIdentifier + "/" + folderName + "/" + nextFile));
        }

        return resource;
    }

    private Resource createCollectionResource(String language, DocStruct logical, Map<Path, List<Path>> files, Path masterFolder, String languageCode,
            Model model, String topCollectionIdentifier, String collectionIdentifier, String resourceIdentifier) {

        String sortTitle = null;
        String orderNumber = null;
        String maintitle = null;
        String subtitle = null;
        String id = null;
        String shelfmark = null;
        String license = "";
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
        Resource processResource =
                model.createResource(resourceIdentifier,
                        model.createResource(model.getNsPrefixURI("acdh") + "Collection"));

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
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"), viewerUrl + "/image/" + id,
                XSDDatatype.XSDanyURI);

        //        hasDescription  0-n     langString  40  --- See note ---    We would need a description such as "A collection of scans from: https://permalink.obvsg.at/AC02277063", where the AC identifier of the original publication is given (retrievable from field "CatalogIDDigital"). Maybe this description can be automatically generated?
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDescription"),
                "A collection of scans from: " + permalinkUrl + id,
                "en");

        //        hasLanguage 0-n     Concept 41  DocLanguage Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/iso6393/
        if ("ger".equals(language)) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLanguage"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/deu"));
        } else {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLanguage"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/iso6393/" + language));
        }
        //        hasLifeCycleStatus  0-1     Concept 42  --- See note ---    "If the process has not been marked as completed yet, set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active
        //        Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        //        But most of the Processes that will be imported into ARCHE should be more or less completed."

        if ("100000000".equals(process.getSortHelperStatus())) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));

        } else {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active"));

        }

        //        hasExtent   0-1     langString  46  --- See note ---    We would need a string such as "544 files", where the total numer of master images is computed.
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasExtent"), files.get(masterFolder).size() + " images",
                "en");

        //        hasNote 0-1     langString  59  --- See note ---    "We would like to insert here a note about the uncertainty of the date provided in the ARCHE property ""hasDate"". Therefore, if the Goobi field ""DateOfOrigin"" presents square brackets (e.g. [1862]), then add acdh:hasNote ""Date is inferred.""@en, ""Datum ist abgeleitet.""@de
        //        If the Goobi field ""DateOfOrigin"" presents square brackets and a question mark (e.g. [1862?]), then add acdh:hasNote ""Date is inferred and uncertain.""@en, ""Datum abgeleitet und unsicher.""@de
        createDateNote(model, dateOfOrigin, processResource);

        //        Agent properties - driven by <propertyMappings> in config
        for (String[] mapping : propertyMappings) {
            createPropertyInResource(model, processResource, mapping[1], mapping[0]);
        }

        //        hasSubject  0-n     langString  94  --- See note ---    Add label of topStruct here, e.g. "Band"@de and "volume"@en for topStruct "Volume". English labels should preferably have small initial letters.
        for (Entry<String, String> l : logical.getType().getAllLanguages().entrySet()) {
            processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasSubject"), l.getValue(), l.getKey());
        }

        //        hasLicense  0-1     Concept 113 AccessLicense   Values should be mapped to the controlled vocabulary used by ARCHE: https://vocabs.acdh.oeaw.ac.at/archelicenses/
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLicense"),
                model.createResource(licenseMapping.get(license)));

        //        hasDate 0-n     date    130 PublicationYgetArcheApiUrl(isProdIngest)ear
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDate"), publicationyear, XSDDatatype.XSDdate);

        //        relation    0-n     Thing   139 --- See note ---    Should be the URL of the object in the OBV catalog, e.g. https://permalink.obvsg.at/AC02277063 (it can be automatically created from CatalogIDDigital, I suppose)
        //        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "relation"),
        //                model.createLiteral("https://permalink.obvsg.at/" + id));
        //        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "relation"),
        //                "https://permalink.obvsg.at/" + id, XSDDatatype.XSDanyURI);
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"),
                permalinkUrl + id, XSDDatatype.XSDanyURI);

        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"),
                model.createResource(topCollectionIdentifier));

        // TODO hasAccessRestriction from metadata value?

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

    private Resource createMetadata(DocStruct docstruct, Model model, String collectionIdentifier, Resource processResource, boolean ingest) {

        String metadataId = null;
        String title = null;
        if (docstruct.getType().isAnchor()) {
            metadataId = collectionIdentifier + "/" + process.getTitel() + "_meta_anchor.xml";
            title = process.getTitel() + "_meta_anchor.xml";
        } else {
            metadataId = collectionIdentifier + "/" + process.getTitel() + "_meta.xml";
            title = process.getTitel() + "_meta.xml";
        }
        String resourceIdentifier = null;

        if (ingest) {
            resourceIdentifier = archeConfiguration.getArcheApiUrl();
        } else {
            resourceIdentifier = metadataId;
        }
        Resource metaResource =
                model.createResource(resourceIdentifier,
                        model.createResource(model.getNsPrefixURI("acdh") + "Metadata"));

        // meta.xml, meta_anchor.xml
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_meta.xml" or "RIIIWE3793_meta_anchor.xml"

        metaResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), title, "und");

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
        String pubId = IDENTIFIER_PREFIX + "pub-" + catalogIdDigital;
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

    private Resource createFolderResource(Model model, String folderName, String collectionIdentifier, Resource processResource, String filename,
            boolean ingest, String doctTypeCode) {

        String id = collectionIdentifier + "/" + folderName;
        String resourceIdentifier = null;
        if (ingest) {
            resourceIdentifier = archeConfiguration.getArcheApiUrl();
        } else {
            resourceIdentifier = id;
        }

        Resource resource =
                model.createResource(resourceIdentifier,
                        model.createResource(model.getNsPrefixURI("acdh") + "Collection"));

        // folder level, master, media, ocr
        //        hasTitle    1       langString  1   --- See note ---    Should be in the form "RIIIWE3793_master" or "RIIIWE3793_media" or "RIIIWE3793_ocr".
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), folderName, "und");
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
        if (collectionIdentifier.contains("woldan") && folderName.endsWith("media")) {
            //        In the specific case of Woldan, ONLY instances of acdh:Collection containing the ""media"" images (e.g. https://id.acdh.oeaw.ac.at/woldan/RIIIWE3791/RIIIWE3791_media)
            // will have the value https://vocabs.acdh.oeaw.ac.at/archeoaisets/kulturpool"
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasOaiSet"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archeoaisets/kulturpool"));
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTag"), doctTypeCode, "und");

        } else {
            createPropertyInResource(model, processResource, "hasOaiSet", "OAISet");
        }

        if (StringUtils.isNotBlank(filename)) {
            resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasNextItem"),
                    model.createResource(collectionIdentifier + "/" + folderName + "/" + filename));
        }

        return resource;
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

    private String createImageFilename(String foldername, int counter, String extension) {
        StringBuilder builder = new StringBuilder();
        builder.append(foldername).append("_").append(counterFormat.format(counter)).append(".").append(extension);

        return builder.toString();
    }

    /**
     * Represents a single configurable Goobi metadata field → ARCHE property mapping for use in createPublicationResource().
     */
    private static class MetadataFieldMapping {
        /** Goobi metadata type name, e.g. "shelfmarksource" */
        final String metadataName;
        /** ARCHE property local name, e.g. "hasNonLinkedIdentifier" */
        final String archeField;
        /**
         * Language mode. One of: DOC_LANGUAGE → per-document language code DEFAULT_LANGUAGE → project default language code DATE → typed XSDdate
         * literal (no language tag) NO_LANGUAGE → plain untagged string literal or any explicit BCP-47 tag, e.g. "und", "en"
         */
        final String languageMode;

        MetadataFieldMapping(String metadataName, String archeField, String languageMode) {
            this.metadataName = metadataName;
            this.archeField = archeField;
            this.languageMode = languageMode;
        }
    }

}
