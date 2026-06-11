package com.codenuance.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Runs a snippet through a Judge0 instance and returns the result (stdout, stderr,
 * compile output, status, time, memory) to the editor's output console.
 *
 * <p>The Judge0 endpoint is configurable so you can point this at the free public
 * instance (default), a self-hosted Judge0, or the RapidAPI Judge0 CE — see
 * {@code application.properties}. When RapidAPI is used, set {@code judge0.key}
 * and {@code judge0.host} and the RapidAPI auth headers are sent automatically.
 */
@RestController
@RequestMapping("/api/run")
public class RunController {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper json;

    private final String judgeUrl;
    private final String judgeKey;
    private final String judgeHost;

    public RunController(ObjectMapper json,
                         @Value("${judge0.url:https://ce.judge0.com}") String judgeUrl,
                         @Value("${judge0.key:}") String judgeKey,
                         @Value("${judge0.host:judge0-ce.p.rapidapi.com}") String judgeHost) {
        this.json = json;
        this.judgeUrl = judgeUrl.replaceAll("/+$", "");
        this.judgeKey = judgeKey == null ? "" : judgeKey.trim();
        this.judgeHost = judgeHost;
    }

    public record RunRequest(String language, String source, String stdin) {
    }

    @PostMapping
    public ResponseEntity<JsonNode> run(@RequestBody RunRequest req) {
        String language = req.language();
        if (!Judge0Languages.isRunnable(language)) {
            return ResponseEntity.ok(error("\"" + language + "\" can't be executed — "
                    + "it has no runtime here. Try Java, JavaScript, Python, C++, or TypeScript."));
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("language_id", Judge0Languages.idFor(language));
        payload.put("source_code", req.source() == null ? "" : req.source());
        if (req.stdin() != null && !req.stdin().isEmpty()) {
            payload.put("stdin", req.stdin());
        }

        URI uri = URI.create(judgeUrl + "/submissions?base64_encoded=false&wait=true");
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        if (!judgeKey.isEmpty()) {
            b.header("X-RapidAPI-Key", judgeKey);
            b.header("X-RapidAPI-Host", judgeHost);
        }

        try {
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return ResponseEntity.ok(error("Judge0 returned HTTP " + resp.statusCode()
                        + ". If you're using the public instance it may be rate-limited — "
                        + "configure judge0.url / judge0.key in application.properties."));
            }
            return ResponseEntity.ok(json.readTree(resp.body()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.OK).body(error(
                    "Couldn't reach the code-execution service (" + e.getClass().getSimpleName()
                    + "). Check your network or set a Judge0 endpoint in application.properties."));
        }
    }

    private JsonNode error(String message) {
        return json.valueToTree(Map.of("clientError", true, "message", message));
    }
}
