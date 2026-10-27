package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.access.RequestAuthorization;
import com.agentforge.controlplane.domain.Agent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.util.Utils;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用 AgentScope 的 A2A 扩展把本平台智能体互相调起来。
 * 主智能体仍在进程内回答；子智能体改走本机的 A2A 接口。
 */
@Service
public class A2aGateway {

    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(5);

    private final BoundAgentRuntime bound;
    private final int port;
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "a2a-server");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "a2a-client");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CallBox> calls = new java.util.concurrent.ConcurrentHashMap<>();

    public A2aGateway(BoundAgentRuntime bound, @Value("${server.port:8080}") int port) {
        this.bound = bound;
        this.port = port;
    }

    /** 编排里的一次子智能体调用。登录头缺失时仍发起请求，失败会直接抛出，不会退回进程内调用。 */
    public ChatReply call(Agent agent, String message, String sessionId, String promptPrefix, String promptSuffix,
                          boolean pinRelease) {
        String callId = UUID.randomUUID().toString();
        CallBox box = new CallBox(sessionId, promptPrefix, promptSuffix, pinRelease, false);
        calls.put(callId, box);
        Instant started = Instant.now();
        try {
            String text = send(agent, message, callId);
            if (box.reply != null) {
                return withHop(box.reply, agent, started);
            }
            if (A2aMessages.failed(text)) {
                throw new IllegalStateException(text);
            }
            return withHop(new ChatReply(text, "ready", List.of(), Map.of(), ""), agent, started);
        } finally {
            calls.remove(callId);
        }
    }

    public String cardJson(Agent agent) {
        try {
            return Utils.toJsonString(server(agent, runner(agent, null)).getAgentCard());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage() == null ? "无法生成 Agent Card" : ex.getMessage(), ex);
        }
    }

    public void write(Agent agent, String body, Map<String, String> headers, HttpServletResponse response)
            throws IOException {
        Object result = server(agent, runner(agent, headers))
                .getTransportWrapper(TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class)
                .handleRequest(body, headers == null ? Map.of() : headers, Map.of());
        if (result instanceof Flux<?> flux) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/event-stream");
            var writer = response.getWriter();
            flux.filter(JSONRPCResponse.class::isInstance)
                    .cast(JSONRPCResponse.class)
                    .doOnNext(item -> writeEvent(writer, item))
                    .blockLast(CALL_TIMEOUT);
            writer.flush();
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write(Utils.toJsonString(result));
    }

    private ChatReply withHop(ChatReply reply, Agent agent, Instant started) {
        int elapsed = (int) Math.max(1, Duration.between(started, Instant.now()).toMillis());
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(AgentScopeRuntime.debugSpan(
                "a2a.call",
                "A2A 调用「" + agent.getName() + "」",
                "agent",
                "error".equals(reply.mode()) ? "error" : "ok",
                elapsed,
                "POST " + A2aAddresses.rpcPath(agent.getId())));
        if (reply.spans() != null) {
            spans.addAll(reply.spans());
        }
        return new ChatReply(reply.reply(), reply.mode(), spans, reply.usage(), reply.traceId());
    }

    private String send(Agent agent, String message, String callId) {
        Map<String, String> headers = new LinkedHashMap<>();
        String authorization = RequestAuthorization.authorization();
        if (authorization != null && !authorization.isBlank()) {
            headers.put("Authorization", authorization);
        }
        String tenant = RequestAuthorization.tenantId();
        if (tenant != null && !tenant.isBlank()) {
            headers.put("X-Tenant-Id", tenant);
        }
        headers.put(A2aAddresses.CALL_HEADER, callId);
        A2aAgent remote = A2aAgent.builder()
                .name(agent.getName())
                .agentCardResolver(WellKnownAgentCardResolver.builder()
                        .baseUrl(A2aAddresses.cardBase(port, agent.getId()))
                        .relativeCardPath(A2aAddresses.cardRelativePath())
                        .authHeaders(headers)
                        .build())
                .a2aAgentConfig(A2aAgentConfig.builder()
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(new HeaderHttpClient(headers)))
                        .clientConfig(ClientConfig.builder().setStreaming(Boolean.FALSE).build())
                        .build())
                .build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent(message == null ? "" : message).build();
        Future<Msg> pending = clientExecutor.submit(() -> remote.call(List.of(input)).block(CALL_TIMEOUT));
        try {
            Msg result = pending.get(CALL_TIMEOUT.toMillis() + 2000, TimeUnit.MILLISECONDS);
            return result == null || result.getTextContent() == null ? "" : result.getTextContent();
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new IllegalStateException("A2A 调用超时");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A 调用被中断");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new IllegalStateException(reason, cause);
        }
    }

    private AgentScopeA2aServer server(Agent agent, AgentRunner runner) {
        String url = A2aAddresses.endpoint(port, agent.getId());
        ConfigurableAgentCard.Builder cardBuilder = new ConfigurableAgentCard.Builder()
                .url(url)
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"));
        A2aCards.apply(cardBuilder, agent);
        ConfigurableAgentCard card = cardBuilder.build();
        return AgentScopeA2aServer.builder(runner)
                .agentCard(card)
                .executor(serverExecutor)
                .withTransport(TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                        .host("127.0.0.1")
                        .port(port)
                        .path(A2aAddresses.rpcPath(agent.getId()))
                        .build())
                .build();
    }

    private AgentRunner runner(Agent agent, Map<String, String> headers) {
        String callId = A2aAddresses.header(headers, A2aAddresses.CALL_HEADER);
        CallBox box = callId == null ? null : calls.get(callId);
        return new PlatformRunner(agent, bound, CurrentUserHolder.get(), box);
    }

    private static void writeEvent(java.io.Writer writer, JSONRPCResponse<?> item) {
        try {
            String data = Utils.toJsonString(item).replace("\r", "").replace("\n", "");
            writer.write("event: jsonrpc\ndata: " + data + "\n\n");
            writer.flush();
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage() == null ? "A2A 流式输出失败" : ex.getMessage(), ex);
        }
    }

    private static String described(Agent agent) {
        String description = agent.getDescription() == null ? "" : agent.getDescription().strip();
        return description.isBlank() ? agent.getName() : description;
    }

    private static final class CallBox {
        private final String sessionId;
        private final String promptPrefix;
        private final String promptSuffix;
        private final boolean pinRelease;
        private final boolean recordExchange;
        private volatile ChatReply reply;

        private CallBox(String sessionId, String promptPrefix, String promptSuffix, boolean pinRelease,
                        boolean recordExchange) {
            this.sessionId = sessionId;
            this.promptPrefix = promptPrefix;
            this.promptSuffix = promptSuffix;
            this.pinRelease = pinRelease;
            this.recordExchange = recordExchange;
        }
    }

    private static final class PlatformRunner implements AgentRunner {
        private final Agent agent;
        private final BoundAgentRuntime bound;
        private final CurrentUser user;
        private final CallBox box;

        private PlatformRunner(Agent agent, BoundAgentRuntime bound, CurrentUser user, CallBox box) {
            this.agent = agent;
            this.bound = bound;
            this.user = user;
            this.box = box;
        }

        @Override
        public String getAgentName() {
            return agent.getName();
        }

        @Override
        public String getAgentDescription() {
            return described(agent);
        }

        @Override
        public Flux<AgentEvent> streamEvents(List<Msg> messages, AgentRequestOptions options) {
            return Flux.defer(() -> {
                String text = A2aMessages.textOf(messages);
                String session = box != null && box.sessionId != null && !box.sessionId.isBlank()
                        ? box.sessionId
                        : sessionOf(options);
                boolean pin = box == null || box.pinRelease;
                boolean record = box == null || box.recordExchange;
                String prefix = box == null ? "" : box.promptPrefix;
                String suffix = box == null ? "" : box.promptSuffix;
                ChatReply reply = CurrentUserHolder.call(user, () -> PromptOverlay.call(prefix, suffix,
                        () -> bound.invoke(agent, text, session, pin, record,
                                com.agentforge.controlplane.domain.ExecutionRecord.EntryPoint.A2A)));
                if (box != null) {
                    box.reply = reply;
                }
                return Flux.fromIterable(A2aMessages.replyEvents(reply.reply()));
            });
        }

        @Override
        public void stop(String taskId) {
        }

        private static String sessionOf(AgentRequestOptions options) {
            if (options != null && options.getSessionId() != null && !options.getSessionId().isBlank()) {
                return options.getSessionId();
            }
            if (options != null && options.getTaskId() != null && !options.getTaskId().isBlank()) {
                return "a2a_" + options.getTaskId();
            }
            return "";
        }
    }

    /** 给每次 JSON-RPC 和拉卡片的请求补上调用方的登录头。 */
    private static final class HeaderHttpClient implements A2AHttpClient {
        private final A2AHttpClient delegate = new JdkA2AHttpClient();
        private final Map<String, String> headers;

        private HeaderHttpClient(Map<String, String> headers) {
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public GetBuilder createGet() {
            GetBuilder builder = delegate.createGet();
            return headers.isEmpty() ? builder : builder.addHeaders(headers);
        }

        @Override
        public PostBuilder createPost() {
            PostBuilder builder = delegate.createPost();
            return headers.isEmpty() ? builder : builder.addHeaders(headers);
        }

        @Override
        public DeleteBuilder createDelete() {
            DeleteBuilder builder = delegate.createDelete();
            return headers.isEmpty() ? builder : builder.addHeaders(headers);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        serverExecutor.shutdownNow();
        clientExecutor.shutdownNow();
    }
}
