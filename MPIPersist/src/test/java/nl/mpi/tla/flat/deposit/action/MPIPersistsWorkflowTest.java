package nl.mpi.tla.flat.deposit.action;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMD;
import nl.mpi.tla.util.Saxon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MPIPersistsWorkflowTest {
    @TempDir Path directory;
    private static final String FID = "lat_12345_1ea7b488_7d1b_40d1_b7e3_8d83fb2211cf";
    private static final String PID = "https://hdl.handle.net/12345/1ea7b488-7d1b-40d1-b7e3-8d83fb2211cf";
    private String retained(String status) {
        return "<cmd:ResourceProxy id='d10002'><cmd:ResourceType mimetype='image/png'>Resource</cmd:ResourceType>"
                + "<cmd:ResourceRef lat:flatURI='" + FID + "' lat:status='" + status + "'>" + PID
                + "</cmd:ResourceRef></cmd:ResourceProxy>";
    }
    private CMD sip(String resources) throws Exception {
        Path record = directory.resolve("record.cmdi");
        Files.writeString(record, "<cmd:CMD xmlns:cmd='http://www.clarin.eu/cmd/' xmlns:lat='http://lat.mpi.nl/'>"
                + "<cmd:Header><cmd:MdSelfLink>https://hdl.handle.net/12345/bundle</cmd:MdSelfLink>"
                + "<cmd:MdProfile>test</cmd:MdProfile></cmd:Header><cmd:Resources><cmd:ResourceProxyList>"
                + resources + "</cmd:ResourceProxyList></cmd:Resources><cmd:Components>"
                + "<cmd:Bundle><cmd:Name>Bundle</cmd:Name></cmd:Bundle></cmd:Components></cmd:CMD>");
        CMD sip = new CMD(record.toFile(), "lat", new XdmAtomicValue("lat"));
        sip.update();
        sip.save();
        return sip;
    }
    private Context context(CMD sip) throws Exception {
        Context context = new Context(null, Saxon.buildDocument(new StreamSource(new StringReader("<flow/>"))),
                Map.of("dk-pidList", new XdmAtomicValue(directory.resolve("pids.csv").toString()),
                        "dk-rollbackLog", new XdmAtomicValue(directory.resolve("rollback.log").toString())));
        context.setSIP(sip);
        return context;
    }
    private MPIPersists action() throws Exception {
        Path mapping = directory.resolve("archive-roots.xml");
        Files.writeString(mapping, "<archive-roots-mapping><mapping><sanitized-fedora-path>/</sanitized-fedora-path>"
                + "<file-system-path>" + directory.resolve("archive") + "/</file-system-path></mapping></archive-roots-mapping>");
        MPIPersists action = new MPIPersists() { @Override public void connect(Context context) { } };
        action.setParameters(Map.of("archiveRoot", new XdmAtomicValue(directory.resolve("archive").toString()),
                "archiveRootMapping", new XdmAtomicValue(mapping.toString()),
                "xpathDatasetName", new XdmAtomicValue("normalize-space(/cmd:CMD/cmd:Components/cmd:Bundle/cmd:Name)")));
        return action;
    }
    @Test void deletionOnlyUpdateKeepsValidatedNoopAcrossReloadAndPersistence() throws Exception {
        CMD validationSip = sip(retained("INSERT"));
        Context validation = context(validationSip);
        ResourceMapping mapping = new ResourceMapping() {
            @Override public void connect(Context context) { }
            @Override public URI lookupFID(URI pid) { return URI.create(FID); }
        };
        mapping.setParameters(Map.of("dir", new XdmAtomicValue(directory.resolve("resources").toString())));
        assertTrue(mapping.perform(validation));
        assertTrue(validation.save());
        CMD archiveSip = new CMD(validationSip.getBase(), "lat", new XdmAtomicValue("lat"));
        archiveSip.update();
        Path oldBinary = directory.resolve("archive/Bundle/retained.png");
        Files.createDirectories(oldBinary.getParent());
        Files.writeString(oldBinary, "existing binary");
        Context archive = context(archiveSip);
        // The removed file is absent from CMDI and handled separately by
        // FedoraDetachResources / MPIDelete. No incoming payload exists.
        archive.putInMemory("flat.detached-resources", java.util.List.of("lat_removed"));
        assertTrue(action().perform(archive));
        Resource kept = archiveSip.getResources().iterator().next();
        assertTrue(kept.isNoop());
        assertFalse(kept.hasFile());
        assertEquals(PID, kept.getPID().toString());
        assertEquals(FID, kept.getFID(true).toString());
        assertEquals("existing binary", Files.readString(oldBinary));
        assertEquals(java.util.List.of("lat_removed"), archive.getFromMemory("flat.detached-resources"));
        try (var files = Files.list(oldBinary.getParent())) { assertEquals(1, files.count()); }
    }
    @Test void staleInsertWithoutPayloadFailsWithUsefulError() throws Exception {
        Context context = context(sip(retained("INSERT")));
        var failure = assertThrows(DepositException.class, () -> action().perform(context));
        assertTrue(failure.getMessage().contains("is marked INSERT but has no local payload"));
        assertFalse(Files.exists(directory.resolve("archive")));
    }
    @Test void missingReplacementUploadIsStillAnError() throws Exception {
        Context context = context(sip(retained("UPDATE")));
        var failure = assertThrows(DepositException.class, () -> action().perform(context));
        assertTrue(failure.getMessage().contains("is marked UPDATE but has no local payload"));
    }
    @Test void allRemovedFilesLeaveEmptyResourceListAndPersistenceSucceeds() throws Exception {
        Context context = context(sip(""));
        assertTrue(action().perform(context));
        assertFalse(Files.exists(directory.resolve("archive")));
    }
    @Test void newPayloadMovesWhileRetainedReferenceKeepsItsHandle() throws Exception {
        Path upload = directory.resolve("incoming.png");
        Files.writeString(upload, "new binary");
        String incoming = "<cmd:ResourceProxy id='new'><cmd:ResourceType>Resource</cmd:ResourceType>"
                + "<cmd:ResourceRef lat:status='INSERT'>" + upload.toUri() + "</cmd:ResourceRef></cmd:ResourceProxy>";
        CMD sip = sip(retained("NOOP") + incoming);
        Context context = context(sip);
        try {
            assertTrue(action().perform(context));
            assertFalse(Files.exists(upload));
            assertEquals("new binary", Files.readString(directory.resolve("archive/Bundle/incoming.png")));
            Resource kept = sip.getResources().iterator().next();
            assertTrue(kept.isNoop());
            assertEquals(PID, kept.getPID().toString());
        } finally { context.close(); }
    }
    @Test void missingLaterUploadIsRejectedBeforeFirstPayloadMoves() throws Exception {
        Path upload = directory.resolve("incoming.png");
        Files.writeString(upload, "new binary");
        String incoming = "<cmd:ResourceProxy id='new'><cmd:ResourceType>Resource</cmd:ResourceType>"
                + "<cmd:ResourceRef lat:status='INSERT'>" + upload.toUri() + "</cmd:ResourceRef></cmd:ResourceProxy>";
        Context context = context(sip(incoming + retained("INSERT")));
        assertThrows(DepositException.class, () -> action().perform(context));
        assertEquals("new binary", Files.readString(upload));
        assertFalse(Files.exists(directory.resolve("archive")));
    }
}
