package uno.zhuchen.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.core.memory.MemoryAdminService;
import uno.zhuchen.agent.security.UserPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/memory")
public class MemoryAdminController {

    private final MemoryAdminService adminService;

    public MemoryAdminController(MemoryAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/health")
    public Mono<Result<Map<String, Object>>> health() {
        return Mono.fromCallable(adminService::health)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @PostMapping("/process")
    public Mono<Result<Integer>> process(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromCallable(() -> adminService.processPendingEvents(limit))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @PostMapping("/reindex")
    public Mono<Result<Void>> reindex(@RequestParam(defaultValue = "default") String tenantId,
                                      @RequestParam String userId) {
        return Mono.fromRunnable(() -> adminService.rebuildUser(tenantId, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.success(null));
    }
}
