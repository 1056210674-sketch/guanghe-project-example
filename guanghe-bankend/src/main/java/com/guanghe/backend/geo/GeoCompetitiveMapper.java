package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GeoCompetitiveMapper {
    String CANDIDATE = "id,user_id AS userId,space_id AS spaceId,topic_id AS topicId,name,name_normalized AS nameNormalized,"
            + "entity_kind AS entityKind,status,version,competitor_id AS competitorId,first_report_id AS firstReportId,"
            + "last_report_id AS lastReportId,evidence::text AS evidenceJson,first_seen_at AS firstSeenAt,last_seen_at AS lastSeenAt";
    String INSIGHT = "id,report_id AS reportId,user_id AS userId,input_hash AS inputHash,status,attempts,claim_token AS claimToken,"
            + "lease_expires_at AS leaseExpiresAt,deadline_at AS deadlineAt,result::text AS resultJson,last_error_code AS lastErrorCode,"
            + "traceparent,available_at AS availableAt,capacity_wait_until AS capacityWaitUntil,finished_at AS finishedAt";

    @Insert("INSERT INTO business.geo_competitor_candidate(id,user_id,space_id,topic_id,name,name_normalized,entity_kind,"
            + "first_report_id,last_report_id,evidence,first_seen_at,last_seen_at) VALUES(#{c.id},#{c.userId},#{c.spaceId},#{c.topicId},"
            + "#{c.name},#{c.nameNormalized},#{c.entityKind},#{c.firstReportId},#{c.lastReportId},CAST(#{c.evidenceJson} AS jsonb),#{c.firstSeenAt},#{c.lastSeenAt})"
            + " ON CONFLICT(topic_id,name_normalized) DO UPDATE SET"
            + " first_report_id=CASE WHEN EXCLUDED.first_seen_at<geo_competitor_candidate.first_seen_at THEN EXCLUDED.first_report_id ELSE geo_competitor_candidate.first_report_id END,"
            + " last_report_id=CASE WHEN EXCLUDED.last_seen_at>=geo_competitor_candidate.last_seen_at THEN EXCLUDED.last_report_id ELSE geo_competitor_candidate.last_report_id END,"
            + " evidence=CASE WHEN EXCLUDED.last_seen_at>=geo_competitor_candidate.last_seen_at THEN EXCLUDED.evidence ELSE geo_competitor_candidate.evidence END,"
            + " first_seen_at=LEAST(geo_competitor_candidate.first_seen_at,EXCLUDED.first_seen_at),last_seen_at=GREATEST(geo_competitor_candidate.last_seen_at,EXCLUDED.last_seen_at)"
            + " WHERE geo_competitor_candidate.name=EXCLUDED.name AND geo_competitor_candidate.entity_kind=EXCLUDED.entity_kind")
    int upsertCandidate(@Param("c") GeoCompetitiveRecords.Candidate candidate);

    @Select("SELECT " + CANDIDATE + " FROM business.geo_competitor_candidate WHERE user_id=#{userId} AND topic_id=#{topicId} AND name_normalized=#{name}")
    Optional<GeoCompetitiveRecords.Candidate> findName(@Param("userId") long userId,@Param("topicId") long topicId,@Param("name") String name);
    @Select("SELECT " + CANDIDATE + " FROM business.geo_competitor_candidate WHERE user_id=#{userId} AND topic_id=#{topicId} AND entity_kind='BRAND'"
            + " AND (name_normalized=#{name} OR substring(name_normalized from '^([一-鿿]{2,30})\\([a-z]{2,40}\\)$')=#{name}) ORDER BY id LIMIT 2")
    List<GeoCompetitiveRecords.Candidate> compatibleBrandCandidates(@Param("userId") long userId,@Param("topicId") long topicId,@Param("name") String name);
    @Select("SELECT " + CANDIDATE + " FROM business.geo_competitor_candidate WHERE id=#{id} AND user_id=#{userId}"
            + " AND EXISTS(SELECT 1 FROM business.business_space s WHERE s.id=geo_competitor_candidate.space_id"
            + " AND s.user_id=geo_competitor_candidate.user_id AND s.storage_kind='PROJECT') FOR UPDATE")
    Optional<GeoCompetitiveRecords.Candidate> lockCandidate(@Param("id") long id,@Param("userId") long userId);
    @Select("SELECT " + CANDIDATE + " FROM business.geo_competitor_candidate WHERE id=#{id} AND user_id=#{userId}"
            + " AND EXISTS(SELECT 1 FROM business.business_space s WHERE s.id=geo_competitor_candidate.space_id"
            + " AND s.user_id=geo_competitor_candidate.user_id AND s.storage_kind='PROJECT')")
    Optional<GeoCompetitiveRecords.Candidate> candidate(@Param("id") long id,@Param("userId") long userId);
    @Select("<script>SELECT " + CANDIDATE + " FROM business.geo_competitor_candidate WHERE user_id=#{userId} AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> LIMIT 6</script>")
    List<GeoCompetitiveRecords.Candidate> reportCandidates(@Param("userId") long userId,@Param("ids") List<Long> ids);
    @Select("SELECT " + CANDIDATE + " FROM business.geo_competitor_candidate WHERE user_id=#{userId} AND topic_id=#{topicId} AND status=#{status} ORDER BY last_seen_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<GeoCompetitiveRecords.Candidate> candidates(@Param("userId") long userId,@Param("topicId") long topicId,@Param("status") String status,@Param("offset") long offset,@Param("size") int size);
    @Select("SELECT count(*) FROM business.geo_competitor_candidate WHERE user_id=#{userId} AND topic_id=#{topicId} AND status=#{status}")
    long countCandidates(@Param("userId") long userId,@Param("topicId") long topicId,@Param("status") String status);
    @Update("UPDATE business.geo_competitor_candidate SET status=#{status},competitor_id=#{competitorId},version=version+1"
            + " WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND status='PENDING'"
            + " AND EXISTS(SELECT 1 FROM business.business_space s WHERE s.id=geo_competitor_candidate.space_id"
            + " AND s.user_id=geo_competitor_candidate.user_id AND s.storage_kind='PROJECT')")
    int decide(@Param("id") long id,@Param("userId") long userId,@Param("version") int version,@Param("status") String status,@Param("competitorId") Long competitorId);
    @Select("SELECT c.id FROM business.competitor c WHERE c.user_id=#{userId} AND c.space_id=#{spaceId} AND (c.name_normalized=#{name} OR EXISTS(SELECT 1 FROM business.competitor_alias a WHERE a.competitor_id=c.id AND a.user_id=c.user_id AND a.alias_normalized=#{name})) ORDER BY c.id LIMIT 1")
    Optional<Long> existingCompetitor(@Param("userId") long userId,@Param("spaceId") long spaceId,@Param("name") String name);
    @Select("<script>SELECT name_normalized AS nameNormalized,id AS competitorId FROM business.competitor WHERE user_id=#{userId} AND space_id=#{spaceId} AND name_normalized IN "
            + "<foreach collection='names' item='name' open='(' separator=',' close=')'>#{name}</foreach>"
            + " UNION ALL SELECT alias_normalized AS nameNormalized,competitor_id AS competitorId FROM business.competitor_alias WHERE user_id=#{userId} AND space_id=#{spaceId} AND alias_normalized IN "
            + "<foreach collection='names' item='name' open='(' separator=',' close=')'>#{name}</foreach> ORDER BY competitorId LIMIT 200</script>")
    List<GeoCompetitiveRecords.CatalogMatch> existingCompetitors(@Param("userId") long userId,@Param("spaceId") long spaceId,@Param("names") List<String> names);
    @Select("SELECT " + INSIGHT + " FROM business.geo_report_competitive_insight WHERE report_id=#{reportId} AND user_id=#{userId}")
    Optional<GeoCompetitiveRecords.Insight> find(@Param("reportId") long reportId,@Param("userId") long userId);
    @Select("SELECT " + INSIGHT + " FROM business.geo_report_competitive_insight WHERE report_id=#{reportId} AND user_id=#{userId} FOR UPDATE")
    Optional<GeoCompetitiveRecords.Insight> lock(@Param("reportId") long reportId,@Param("userId") long userId);
    @Select("SELECT count(*) FROM business.geo_report_competitive_insight WHERE user_id=#{userId} AND status IN ('QUEUED','RUNNING')")
    int countActive(@Param("userId") long userId);
    @Insert("INSERT INTO business.geo_report_competitive_insight(id,report_id,user_id,input_hash,status,attempts,result,available_at,capacity_wait_until,finished_at,created_at,updated_at)"
            + " VALUES(#{id},#{reportId},#{userId},#{hash},'SUCCEEDED',0,CAST(#{result} AS jsonb),#{now},#{now},#{now},#{now},#{now}) ON CONFLICT(report_id) DO NOTHING")
    int insertAutomatic(@Param("id") long id,@Param("reportId") long reportId,@Param("userId") long userId,@Param("hash") String hash,@Param("result") String result,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_report_competitive_insight SET status='SUCCEEDED',result=CAST(#{result} AS jsonb),usage=NULL,last_error_code=NULL,finished_at=#{now},updated_at=#{now} WHERE id=#{id} AND status='FAILED'")
    int publishAutomatic(@Param("id") long id,@Param("result") String result,@Param("now") OffsetDateTime now);
    @Insert("INSERT INTO business.geo_report_competitive_insight(id,report_id,user_id,input_hash,status,attempts,traceparent,available_at,capacity_wait_until,created_at,updated_at)"
            + " VALUES(#{id},#{reportId},#{userId},#{hash},'QUEUED',1,#{traceparent},#{now},#{now}+INTERVAL '120 seconds',#{now},#{now})")
    int enqueue(@Param("id") long id,@Param("reportId") long reportId,@Param("userId") long userId,@Param("hash") String hash,@Param("traceparent") String traceparent,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_report_competitive_insight SET status='QUEUED',attempts=CASE WHEN last_error_code='CAPACITY_FULL' THEN attempts ELSE attempts+1 END,"
            + "last_error_code=NULL,finished_at=NULL,usage=NULL,traceparent=#{traceparent},available_at=#{now},capacity_wait_until=#{now}+INTERVAL '120 seconds',updated_at=#{now}"
            + " WHERE id=#{id} AND status='FAILED' AND available_at<=#{now} AND (attempts<2 OR last_error_code='CAPACITY_FULL')")
    int requeue(@Param("id") long id,@Param("traceparent") String traceparent,@Param("now") OffsetDateTime now);
    @Select("SELECT " + INSIGHT + " FROM business.geo_report_competitive_insight WHERE status='QUEUED' AND available_at<=#{now} ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<GeoCompetitiveRecords.Insight> lockNext(@Param("now") OffsetDateTime now);
    @Select("SELECT " + INSIGHT + " FROM business.geo_report_competitive_insight WHERE status='RUNNING' AND lease_expires_at<=#{now} ORDER BY lease_expires_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<GeoCompetitiveRecords.Insight> lockExpired(@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_report_competitive_insight SET status='RUNNING',claim_token=#{token},lease_expires_at=#{now}+INTERVAL '90 seconds',deadline_at=#{now}+INTERVAL '60 seconds',updated_at=#{now} WHERE id=#{id} AND status='QUEUED'")
    int claim(@Param("id") long id,@Param("token") UUID token,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_report_competitive_insight SET status=#{status},claim_token=NULL,lease_expires_at=NULL,deadline_at=NULL,finished_at=#{now},last_error_code=#{error},result=CAST(#{result} AS jsonb),usage=CAST(#{usage} AS jsonb),updated_at=#{now}"
            + " WHERE id=#{id} AND status='RUNNING' AND attempts=#{attempt} AND claim_token=#{token} AND lease_expires_at>#{now}")
    int finish(@Param("id") long id,@Param("attempt") int attempt,@Param("token") UUID token,@Param("status") String status,@Param("error") String error,@Param("result") String result,@Param("usage") String usage,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_report_competitive_insight SET status='FAILED',claim_token=NULL,lease_expires_at=NULL,deadline_at=NULL,finished_at=#{now},last_error_code=#{error},updated_at=#{now} WHERE id=#{id} AND (status='QUEUED' OR (status='RUNNING' AND lease_expires_at<=#{now}))")
    int reject(@Param("id") long id,@Param("error") String error,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_report_competitive_insight SET status=CASE WHEN capacity_wait_until>#{now} THEN 'QUEUED' ELSE 'FAILED' END,finished_at=CASE WHEN capacity_wait_until>#{now} THEN NULL ELSE #{now} END,claim_token=NULL,lease_expires_at=NULL,deadline_at=NULL,available_at=#{now}+INTERVAL '5 seconds',last_error_code='CAPACITY_FULL',updated_at=#{now}"
            + " WHERE id=#{id} AND status='RUNNING' AND attempts=#{attempt} AND claim_token=#{token} AND lease_expires_at>#{now}")
    int capacity(@Param("id") long id,@Param("attempt") int attempt,@Param("token") UUID token,@Param("now") OffsetDateTime now);
}
