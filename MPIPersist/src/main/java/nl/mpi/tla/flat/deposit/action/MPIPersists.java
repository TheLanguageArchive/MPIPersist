/*
 * Copyright (C) 2015-2026 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package nl.mpi.tla.flat.deposit.action;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.nio.file.attribute.PosixFilePermission;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.util.Saxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * External-payload persistence implementation for Fedora 7.
 *
 * <p>Current payloads keep their unsuffixed names. When updating a resource,
 * the Fedora relocation extension moves the old current payload to a
 * timestamped sibling and reconstructs the last OCFL version to point there.
 * The incoming payload is then installed at the unsuffixed path; the ordinary
 * Fedora update and post-commit memento actions retain their existing roles.</p>
 */
public class MPIPersists extends FedoraAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(MPIPersists.class);
    private static final DateTimeFormatter VERSION_SUFFIX = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss.SSS").withZone(ZoneOffset.UTC);

    @Override
    public boolean perform(final Context context) throws DepositException {
        connect(context);
        final SIPInterface sip = context.getSIP();
        // Check all payloads before moving any file. A stale classification
        // must fail before another resource's archive version is relocated.
        for (final Resource resource : sip.getResources()) {
            if ((resource.isInsert() || resource.isUpdate()) && !resource.hasFile()) {
                throw new DepositException("Resource[" + resource.getURI() + "] is marked "
                        + resource.getStatus() + " but has no local payload; validate the SIP again "
                        + "so retained archive references are classified as NOOP");
            }
        }
        // A SIP without new or updated payloads, such as a collection, has
        // nothing to move; the archive path and its mapping are not needed.
        boolean hasPayloadToPersist = false;
        for (final Resource resource : sip.getResources()) {
            if (resource.isInsert() || resource.isUpdate()) {
                hasPayloadToPersist = true;
                break;
            }
        }
        if (!hasPayloadToPersist) {
            LOGGER.info("No resources to persist; archive root mapping not consulted");
            return true;
        }
        final Set<PosixFilePermission> filePermissions = parseFileMode(getParameter("fileMode", ""));
        final Path archiveRoot = Path.of(getParameter("archiveRoot")).toAbsolutePath().normalize();
        final Path datasetDirectory;
        try {
            final String requestedDatasetName = context.getProperty("bundleName", "")
                    .itemAt(0).getStringValue();
            final String datasetName = datasetName(Saxon.wrapNode(sip.getRecord()),
                    requestedDatasetName, getParameter("xpathDatasetName"));
            if (datasetName == null || datasetName.isBlank()) {
                throw new DepositException("SIP name is unknown");
            }
            final String fedoraPath = collectionPath(sip) + "/" + sanitizePathComponent(datasetName) + "/";
            datasetDirectory = applyArchiveRootMapping(
                    new File(getParameter("archiveRootMapping")), fedoraPath).toAbsolutePath().normalize();
            if (!datasetDirectory.startsWith(archiveRoot)) {
                throw new DepositException("Archive root mapping resolved outside archiveRoot: "
                        + datasetDirectory + " (expected below " + archiveRoot + ")");
            }
            LOGGER.info("Resolved archive path [{}] to [{}]", fedoraPath, datasetDirectory);
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Couldn't determine the archive dataset path", ex);
        }

        for (final Resource resource : sip.getResources()) {
            if (!resource.isInsert() && !resource.isUpdate()) {
                continue;
            }
            persist(context, resource, datasetDirectory.resolve(resource.getFile().getName()), filePermissions);
        }
        return true;
    }

    static String datasetName(final XdmNode record, final String requestedName,
                              final String fallbackXPath) throws Exception {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        return Saxon.xpath2string(record, fallbackXPath, null, Global.NAMESPACES);
    }

    /** Recreate the Fedora 3 archive layout from the hierarchy loaded into the SIP. */
    private String collectionPath(final SIPInterface sip) throws DepositException {
        final Set<Collection> collections = sip.getCollections(false);
        if (collections.size() > 1) {
            throw new DepositException("MPI doesn't support more than one parent collection");
        }
        if (collections.isEmpty()) {
            return "";
        }
        final StringBuilder path = new StringBuilder();
        appendCollectionPath(collections.iterator().next(), path, new HashSet<>());
        return path.toString();
    }

    private void appendCollectionPath(final Collection collection, final StringBuilder path,
                                      final Set<String> visited) throws DepositException {
        final String fid = collection.getFID(true).toString();
        if (!visited.add(fid)) {
            throw new DepositException("Cycle in collection hierarchy at " + fid);
        }
        final Set<Collection> parents = collection.getParentCollections(false);
        if (parents.size() > 1) {
            throw new DepositException("MPI doesn't support more than one parent collection for " + fid);
        }
        if (!parents.isEmpty()) {
            appendCollectionPath(parents.iterator().next(), path, visited);
        } else {
            // FedoraLoadCollectionHierarchy deliberately stops above the
            // highest parent that has CMD, because other actions may update
            // every collection held in the SIP. Read any remaining structural
            // ancestors directly for this path only; never add them to the SIP.
            appendStructuralAncestors(collection.getFID(true), path, visited);
        }
        path.append('/').append(sanitizePathComponent(collectionLabel(collection)));
    }

    private void appendStructuralAncestors(final URI child, final StringBuilder path,
                                           final Set<String> visited) throws DepositException {
        final Set<URI> parents = repositoryParents(child);
        if (parents.size() > 1) {
            throw new DepositException("MPI doesn't support more than one parent collection for " + child);
        }
        if (parents.isEmpty()) {
            return;
        }
        final URI parent = parents.iterator().next();
        if (!visited.add(parent.toString())) {
            throw new DepositException("Cycle in collection hierarchy at " + parent);
        }
        appendStructuralAncestors(parent, path, visited);
        path.append('/').append(sanitizePathComponent(collectionLabel(parent)));
    }

    private Set<URI> repositoryParents(final URI child) throws DepositException {
        try {
            final String localBase = fedoraConfig.getString("localBase");
            final String localServer = fedoraConfig.getString("localServer");
            final String query = "SELECT ?fid WHERE { <" + localBase + "/" + child
                    + "> <info:fedora/fedora-system:def/relations-external#isMemberOfCollection> ?fid }";
            final XdmNode result = sparql(query);
            if (result == null) {
                throw new DepositException("No result while querying the triple store for parents of " + child);
            }
            final Set<URI> parents = new HashSet<>();
            for (final XdmItem item : Saxon.xpath(result,
                    "//srx:results/srx:result/srx:binding[@name='fid']/*"
                            + "[self::srx:uri or self::srx:literal]",
                    null, Global.NAMESPACES)) {
                final String value = item.getStringValue();
                if (value != null && !value.isBlank()) {
                    parents.add(FedoraLoadCollectionHierarchy.normalizeCollectionFID(
                            value, localBase, localServer));
                }
            }
            return parents;
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Couldn't load structural parents for " + child, ex);
        }
    }

    /** Use the Fedora 3 object label preserved by migration, with DC as fallback. */
    private String collectionLabel(final Collection collection) throws DepositException {
        return collectionLabel(collection.getFID(true));
    }

    private String collectionLabel(final URI fid) throws DepositException {
        try {
            final XdmNode description = fcrepo(fid);
            String label = preservedObjectLabel(description);
            if (label == null || label.isBlank()) {
                final XdmNode dc = getXMLDataStream(fid, "DC");
                label = firstDublinCoreTitle(dc);
                if (label == null || label.isBlank()) {
                    label = firstDublinCoreTitle(description);
                }
            }
            if (label == null || label.isBlank()) {
                throw new DepositException("Collection label is unknown for " + fid);
            }
            return label;
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Couldn't retrieve the collection label for " + fid, ex);
        }
    }

    static String preservedObjectLabel(final XdmNode description) throws Exception {
        return description == null ? null : Saxon.xpath2string(description,
                "normalize-space((//*[namespace-uri() = "
                        + "'info:fedora/fedora-system:def/model#' "
                        + "and local-name() = 'label'])[1])");
    }

    static String firstDublinCoreTitle(final XdmNode document) throws Exception {
        return document == null ? null : Saxon.xpath2string(document,
                "normalize-space((//dc:title)[1])", null, Global.NAMESPACES);
    }

    static String sanitizePathComponent(final String value) throws DepositException {
        final String sanitized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-zA-Z0-9]", "_");
        if (sanitized.isEmpty()) {
            throw new DepositException("Archive path component is empty after sanitization");
        }
        return sanitized;
    }

    static Path applyArchiveRootMapping(final File mappingFile, final String fedoraPath)
            throws DepositException {
        if (!mappingFile.isFile() || !mappingFile.canRead()) {
            throw new DepositException("Archive root mapping isn't a readable file: " + mappingFile);
        }
        try {
            final XdmNode mappings = Saxon.buildDocument(new StreamSource(mappingFile));
            final XdmValue entries = Saxon.xpath(mappings, "/archive-roots-mapping/mapping");
            for (final XdmItem entry : entries) {
                final String source = Saxon.xpath2string(entry,
                        "normalize-space(sanitized-fedora-path)");
                final String target = Saxon.xpath2string(entry,
                        "normalize-space(file-system-path)");
                if (source != null && !source.isEmpty() && fedoraPath.startsWith(source)) {
                    final Path targetRoot = Path.of(target);
                    return targetRoot.resolve(fedoraPath.substring(source.length())).normalize();
                }
            }
        } catch (SaxonApiException | IllegalArgumentException ex) {
            throw new DepositException("Couldn't read archive root mapping " + mappingFile, ex);
        }
        throw new DepositException("Path isn't specified in archive-roots-mapping.xml: " + fedoraPath);
    }

    private static Set<PosixFilePermission> parseFileMode(final String value) throws DepositException {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String mode = value.trim();
        if (!mode.matches("0?[0-7]{3}")) {
            throw new DepositException("Invalid MPIPersist fileMode[" + value + "]; use an octal mode such as 0640");
        }
        final int bits;
        try {
            bits = Integer.parseInt(mode, 8);
        } catch (NumberFormatException ex) {
            throw new DepositException("Invalid MPIPersist fileMode[" + value + "]; use an octal mode such as 0640", ex);
        }
        final PosixFilePermission[] permissions = {
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        };
        final EnumSet<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);
        for (int index = 0; index < permissions.length; index++) {
            if ((bits & (1 << (8 - index))) != 0) {
                result.add(permissions[index]);
            }
        }
        return result;
    }

    private void persist(final Context context, final Resource resource, final Path canonical,
                         final Set<PosixFilePermission> filePermissions) throws DepositException {
        final Path source = resource.getFile().toPath();
        try {
            Files.createDirectories(canonical.getParent());
            if (resource.isInsert()) {
                if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DepositException("File path clash for inserted resource: " + canonical);
                }
                move(source, canonical);
                context.registerRollbackEvent(this, "insert-move", "src", canonical.toString(), "dst", source.toString());
            } else {
                final Path journal = canonical.resolveSibling("." + canonical.getFileName() + ".mpipersist-update");
                if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(canonical) || !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS))) {
                    throw new DepositException("Updated resource has no regular current payload at " + canonical);
                }
                resumeUpdate(resource, source, canonical);
            }
            if (filePermissions != null) {
                Files.setPosixFilePermissions(canonical, filePermissions);
            }
            resource.setFile(canonical.toFile());
            LOGGER.info("Persisted resource [{}] at current path [{}]", source, canonical);
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Couldn't persist resource at " + canonical, ex);
        }
    }

    /**
     * The relocation endpoint durably journals the OCFL reconstruction.  This
     * small, adjacent journal covers the other half of the operation: the
     * incoming file that must replace the now-relocated current file.  It is
     * intentionally retained on failure so rerunning the same SIP resumes the
     * exact operation rather than guessing whether Fedora or the filesystem
     * already advanced.
     */
    private void resumeUpdate(final Resource resource, final Path source, final Path canonical) throws Exception {
        final Path journalPath = canonical.resolveSibling("." + canonical.getFileName() + ".mpipersist-update");
        UpdateJournal journal = Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)
                ? readJournal(journalPath, canonical)
                : createJournal(journalPath, source, canonical);

        if ("CREATED".equals(journal.phase())) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
                throw new DepositException("Incoming payload is missing while resuming " + journalPath);
            move(source, journal.staged());
            journal = journal.withPhase("STAGED");
            writeJournal(journalPath, journal);
        }
        if ("STAGED".equals(journal.phase())) {
            relocateExternalPayload(resource, canonical, journal.archived(), journal.operationId());
            journal = journal.withPhase("RELOCATED");
            writeJournal(journalPath, journal);
        }
        if ("RELOCATED".equals(journal.phase())) {
            if (!Files.isRegularFile(journal.staged(), LinkOption.NOFOLLOW_LINKS))
                throw new DepositException("Staged incoming payload is missing while resuming " + journalPath);
            if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS))
                throw new DepositException("Current payload unexpectedly exists while resuming " + journalPath);
            move(journal.staged(), canonical);
            Files.deleteIfExists(journalPath);
        }
    }

    private static UpdateJournal createJournal(final Path journalPath, final Path source, final Path canonical) throws IOException {
        final UpdateJournal journal = new UpdateJournal("relocate-" + UUID.randomUUID(), source,
                canonical.resolveSibling(canonical.getFileName() + ".incoming-" + UUID.randomUUID()),
                versionedPath(canonical), "CREATED");
        writeJournal(journalPath, journal);
        return journal;
    }

    private static UpdateJournal readJournal(final Path journalPath, final Path canonical) throws IOException, DepositException {
        final Properties values = new Properties();
        try (var input = Files.newInputStream(journalPath)) {
            values.load(input);
        }
        final UpdateJournal journal = new UpdateJournal(values.getProperty("operationId"), Path.of(values.getProperty("source", "")),
                Path.of(values.getProperty("staged", "")), Path.of(values.getProperty("archived", "")), values.getProperty("phase"));
        if (!journal.validFor(canonical))
            throw new DepositException("Invalid MPIPersist recovery journal: " + journalPath);
        return journal;
    }

    private static void writeJournal(final Path path, final UpdateJournal journal) throws IOException {
        final Properties values = new Properties();
        values.setProperty("operationId", journal.operationId());
        values.setProperty("source", journal.source().toString());
        values.setProperty("staged", journal.staged().toString());
        values.setProperty("archived", journal.archived().toString());
        values.setProperty("phase", journal.phase());
        final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary)) {
            values.store(output, "MPIPersist external payload update recovery");
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void relocateExternalPayload(final Resource resource, final Path canonical, final Path archived,
                                         final String operationId) throws Exception {
        final String fid = resource.getFID(true).toString();
        final URI endpoint = URI.create(fedoraConfig.getString("localServer") + "/" + fid + "/OBJ/fcr:relocate-external");
        final String expected = fedoraFileUri(canonical);
        final String replacement = fedoraFileUri(archived);
        final String body = "{\"operationId\":\"" + operationId + "\",\"expectedUrl\":\"" + expected
                + "\",\"replacementUrl\":\"" + replacement + "\"}";
        final String password = readSecret(fedoraConfig, "userPass", "userPassFile");
        final String credentials = Base64.getEncoder().encodeToString((getFedoraUser() + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        final HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) {
            throw new DepositException("Fedora external relocation failed for " + fid + ", status "
                    + response.statusCode() + ": " + response.body());
        }
        LOGGER.info("Relocated prior payload for resource [{}] from [{}] to [{}]", fid, canonical, archived);
    }

    private record UpdateJournal(String operationId, Path source, Path staged, Path archived, String phase) {
        UpdateJournal withPhase(final String nextPhase) {
            return new UpdateJournal(operationId, source, staged, archived, nextPhase);
        }

        boolean validFor(final Path canonical) {
            return operationId != null && operationId.matches("[A-Za-z0-9._-]{1,128}")
                    && Set.of("CREATED", "STAGED", "RELOCATED").contains(phase)
                    && canonical.getParent().equals(staged.getParent())
                    && canonical.getParent().equals(archived.getParent())
                    && staged.getFileName().toString().startsWith(canonical.getFileName() + ".incoming-")
                    && archived.getFileName().toString().startsWith(canonical.getFileName() + ".");
        }
    }

    /**
     * Fedora's existing external binary references use {@code file:/path},
     * whereas {@link Path#toUri()} emits {@code file:///path}. Both designate
     * the same POSIX file, but relocation deliberately uses an exact match to
     * avoid rewriting an unexpected binary.
     */
    private static String fedoraFileUri(final Path path) {
        return path.toUri().toString().replaceFirst("^file:///", "file:/");
    }

    private static Path versionedPath(final Path canonical) {
        final String suffix = VERSION_SUFFIX.format(Instant.now());
        Path candidate = canonical.resolveSibling(canonical.getFileName() + "." + suffix);
        for (int collision = 1; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); collision++) {
            candidate = canonical.resolveSibling(canonical.getFileName() + "." + suffix + "." + collision);
        }
        return candidate;
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    @Override
    public void rollback(final Context context, final List<XdmItem> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            try {
                final XdmItem event = events.get(index);
                if (!"insert-move".equals(nl.mpi.tla.util.Saxon.xpath2string(event, "@type"))) {
                    continue;
                }
                final Path source = Path.of(nl.mpi.tla.util.Saxon.xpath2string(event, "param[@name='src']/@value"));
                final Path target = Path.of(nl.mpi.tla.util.Saxon.xpath2string(event, "param[@name='dst']/@value"));
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    move(source, target);
                }
            } catch (Exception ex) {
                LOGGER.error("Could not roll back hybrid persist event", ex);
            }
        }
    }
}
