package com.codenuance.ot;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable-ish description of how to turn one string into another.
 *
 * <p>An operation is a sequence of components, applied left to right against a
 * document of length {@link #baseLength}:
 * <ul>
 *   <li><b>retain(n)</b> — keep the next {@code n} characters unchanged (stored as a positive int)</li>
 *   <li><b>insert(s)</b> — insert the string {@code s} at the cursor (stored as a String)</li>
 *   <li><b>delete(n)</b> — drop the next {@code n} characters (stored as a negative int)</li>
 * </ul>
 *
 * <p>This is the same model used by Etherpad / ShareDB / ot.js. Two operations
 * generated against the <i>same</i> document can be merged deterministically with
 * {@link #transform(TextOperation, TextOperation)} so that every collaborator
 * converges on an identical document regardless of network ordering. That property
 * — convergence under concurrent edits — is what makes a Google-Docs-style editor
 * possible without locking the document.
 */
public final class TextOperation {

    /** Components: Integer (>0 retain, <0 delete) or String (insert). */
    private final List<Object> ops = new ArrayList<>();

    /** Length of the document this operation expects as input. */
    private int baseLength = 0;

    /** Length of the document produced after applying this operation. */
    private int targetLength = 0;

    public List<Object> getOps() {
        return ops;
    }

    public int getBaseLength() {
        return baseLength;
    }

    public int getTargetLength() {
        return targetLength;
    }

    // ----- Builders -------------------------------------------------------

    public TextOperation retain(int n) {
        if (n == 0) {
            return this;
        }
        if (n < 0) {
            throw new IllegalArgumentException("retain expects a positive length");
        }
        baseLength += n;
        targetLength += n;
        int last = ops.size() - 1;
        if (last >= 0 && isRetain(ops.get(last))) {
            // Merge with the previous retain to keep operations compact.
            ops.set(last, (Integer) ops.get(last) + n);
        } else {
            ops.add(n);
        }
        return this;
    }

    public TextOperation insert(String s) {
        if (s == null || s.isEmpty()) {
            return this;
        }
        targetLength += s.length();
        int last = ops.size() - 1;
        if (last >= 0 && isInsert(ops.get(last))) {
            ops.set(last, (String) ops.get(last) + s);
        } else if (last >= 0 && isDelete(ops.get(last))) {
            // Keep inserts before deletes for a canonical ordering.
            if (last > 0 && isInsert(ops.get(last - 1))) {
                ops.set(last - 1, (String) ops.get(last - 1) + s);
            } else {
                ops.add(last, s);
            }
        } else {
            ops.add(s);
        }
        return this;
    }

    public TextOperation delete(int n) {
        if (n == 0) {
            return this;
        }
        if (n < 0) {
            n = -n; // accept either signed convention from callers
        }
        baseLength += n;
        int last = ops.size() - 1;
        if (last >= 0 && isDelete(ops.get(last))) {
            ops.set(last, (Integer) ops.get(last) - n);
        } else {
            ops.add(-n);
        }
        return this;
    }

    // ----- Component type checks -----------------------------------------

    static boolean isRetain(Object o) {
        return o instanceof Integer && (Integer) o > 0;
    }

    static boolean isDelete(Object o) {
        return o instanceof Integer && (Integer) o < 0;
    }

    static boolean isInsert(Object o) {
        return o instanceof String;
    }

    // ----- Apply ----------------------------------------------------------

    /**
     * Apply this operation to {@code doc}, producing the transformed document.
     */
    public String apply(String doc) {
        if (doc.length() != baseLength) {
            throw new IllegalArgumentException(
                    "operation base length " + baseLength + " does not match document length " + doc.length());
        }
        StringBuilder out = new StringBuilder(targetLength);
        int cursor = 0;
        for (Object op : ops) {
            if (isRetain(op)) {
                int n = (Integer) op;
                out.append(doc, cursor, cursor + n);
                cursor += n;
            } else if (isInsert(op)) {
                out.append((String) op);
            } else { // delete
                cursor += -(Integer) op;
            }
        }
        return out.toString();
    }

    // ----- Compose --------------------------------------------------------

    /**
     * Returns an operation equivalent to applying {@code a} and then {@code b}.
     * Requires {@code a.targetLength == b.baseLength}.
     */
    public static TextOperation compose(TextOperation a, TextOperation b) {
        if (a.targetLength != b.baseLength) {
            throw new IllegalArgumentException("compose: a.targetLength must equal b.baseLength");
        }
        TextOperation result = new TextOperation();
        List<Object> ops1 = a.ops;
        List<Object> ops2 = b.ops;
        int i1 = 0, i2 = 0;
        Object op1 = next(ops1, i1++);
        Object op2 = next(ops2, i2++);

        while (true) {
            if (op1 == null && op2 == null) {
                break;
            }
            if (isDelete(op1)) {
                result.delete((Integer) op1);
                op1 = next(ops1, i1++);
                continue;
            }
            if (isInsert(op2)) {
                result.insert((String) op2);
                op2 = next(ops2, i2++);
                continue;
            }
            if (op1 == null) {
                throw new IllegalStateException("compose: first operation is too short");
            }
            if (op2 == null) {
                throw new IllegalStateException("compose: first operation is too long");
            }

            if (isRetain(op1) && isRetain(op2)) {
                int n1 = (Integer) op1, n2 = (Integer) op2;
                if (n1 > n2) {
                    result.retain(n2);
                    op1 = n1 - n2;
                    op2 = next(ops2, i2++);
                } else if (n1 < n2) {
                    result.retain(n1);
                    op2 = n2 - n1;
                    op1 = next(ops1, i1++);
                } else {
                    result.retain(n1);
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
            } else if (isInsert(op1) && isDelete(op2)) {
                String s = (String) op1;
                int d = -(Integer) op2;
                if (s.length() > d) {
                    op1 = s.substring(d);
                    op2 = next(ops2, i2++);
                } else if (s.length() < d) {
                    op2 = -(d - s.length());
                    op1 = next(ops1, i1++);
                } else {
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
            } else if (isInsert(op1) && isRetain(op2)) {
                String s = (String) op1;
                int n2 = (Integer) op2;
                if (s.length() > n2) {
                    result.insert(s.substring(0, n2));
                    op1 = s.substring(n2);
                    op2 = next(ops2, i2++);
                } else if (s.length() < n2) {
                    result.insert(s);
                    op2 = n2 - s.length();
                    op1 = next(ops1, i1++);
                } else {
                    result.insert(s);
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
            } else if (isRetain(op1) && isDelete(op2)) {
                int n1 = (Integer) op1;
                int d = -(Integer) op2;
                if (n1 > d) {
                    result.delete(d);
                    op1 = n1 - d;
                    op2 = next(ops2, i2++);
                } else if (n1 < d) {
                    result.delete(n1);
                    op2 = -(d - n1);
                    op1 = next(ops1, i1++);
                } else {
                    result.delete(d);
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
            } else {
                throw new IllegalStateException("compose: unreachable component combination");
            }
        }
        return result;
    }

    // ----- Transform (the core OT operation) ------------------------------

    /**
     * The defining property of OT. Given two operations {@code a} and {@code b}
     * that were both produced against the same document, returns {@code [a', b']}
     * such that:
     *
     * <pre>compose(a, b') == compose(b, a')</pre>
     *
     * i.e. applying {@code a} then {@code b'} yields the exact same document as
     * applying {@code b} then {@code a'}. This is how two people typing at the
     * same time end up with identical files.
     */
    public static TextOperation[] transform(TextOperation a, TextOperation b) {
        if (a.baseLength != b.baseLength) {
            throw new IllegalArgumentException("transform: both operations must share a base length");
        }
        TextOperation aPrime = new TextOperation();
        TextOperation bPrime = new TextOperation();
        List<Object> ops1 = a.ops;
        List<Object> ops2 = b.ops;
        int i1 = 0, i2 = 0;
        Object op1 = next(ops1, i1++);
        Object op2 = next(ops2, i2++);

        while (true) {
            if (op1 == null && op2 == null) {
                break;
            }

            // Inserts always win their slot: the other side just retains over them.
            if (isInsert(op1)) {
                String s = (String) op1;
                aPrime.insert(s);
                bPrime.retain(s.length());
                op1 = next(ops1, i1++);
                continue;
            }
            if (isInsert(op2)) {
                String s = (String) op2;
                aPrime.retain(s.length());
                bPrime.insert(s);
                op2 = next(ops2, i2++);
                continue;
            }

            if (op1 == null) {
                throw new IllegalStateException("transform: operation a is too short");
            }
            if (op2 == null) {
                throw new IllegalStateException("transform: operation b is too short");
            }

            int minl;
            if (isRetain(op1) && isRetain(op2)) {
                int n1 = (Integer) op1, n2 = (Integer) op2;
                if (n1 > n2) {
                    minl = n2;
                    op1 = n1 - n2;
                    op2 = next(ops2, i2++);
                } else if (n1 < n2) {
                    minl = n1;
                    op2 = n2 - n1;
                    op1 = next(ops1, i1++);
                } else {
                    minl = n1;
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
                aPrime.retain(minl);
                bPrime.retain(minl);
            } else if (isDelete(op1) && isDelete(op2)) {
                // Both delete the same region — collapse to a single delete (no-op for both primes).
                int d1 = -(Integer) op1, d2 = -(Integer) op2;
                if (d1 > d2) {
                    op1 = -(d1 - d2);
                    op2 = next(ops2, i2++);
                } else if (d1 < d2) {
                    op2 = -(d2 - d1);
                    op1 = next(ops1, i1++);
                } else {
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
            } else if (isDelete(op1) && isRetain(op2)) {
                int d1 = -(Integer) op1, n2 = (Integer) op2;
                if (d1 > n2) {
                    minl = n2;
                    op1 = -(d1 - n2);
                    op2 = next(ops2, i2++);
                } else if (d1 < n2) {
                    minl = d1;
                    op2 = n2 - d1;
                    op1 = next(ops1, i1++);
                } else {
                    minl = d1;
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
                aPrime.delete(minl);
            } else if (isRetain(op1) && isDelete(op2)) {
                int n1 = (Integer) op1, d2 = -(Integer) op2;
                if (n1 > d2) {
                    minl = d2;
                    op1 = n1 - d2;
                    op2 = next(ops2, i2++);
                } else if (n1 < d2) {
                    minl = n1;
                    op2 = -(d2 - n1);
                    op1 = next(ops1, i1++);
                } else {
                    minl = n1;
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                }
                bPrime.delete(minl);
            } else {
                throw new IllegalStateException("transform: unreachable component combination");
            }
        }
        return new TextOperation[]{aPrime, bPrime};
    }

    /** Returns the component at {@code index}, or {@code null} when past the end. */
    private static Object next(List<Object> ops, int index) {
        return index < ops.size() ? ops.get(index) : null;
    }

    @Override
    public String toString() {
        return "TextOperation" + ops;
    }
}
