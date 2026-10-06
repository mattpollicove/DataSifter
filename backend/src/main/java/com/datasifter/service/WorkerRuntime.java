package com.datasifter.service;

import com.datasifter.model.JobExecution;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "datasifter.worker.enabled", havingValue = "true")
public class WorkerRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerRuntime.class);
    private final JobExecutionService jobExecutionService;
    private final CsvJdbcJobProcessor processor;
    private final String workerId;

    public WorkerRuntime(
            JobExecutionService jobExecutionService,
            CsvJdbcJobProcessor processor,
            Environment environment,
            @Value("${datasifter.worker.id:worker}") String workerId) {
        if (environment.acceptsProfiles(Profiles.of("json"))) {
            throw new IllegalStateException("Distributed workers require the durable MySQL persistence profile");
        }
        this.jobExecutionService = jobExecutionService;
        this.processor = processor;
        this.workerId = workerId + "-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${datasifter.worker.poll-interval:1000}")
    public void claimAndProcessOne() {
        jobExecutionService.claimNext(workerId).ifPresent(this::process);
    }

    private void process(JobExecution job) {
        try {
            int processed = processor.process(job, workerId);
            jobExecutionService.complete(job.getId(), workerId, processed, 0);
            LOGGER.info("Worker {} completed job {} with {} records", workerId, job.getId(), processed);
        } catch (Exception exception) {
            String error = "Workflow execution failed (" + exception.getClass().getSimpleName() + ")";
            try {
                JobExecutionService.FailureResult result = jobExecutionService.fail(job.getId(), workerId, error);
                LOGGER.warn("Worker {} failed job {}; retry scheduled: {}",
                        workerId, job.getId(), result.retryScheduled());
            } catch (RuntimeException failError) {
                LOGGER.error("Worker {} could not record failure for job {}", workerId, job.getId(), failError);
            }
        }
    }
}
