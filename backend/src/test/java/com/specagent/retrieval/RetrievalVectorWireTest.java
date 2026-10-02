package com.specagent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.retrieval.protocol.RetrievalVectors;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalVectorWireTest {
    @Test
    void consumesPythonFloat32ChecksumFixture() throws Exception {
        var root = new ObjectMapper().readTree(Files.readString(Path.of(
                "../contracts/retrieval/fixtures/store-candidates-request-valid.json")));
        var vector = root.get("queryVector");
        double[] values = new ObjectMapper().convertValue(vector.get("values"), double[].class);
        assertEquals(vector.get("checksum").textValue(), RetrievalVectors.checksum(values));
    }

    @Test
    void nonfiniteDimensionsAndUnnormalizedVectorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalVectors.checksum(new double[1]));
        assertThrows(IllegalArgumentException.class, () -> RetrievalVectors.checksum(new double[1024]));
        double[] values = new double[1024];
        values[0] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> RetrievalVectors.checksum(values));
        values[0] = Double.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> RetrievalVectors.checksum(values));
    }
}
