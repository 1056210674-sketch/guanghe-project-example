package com.guanghe.backend.geo;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "geo-plan-enabled", havingValue = "true")
public class GeoPlanQuartzConfiguration {
    @Bean
    SchedulerFactoryBeanCustomizer geoPlanJobFactoryCustomizer(ApplicationContext context) {
        AutowireCapableBeanFactory beanFactory = context.getAutowireCapableBeanFactory();
        return schedulerFactory -> schedulerFactory.setJobFactory(new SpringBeanJobFactory() {
            @Override
            protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
                Object job = super.createJobInstance(bundle);
                beanFactory.autowireBean(job);
                return job;
            }
        });
    }
}
