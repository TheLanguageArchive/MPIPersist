package nl.mpi.tla.flat.deposit.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.transform.stream.StreamSource;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.util.Saxon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MPIPersistsPathTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sanitizesNamesLikeTheFedora3Implementation() throws Exception {
        assertEquals("Test_speed_1", MPIPersists.sanitizePathComponent("Test speed 1"));
        assertEquals("Ile_de_Re", MPIPersists.sanitizePathComponent("Île-de-Ré"));
    }

    @Test
    void readsThePreservedFedoraObjectLabel() throws Exception {
        final var description = Saxon.buildDocument(new StreamSource(new StringReader("""
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:model="info:fedora/fedora-system:def/model#"
                         xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <rdf:Description>
                    <dc:title>Documentation of endangered languages</dc:title>
                    <model:label>DOBES Archive</model:label>
                  </rdf:Description>
                </rdf:RDF>
                """)));

        assertEquals("DOBES Archive", MPIPersists.preservedObjectLabel(description));
    }

    @Test
    void readsTheFirstDcTitleAsFallback() throws Exception {
        final var dc = Saxon.buildDocument(new StreamSource(new StringReader("""
                <oai_dc:dc xmlns:oai_dc="http://www.openarchives.org/OAI/2.0/oai_dc/"
                           xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>DOBES Archive</dc:title>
                  <dc:title>Documentation of endangered languages</dc:title>
                </oai_dc:dc>
                """)));

        assertEquals("DOBES Archive", MPIPersists.firstDublinCoreTitle(dc));
    }

    @Test
    void prefersTheExplicitDrupalBundleNameOverCmdiMetadata() throws Exception {
        final var record = Saxon.buildDocument(new StreamSource(new StringReader("""
                <CMD xmlns="http://www.clarin.eu/cmd/1">
                  <Components><Bundle><Name>98e71ad1-6320-4348-8fd1-f311e345f120</Name></Bundle></Components>
                </CMD>
                """)));

        assertEquals("First test bundle", MPIPersists.datasetName(record,
                "First test bundle",
                "normalize-space((/cmd:CMD/cmd:Components/*/cmd:Name)[1])"));
    }

    @Test
    void appliesTheFirstMatchingArchiveRootMapping() throws Exception {
        final Path mapping = temporaryDirectory.resolve("archive-roots-mapping.xml");
        Files.writeString(mapping, """
                <archive-roots-mapping>
                  <mapping>
                    <sanitized-fedora-path>/The_Language_Archive/Specific/</sanitized-fedora-path>
                    <file-system-path>/archive-storage/archive-data/special/</file-system-path>
                  </mapping>
                  <mapping>
                    <sanitized-fedora-path>/The_Language_Archive/</sanitized-fedora-path>
                    <file-system-path>/archive-storage/archive-data/</file-system-path>
                  </mapping>
                </archive-roots-mapping>
                """);

        assertEquals(Path.of("/archive-storage/archive-data/special/Bundle_name"),
                MPIPersists.applyArchiveRootMapping(mapping.toFile(),
                        "/The_Language_Archive/Specific/Bundle_name/"));
    }

    @Test
    void rejectsAnUnmappedHierarchy() throws Exception {
        final Path mapping = temporaryDirectory.resolve("archive-roots-mapping.xml");
        Files.writeString(mapping, """
                <archive-roots-mapping>
                  <mapping>
                    <sanitized-fedora-path>/The_Language_Archive/</sanitized-fedora-path>
                    <file-system-path>/archive-storage/archive-data/</file-system-path>
                  </mapping>
                </archive-roots-mapping>
                """);

        assertThrows(DepositException.class, () -> MPIPersists.applyArchiveRootMapping(
                mapping.toFile(), "/Another_archive/Bundle/"));
    }
}
