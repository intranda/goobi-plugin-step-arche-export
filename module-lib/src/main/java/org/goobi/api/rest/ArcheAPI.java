package org.goobi.api.rest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.metadaten.search.EntityLoggingFilter;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ArcheAPI {

    private static final boolean enableDebugging = true;

    /**
     * Get the client
     * 
     * @param username
     * @param password
     * @return
     */

    public static Client getClient(String username, String password) {
        Client client = ClientBuilder.newClient().register(new BasicAuthentication(username, password));
        client.register(TurtleReader.class);
        client.register(TurtleWriter.class);
        if (enableDebugging) {
            client.register(new EntityLoggingFilter());
        }
        return client;
    }

    /**
     * 
     * Start a new transaction
     * 
     * @param client
     * @param baseURI
     * @return
     */

    public static TransactionInfo startTransaction(Client client, String baseURI) {
        WebTarget target = client.target(baseURI).path("transaction");
        Invocation.Builder builder = target.request();
        builder.header("Accept", "application/json");
        Response response = builder.post(null); //TODO or null?
        return response.readEntity(TransactionInfo.class);
    }

    public static String updateMetadata(Client client, String location, TransactionInfo ti, Resource resource) {

        WebTarget target = client.target(location);
        Invocation.Builder builder = target.request();
        builder.property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true);
        builder.header("X-TRANSACTION-ID", ti.getTransactionId());
        builder.accept("text/turtle");
        Model m = resource.getModel();
        Entity<Model> entity = Entity.entity(m, "text/turtle");
        Response response = builder.method("PATCH", entity);
        switch (response.getStatus()) {
            case 200, 201, 202, 203, 204:
                return response.getHeaderString("location");
            default:
                // TODO error
                return null;
        }

    }

    public static String uploadMetadata(Client client, String baseURI, TransactionInfo ti, Resource resource) {
        WebTarget target = client.target(baseURI).path("metadata");
        Invocation.Builder builder = target.request("text/turtle");
        builder.header("X-TRANSACTION-ID", ti.getTransactionId());
        builder.accept("text/turtle");
        Model m = resource.getModel();
        Entity<Model> entity = Entity.entity(m, "text/turtle");
        Response response = builder.post(entity);
        switch (response.getStatus()) {
            case 201:
                // created, read location
                // TODO

                m = response.readEntity(Model.class);

                return response.getHeaderString("location");
            case 409:
                // Resource with the identifier already exists
                // find record uri, use patch instead
                break;

            default:
                // handle error
                break;
        }

        return null;
    }

    public static void uploadBinary(Client client, String uri, TransactionInfo ti, Path file) {
        WebTarget target = client.target(uri); // http://example.com/api/{resourceId}
        Invocation.Builder builder = target.request();
        builder.header("X-TRANSACTION-ID", ti.getTransactionId());
        try (InputStream in = StorageProvider.getInstance().newInputStream(file)) {
            Entity<InputStream> entity = null;

            if (file.getFileName().toString().endsWith(".xml")) {
                entity = Entity.entity(in, MediaType.APPLICATION_XML);
            } else if (file.getFileName().toString().endsWith(".jpg")) {
                entity = Entity.entity(in, "image/jpeg");
            } else if (file.getFileName().toString().endsWith(".tif")) {
                entity = Entity.entity(in, "image/tiff");
            } else {
                entity = Entity.entity(in, MediaType.APPLICATION_OCTET_STREAM);
            }

            Response response = builder.put(entity);
            switch (response.getStatus()) {
                //            204 Binary payload updated
                case 204:

                    break;
                //            401 Unauthorized
                //            403 Not authorized to update the resource
                case 401, 403:

                    break;

                //            404  Resource doesn't exist
                //            410 Resource has been deleted (but tombstone exists)
                case 404, 410:

                    break;
                default:
                    // handle error
                    break;

            }
        } catch (IOException e) {
            log.error(e);
        }

    }

    /**
     * Finish the transaction and execute the changes
     * 
     * @param client
     * @param baseURI
     * @param ti
     */

    public static void finishTransaction(Client client, String baseURI, TransactionInfo ti) {
        WebTarget target = client.target(baseURI).path("transaction");
        Invocation.Builder builder = target.request();
        builder.header("Accept", "application/json");
        builder.header("X-TRANSACTION-ID", ti.getTransactionId());
        Response response = builder.put(Entity.json(""));
        if (response.getStatus() != 204) {
            // TODOD handle errors
        }
    }

    public static String findResourceURI(Client client, String baseURI, String value, TransactionInfo ti) {
        WebTarget target = client.target(baseURI)
                .path("search")
                .queryParam("value[]", value)
                .queryParam("property[]", "https%3A%2F%2Fvocabs.acdh.oeaw.ac.at%2Fschema%23hasIdentifier");

        Invocation.Builder builder = target.request();
        builder.header("Accept", "text/turtle");
        builder.header("X-TRANSACTION-ID", ti.getTransactionId());
        Response response = builder.get();
        switch (response.getStatus()) {
            case 200:
                Model m = response.readEntity(Model.class);
                StmtIterator qIter = m.listStatements();
                while (qIter.hasNext()) {
                    Statement stmt = qIter.nextStatement();
                    Resource subject = stmt.getSubject(); // get the subject
                    return subject.getURI();
                }

                break;

            case 404:
                // resource not found
                return null;
            default:
                // handle errors
        }

        return "";
    }
}
