package uno.zhuchen.agent.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.core.memory.block.MemoryBlockService;
import uno.zhuchen.agent.core.memory.block.MemoryBlockType;
import uno.zhuchen.agent.domain.dto.MemoryContentRequest;
import uno.zhuchen.agent.domain.vo.MemoryBlockRevisionVO;
import uno.zhuchen.agent.domain.vo.MemoryBlockVO;
import uno.zhuchen.agent.domain.vo.MemoryEventStatusVO;
import uno.zhuchen.agent.domain.vo.MemoryPromptVO;
import uno.zhuchen.agent.security.UserPrincipal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryBlockController {

    private final MemoryBlockService service;

    public MemoryBlockController(MemoryBlockService service) {
        this.service = service;
    }

    @GetMapping("/prompt")
    public Mono<Result<MemoryPromptVO>> prompt(@AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> Result.success(
                        MemoryPromptVO.from(service.prompt(user.userId()), service.userPromptMaxChars())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/prompt")
    public Mono<Result<MemoryPromptVO>> savePrompt(@RequestBody MemoryContentRequest request,
                                                    @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> {
                    service.saveUserPrompt(user.userId(),
                            request == null ? "" : request.content());
                    return Result.success(MemoryPromptVO.from(
                            service.prompt(user.userId()), service.userPromptMaxChars()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/blocks")
    public Mono<Result<List<MemoryBlockVO>>> blocks(@AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> Result.success(service.blocks(user.userId()).stream()
                        .map(entity -> MemoryBlockVO.from(entity,
                                service.maxChars(MemoryBlockType.fromPath(entity.getBlockType()))))
                        .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/events/latest")
    public Mono<Result<MemoryEventStatusVO>> latestEvent(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) Long since,
            @AuthenticationPrincipal UserPrincipal user) {
        LocalDateTime sinceTime = since == null ? null
                : Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalDateTime();
        return Mono.fromCallable(() -> service.latestEvent(user.userId(), conversationId, sinceTime))
                .subscribeOn(Schedulers.boundedElastic())
                .map(entity -> Result.success(
                        entity == null ? null : MemoryEventStatusVO.from(entity)));
    }

    @GetMapping("/events")
    public Mono<Result<List<MemoryEventStatusVO>>> events(
            @RequestParam String conversationId,
            @RequestParam(required = false) Long since,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal UserPrincipal user) {
        LocalDateTime sinceTime = since == null ? null
                : Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalDateTime();
        return Mono.fromCallable(() -> Result.success(
                        service.conversationEvents(user.userId(), conversationId, sinceTime, limit).stream()
                                .map(MemoryEventStatusVO::from)
                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/blocks/{type}")
    public Mono<Result<MemoryBlockVO>> saveBlock(@PathVariable String type,
                                                  @RequestBody MemoryContentRequest request,
                                                  @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> {
                    MemoryBlockType blockType = MemoryBlockType.fromPath(type);
                    service.saveGeneratedBlock(user.userId(), blockType,
                            request == null ? "" : request.content());
                    return Result.success(MemoryBlockVO.from(
                            service.block(user.userId(), blockType),
                            service.maxChars(blockType)));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/prompt/revisions")
    public Mono<Result<List<MemoryBlockRevisionVO>>> promptRevisions(
            @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> Result.success(
                        service.revisions(user.userId(), MemoryBlockService.USER_PROMPT, 20).stream()
                                .map(MemoryBlockRevisionVO::from)
                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/blocks/{type}/revisions")
    public Mono<Result<List<MemoryBlockRevisionVO>>> blockRevisions(
            @PathVariable String type,
            @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> {
                    MemoryBlockType blockType = MemoryBlockType.fromPath(type);
                    return Result.success(service.revisions(user.userId(), blockType.name(), 20).stream()
                            .map(MemoryBlockRevisionVO::from)
                            .toList());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/prompt/rollback/{revisionId}")
    public Mono<Result<MemoryPromptVO>> rollbackPrompt(@PathVariable String revisionId,
                                                        @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> {
                    service.rollback(user.userId(), MemoryBlockService.USER_PROMPT, revisionId);
                    return Result.success(MemoryPromptVO.from(
                            service.prompt(user.userId()), service.userPromptMaxChars()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/blocks/{type}/rollback/{revisionId}")
    public Mono<Result<MemoryBlockVO>> rollbackBlock(@PathVariable String type,
                                                      @PathVariable String revisionId,
                                                      @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromCallable(() -> {
                    MemoryBlockType blockType = MemoryBlockType.fromPath(type);
                    service.rollback(user.userId(), blockType.name(), revisionId);
                    return Result.success(MemoryBlockVO.from(
                            service.block(user.userId(), blockType),
                            service.maxChars(blockType)));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
