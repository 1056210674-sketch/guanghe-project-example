package com.guanghe.backend.article;

import com.guanghe.backend.common.api.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Existing scheduling facility; ticks inspect persisted facts, never wake a model. */
@Component
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name="enabled",havingValue="true")
public class ArticleFullScheduler {
    private static final Logger log=LoggerFactory.getLogger(ArticleFullScheduler.class);
    private final ArticleFullService service;
    public ArticleFullScheduler(ArticleFullService service) { this.service=service; }
    @Scheduled(fixedDelayString="${zhixiaohe.article.full-advance-delay:3000}")
    public void advance() {
        for(long id:service.pending()) {
            try { service.advance(id); }
            catch(BusinessException e) {
                try { service.preparationFailed(id,e.errorCode().name()); }
                catch(RuntimeException failure) { log.warn("Article continuation persistence unavailable planId={} errorType={}",id,failure.getClass().getSimpleName()); }
            }
            catch(RuntimeException failure) {
                // Retry infrastructure faults on the next durable scan without ending other users'
                // continuations or releasing an unknown in-flight reservation. Never log facts/text.
                log.warn("Article continuation temporarily unavailable planId={} errorType={}",id,failure.getClass().getSimpleName());
            }
        }
    }
}
