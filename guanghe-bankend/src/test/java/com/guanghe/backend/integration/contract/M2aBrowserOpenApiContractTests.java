package com.guanghe.backend.integration.contract;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class M2aBrowserOpenApiContractTests {

    private static final String BROWSER = "/contracts/openapi/browser-openapi.yaml";
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/me", "/api/v1/auth/logout"
    );
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("GET /api/v1/spaces", "listBusinessSpaces"),
            Map.entry("POST /api/v1/spaces", "createBusinessSpace"),
            Map.entry("GET /api/v1/spaces/{spaceId}", "getBusinessSpace"),
            Map.entry("PATCH /api/v1/spaces/{spaceId}", "updateBusinessSpace"),
            Map.entry("DELETE /api/v1/spaces/{spaceId}", "deleteBusinessSpace"),
            Map.entry("GET /api/v1/spaces/{spaceId}/products", "listProducts"),
            Map.entry("POST /api/v1/spaces/{spaceId}/products", "createProduct"),
            Map.entry("GET /api/v1/products/{productId}", "getProduct"),
            Map.entry("PATCH /api/v1/products/{productId}", "updateProduct"),
            Map.entry("DELETE /api/v1/products/{productId}", "deleteProduct"),
            Map.entry("PUT /api/v1/products/{productId}/aliases", "replaceProductAliases"),
            Map.entry("GET /api/v1/spaces/{spaceId}/competitors", "listCompetitors"),
            Map.entry("POST /api/v1/spaces/{spaceId}/competitors", "createCompetitor"),
            Map.entry("GET /api/v1/competitors/{competitorId}", "getCompetitor"),
            Map.entry("PATCH /api/v1/competitors/{competitorId}", "updateCompetitor"),
            Map.entry("DELETE /api/v1/competitors/{competitorId}", "deleteCompetitor"),
            Map.entry("PUT /api/v1/competitors/{competitorId}/aliases", "replaceCompetitorAliases")
    );

    @Test
    void catalogMethodsPathsAndOperationIdsAreExact() throws Exception {
        OpenAPI browser = parse();
        Map<String, String> actual = new LinkedHashMap<>();
        browser.getPaths().forEach((path, item) -> {
            item.readOperationsMap().forEach((method, operation) -> {
                String key = method.name() + " " + path;
                if (OPERATIONS.containsKey(key)) {
                    actual.put(key, operation.getOperationId());
                }
            });
        });

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(OPERATIONS);
        assertThat(actual).hasSize(17);
    }

    @Test
    void allCatalogOperationsRequireBearerAndNoneRequireIdempotencyKey() throws Exception {
        OpenAPI browser = parse();
        catalogOperations(browser).forEach(operation -> {
            assertThat(operation.getSecurity()).as(operation.getOperationId()).isNotEmpty();
            assertThat(operation.getParameters() == null ? List.of() : operation.getParameters())
                    .as(operation.getOperationId())
                    .noneMatch(parameter -> "Idempotency-Key".equals(parameter.getName()));
        });
    }

    @Test
    void idsAreStringsAndRequestsDoNotExposeOwnershipOrPrivilegeFields() throws Exception {
        OpenAPI browser = parse();
        Schema<?> id = browser.getComponents().getSchemas().get("BusinessId");
        assertThat(types(id)).containsExactly("string");
        assertThat(id.getMaxLength()).isEqualTo(19);
        assertThat(id.getPattern()).isEqualTo("^[1-9][0-9]{0,18}$");

        for (String schemaName : List.of(
                "CreateBusinessSpaceRequest", "UpdateBusinessSpaceRequest",
                "CreateProductRequest", "UpdateProductRequest", "ReplaceProductAliasesRequest",
                "CreateCompetitorRequest", "UpdateCompetitorRequest", "ReplaceCompetitorAliasesRequest"
        )) {
            Schema<?> schema = browser.getComponents().getSchemas().get(schemaName);
            assertThat(schema.getProperties().keySet())
                    .as(schemaName)
                    .doesNotContain("userId", "role", "points", "tokenVersion", "nameNormalized");
        }
        assertThat(types(browser.getComponents().getSchemas().get("NullableBusinessId")))
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void validationEnumsVersionsPaginationAndNullOnlyAssetsMatchControllers() throws Exception {
        OpenAPI browser = parse();
        assertThat(browser.getComponents().getSchemas().get("CompetitorType").getEnum())
                .containsExactly("DIRECT", "INDIRECT", "SUBSTITUTE");
        assertThat(browser.getComponents().getSchemas().get("CompetitorStatus").getEnum())
                .containsExactly("ACTIVE", "INACTIVE");
        assertThat(browser.getComponents().getParameters().get("Page").getSchema().getDefault()).isEqualTo(1);
        assertThat(browser.getComponents().getParameters().get("Size").getSchema().getDefault()).isEqualTo(20);
        assertThat(browser.getComponents().getParameters().get("Size").getSchema().getMaximum())
                .isEqualByComparingTo("100");

        for (String request : List.of(
                "UpdateBusinessSpaceRequest", "UpdateProductRequest", "UpdateCompetitorRequest",
                "ReplaceProductAliasesRequest", "ReplaceCompetitorAliasesRequest"
        )) {
            assertThat(browser.getComponents().getSchemas().get(request).getRequired())
                    .as(request).contains("version");
        }
        Schema<?> createSpace = browser.getComponents().getSchemas().get("CreateBusinessSpaceRequest");
        Schema<?> createProduct = browser.getComponents().getSchemas().get("CreateProductRequest");
        assertThat(types((Schema<?>) createSpace.getProperties().get("logoAssetId"))).containsExactly("null");
        assertThat(types((Schema<?>) createProduct.getProperties().get("primaryAssetId")))
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void operationsDeclareActualSuccessAndOwnershipConflictErrors() throws Exception {
        OpenAPI browser = parse();
        assertThat(operation(browser, "createBusinessSpace").getResponses()).containsKeys("201", "409", "422");
        assertThat(operation(browser, "createProduct").getResponses()).containsKeys("201", "404", "409", "422");
        assertThat(operation(browser, "createCompetitor").getResponses()).containsKeys("201", "404", "409", "422");
        assertThat(operation(browser, "deleteBusinessSpace").getResponses()).containsKeys("200", "404", "409");
        for (String operationId : OPERATIONS.values()) {
            Operation operation = operation(browser, operationId);
            assertThat(operation.getResponses()).as(operationId).containsKeys("401", "403");
        }
    }

    @Test
    void everyCatalogOperationDeclaresValidationFailureForMalformedParametersOrBodies() throws Exception {
        OpenAPI browser = parse();
        for (String operationId : OPERATIONS.values()) {
            assertThat(operation(browser, operationId).getResponses())
                    .as(operationId)
                    .containsKey("422");
        }
    }

    private Operation operation(OpenAPI browser, String operationId) {
        return catalogOperations(browser).stream()
                .filter(operation -> operationId.equals(operation.getOperationId()))
                .findFirst().orElseThrow();
    }

    private List<Operation> catalogOperations(OpenAPI browser) {
        List<Operation> result = new ArrayList<>();
        browser.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/spaces")
                    || path.startsWith("/api/v1/products")
                    || path.startsWith("/api/v1/competitors")) {
                result.addAll(item.readOperations());
            }
        });
        return result;
    }

    private Set<String> types(Schema<?> schema) {
        return schema.getTypes() == null || schema.getTypes().isEmpty()
                ? Set.of(schema.getType()) : schema.getTypes();
    }

    private OpenAPI parse() throws Exception {
        URL location = getClass().getResource(BROWSER);
        assertThat(location).isNotNull();
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location.toExternalForm(), null, options);
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isEmpty();
        return result.getOpenAPI();
    }
}
