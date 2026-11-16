/*
 * Copyright (C) 2015-2026 The Language Archive
 */
package nl.mpi.tla.flat.deposit.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * relocation endpoint. In the detachment workflow this runs after commit and
 * preserves Fedora objects, Handles and memberships in other bundles.
 */
public class MPIDelete extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(MPIDelete.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    // Contract published by DoorKeeper FedoraDetachResources.
    private static final String DETACHED_RESOURCES = "flat.detached-resources";
    private static final String EXTERNAL_CONTENT = "http://fedora.info/definitions/v4/repository#ExternalContent";
    private static final DateTimeFormatter DELETED_SUFFIX = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss.SSS").withZone(ZoneOffset.UTC);

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            SIPInterface sip = context.getSIP();
            if (!sip.isUpdate()) return true;
            connect(context);
            if (getParameter("detachedResources", "false").equals("true")) {
                if (!context.isCommitted())
                    throw new DepositException("Deleted payload relocation requires a committed Fedora transaction");
                Object intent = context.getFromMemory(DETACHED_RESOURCES);
                if (!(intent instanceof List<?> removed))
                    throw new DepositException("Missing resource detachment intent for deleted payload relocation");
                for (Object entry : removed) {
                    if (!(entry instanceof String value) || !value.matches("[A-Za-z0-9_:-]+"))
                        throw new DepositException("Invalid resource detachment identifier");
                    URI fid = URI.create(value);
                    XdmNode current = fcrepo(fid);
                    if (current == null)
                        throw new DepositException("Cannot read remaining memberships for [" + fid + "]");
                    if (!Saxon.xpathList(current, "//relsext:isConstituentOf", null, Global.NAMESPACES).isEmpty()) {
                        logger.info("Resource[{}] still belongs to a bundle; retaining its current payload name", fid);
                        continue;
                    }
                    markDeleted(context, fid);
                }
                return true;
            }
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

    protected void markDeleted(Context context, URI fid) throws Exception {
        URI external = externalContent(fid);
        if (external == null && getParameter("detachedResources", "false").equals("true"))
            throw new DepositException("OBJ for [" + fid + "] did not expose an external content URL; "
                    + "cannot confirm its archival payload for deletion");
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
        Path journalDirectory = Path.of(getParameter("journalDir", "./metadata/deleted-payloads"));
        Files.createDirectories(journalDirectory);
        Path journal = journalDirectory.resolve(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(fid.toString().getBytes(StandardCharsets.UTF_8)) + ".json");
        JsonNode operation;
        if (Files.exists(journal)) {
            operation = JSON.readTree(journal.toFile());
            if (!fid.toString().equals(operation.path("fid").asText())
                    || !fedoraFileUri(payload).equals(operation.path("expectedUrl").asText())
                    || !operation.path("operationId").asText().matches("delete-[a-f0-9-]{36}"))
                throw new DepositException("Deleted payload relocation journal does not match [" + fid + "]");
            Path replacement = Path.of(URI.create(operation.path("replacementUrl").asText()));
            if (!replacement.getParent().equals(payload.getParent())
                    || !replacement.getFileName().toString().startsWith(payload.getFileName() + ".Deleted."))
                throw new DepositException("Invalid deleted payload replacement path");
        } else {
            if (Files.isSymbolicLink(payload) || !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS))
                throw new DepositException("Payload to delete isn't a regular current file: " + payload);
            Path deleted = payload.resolveSibling(payload.getFileName() + ".Deleted." + DELETED_SUFFIX.format(Instant.now()));
            operation = JSON.createObjectNode().put("fid", fid.toString())
                    .put("operationId", "delete-" + UUID.randomUUID())
                    .put("expectedUrl", fedoraFileUri(payload)).put("replacementUrl", fedoraFileUri(deleted));
            Path temporary = Files.createTempFile(journalDirectory, "relocation-", ".json");
            try {
                Files.writeString(temporary, operation.toString());
                Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Path deleted = Path.of(URI.create(operation.path("replacementUrl").asText()));
        // Keep the same request even if Fedora moved the file before the HTTP
        // response was lost. The relocation endpoint resumes its own journal.
        relocateExternalPayload(fid, payload, deleted, operation.path("operationId").asText());
        logger.info("Marked resource[{}] payload as deleted: [{}]", fid, deleted);
    }

    protected URI externalContent(URI fid) throws Exception {
        URI obj = URI.create(fedoraConfig.getString("localServer") + "/" + fid + "/OBJ");
        // Read Fedora's own headers, without following an external redirect.
        try (FcrepoResponse response = new HeadBuilder(obj, fedoraClient).disableRedirects().perform()) {
            int status = response.getStatusCode();
            if (status == 404 || status == 410)
                throw new DepositException("OBJ for [" + fid + "] no longer exists, status " + status);
            boolean redirect = status == 307 || status == 308;
            if (status >= 300 && !redirect)
                throw new DepositException("Couldn't read OBJ for [" + fid + "], status " + status);
            // Fedora 7 advertises proxy/redirect payloads using Content-Location;
            // the ExternalContent relation is primarily an ingest Link header.
            String location = response.getHeaderValue("Content-Location");
            if (location != null && !location.isBlank())
                return URI.create(location.trim());
            if (redirect) {
                location = response.getHeaderValue("Location");
                if (location != null && !location.isBlank())
                    return URI.create(location.trim());
            }
            // Retain compatibility with servers that expose an external Link.
            for (String relation : List.of("http://fedora.info/definitions/fcrepo#ExternalContent", EXTERNAL_CONTENT)) {
                List<URI> links = response.getLinkHeaders(relation);
                if (!links.isEmpty()) return links.get(0);
            }
            return null;
        }
    }

    protected void relocateExternalPayload(final URI fid, final Path current, final Path deleted,
                                         final String operationId) throws Exception {
        final URI endpoint = URI.create(fedoraConfig.getString("localServer") + "/" + fid + "/OBJ/fcr:relocate-external");
        final String expected = fedoraFileUri(current);
        final String replacement = fedoraFileUri(deleted);
        final String body = JSON.createObjectNode().put("operationId", operationId)
                .put("expectedUrl", expected).put("replacementUrl", replacement).toString();
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
