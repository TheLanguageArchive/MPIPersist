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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistDatasetNameRetriever;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicies;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicy;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicyLoader;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicyMatcher;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
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
        final String resourcesDir = getParameter("resourcesDir", null);
        final String policyFile = getParameter("policyFile", null);
        final SIPInterface sip = context.getSIP();
        final PersistencePolicies policies;
        try {
            final String datasetName = new PersistDatasetNameRetriever()
                    .getDatasetName(sip.getRecord(), getParameter("xpathDatasetName"));
            policies = new PersistencePolicyLoader(new File(resourcesDir))
                    .loadPersistencePolicies(new StreamSource(policyFile), sip, datasetName);
        } catch (SaxonApiException | IllegalStateException ex) {
            throw new DepositException("Error loading persistence policy '" + policyFile + "'", ex);
        }

        for (final Resource resource : sip.getResources()) {
            if (!resource.isInsert() && !resource.isUpdate()) {
                continue;
            }
            final PersistencePolicy policy = new PersistencePolicyMatcher(policies).matchPersistencePolicy(resource);
            persist(context, resource, policy.getTarget().toPath().resolve(resource.getFile().getName()));
        }
        return true;
    }

    private void persist(final Context context, final Resource resource, final Path canonical) throws DepositException {
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
