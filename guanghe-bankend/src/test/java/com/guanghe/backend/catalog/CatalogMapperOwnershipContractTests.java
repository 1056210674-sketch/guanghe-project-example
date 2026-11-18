package com.guanghe.backend.catalog;

import com.guanghe.backend.article.ArticleMapper;
import com.guanghe.backend.article.ArticleFullMapper;
import com.guanghe.backend.catalog.competitor.CompetitorMapper;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.conversation.resource.RunResourceMapper;
import com.guanghe.backend.geo.GeoMapper;
import com.guanghe.backend.geo.GeoCompetitiveMapper;
import com.guanghe.backend.geo.GeoPlanMapper;
import com.guanghe.backend.geo.GeoPlanRecords;
import com.guanghe.backend.geo.GeoPeriodReportMapper;
import com.guanghe.backend.geo.GeoTaskMapper;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogMapperOwnershipContractTests {

    @Test
    void everyCatalogSqlStatementCarriesTheExplicitUserOwnershipColumn() {
        for (Class<?> mapper : List.of(BusinessSpaceMapper.class, ProductMapper.class, CompetitorMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = sql(method);
                if (sql != null) {
                    assertThat(sql.toLowerCase(Locale.ROOT))
                            .as(mapper.getSimpleName() + "." + method.getName())
                            .contains("user_id");
                }
            }
        }
    }

    @Test
    void customerProjectReadersExplicitlyExcludePersonalCreativeStorage() throws Exception {
        assertProjectScoped(BusinessSpaceMapper.class, "findProjectByIdAndUserId", long.class, long.class);
        assertProjectScoped(BusinessSpaceMapper.class, "shareProjectByIdAndUserId", long.class, long.class);
        assertThat(sql(BusinessSpaceMapper.class.getMethod(
                "findOwnedStorageByIdAndUserId", long.class, long.class)).toLowerCase(Locale.ROOT))
                .contains("user_id")
                .doesNotContain("storage_kind='project'");
        assertThat(sql(BusinessSpaceMapper.class.getMethod(
                "findPersonalCreativeByIdAndUserId", long.class, long.class)).toLowerCase(Locale.ROOT))
                .contains("user_id", "storage_kind='personal_creative'")
                .doesNotContain("storage_kind='project'");
        for (String method : List.of("findByIdAndUserId", "shareByIdAndUserId", "findPage", "countBySpace")) {
            Class<?>[] arguments = switch (method) {
                case "findByIdAndUserId", "shareByIdAndUserId" -> new Class<?>[]{long.class, long.class};
                case "findPage" -> new Class<?>[]{long.class, long.class, long.class, int.class};
                default -> new Class<?>[]{long.class, long.class};
            };
            assertProjectScoped(ProductMapper.class, method, arguments);
            assertProjectScoped(CompetitorMapper.class, method, arguments);
        }
        assertProjectScoped(ArticleMapper.class, "find", long.class, long.class);
        assertProjectScoped(ArticleMapper.class, "lock", long.class, long.class);
        assertProjectScoped(ArticleMapper.class, "findPage", long.class, Long.class, Long.class,
                String.class, String.class, Boolean.class, long.class, int.class);
        assertProjectScoped(ArticleMapper.class, "count", long.class, Long.class, Long.class,
                String.class, String.class, Boolean.class);
        assertProjectScoped(GeoMapper.class, "findTopic", long.class, long.class);
        assertProjectScoped(GeoMapper.class, "shareTopic", long.class, long.class);
        assertProjectScoped(GeoMapper.class, "findPage", long.class, Long.class, Long.class,
                String.class, long.class, int.class);
        assertProjectScoped(GeoMapper.class, "count", long.class, Long.class, Long.class, String.class);
        assertProjectScoped(GeoCompetitiveMapper.class, "lockCandidate", long.class, long.class);
        assertProjectScoped(GeoCompetitiveMapper.class, "candidate", long.class, long.class);
        assertProjectScoped(GeoCompetitiveMapper.class, "decide", long.class, long.class, int.class,
                String.class, Long.class);
        assertProjectScoped(GeoTaskMapper.class, "activeProject", long.class, long.class);
        assertProjectScoped(GeoTaskMapper.class, "findRun", long.class, long.class);
        assertProjectScoped(GeoTaskMapper.class, "findRunPage", long.class, Long.class,
                String.class, long.class, int.class);
        assertProjectScoped(GeoTaskMapper.class, "countRuns", long.class, Long.class, String.class);
        assertProjectScoped(GeoTaskMapper.class, "findReport", long.class, long.class);
        assertProjectScoped(GeoTaskMapper.class, "lockReport", long.class, long.class);
        assertProjectScoped(GeoTaskMapper.class, "archiveReport", long.class, long.class, OffsetDateTime.class);
        assertProjectScoped(GeoTaskMapper.class, "restoreReport", long.class, long.class);
        assertProjectScoped(GeoTaskMapper.class, "findReportPage", long.class, Long.class, Long.class,
                Boolean.class, long.class, int.class);
        assertProjectScoped(GeoTaskMapper.class, "countReports", long.class, Long.class, Long.class,
                Boolean.class);
        assertThat(sql(GeoTaskMapper.class.getMethod("lockReport", long.class, long.class))
                .toLowerCase(Locale.ROOT)).contains("for update of r");
        assertProjectScoped(GeoPeriodReportMapper.class, "project", long.class, long.class);
        assertProjectScoped(GeoPlanMapper.class, "insertPlan", GeoPlanRecords.Plan.class);
        assertProjectScoped(GeoPlanMapper.class, "findByIdempotency", long.class, UUID.class);
        assertProjectScoped(GeoPlanMapper.class, "findPlan", long.class, long.class);
        assertProjectScoped(GeoPlanMapper.class, "lockPlan", long.class, long.class);
        assertProjectScoped(GeoPlanMapper.class, "lockPlanInternal", long.class);
        assertProjectScoped(GeoPlanMapper.class, "findPage", long.class, String.class, Long.class,
                long.class, int.class);
        assertProjectScoped(GeoPlanMapper.class, "count", long.class, String.class, Long.class);
        assertProjectScoped(GeoPlanMapper.class, "findActivePlans");
        assertProjectScoped(GeoPlanMapper.class, "planNameExists", long.class, String.class, long.class);
        assertProjectScoped(GeoPlanMapper.class, "updateName", long.class, long.class, int.class,
                String.class, String.class, OffsetDateTime.class);
        assertProjectScoped(GeoPlanMapper.class, "replacePlan", GeoPlanRecords.Plan.class, int.class);
        assertThat(sql(GeoPlanMapper.class.getMethod("replacePlan", GeoPlanRecords.Plan.class, int.class))
                .toLowerCase(Locale.ROOT).split("storage_kind='project'", -1))
                .as("GeoPlanMapper.replacePlan old and replacement spaces")
                .hasSize(3);
        assertProjectScoped(GeoPlanMapper.class, "pause", long.class, long.class, int.class,
                String.class, OffsetDateTime.class);
        assertProjectScoped(GeoPlanMapper.class, "resume", long.class, long.class, int.class,
                OffsetDateTime.class, OffsetDateTime.class);
        assertProjectScoped(GeoPlanMapper.class, "advance", long.class, OffsetDateTime.class,
                OffsetDateTime.class, OffsetDateTime.class);
        assertProjectScoped(GeoPlanMapper.class, "pauseAfterSkip", long.class, OffsetDateTime.class,
                String.class, OffsetDateTime.class);
        assertThat(sql(GeoPlanMapper.class.getMethod("lockPlan", long.class, long.class))
                .toLowerCase(Locale.ROOT)).contains("for update of p");
        assertThat(sql(GeoPlanMapper.class.getMethod("lockPlanInternal", long.class))
                .toLowerCase(Locale.ROOT)).contains("for update of p");
        assertProjectScoped(ArticleFullMapper.class, "grants", long.class, long.class);
        assertProjectScoped(ArticleFullMapper.class, "lockGrant", long.class, long.class, long.class);
        assertProjectScoped(ArticleFullMapper.class, "closeGrant", long.class, long.class, int.class,
                String.class, OffsetDateTime.class);
        assertThat(sql(ArticleFullMapper.class.getMethod("lockGrant", long.class, long.class, long.class))
                .toLowerCase(Locale.ROOT)).contains("for update of g");
        String resourceSearch = sql(RunResourceMapper.class.getMethod(
                "search", long.class, String.class, String.class, Long.class, Long.class))
                .toLowerCase(Locale.ROOT);
        assertThat(resourceSearch.split("storage_kind='project'", -1))
                .as("RunResourceMapper.search SPACE, PRODUCT and GEO_TOPIC branches")
                .hasSize(4);
    }

    private void assertProjectScoped(Class<?> mapper, String method, Class<?>... arguments) throws Exception {
        assertThat(sql(mapper.getMethod(method, arguments)).toLowerCase(Locale.ROOT))
                .as(mapper.getSimpleName() + "." + method)
                .contains("storage_kind='project'");
    }

    private String sql(Method method) {
        if (method.isAnnotationPresent(Select.class)) {
            return join(method.getAnnotation(Select.class).value());
        }
        if (method.isAnnotationPresent(Insert.class)) {
            return join(method.getAnnotation(Insert.class).value());
        }
        if (method.isAnnotationPresent(Update.class)) {
            return join(method.getAnnotation(Update.class).value());
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return join(method.getAnnotation(Delete.class).value());
        }
        return null;
    }

    private String join(String[] statements) {
        return String.join(" ", Arrays.asList(statements));
    }
}
