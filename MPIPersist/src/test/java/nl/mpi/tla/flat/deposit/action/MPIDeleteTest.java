package nl.mpi.tla.flat.deposit.action;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.util.Saxon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MPIDeleteTest {
    @TempDir Path directory;
    private static XdmNode xml(String value) throws Exception {
        return Saxon.buildDocument(new StreamSource(new StringReader(value)));
    }
    private Context context(boolean update, boolean committed) throws Exception {
        SIPInterface sip = (SIPInterface) Proxy.newProxyInstance(SIPInterface.class.getClassLoader(),
                new Class<?>[]{SIPInterface.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isUpdate" -> update;
                    default -> throw new AssertionError("Unexpected SIP method " + method.getName());
                });
        Context context = new Context(null, xml("<flow/>"),
                Map.of("dk-pidList", new XdmAtomicValue(directory.resolve("pids.csv").toString())));
        context.setSIP(sip);
        if (committed) context.markCommitted();
        return context;
    }
    private class Deleter extends MPIDelete {
        URI external;
        String membership = "";
        List<String> requests = new ArrayList<>();
        boolean interrupted;
        boolean connected;
        Deleter() {
            setParameters(Map.of("detachedResources", new XdmAtomicValue("true"),
                    "journalDir", new XdmAtomicValue(directory.resolve("journals").toString())));
        }
        @Override public void connect(Context context) { connected = true; }
        @Override public XdmNode fcrepo(URI fid) throws DepositException {
            try {
                return xml("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' "
                        + "xmlns:r='info:fedora/fedora-system:def/relations-external#'>"
                        + "<rdf:Description>" + membership + "</rdf:Description></rdf:RDF>");
            } catch (Exception e) { throw new DepositException(e); }
        }
        @Override protected URI externalContent(URI fid) { return external; }
        @Override protected void relocateExternalPayload(URI fid, Path current, Path deleted, String id) throws Exception {
            requests.add(fid + " " + current + " " + deleted + " " + id);
            if (Files.exists(current)) Files.move(current, deleted);
            assertTrue(Files.isRegularFile(deleted));
            if (interrupted) throw new IOException("Response lost during relocation");
            external = deleted.toUri();
        }
    }
    private Deleter action() throws Exception {
        Deleter action = new Deleter();
        Path payload = directory.resolve("audio with spaces.wav");
        Files.writeString(payload, "preserved bytes");
        action.external = payload.toUri();
        return action;
    }
    private Context removed() throws Exception {
        Context context = context(true, true);
        context.putInMemory("flat.detached-resources", List.of("lat_removed"));
        return context;
    }
    @Test void renamesOmittedFileUsingIntentAfterMembershipHasGone() throws Exception {
        Deleter action = action();
        Path original = Path.of(action.external);
        assertTrue(action.perform(removed()));
        assertFalse(Files.exists(original));
        assertTrue(Path.of(action.external).getFileName().toString().startsWith("audio with spaces.wav.Deleted."));
        assertEquals("preserved bytes", Files.readString(Path.of(action.external)));
        assertEquals(1, action.requests.size());
    }
    @Test void retainedOtherBundleMembershipPreventsRename() throws Exception {
        Deleter action = action();
        action.membership = "<r:isConstituentOf rdf:resource='info:fedora/lat_other'/>";
        assertTrue(action.perform(removed()));
        assertTrue(Files.exists(Path.of(action.external)));
        assertTrue(action.requests.isEmpty());
    }
    @Test void retryAfterSuccessfulRelocationAddsNoSecondSuffix() throws Exception {
        Deleter action = action();
        action.perform(removed());
        URI first = action.external;
        action.perform(removed());
        assertEquals(first, action.external);
        assertEquals(1, action.requests.size());
    }
    @Test void interruptionAfterMoveRetriesIdenticalOperationEvenWithMissingOriginal() throws Exception {
        Deleter action = action();
        action.interrupted = true;
        assertThrows(DepositException.class, () -> action.perform(removed()));
        assertFalse(Files.exists(Path.of(action.external)));
        action.interrupted = false;
        assertTrue(action.perform(removed()));
        assertEquals(2, action.requests.size());
        assertEquals(action.requests.get(0), action.requests.get(1));
        assertEquals("preserved bytes", Files.readString(Path.of(action.external)));
    }
    @Test void rejectsUncommittedRelocation() throws Exception {
        Deleter action = action();
        Context context = context(true, false);
        context.putInMemory("flat.detached-resources", List.of("lat_removed"));
        assertThrows(DepositException.class, () -> action.perform(context));
        assertTrue(action.requests.isEmpty());
    }
    @Test void rejectsMissingRemovalIntent() throws Exception {
        assertThrows(DepositException.class, () -> action().perform(context(true, true)));
    }
    @Test void newBundleIsANoOp() throws Exception {
        Deleter action = new Deleter();
        assertTrue(action.perform(context(false, false)));
        assertFalse(action.connected);
    }
    @Test void emptyRemovalListIsANoOp() throws Exception {
        Deleter action = action();
        Context context = context(true, true);
        context.putInMemory("flat.detached-resources", List.of());
        assertTrue(action.perform(context));
        assertTrue(action.requests.isEmpty());
    }
    @Test void invalidIdentityIsRejected() throws Exception {
        Context context = context(true, true);
        context.putInMemory("flat.detached-resources", List.of("lat_removed/OBJ"));
        assertThrows(DepositException.class, () -> action().perform(context));
    }
    @Test void missingPayloadFailsWithoutRelocation() throws Exception {
        Deleter action = action();
        Files.delete(Path.of(action.external));
        assertThrows(DepositException.class, () -> action.perform(removed()));
        assertTrue(action.requests.isEmpty());
    }
    @Test void changedPayloadRejectsSavedJournal() throws Exception {
        Deleter action = action();
        action.interrupted = true;
        assertThrows(DepositException.class, () -> action.perform(removed()));
        Path replacement = directory.resolve("different.wav");
        Files.writeString(replacement, "different bytes");
        action.external = replacement.toUri();
        action.interrupted = false;
        assertThrows(DepositException.class, () -> action.perform(removed()));
        assertEquals(1, action.requests.size());
        assertEquals("different bytes", Files.readString(replacement));
    }
    @Test void nonFileExternalContentIsSkipped() throws Exception {
        Deleter action = new Deleter();
        action.external = URI.create("https://example.org/audio.wav");
        assertTrue(action.perform(removed()));
        assertTrue(action.requests.isEmpty());
    }

}
