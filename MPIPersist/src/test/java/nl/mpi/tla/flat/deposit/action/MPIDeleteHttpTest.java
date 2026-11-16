package nl.mpi.tla.flat.deposit.action;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.util.Saxon;
import org.apache.commons.configuration.XMLConfiguration;
import org.fcrepo.client.FcrepoClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MPIDeleteHttpTest {
    @TempDir Path directory;
    private HttpServer server;
    private HttpDeleter action;
    private final AtomicReference<String> contentLocation = new AtomicReference<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final List<String> requests = new ArrayList<>();
    private String location;
    private String link;
    private int headStatus = 200;
    private int relocationStatus = 200;
    private String requestBody;

    private static class HttpDeleter extends MPIDelete {
        HttpDeleter(String base, Path journals) {
            fedoraConfig = new XMLConfiguration();
            fedoraConfig.addProperty("localServer", base);
            fedoraConfig.addProperty("userPass", "test-password");
            fedoraClient = FcrepoClient.client().build();
            setParameters(Map.of("detachedResources", new XdmAtomicValue("true"),
                    "journalDir", new XdmAtomicValue(journals.toString())));
        }
        @Override public void connect(Context context) { }
        @Override public String getFedoraUser() { return "test-user"; }
        void close() throws Exception { fedoraClient.close(); }
    }
    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/lat_removed/OBJ", exchange -> {
            try {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                if (exchange.getRequestMethod().equals("HEAD")) {
                    // Fedora 7 emits Content-Location and ordinary LDP links,
                    // without an ExternalContent Link response header.
                    if (contentLocation.get() != null)
                        exchange.getResponseHeaders().add("content-location", contentLocation.get());
                    if (location != null) exchange.getResponseHeaders().add("Location", location);
                    if (link != null) exchange.getResponseHeaders().add("Link", link);
                    exchange.sendResponseHeaders(headStatus, -1);
                } else if (exchange.getRequestMethod().equals("POST")) {
                    requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (relocationStatus == 200) {
                        var operation = new ObjectMapper().readTree(requestBody);
                        Path from = Path.of(URI.create(operation.path("expectedUrl").asText()));
                        Path to = Path.of(URI.create(operation.path("replacementUrl").asText()));
                        Files.move(from, to);
                        contentLocation.set(operation.path("replacementUrl").asText());
                    }
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(relocationStatus, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } catch (Throwable failure) {
                serverFailure.set(failure);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        action = new HttpDeleter("http://127.0.0.1:" + server.getAddress().getPort() + "/rest",
                directory.resolve("journals"));
    }
    @AfterEach void stopServer() throws Exception {
        if (server != null) server.stop(0);
        if (action != null) action.close();
        assertNull(serverFailure.get());
    }
    private Context context() throws Exception {
        return new Context(null, Saxon.buildDocument(new StreamSource(new StringReader("<flow/>"))),
                Map.of("dk-pidList", new XdmAtomicValue(directory.resolve("pids.csv").toString())));
    }
    @Test void resolvesFedora7ContentLocationWithoutAnExternalLink() throws Exception {
        contentLocation.set("file:/archive/audio%20one.wav");
        link = "<http://www.w3.org/ns/ldp#NonRDFSource>; rel=\"type\"";
        assertEquals(URI.create(contentLocation.get()), action.externalContent(URI.create("lat_removed")));
        assertEquals(List.of("HEAD /rest/lat_removed/OBJ"), requests);
    }
    @Test void realHeaderLookupReachesRelocationAndRetryKeepsOneSuffix() throws Exception {
        Path original = directory.resolve("audio with spaces.wav");
        Files.writeString(original, "preserved bytes");
        contentLocation.set(original.toUri().toString());
        action.markDeleted(context(), URI.create("lat_removed"));
        assertFalse(Files.exists(original));
        Path renamed = Path.of(URI.create(contentLocation.get()));
        assertTrue(renamed.getFileName().toString().startsWith(original.getFileName() + ".Deleted."));
        assertEquals("preserved bytes", Files.readString(renamed));
        var operation = new ObjectMapper().readTree(requestBody);
        assertTrue(operation.path("operationId").asText().startsWith("delete-"));
        assertTrue(Files.isDirectory(directory.resolve("journals")));
        action.markDeleted(context(), URI.create("lat_removed"));
        assertEquals(List.of("HEAD /rest/lat_removed/OBJ", "POST /rest/lat_removed/OBJ/fcr:relocate-external",
                "HEAD /rest/lat_removed/OBJ"), requests);
    }
    @Test void missingExternalHeadersCannotSilentlyCompleteDetachment() throws Exception {
        var failure = assertThrows(DepositException.class, () -> action.markDeleted(context(), URI.create("lat_removed")));
        assertTrue(failure.getMessage().contains("did not expose an external content URL"));
        assertEquals(List.of("HEAD /rest/lat_removed/OBJ"), requests);
    }
    @Test void contentLocationTakesPrecedenceOverLegacyLink() throws Exception {
        contentLocation.set("file:/archive/current.wav");
        link = "<file:/archive/stale.wav>; rel=\"http://fedora.info/definitions/v4/repository#ExternalContent\"";
        assertEquals(URI.create(contentLocation.get()), action.externalContent(URI.create("lat_removed")));
    }
    @Test void legacyExternalLinkRemainsSupported() throws Exception {
        link = "<file:/archive/current.wav>; rel=\"http://fedora.info/definitions/v4/repository#ExternalContent\"";
        assertEquals(URI.create("file:/archive/current.wav"), action.externalContent(URI.create("lat_removed")));
    }
    @Test void redirectLocationIsReadWithoutFollowingTheExternalTarget() throws Exception {
        headStatus = 307;
        location = "file:/archive/current.wav";
        assertEquals(URI.create(location), action.externalContent(URI.create("lat_removed")));
        assertEquals(List.of("HEAD /rest/lat_removed/OBJ"), requests);
    }
    @Test void missingObjFailsInsteadOfReportingSuccess() throws Exception {
        for (int status : List.of(404, 410)) {
            headStatus = status;
            assertThrows(DepositException.class, () -> action.externalContent(URI.create("lat_removed")));
        }
    }
    @Test void permissionFailureIsPropagated() throws Exception {
        headStatus = 403;
        assertThrows(DepositException.class, () -> action.externalContent(URI.create("lat_removed")));
    }
    @Test void failedRelocationKeepsOriginalFileAndJournal() throws Exception {
        Path original = directory.resolve("audio.wav");
        Files.writeString(original, "preserved bytes");
        contentLocation.set(original.toUri().toString());
        relocationStatus = 409;
        assertThrows(DepositException.class, () -> action.markDeleted(context(), URI.create("lat_removed")));
        assertEquals("preserved bytes", Files.readString(original));
        try (var journals = Files.list(directory.resolve("journals"))) { assertEquals(1, journals.count()); }
    }
}
