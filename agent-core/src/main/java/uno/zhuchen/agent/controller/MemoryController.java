package uno.zhuchen.agent.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.common.PageResult;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.core.memory.MemoryGovernanceService;
import uno.zhuchen.agent.core.memory.UserMemoryService;
import uno.zhuchen.agent.domain.dto.MemoryCorrectRequest;
import uno.zhuchen.agent.domain.dto.MemoryVetoRequest;
import uno.zhuchen.agent.domain.entity.MemoryRevisionEntity;
import uno.zhuchen.agent.domain.vo.MemoryRevisionVO;
import uno.zhuchen.agent.domain.vo.UserMemoryVO;
import uno.zhuchen.agent.security.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final UserMemoryService userMemoryService;
    private final MemoryGovernanceService governanceService;

    public MemoryController(UserMemoryService userMemoryService,
                            MemoryGovernanceService governanceService) {
        this.userMemoryService = userMemoryService;
        this.governanceService = governanceService;
    }

    @GetMapping
    public Mono<Result<PageResult<UserMemoryVO>>> list(
            @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> {
            List<UserMemoryVO> items = userMemoryService.list(user.userId());
            return Result.success(PageResult.of(items, items.size(), 0, items.size()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@PathVariable String id,
                                     @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromRunnable(() -> userMemoryService.delete(user.userId(), id))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.success(null));
    }

    @DeleteMapping
    public Mono<Result<Void>> deleteAll(@AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromRunnable(() -> userMemoryService.deleteAll(user.userId()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.success(null));
    }

    @PatchMapping("/{id}")
    public Mono<Result<Void>> correct(@PathVariable String id,
                                      @Valid @RequestBody MemoryCorrectRequest request,
                                      @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromRunnable(() -> governanceService.correct("default", user.userId(), id, request.value()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.success(null));
    }

    @PostMapping("/veto")
    public Mono<Result<String>> veto(@Valid @RequestBody MemoryVetoRequest request,
                                     @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> governanceService.createVeto("default", user.userId(), request.rule()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @GetMapping("/revisions/{id}")
    public Mono<Result<List<MemoryRevisionVO>>> revisions(@PathVariable String id,
                                                          @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> governanceService.revisions("default", user.userId(), id).stream()
                        .map(MemoryRevisionVO::from).toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }
}
