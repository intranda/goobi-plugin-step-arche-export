package org.goobi.api.rest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.metadaten.search.EntityLoggingFilter;
import io.goobi.api.job.model.BasicAuthentication;
import io.goobi.api.job.model.TransactionInfo;
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
        Response response = builder.post(Entity.json("")); //TODO or null?
        return response.readEntity(TransactionInfo.class);
    }

    public static String uploadMetadata(Client client, String baseURI, TransactionInfo ti, Resource resource) {
        WebTarget target = client.target(baseURI).path("metadata");
        Invocation.Builder builder = target.request();
        builder.header("X-TRANSACTION-ID", ti.getTransactionId());
        builder.accept("text/turtle");
        Entity<Model> entity = Entity.entity(resource.getModel(), "text/turtle");
        Response response = builder.post(entity);
        switch (response.getStatus()) {
            case 201:
                // created, read location
                // TODO

                Model m = response.readEntity(Model.class);

                return response.getHeaderString("location");
            case 409:
                // Resource with the identifier already exists
                // use patch instead

                // TODO find resourceId
                // --> search for document id in arche
                // --> parse ttl
                // --> get local name
                String resourceId = "";

                target = client.target(baseURI).path(resourceId).path("metadata");
                builder = target.request();
                builder.header("X-TRANSACTION-ID", ti.getTransactionId());
                response = builder.method("PATCH", entity);
                // 200 - all good
                // 4XX - error

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
        try (InputStream in = StorageProvider.getInstance().newInputStream(file)) {
            Entity<InputStream> entity = Entity.entity(in, MediaType.APPLICATION_OCTET_STREAM);
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
        Response response = builder.put(Entity.json("")); //TODO or null?
        if (response.getStatus() != 204) {
            // TODOD handle errors
        }

    }

}
