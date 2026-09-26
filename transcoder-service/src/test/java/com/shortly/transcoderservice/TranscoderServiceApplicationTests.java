package com.shortly.transcoderservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test that the application context wires up.
 * <p>
 * Worth keeping because the failure this catches is a broken bean graph, and the alternative is
 * discovering it from a container restart loop. The test profile keeps the AMQP listener from
 * connecting, so this needs no infrastructure.
 */
@SpringBootTest
class TranscoderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
