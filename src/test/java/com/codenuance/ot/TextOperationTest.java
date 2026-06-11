package com.codenuance.ot;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property tests for the OT engine. The decisive one is
 * {@link #transformConvergesForRandomConcurrentEdits()}: for thousands of random
 * pairs of concurrent edits it asserts the convergence law
 * {@code apply(apply(S,a), b') == apply(apply(S,b), a')}. If that holds, two
 * users editing at once can never permanently diverge.
 */
class TextOperationTest {

    @Test
    void applyPerformsRetainInsertDelete() {
        TextOperation op = new TextOperation()
                .retain(5)       // "hello"
                .insert(", ")    // add a comma
                .retain(5)       // "world"
                .delete(1);      // drop "!"
        assertEquals("hello, world", op.apply("helloworld!"));
    }

    @Test
    void composeIsEquivalentToSequentialApply() {
        String doc = "the quick brown fox";
        TextOperation a = new TextOperation().retain(19).insert(" jumps");
        TextOperation b = new TextOperation().delete(4).retain(21);
        TextOperation composed = TextOperation.compose(a, b);
        assertEquals(b.apply(a.apply(doc)), composed.apply(doc));
    }

    @Test
    void transformConvergesForRandomConcurrentEdits() {
        Random rnd = new Random(42);
        for (int i = 0; i < 5000; i++) {
            String doc = randomString(rnd, rnd.nextInt(40));
            TextOperation a = randomOperation(rnd, doc);
            TextOperation b = randomOperation(rnd, doc);

            TextOperation[] primes = TextOperation.transform(a, b);
            TextOperation aPrime = primes[0];
            TextOperation bPrime = primes[1];

            String viaA = bPrime.apply(a.apply(doc));
            String viaB = aPrime.apply(b.apply(doc));
            assertEquals(viaB, viaA, "divergence on iteration " + i + " for doc=\"" + doc + "\"");
        }
    }

    // ----- Random fixture builders ---------------------------------------

    private static String randomString(Random rnd, int len) {
        StringBuilder sb = new StringBuilder();
        String alphabet = "abc \n";
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Builds a random but valid operation whose base length matches {@code doc}. */
    private static TextOperation randomOperation(Random rnd, String doc) {
        TextOperation op = new TextOperation();
        int i = 0;
        int n = doc.length();
        while (i < n) {
            int remaining = n - i;
            int chunk = 1 + rnd.nextInt(remaining);
            switch (rnd.nextInt(3)) {
                case 0 -> { // retain
                    op.retain(chunk);
                    i += chunk;
                }
                case 1 -> { // insert (does not consume the document)
                    op.insert(randomString(rnd, 1 + rnd.nextInt(3)));
                }
                default -> { // delete
                    op.delete(chunk);
                    i += chunk;
                }
            }
        }
        // Occasionally end with a trailing insert.
        if (rnd.nextBoolean()) {
            op.insert(randomString(rnd, 1 + rnd.nextInt(3)));
        }
        return op;
    }
}
