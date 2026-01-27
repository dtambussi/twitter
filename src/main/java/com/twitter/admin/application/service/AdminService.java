package com.twitter.admin.application.service;

import com.twitter.admin.application.port.in.GetStatsUseCase;
import com.twitter.admin.application.port.in.ResetDemoUseCase;
import com.twitter.admin.application.port.out.AdminDataPort;
import com.twitter.admin.application.port.out.AdminDataPort.ClearResult;
import com.twitter.admin.application.port.out.AdminDataPort.DataCounts;
import com.twitter.application.port.out.MetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService implements ResetDemoUseCase, GetStatsUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final AdminDataPort adminDataPort;
    private final MetricsPort metricsPort;

    public AdminService(AdminDataPort adminDataPort, MetricsPort metricsPort) {
        this.adminDataPort = adminDataPort;
        this.metricsPort = metricsPort;
    }

    @Override
    @Transactional
    public ClearResult resetDemo() {
        log.warn("Demo reset initiated - clearing all data");

        ClearResult result = adminDataPort.clearAll();

        // Reset application metrics (counters back to zero)
        metricsPort.resetAll();

        log.warn("Demo reset completed: users={}, tweets={}, follows={}, kafka={}",
            result.users(), result.tweets(), result.follows(), result.kafkaRecordsPurged());

        return result;
    }

    @Override
    public DataCounts getStats() {
        return adminDataPort.getCounts();
    }
}
