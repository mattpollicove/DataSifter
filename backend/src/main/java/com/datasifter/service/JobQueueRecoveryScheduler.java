package com.datasifter.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "datasifter.jobs.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class JobQueueRecoveryScheduler {
    private final JobExecutionService jobExecutionService;

    public JobQueueRecoveryScheduler(JobExecutionService jobExecutionService) {
        this.jobExecutionService = jobExecutionService;
    }

    @Scheduled(fixedDelayString = "${datasifter.jobs.recovery-interval-ms:5000}")
    public void recoverExpiredLeases() {
        jobExecutionService.recoverExpiredLeases();
    }
}
