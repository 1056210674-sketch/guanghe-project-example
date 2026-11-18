package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

@DisallowConcurrentExecution
public class GeoPlanQuartzJob extends QuartzJobBean {
    private GeoPlanService service;

    @Autowired
    public void setService(GeoPlanService service) {
        this.service = service;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        long planId = Long.parseLong(context.getMergedJobDataMap().getString("planId"));
        OffsetDateTime slot = context.getScheduledFireTime().toInstant().atOffset(ZoneOffset.UTC);
        try {
            service.dispatchScheduledSlot(planId, slot);
        } catch (RuntimeException exception) {
            throw new JobExecutionException("GEO plan slot dispatch failed", exception, false);
        }
    }
}
