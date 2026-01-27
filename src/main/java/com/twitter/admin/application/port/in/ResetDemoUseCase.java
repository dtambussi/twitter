package com.twitter.admin.application.port.in;

import com.twitter.admin.application.port.out.AdminDataPort.ClearResult;

public interface ResetDemoUseCase {
    ClearResult resetDemo();
}
