package com.twitter.admin.adapter.in.web;

import com.twitter.admin.application.port.in.GetStatsUseCase;
import com.twitter.admin.application.port.in.ResetDemoUseCase;
import com.twitter.admin.application.port.out.AdminDataPort.ClearResult;
import com.twitter.admin.application.port.out.AdminDataPort.DataCounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Admin", description = "Administrative and demo operations")
public class AdminController {

    private final GetStatsUseCase getStatsUseCase;
    private final ResetDemoUseCase resetDemoUseCase;

    public AdminController(GetStatsUseCase getStatsUseCase, ResetDemoUseCase resetDemoUseCase) {
        this.getStatsUseCase = getStatsUseCase;
        this.resetDemoUseCase = resetDemoUseCase;
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset demo data", description = "Clears all data from PostgreSQL, Redis, and Kafka")
    public ResponseEntity<ResetResponse> resetDemo() {
        ClearResult result = resetDemoUseCase.resetDemo();
        return ResponseEntity.ok(new ResetResponse(
            "reset_complete",
            Instant.now(),
            result
        ));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get system statistics", description = "Returns current counts of users, tweets, follows, etc.")
    public ResponseEntity<DataCounts> stats() {
        return ResponseEntity.ok(getStatsUseCase.getStats());
    }

    public record ResetResponse(
        String status,
        Instant timestamp,
        ClearResult cleared
    ) {}
}
