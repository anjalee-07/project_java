package com.codenuance.run;

import java.util.Map;

/**
 * Maps CodeNuance language ids (the same ones {@link com.codenuance.session.Languages}
 * uses) to Judge0 CE {@code language_id} values. Languages that have no meaningful
 * "run" (markup/stylesheets) are intentionally absent — the editor disables the Run
 * button for them.
 */
public final class Judge0Languages {

    // Judge0 CE language ids (https://ce.judge0.com/languages).
    private static final Map<String, Integer> IDS = Map.ofEntries(
            Map.entry("c", 50),          // C (GCC 9.2.0)
            Map.entry("cpp", 54),        // C++ (GCC 9.2.0)
            Map.entry("java", 62),       // Java (OpenJDK 13.0.1)
            Map.entry("javascript", 63), // JavaScript (Node.js 12.14.0)
            Map.entry("typescript", 74), // TypeScript (3.7.4)
            Map.entry("python", 71),     // Python (3.8.1)
            Map.entry("csharp", 51),     // C# (Mono 6.6.0.161)
            Map.entry("go", 60),         // Go (1.13.5)
            Map.entry("rust", 73),       // Rust (1.40.0)
            Map.entry("ruby", 72),       // Ruby (2.7.0)
            Map.entry("php", 68),        // PHP (7.4.1)
            Map.entry("sql", 82));       // SQL (SQLite 3.27.2)

    private Judge0Languages() {
    }

    public static boolean isRunnable(String languageId) {
        return languageId != null && IDS.containsKey(languageId);
    }

    /** @return the Judge0 language_id, or {@code null} if the language isn't runnable. */
    public static Integer idFor(String languageId) {
        return IDS.get(languageId);
    }
}
