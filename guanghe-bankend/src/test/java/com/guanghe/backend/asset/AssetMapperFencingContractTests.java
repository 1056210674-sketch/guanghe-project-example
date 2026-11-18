package com.guanghe.backend.asset;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetMapperFencingContractTests {
    @Test
    void ingestionWorkSelectsItsCanonicalRecordConstructorExplicitly() {
        int canonicalParameterCount = AssetRecords.IngestionWork.class.getRecordComponents().length;

        assertThat(Arrays.stream(AssetRecords.IngestionWork.class.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == canonicalParameterCount)
                .filter(constructor -> constructor.isAnnotationPresent(AutomapConstructor.class)))
                .hasSize(1);
    }

    @Test
    void videoDurationIsExplicitlyBoundToPostgresqlNumeric() {
        assertThat(insertSql("insertAsset"))
                .contains("cast(#{a.durationseconds} as numeric(12,3))");
    }

    @Test
    void optionalAssetFiltersUseDynamicSqlInsteadOfUntypedNullComparisons() {
        for (String method : new String[] {"countAssets", "findAssetPage"}) {
            String sql = selectSql(method);
            assertThat(sql).contains(
                    "<script>",
                    "<if test='type != null'>",
                    "<if test='spaceid != null'>",
                    "<if test='productid != null'>",
                    "<if test='sourcetype != null'>")
                    .doesNotContain("#{type} is null", "#{spaceid} is null",
                            "#{productid} is null", "#{sourcetype} is null");
        }
    }

    @Test
    void productFilterAndDetailIncludeGeneratedAndIdentityAssetRelationsWithoutDuplicates() {
        for (String method : new String[] {"countAssets", "findAssetPage"}) {
            assertThat(selectSql(method)).contains(
                    "exists (select 1 from business.asset_product_relation",
                    "or exists (select 1 from business.product_identity_asset");
        }
        assertThat(selectSql("findProductIds")).contains(
                "from business.asset_product_relation",
                "union select product_id from business.product_identity_asset");
    }

    @Test
    void previousImageResultIsFencedToTheCurrentUserConversationSpaceAndMessageBoundary() {
        String sql = selectSql("findPreviousReadyImageResult");

        assertThat(sql).contains(
                "t.user_id=#{userid}",
                "t.conversation_id=#{conversationid}",
                "t.space_id=#{spaceid}",
                "t.task_type='image'",
                "t.status in ('succeeded','partial')",
                "current_message.id=#{currentmessageid}",
                "current_message.user_id=#{userid}",
                "current_message.conversation_id=#{conversationid}",
                "current_message.role='user'",
                "t.created_at<least(current_message.created_at,#{before})",
                "t.finished_at<least(current_message.created_at,#{before})",
                "a.user_id=#{userid}",
                "a.space_id=#{spaceid}",
                "a.asset_type='image'",
                "a.source_type='generated'",
                "a.status='ready'",
                "w.status='succeeded'",
                "order by t.created_at desc,t.id desc limit 1",
                "order by a.created_at desc,a.id desc limit 1");
    }

    @Test
    void leaseAndAllStateTransitionsAreFencedByGenerationAndClaimToken() {
        for (String method : new String[] {"renewLease", "completeWork", "retryWork", "failWork"}) {
            assertThat(updateSql(method)).contains(
                    "status='processing'",
                    "attempt=#{expectedgeneration}",
                    "claim_token=#{expectedclaimtoken}",
                    "lease_expires_at>=#{now}");
        }
    }

    @Test
    void implicitCreativeImageSourceRejectsAmbiguityAndChecksOriginProjectAfterSelectingLatestTask() {
        String sql=selectSql("findContinuationImageResults");
        assertThat(sql).contains("with latest_task as", "t.user_id=#{userid}",
                "t.conversation_id=#{conversationid}", "current_message.id=#{currentmessageid}",
                "current_message.user_id=#{userid}", "current_message.role='user'",
                "t.finished_at<least(current_message.created_at,#{before})",
                "d.user_id=t.user_id", "d.conversation_id=t.conversation_id",
                "r.user_id=t.user_id", "r.conversation_id=t.conversation_id",
                "r.selected_space_id is not distinct from cast(#{projectid} as bigint)",
                "a.user_id=t.user_id", "a.space_id=t.space_id", "a.status='ready'",
                "a.created_at<least(t.current_requested_at,#{before})",
                "w.completed_at<least(t.current_requested_at,#{before})",
                "w.status='succeeded'", "order by a.created_at desc,a.id desc limit 2");
        assertThat(sql.indexOf("order by t.created_at desc,t.id desc limit 1"))
                .isLessThan(sql.indexOf("r.selected_space_id is not distinct from"));
    }

    @Test
    void terminalTransitionsClearProviderUrlWhileRetryKeepsIt() {
        assertThat(updateSql("completeWork")).contains(
                "provider_url=null", "provider_url_expires_at=null", "claim_token=null");
        assertThat(updateSql("failWork")).contains(
                "provider_url=null", "provider_url_expires_at=null", "claim_token=null");
        assertThat(updateSql("retryWork")).contains("status='retry_wait'", "claim_token=null")
                .doesNotContain("provider_url=null", "provider_url_expires_at=null");
    }

    private String updateSql(String methodName) {
        Method method = Arrays.stream(AssetMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join(" ", method.getAnnotation(Update.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String selectSql(String methodName) {
        Method method = Arrays.stream(AssetMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String insertSql(String methodName) {
        Method method = Arrays.stream(AssetMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join(" ", method.getAnnotation(Insert.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
