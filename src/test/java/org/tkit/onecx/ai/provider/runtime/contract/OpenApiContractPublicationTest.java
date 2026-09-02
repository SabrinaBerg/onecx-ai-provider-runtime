package org.tkit.onecx.ai.provider.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Validates that the OpenAPI contract published as the {@code openapi-runtime} classifier artifact
 * (see {@code pom.xml} / build-helper {@code attach-artifact}) exists and is structurally complete
 * for consumption by downstream modules.
 *
 * <p>
 * This is a plain JUnit test (no Quarkus bootstrap, no containers) so it runs in any environment
 * and guards the invariant that the released contract artifact is a well-formed, self-contained
 * OpenAPI document carrying the typed text-dispatch and provider-health operations consumers depend
 * on.
 */
class OpenApiContractPublicationTest {

    private static final Path CONTRACT = Paths.get("src/main/openapi/openapi-runtime.yaml");

    @Test
    void contractFile_existsAndIsWellFormedOpenApi() throws IOException {
        assertThat(CONTRACT)
                .as("the runtime contract source file must exist for publication")
                .isRegularFile();

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = loadContract();

        assertThat(spec)
                .as("the contract must parse into a YAML mapping")
                .isNotNull()
                .isNotEmpty();

        Object openapiVersion = spec.get("openapi");
        assertThat(openapiVersion)
                .as("the document must declare an OpenAPI version")
                .isNotNull()
                .isInstanceOf(String.class);
        assertThat((String) openapiVersion)
                .as("the document must target OpenAPI 3.x so it can be served and consumed")
                .startsWith("3.");
    }

    @Test
    void contract_carriesImmutableVersionIdentity() throws IOException {
        Map<String, Object> info = info();

        assertThat(info).as("info section must be present").isNotNull();

        Object title = info.get("title");
        assertThat(title).as("info.title must be non-blank").isNotNull();
        assertThat(String.valueOf(title)).isNotBlank();

        // The immutable identity field: consumers resolve the artifact by Maven version, but the
        // spec itself must carry a non-empty version so the released artifact is self-describing.
        Object version = info.get("version");
        assertThat(version)
                .as("info.version must be present (immutable identity of the contract)")
                .isNotNull();
        assertThat(String.valueOf(version)).isNotBlank();
    }

    @Test
    void contract_declaresTextDispatchOperation() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = paths();

        @SuppressWarnings("unchecked")
        Map<String, Object> chatPath = (Map<String, Object>) paths.get("/internal/runtime/chat");
        assertThat(chatPath).as("the text dispatch path /internal/runtime/chat must be declared").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) chatPath.get("post");
        assertThat(post).as("the text dispatch path must expose a POST operation").isNotNull();

        assertThat(post.get("operationId")).as("text dispatch operationId must remain stable")
                .isEqualTo("chat");
        assertThat(requestSchemaRef(post)).isEqualTo("#/components/schemas/RuntimeChatRequest");
        assertThat(responseSchemaRef(post, "200")).isEqualTo("#/components/schemas/RuntimeChatResponse");
    }

    @Test
    void contract_declaresProviderHealthOperation() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = paths();

        @SuppressWarnings("unchecked")
        Map<String, Object> healthPath = (Map<String, Object>) paths.get("/internal/runtime/provider-health");
        assertThat(healthPath).as("the provider-health path must be declared").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) healthPath.get("post");
        assertThat(post).as("the provider-health path must expose a POST operation").isNotNull();

        assertThat(post.get("operationId")).as("provider-health operationId must remain stable")
                .isEqualTo("getProviderHealthStatus");
        assertThat(requestSchemaRef(post)).isEqualTo("#/components/schemas/ProviderHealthRequest");
        assertThat(responseSchemaRef(post, "200")).isEqualTo("#/components/schemas/ProviderHealthStatus");
    }

    @Test
    void contract_declaresTypedDispatchSchemas() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = schemas();

        // The schemas consumers' generated clients are built from must all be present.
        assertThat(schemas).containsKeys(
                "RuntimeChatRequest",
                "RuntimeChatResponse",
                "ProviderHealthRequest",
                "ProviderHealthStatus",
                "ChatRequest",
                "ChatMessage",
                "AgentSnapshot",
                "ProviderSnapshot");

        // The typed text-dispatch request must keep its required fields, so existing dispatch
        // requests remain valid when the contract is republished.
        @SuppressWarnings("unchecked")
        Map<String, Object> chatRequest = (Map<String, Object>) schemas.get("RuntimeChatRequest");
        @SuppressWarnings("unchecked")
        List<String> chatRequestRequired = (List<String>) chatRequest.get("required");
        assertThat(chatRequestRequired)
                .as("RuntimeChatRequest required fields define the typed dispatch contract")
                .containsExactlyInAnyOrder("chatRequest", "rootAgent");

        // The provider-health status must keep its required status enum.
        @SuppressWarnings("unchecked")
        Map<String, Object> healthStatus = (Map<String, Object>) schemas.get("ProviderHealthStatus");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusProps = (Map<String, Object>) healthStatus.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusField = (Map<String, Object>) statusProps.get("status");
        @SuppressWarnings("unchecked")
        List<String> statusEnum = (List<String>) statusField.get("enum");
        assertThat(statusEnum)
                .as("ProviderHealthStatus.status enum is part of the provider-health contract")
                .containsExactlyInAnyOrder("HEALTHY", "UNHEALTHY");
        @SuppressWarnings("unchecked")
        List<String> healthStatusRequired = (List<String>) healthStatus.get("required");
        assertThat(healthStatusRequired).containsExactly("status");

        // The text-dispatch response must expose a string message field.
        @SuppressWarnings("unchecked")
        Map<String, Object> chatResponse = (Map<String, Object>) schemas.get("RuntimeChatResponse");
        @SuppressWarnings("unchecked")
        Map<String, Object> responseProps = (Map<String, Object>) chatResponse.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> messageField = (Map<String, Object>) responseProps.get("message");
        assertThat(messageField.get("type"))
                .as("RuntimeChatResponse.message must remain a string")
                .isEqualTo("string");
    }

    private Map<String, Object> loadContract() throws IOException {
        // Resolve relative to the project basedir (surefire working directory); fall back to the
        // file itself so the test also passes when run from an IDE with a different working dir.
        Path resolved = resolveContractPath();
        try (InputStream in = Files.newInputStream(resolved)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new AssertionError("OpenAPI contract did not parse into a mapping: " + resolved);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) loaded;
            return spec;
        }
    }

    private static Path resolveContractPath() {
        // surefire runs with the project basedir as the working directory; walk up defensively so
        // the test also passes when launched from an IDE with a different working directory.
        Path candidate = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5; depth++) {
            Path resolved = candidate.resolve(CONTRACT);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
            if (candidate.getParent() == null) {
                break;
            }
            candidate = candidate.getParent();
        }
        return Paths.get("").toAbsolutePath().resolve(CONTRACT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> info() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = loadContract();
        return (Map<String, Object>) spec.get("info");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = loadContract();
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = loadContract();
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        assertThat(components).as("components section must be present").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).as("components.schemas must be present").isNotNull();
        return schemas;
    }

    @SuppressWarnings("unchecked")
    private static String requestSchemaRef(Map<String, Object> operation) {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) operation.get("requestBody");
        assertThat(requestBody).as("operation must declare a request body").isNotNull();
        assertThat(Boolean.TRUE.equals(requestBody.get("required")))
                .as("the request body must be required");
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) requestBody.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        return (String) schema.get("$ref");
    }

    @SuppressWarnings("unchecked")
    private static String responseSchemaRef(Map<String, Object> operation, String statusCode) {
        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) responses.get(statusCode);
        assertThat(response).as("operation must declare a " + statusCode + " response").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) response.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        return (String) schema.get("$ref");
    }
}
