package com.twitter.admin.application.port.in;

import com.twitter.admin.application.port.out.AdminDataPort.DataCounts;

public interface GetStatsUseCase {
    DataCounts getStats();
}
