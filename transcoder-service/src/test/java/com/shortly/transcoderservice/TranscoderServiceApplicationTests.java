package com.shortly.transcoderservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Smoke test that the application context wires up. Worth keeping because the failure this catches
 * is a broken bean graph, and the alternative is discovering it from a container restart loop. */
@SpringBootTest
class TranscoderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
