package org.goobi.api.rest;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

@Path("/turtle")
public class TurtleRestApiTest {

    private static final String IDENTIFIER_PREFIX = "https://id.acdh.oeaw.ac.at/";

    @GET
    @Produces("text/turtle")

    public Response getModel() {

        Model model = ModelFactory.createDefaultModel();
        // collection name
        String topCollectionIdentifier = IDENTIFIER_PREFIX + "project";

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");

        // process level
        String collectionIdentifier = topCollectionIdentifier + "/" + "process";

        model = ModelFactory.createDefaultModel();

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");
        model.setNsPrefix("top", topCollectionIdentifier);

        Resource processResource = model.createResource(collectionIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "Collection"));
        processResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), "title", "de");

        return Response.ok().entity(processResource.getModel()).build();

    }

}
