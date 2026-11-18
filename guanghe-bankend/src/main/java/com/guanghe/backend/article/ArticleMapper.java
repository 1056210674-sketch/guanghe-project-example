package com.guanghe.backend.article;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArticleMapper {
    String COLUMNS = """
            a.id,a.user_id AS userId,a.space_id AS spaceId,a.product_id AS productId,
            a.title,a.body_html AS bodyHtml,a.body_text AS bodyText,a.outline::text AS outlineJson,
            a.status,a.source_task_id AS sourceTaskId,a.version,
            a.created_at AS createdAt,a.updated_at AS updatedAt,
            a.saved_to_assets_at AS savedToAssetsAt,a.saved_article_version AS savedArticleVersion
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM business.article a WHERE a.user_id=#{userId} "
            + "AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=a.space_id "
            + "AND s.user_id=a.user_id AND s.storage_kind='PROJECT') "
            + "<if test='spaceId != null'>AND a.space_id=#{spaceId} </if>"
            + "<if test='productId != null'>AND a.product_id=#{productId} </if>"
            + "<if test='status != null'>AND a.status=#{status} </if>"
            + "<if test='savedToAssets != null and savedToAssets'>AND a.saved_to_assets_at IS NOT NULL AND a.status='COMPLETED' </if>"
            + "<if test='savedToAssets != null and !savedToAssets'>AND a.saved_to_assets_at IS NULL </if>"
            + "<if test='keyword != null'>AND (lower(a.title) LIKE CONCAT('%',#{keyword},'%') "
            + "OR lower(a.body_text) LIKE CONCAT('%',#{keyword},'%')) </if>"
            + "ORDER BY a.created_at DESC,a.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ArticleRecords.Article> findPage(@Param("userId") long userId,
            @Param("spaceId") Long spaceId, @Param("productId") Long productId,
            @Param("status") String status, @Param("keyword") String keyword, @Param("savedToAssets") Boolean savedToAssets,
            @Param("offset") long offset, @Param("size") int size);

    @Select("""
            <script>SELECT count(*) FROM business.article a WHERE a.user_id=#{userId}
            AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=a.space_id
                        AND s.user_id=a.user_id AND s.storage_kind='PROJECT')
            <if test="spaceId != null">AND a.space_id=#{spaceId}</if>
            <if test="productId != null">AND a.product_id=#{productId}</if>
            <if test="status != null">AND a.status=#{status}</if>
            <if test="savedToAssets != null and savedToAssets">AND a.saved_to_assets_at IS NOT NULL AND a.status='COMPLETED'</if>
            <if test="savedToAssets != null and !savedToAssets">AND a.saved_to_assets_at IS NULL</if>
            <if test="keyword != null">AND (lower(a.title) LIKE CONCAT('%',#{keyword},'%')
                OR lower(a.body_text) LIKE CONCAT('%',#{keyword},'%'))</if>
            </script>
            """)
    long count(@Param("userId") long userId, @Param("spaceId") Long spaceId,
            @Param("productId") Long productId, @Param("status") String status,
            @Param("keyword") String keyword, @Param("savedToAssets") Boolean savedToAssets);

    @Update("UPDATE business.article SET saved_to_assets_at=#{now},saved_article_version=#{version} "
            + "WHERE id=#{articleId} AND user_id=#{userId} AND version=#{version} AND status='COMPLETED'")
    int saveToAssets(@Param("articleId") long articleId,@Param("userId") long userId,
                     @Param("version") int version,@Param("now") OffsetDateTime now);

    @Select("SELECT task_id AS taskId,execution_no AS executionNo,attempt,sequence,markdown,updated_at AS updatedAt "
            + "FROM business.article_stream_preview WHERE task_id=#{taskId}")
    Optional<ArticleRecords.StreamPreview> preview(@Param("taskId") long taskId);

    @Insert("INSERT INTO business.article_stream_preview(task_id,execution_no,attempt,sequence,markdown,updated_at) "
            + "VALUES(#{p.taskId},#{p.executionNo},#{p.attempt},#{p.sequence},#{p.markdown},#{p.updatedAt}) "
            + "ON CONFLICT(task_id) DO UPDATE SET execution_no=EXCLUDED.execution_no,attempt=EXCLUDED.attempt,"
            + "sequence=EXCLUDED.sequence,markdown=EXCLUDED.markdown,updated_at=EXCLUDED.updated_at "
            + "WHERE EXCLUDED.execution_no > article_stream_preview.execution_no OR "
            + "(EXCLUDED.execution_no=article_stream_preview.execution_no AND EXCLUDED.sequence>article_stream_preview.sequence)")
    int savePreview(@Param("p") ArticleRecords.StreamPreview preview);

    @Select("SELECT p.plan_id FROM business.article_full_preparation p "
            + "JOIN business.workflow_plan_step s ON s.plan_id=p.plan_id AND s.task_id=#{taskId} AND s.deferred_article_mode='BODY' "
            + "JOIN business.article a ON a.id=p.article_id AND a.user_id=p.user_id "
            + "WHERE p.user_id=#{userId} AND p.conversation_id=#{conversationId} AND p.article_id=#{articleId} "
            + "AND p.status='RUNNING' AND a.space_id=#{spaceId} AND a.workflow_stage='BODY_GENERATING' "
            + "AND a.status NOT IN ('ARCHIVED','COMPLETED','FAILED')")
    Optional<Long> activePreviewPreparation(@Param("taskId") long taskId,@Param("userId") long userId,
            @Param("conversationId") Long conversationId,@Param("articleId") long articleId,@Param("spaceId") long spaceId);

    @Select("SELECT " + COLUMNS + " FROM business.article a WHERE a.id=#{articleId} AND a.user_id=#{userId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=a.space_id"
            + " AND s.user_id=a.user_id AND s.storage_kind='PROJECT')")
    Optional<ArticleRecords.Article> find(@Param("articleId") long articleId, @Param("userId") long userId);

    @Select("SELECT geo_report_id FROM business.article WHERE id=#{articleId} AND user_id=#{userId}")
    Optional<Long> findGeoReportId(@Param("articleId") long articleId, @Param("userId") long userId);

    @Update("""
            UPDATE business.article SET geo_report_id=#{reportId}
            WHERE id=#{articleId} AND user_id=#{userId}
              AND (geo_report_id IS NULL OR geo_report_id=#{reportId})
            """)
    int bindGeoReport(@Param("articleId") long articleId, @Param("userId") long userId,
                      @Param("reportId") long reportId);

    @Select("SELECT " + COLUMNS + " FROM business.article a WHERE a.id=#{articleId} AND a.user_id=#{userId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=a.space_id"
            + " AND s.user_id=a.user_id AND s.storage_kind='PROJECT') FOR UPDATE OF a")
    Optional<ArticleRecords.Article> lock(@Param("articleId") long articleId, @Param("userId") long userId);

    @Select("""
            SELECT id AS articleId,user_id AS userId,workflow_stage AS workflowStage,
                   current_title_batch_version AS currentTitleBatchVersion,
                   confirmed_title AS confirmedTitle,confirmed_title_source AS confirmedTitleSource,
                   confirmed_title_batch_version AS confirmedTitleBatchVersion,
                   confirmed_title_candidate_index AS confirmedTitleCandidateIndex,
                   confirmed_title_at AS confirmedTitleAt,current_plan_version AS currentPlanVersion,
                   confirmed_plan_version AS confirmedPlanVersion
            FROM business.article WHERE id=#{articleId} AND user_id=#{userId}
            """)
    Optional<ArticleRecords.Workflow> findWorkflow(@Param("articleId") long articleId,
                                                    @Param("userId") long userId);

    @Insert("""
            INSERT INTO business.article(id,user_id,space_id,product_id,title,body_html,body_text,outline,
                status,source_task_id,version,created_at,updated_at)
            VALUES(#{a.id},#{a.userId},#{a.spaceId},#{a.productId},#{a.title},#{a.bodyHtml},#{a.bodyText},
                CAST(#{a.outlineJson} AS jsonb),#{a.status},#{a.sourceTaskId},#{a.version},#{a.createdAt},#{a.updatedAt})
            """)
    int insert(@Param("a") ArticleRecords.Article article);

    @Update("""
            UPDATE business.article SET title=#{a.title},body_html=#{a.bodyHtml},body_text=#{a.bodyText},
                status=#{a.status},version=version+1,updated_at=#{a.updatedAt}
            WHERE id=#{a.id} AND user_id=#{a.userId} AND version=#{expectedVersion}
            """)
    int update(@Param("a") ArticleRecords.Article article, @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE business.article SET outline=CAST(#{outlineJson} AS jsonb),version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int updateOutline(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("outlineJson") String outlineJson,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int incrementVersion(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET title=#{title},body_html=#{bodyHtml},body_text=#{bodyText},
                status='COMPLETED',source_task_id=#{taskId},version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int applyGeneratedBody(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("title") String title,
            @Param("bodyHtml") String bodyHtml, @Param("bodyText") String bodyText,
            @Param("taskId") long taskId, @Param("now") OffsetDateTime now);

    @Select("SELECT COALESCE(max(batch_version),0)+1 FROM business.article_title_candidate_batch "
            + "WHERE article_id=#{articleId}")
    int nextTitleBatchVersion(@Param("articleId") long articleId);

    @Insert("""
            INSERT INTO business.article_title_candidate_batch(id,article_id,user_id,space_id,batch_version,
                article_version,candidates,source_task_id,created_at)
            VALUES(#{b.id},#{b.articleId},#{b.userId},#{b.spaceId},#{b.batchVersion},#{b.articleVersion},
                CAST(#{b.candidatesJson} AS jsonb),#{b.sourceTaskId},#{b.createdAt})
            """)
    int insertTitleBatch(@Param("b") ArticleRecords.TitleCandidateBatch batch);

    @Select("""
            SELECT id,article_id AS articleId,user_id AS userId,space_id AS spaceId,
                   batch_version AS batchVersion,article_version AS articleVersion,
                   candidates::text AS candidatesJson,source_task_id AS sourceTaskId,created_at AS createdAt
            FROM business.article_title_candidate_batch
            WHERE article_id=#{articleId} AND user_id=#{userId} AND batch_version=#{batchVersion}
            """)
    Optional<ArticleRecords.TitleCandidateBatch> findTitleBatch(@Param("articleId") long articleId,
            @Param("userId") long userId, @Param("batchVersion") int batchVersion);

    @Update("""
            UPDATE business.article SET workflow_stage='TITLE_AWAITING_CONFIRMATION',
                current_title_batch_version=#{batchVersion},confirmed_title=NULL,
                confirmed_title_source=NULL,confirmed_title_batch_version=NULL,
                confirmed_title_candidate_index=NULL,confirmed_title_at=NULL,
                current_plan_version=NULL,confirmed_plan_version=NULL,outline='[]'::jsonb,
                body_html='',body_text='',status='DRAFT',version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
                AND workflow_stage='TITLE_GENERATING'
            """)
    int applyGeneratedTitleBatch(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("batchVersion") int batchVersion,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET title=#{title},workflow_stage='PLAN_READY',
                confirmed_title=#{title},confirmed_title_source=#{source},
                confirmed_title_batch_version=#{batchVersion},
                confirmed_title_candidate_index=#{candidateIndex},confirmed_title_at=#{now},
                current_plan_version=NULL,confirmed_plan_version=NULL,outline='[]'::jsonb,
                body_html='',body_text='',status='DRAFT',version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int confirmTitle(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("title") String title,
            @Param("source") String source, @Param("batchVersion") int batchVersion,
            @Param("candidateIndex") Integer candidateIndex, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET title=#{title},workflow_stage='PLAN_READY',confirmed_title=#{title},
                confirmed_title_source='AUTO_POLICY',confirmed_title_batch_version=#{batchVersion},
                confirmed_title_candidate_index=1,confirmed_title_at=NULL,current_plan_version=NULL,
                confirmed_plan_version=NULL,outline='[]'::jsonb,body_html='',body_text='',status='DRAFT',
                version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{version}
                AND workflow_stage='TITLE_AWAITING_CONFIRMATION'
            """)
    int adoptTitle(@Param("articleId") long articleId,@Param("userId") long userId,
            @Param("version") int version,@Param("title") String title,
            @Param("batchVersion") int batchVersion,@Param("now") OffsetDateTime now);

    @Select("SELECT COALESCE(max(plan_version),0)+1 FROM business.article_creative_plan_revision "
            + "WHERE article_id=#{articleId}")
    int nextPlanVersion(@Param("articleId") long articleId);

    @Insert("""
            INSERT INTO business.article_creative_plan_revision(id,article_id,user_id,space_id,plan_version,
                article_version,plan,source,source_task_id,confirmed_at,created_at,updated_at)
            VALUES(#{p.id},#{p.articleId},#{p.userId},#{p.spaceId},#{p.planVersion},#{p.articleVersion},
                CAST(#{p.planJson} AS jsonb),#{p.source},#{p.sourceTaskId},#{p.confirmedAt},
                #{p.createdAt},#{p.updatedAt})
            """)
    int insertPlanRevision(@Param("p") ArticleRecords.CreativePlanRevision plan);

    @Select("""
            SELECT id,article_id AS articleId,user_id AS userId,space_id AS spaceId,
                   plan_version AS planVersion,article_version AS articleVersion,plan::text AS planJson,
                   source,source_task_id AS sourceTaskId,confirmed_at AS confirmedAt,
                   created_at AS createdAt,updated_at AS updatedAt
            FROM business.article_creative_plan_revision
            WHERE article_id=#{articleId} AND user_id=#{userId} AND plan_version=#{planVersion}
            """)
    Optional<ArticleRecords.CreativePlanRevision> findPlanRevision(@Param("articleId") long articleId,
            @Param("userId") long userId, @Param("planVersion") int planVersion);

    @Update("""
            UPDATE business.article SET workflow_stage='PLAN_AWAITING_CONFIRMATION',
                current_plan_version=#{planVersion},confirmed_plan_version=NULL,
                outline='[]'::jsonb,body_html='',body_text='',status='DRAFT',
                version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
                AND workflow_stage='PLAN_GENERATING'
            """)
    int applyGeneratedPlan(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("planVersion") int planVersion,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET workflow_stage='PLAN_AWAITING_CONFIRMATION',
                current_plan_version=#{planVersion},confirmed_plan_version=NULL,
                outline='[]'::jsonb,body_html='',body_text='',status='DRAFT',
                version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int saveEditedPlan(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("planVersion") int planVersion,
            @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET workflow_stage='BODY_READY_TO_GENERATE',
                confirmed_plan_version=#{planVersion},outline=CAST(#{outlineJson} AS jsonb),
                body_html='',body_text='',status='DRAFT',version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
                AND current_plan_version=#{planVersion}
            """)
    int confirmPlan(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("planVersion") int planVersion,
            @Param("outlineJson") String outlineJson, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article_creative_plan_revision SET confirmed_at=#{now},updated_at=#{now}
            WHERE article_id=#{articleId} AND user_id=#{userId} AND plan_version=#{planVersion}
                AND confirmed_at IS NULL
            """)
    int markPlanConfirmed(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("planVersion") int planVersion, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET title=#{title},body_html=#{bodyHtml},body_text=#{bodyText},
                status='COMPLETED',workflow_stage='BODY_READY',source_task_id=#{taskId},
                version=version+1,updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
                AND confirmed_plan_version IS NOT NULL AND workflow_stage='BODY_GENERATING'
            """)
    int applyGeneratedWorkflowBody(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("title") String title,
            @Param("bodyHtml") String bodyHtml, @Param("bodyText") String bodyText,
            @Param("taskId") long taskId, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET workflow_stage=#{stage},updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int updateWorkflowStage(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("stage") String stage,
            @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.article_submission_preference(article_id,user_id,space_id,article_version,
                goal,preferred_region,max_total_points,requires_link,confirmed_at,updated_at)
            VALUES(#{p.articleId},#{p.userId},#{p.spaceId},#{p.articleVersion},#{p.goal},#{p.preferredRegion},
                #{p.maxTotalPoints},#{p.requiresLink},#{p.confirmedAt},#{p.updatedAt})
            ON CONFLICT(article_id) DO UPDATE SET article_version=EXCLUDED.article_version,
                goal=EXCLUDED.goal,preferred_region=EXCLUDED.preferred_region,
                max_total_points=EXCLUDED.max_total_points,requires_link=EXCLUDED.requires_link,
                confirmed_at=EXCLUDED.confirmed_at,updated_at=EXCLUDED.updated_at
            WHERE business.article_submission_preference.user_id=EXCLUDED.user_id
                AND business.article_submission_preference.space_id=EXCLUDED.space_id
            """)
    int upsertSubmissionPreference(@Param("p") ArticleRecords.SubmissionPreference preference);

    @Select("""
            SELECT article_id AS articleId,user_id AS userId,space_id AS spaceId,
                   article_version AS articleVersion,goal,preferred_region AS preferredRegion,
                   max_total_points AS maxTotalPoints,requires_link AS requiresLink,
                   confirmed_at AS confirmedAt,updated_at AS updatedAt
            FROM business.article_submission_preference
            WHERE article_id=#{articleId} AND user_id=#{userId}
            """)
    Optional<ArticleRecords.SubmissionPreference> findSubmissionPreference(
            @Param("articleId") long articleId, @Param("userId") long userId);

    @Update("""
            UPDATE business.article SET workflow_stage='READY_TO_SUBMIT',updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND version=#{expectedVersion}
                AND status='COMPLETED' AND length(btrim(body_text))>0
                AND workflow_stage IN ('BODY_READY','EDITING','READY_TO_SUBMIT')
            """)
    int confirmBody(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.article SET workflow_stage=#{toStage},updated_at=#{now}
            WHERE id=#{articleId} AND user_id=#{userId} AND workflow_stage=#{fromStage}
            """)
    int restoreWorkflowStage(@Param("articleId") long articleId, @Param("userId") long userId,
            @Param("fromStage") String fromStage, @Param("toStage") String toStage,
            @Param("now") OffsetDateTime now);

    @Delete("DELETE FROM business.article WHERE id=#{articleId} AND user_id=#{userId}")
    int delete(@Param("articleId") long articleId, @Param("userId") long userId);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM business.article_asset_relation r
                           WHERE r.article_id=#{articleId} AND r.user_id=#{userId})
                OR EXISTS(SELECT 1 FROM business.article_submission_snapshot s
                           WHERE s.article_id=#{articleId} AND s.user_id=#{userId})
                OR EXISTS(SELECT 1 FROM business.task_draft d
                           WHERE d.user_id=#{userId} AND d.task_type='ARTICLE'
                             AND d.input_snapshot->'parameters'->>'articleId'=#{articleIdText})
                OR EXISTS(SELECT 1 FROM business.business_task t
                           WHERE t.user_id=#{userId} AND t.task_type='ARTICLE'
                             AND t.input_snapshot->'parameters'->>'articleId'=#{articleIdText})
            """)
    boolean hasProtectedReference(@Param("articleId") long articleId, @Param("userId") long userId,
                                  @Param("articleIdText") String articleIdText);

    @Select("""
            SELECT id,article_id AS articleId,asset_id AS assetId,user_id AS userId,space_id AS spaceId,
                   position_key AS positionKey,sort_order AS sortOrder,alt_text AS altText,created_at AS createdAt
            FROM business.article_asset_relation WHERE article_id=#{articleId} AND user_id=#{userId}
            ORDER BY sort_order,id
            """)
    List<ArticleRecords.AssetRelation> findAssets(@Param("articleId") long articleId,
                                                   @Param("userId") long userId);

    @Delete("DELETE FROM business.article_asset_relation WHERE article_id=#{articleId} AND user_id=#{userId}")
    int deleteAssets(@Param("articleId") long articleId, @Param("userId") long userId);

    @Insert("""
            INSERT INTO business.article_asset_relation(id,article_id,asset_id,user_id,space_id,
                position_key,sort_order,alt_text,created_at)
            VALUES(#{r.id},#{r.articleId},#{r.assetId},#{r.userId},#{r.spaceId},#{r.positionKey},
                #{r.sortOrder},#{r.altText},#{r.createdAt})
            """)
    int insertAsset(@Param("r") ArticleRecords.AssetRelation relation);

    @Insert("""
            INSERT INTO business.article_submission_snapshot(id,article_id,user_id,space_id,product_id,
                article_version,title,body_html,body_text,body_hash,outline,asset_manifest,created_at)
            VALUES(#{s.id},#{s.articleId},#{s.userId},#{s.spaceId},#{s.productId},#{s.articleVersion},
                #{s.title},#{s.bodyHtml},#{s.bodyText},#{s.bodyHash},CAST(#{s.outlineJson} AS jsonb),
                CAST(#{s.assetManifestJson} AS jsonb),#{s.createdAt}) ON CONFLICT(article_id,article_version) DO NOTHING
            """)
    int insertSnapshot(@Param("s") ArticleRecords.SubmissionSnapshot snapshot);

    @Select("""
            SELECT id,article_id AS articleId,user_id AS userId,space_id AS spaceId,product_id AS productId,
                   article_version AS articleVersion,title,body_html AS bodyHtml,body_text AS bodyText,
                   body_hash AS bodyHash,outline::text AS outlineJson,asset_manifest::text AS assetManifestJson,
                   created_at AS createdAt
            FROM business.article_submission_snapshot
            WHERE id=#{snapshotId} AND article_id=#{articleId} AND user_id=#{userId}
            """)
    Optional<ArticleRecords.SubmissionSnapshot> findSnapshot(@Param("snapshotId") long snapshotId,
            @Param("articleId") long articleId, @Param("userId") long userId);

    @Select("""
            SELECT id,article_id AS articleId,user_id AS userId,space_id AS spaceId,product_id AS productId,
                   article_version AS articleVersion,title,body_html AS bodyHtml,body_text AS bodyText,
                   body_hash AS bodyHash,outline::text AS outlineJson,asset_manifest::text AS assetManifestJson,
                   created_at AS createdAt
            FROM business.article_submission_snapshot
            WHERE article_id=#{articleId} AND article_version=#{articleVersion} AND user_id=#{userId}
            """)
    Optional<ArticleRecords.SubmissionSnapshot> findSnapshotByVersion(@Param("articleId") long articleId,
            @Param("articleVersion") int articleVersion, @Param("userId") long userId);
}
