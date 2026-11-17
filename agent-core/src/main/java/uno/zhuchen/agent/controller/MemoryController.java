package uno.zhuchen.agent.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.common.PageResult;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.core.memory.UserMemoryService;
import uno.zhuchen.agent.domain.vo.UserMemoryVO;
import uno.zhuchen.agent.security.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final UserMemoryService userMemoryService;

    public MemoryController(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
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
}
