package com.codenuance.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The set of languages a room can be set to, plus a friendly starter snippet for
 * each. The canonical id list lives here on the server so it can validate any
 * language a client asks for; the browser keeps the matching CodeMirror MIME
 * types for syntax highlighting.
 */
public final class Languages {

    public static final String DEFAULT = "javascript";

    private static final Map<String, String> STARTERS = new LinkedHashMap<>();

    private static final String INTRO_JS = "// Welcome to CodeNuance — a live, shared editor.\n"
            + "// Open this URL in another tab or send it to a friend;\n"
            + "// every keystroke syncs through an Operational Transformation engine.\n\n";

    static {
        STARTERS.put("javascript", INTRO_JS
                + "function greet(name) {\n  return `hello, ${name}`;\n}\n\nconsole.log(greet(\"world\"));\n");

        STARTERS.put("typescript", INTRO_JS
                + "function greet(name: string): string {\n  return `hello, ${name}`;\n}\n\nconsole.log(greet(\"world\"));\n");

        STARTERS.put("python",
                "# Welcome to CodeNuance — a live, shared editor.\n"
                + "# Open this URL in another tab or send it to a friend;\n"
                + "# every keystroke syncs through an Operational Transformation engine.\n\n"
                + "def greet(name):\n    return f\"hello, {name}\"\n\n\nprint(greet(\"world\"))\n");

        STARTERS.put("java", INTRO_JS
                + "class Main {\n"
                + "    static String greet(String name) {\n"
                + "        return \"hello, \" + name;\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(greet(\"world\"));\n"
                + "    }\n}\n");

        STARTERS.put("cpp", INTRO_JS
                + "#include <iostream>\n#include <string>\n\n"
                + "std::string greet(const std::string& name) {\n"
                + "    return \"hello, \" + name;\n}\n\n"
                + "int main() {\n"
                + "    std::cout << greet(\"world\") << std::endl;\n}\n");

        STARTERS.put("c",
                "/* Welcome to CodeNuance — a live, shared editor. */\n"
                + "#include <stdio.h>\n\n"
                + "int main(void) {\n"
                + "    printf(\"hello, world\\n\");\n    return 0;\n}\n");

        STARTERS.put("csharp", INTRO_JS
                + "using System;\n\n"
                + "class Program {\n"
                + "    static string Greet(string name) => $\"hello, {name}\";\n\n"
                + "    static void Main() {\n"
                + "        Console.WriteLine(Greet(\"world\"));\n    }\n}\n");

        STARTERS.put("go", INTRO_JS
                + "package main\n\nimport \"fmt\"\n\n"
                + "func greet(name string) string {\n    return \"hello, \" + name\n}\n\n"
                + "func main() {\n    fmt.Println(greet(\"world\"))\n}\n");

        STARTERS.put("rust", INTRO_JS
                + "fn greet(name: &str) -> String {\n    format!(\"hello, {name}\")\n}\n\n"
                + "fn main() {\n    println!(\"{}\", greet(\"world\"));\n}\n");

        STARTERS.put("ruby",
                "# Welcome to CodeNuance — a live, shared editor.\n\n"
                + "def greet(name)\n  \"hello, #{name}\"\nend\n\nputs greet(\"world\")\n");

        STARTERS.put("php",
                "<?php\n// Welcome to CodeNuance — a live, shared editor.\n\n"
                + "function greet($name) {\n    return \"hello, $name\";\n}\n\n"
                + "echo greet(\"world\") . PHP_EOL;\n");

        STARTERS.put("sql",
                "-- Welcome to CodeNuance — a live, shared editor.\n\n"
                + "SELECT 'hello, ' || name AS greeting\nFROM people\nORDER BY name;\n");

        STARTERS.put("html",
                "<!-- Welcome to CodeNuance — a live, shared editor. -->\n"
                + "<!DOCTYPE html>\n<html>\n  <body>\n    <h1>hello, world</h1>\n  </body>\n</html>\n");

        STARTERS.put("css",
                "/* Welcome to CodeNuance — a live, shared editor. */\n\n"
                + "body {\n  font-family: system-ui, sans-serif;\n  color: #43302e;\n  background: #fff1b5;\n}\n");
    }

    private Languages() {
    }

    public static Set<String> supported() {
        return STARTERS.keySet();
    }

    public static boolean isSupported(String id) {
        return id != null && STARTERS.containsKey(id);
    }

    /** Normalises an arbitrary client value to a supported id, falling back to the default. */
    public static String normalize(String id) {
        return isSupported(id) ? id : DEFAULT;
    }

    public static String starterFor(String id) {
        return STARTERS.getOrDefault(normalize(id), STARTERS.get(DEFAULT));
    }
}
