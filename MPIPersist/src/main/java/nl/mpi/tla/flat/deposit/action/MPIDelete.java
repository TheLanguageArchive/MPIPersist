/*
 * Copyright (C) 2015-2026 The Language Archive
 */
package nl.mpi.tla.flat.deposit.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.util.Saxon;
import org.fcrepo.client.FcrepoResponse;
import org.fcrepo.client.HeadBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relocates a deleted resource's current external payload to a deleted-name
 * sibling and reconstructs the immutable OCFL version through Fedora's
 * relocation endpoint. Fedora deletion remains the responsibility of the
 * following FedoraDelete action in the workflow.
 */
public class MPIDelete extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(MPIDelete.class);
    private static final String EXTERNAL_CONTENT = "http://fedora.info/definitions/v4/repository#ExternalContent";
    private static final DateTimeFormatter DELETED_SUFFIX = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss.SSS").withZone(ZoneOffset.UTC);

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);
            SIPInterface sip = context.getSIP();
            String sid = sip.getFID().toString().replaceFirst("@.*$", "").replaceFirst("#.*$", "");
            String query = "SELECT ?fid WHERE { ?fid <info:fedora/fedora-system:def/relations-external#isConstituentOf> <"
                    + fedoraConfig.getString("localBase") + "/" + sid + "> }";
            XdmNode result = sparql(query);
            for (Iterator<XdmItem> iterator = Saxon.xpathIterator(result,
                    "//srx:results/srx:result/srx:binding[@name='fid']/*[self::srx:uri or self::srx:literal]", null, Global.NAMESPACES);
                    iterator.hasNext();) {
                URI fid = URI.create(normalizeFID(iterator.next().getStringValue()));
                if (sip.getResourceByFID(fid) == null)
                    markDeleted(context, fid);
            }
            return true;
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Couldn't prepare deleted payloads", ex);
        }
    }

    private void markDeleted(Context context, URI fid) throws Exception {
        URI external = externalContent(fid);
        if (external == null || !"file".equalsIgnoreCase(external.getScheme())) {
            logger.warn("Resource[{}] has no file-based external OBJ content; skipped", fid);
            return;
        }
        Path payload = Path.of(external);
        // A lost HTTP response is safe to retry: Fedora already points at the
        // deleted sibling, so do not generate a second deletion suffix.
        if (payload.getFileName().toString().contains(".Deleted.")) {
            logger.info("Resource[{}] already has a relocated deleted payload [{}]", fid, payload);
            return;
        }
        if (Files.isSymbolicLink(payload) || !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS))
            throw new DepositException("Payload to delete isn't a regular current file: " + payload);

        Path deleted = payload.resolveSibling(payload.getFileName() + ".Deleted." + DELETED_SUFFIX.format(Instant.now()));
        relocateExternalPayload(fid, payload, deleted, "delete-" + UUID.randomUUID());
        logger.info("Marked resource[{}] payload as deleted: [{}]", fid, deleted);
    }

    private URI externalContent(URI fid) throws Exception {
        URI obj = URI.create(fedoraConfig.getString("localServer") + "/" + fid + "/OBJ");
        try (FcrepoResponse response = new HeadBuilder(obj, fedoraClient).perform()) {
            if (response.getStatusCode() == 404 || response.getStatusCode() == 410)
                return null;
            if (response.getStatusCode() >= 300)
                throw new DepositException("Couldn't read OBJ for [" + fid + "], status " + response.getStatusCode());
            List<URI> links = response.getLinkHeaders(EXTERNAL_CONTENT);
            return links.isEmpty() ? null : links.get(0);
        }
    }

    private void relocateExternalPayload(final URI fid, final Path current, final Path deleted,
                                         final String operationId) throws Exception {
        final URI endpoint = URI.create(fedoraConfig.getString("localServer") + "/" + fid + "/OBJ/fcr:relocate-external");
        final String expected = fedoraFileUri(current);
        final String replacement = fedoraFileUri(deleted);
        final String body = "{\"operationId\":\"" + operationId + "\",\"expectedUrl\":\"" + expected
                + "\",\"replacementUrl\":\"" + replacement + "\"}";
        final String password = readSecret(fedoraConfig, "userPass", "userPassFile");
        final String credentials = Base64.getEncoder().encodeToString((getFedoraUser() + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        final HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(2))
                .header("Authorization", "Basic " + credentials).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        final HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300)
            throw new DepositException("Fedora external relocation failed for " + fid + ", status "
                    + response.statusCode() + ": " + response.body());
    }

    private static String fedoraFileUri(final Path path) {
        return path.toUri().toString().replaceFirst("^file:///", "file:/");
    }

    private String normalizeFID(String value) {
        String fid = value.trim().replaceFirst("#.*$", "");
        for (String prefix : List.of(fedoraConfig.getString("localBase") + "/", fedoraConfig.getString("localServer") + "/", "info:fedora/")) {
            if (fid.startsWith(prefix))
                return fid.substring(prefix.length());
        }
        return fid;
    }

}
