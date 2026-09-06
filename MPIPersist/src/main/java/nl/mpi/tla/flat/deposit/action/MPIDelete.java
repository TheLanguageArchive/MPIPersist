/*
 * Copyright (C) 2015-2026 The Language Archive
 */
package nl.mpi.tla.flat.deposit.action;

import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
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
 * Moves the conventional current-name link of resources removed from a SIP to
 * a deleted-name link. The immutable target is never copied or removed.
 * Fedora deletion remains the responsibility of the following FedoraDelete
 * action in the workflow.
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
        if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS))
            throw new DepositException("Payload to delete doesn't exist: " + payload);

        Path current = currentLink(payload);
        Path deleted = (current == null ? payload : current).resolveSibling(
                (current == null ? payload : current).getFileName() + ".Deleted." + DELETED_SUFFIX.format(Instant.now()));
        move(current == null ? payload : current, deleted);
        context.registerRollbackEvent(this, "move", "src", deleted.toString(), "dst", (current == null ? payload : current).toString());
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

    /** Return the sibling current-name symlink, if this immutable payload has one. */
    private Path currentLink(Path payload) throws Exception {
        try (var entries = Files.list(payload.getParent())) {
            return entries.filter(Files::isSymbolicLink)
                    .filter(path -> {
                        try {
                            return Files.readSymbolicLink(path).equals(payload.getFileName());
                        } catch (Exception ex) {
                            return false;
                        }
                    }).findFirst().orElse(null);
        }
    }

    private String normalizeFID(String value) {
        String fid = value.trim().replaceFirst("#.*$", "");
        for (String prefix : List.of(fedoraConfig.getString("localBase") + "/", fedoraConfig.getString("localServer") + "/", "info:fedora/")) {
            if (fid.startsWith(prefix))
                return fid.substring(prefix.length());
        }
        return fid;
    }

    private static void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    @Override
    public void rollback(Context context, List<XdmItem> events) {
        for (ListIterator<XdmItem> iterator = events.listIterator(events.size()); iterator.hasPrevious();) {
            XdmItem event = iterator.previous();
            try {
                if (!"move".equals(Saxon.xpath2string(event, "@type")))
                    continue;
                Path source = Path.of(Saxon.xpath2string(event, "param[@name='src']/@value"));
                Path target = Path.of(Saxon.xpath2string(event, "param[@name='dst']/@value"));
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    move(source, target);
            } catch (Exception ex) {
                logger.error("Rollback event [{}] failed", event, ex);
            }
        }
    }
}
