package run.halo.inviteregister.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;
import run.halo.inviteregister.config.InviteRegistrationSettings;
import run.halo.inviteregister.service.InviteCodeService;
import run.halo.inviteregister.service.InviteSignupTicketService;

@Component
public class InviteRegistrationFilter implements AdditionalWebFilter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String NATIVE_SIGNUP_PATH = "/signup";
    private static final String INVITE_VALIDATE_ACTION = "__invite_action";
    private static final String INVITE_VALIDATE_VALUE = "validate";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InviteCodeService inviteCodeService;
    private final InviteSignupTicketService ticketService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public InviteRegistrationFilter(InviteCodeService inviteCodeService,
        InviteSignupTicketService ticketService) {
        this.inviteCodeService = inviteCodeService;
        this.ticketService = ticketService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return inviteCodeService.getSettings()
            .flatMap(settings -> {
                if (!settings.isEnabledSafely()) {
                    return chain.filter(exchange);
                }

                String path = exchange.getRequest().getPath().pathWithinApplication().value();
                HttpMethod method = exchange.getRequest().getMethod();

                if (method == HttpMethod.GET
                    && isRegistrationPage(path, settings)
                    && !hasValidTicket(exchange)) {
                    return writeBridgePage(exchange, settings, queryError(exchange));
                }

                if (method == HttpMethod.POST && NATIVE_SIGNUP_PATH.equals(path) && !hasValidTicket(exchange)) {
                    return readBody(exchange)
                        .flatMap(body -> handleInviteValidation(exchange, settings, body));
                }

                if (method == HttpMethod.POST
                    && matches(path, settings.getRegistrationApiPatternList())) {
                    return handleRegistrationSubmit(exchange, chain);
                }

                return chain.filter(exchange);
            });
    }

    private Mono<Void> handleInviteValidation(ServerWebExchange exchange,
        InviteRegistrationSettings settings, byte[] bodyBytes) {
        Map<String, Object> payload = parseBody(bodyBytes);
        if (!isInviteValidationRequest(payload)) {
            return redirect(exchange, NATIVE_SIGNUP_PATH);
        }
        String inviteCode = extractInviteCode(payload, settings.getInviteFieldNameSafely());
        return inviteCodeService.validate(inviteCode)
            .flatMap(result -> {
                if (!result.valid()) {
                    return redirect(exchange, NATIVE_SIGNUP_PATH + "?error=" + encode(result.message()));
                }
                String ticket = ticketService.create(result.name());
                exchange.getResponse().addCookie(ticketCookie(ticket));
                return redirect(exchange, NATIVE_SIGNUP_PATH);
            });
    }

    private Mono<Void> handleRegistrationSubmit(ServerWebExchange exchange, WebFilterChain chain) {
        String ticket = ticketToken(exchange);
        String inviteName = ticketService.getInviteName(ticket);
        if (inviteName == null) {
            clearTicketCookie(exchange);
            return redirect(exchange, NATIVE_SIGNUP_PATH);
        }

        return continueNativeSignup(exchange, chain, inviteName, ticket);
    }

    private Mono<Void> continueNativeSignup(ServerWebExchange exchange, WebFilterChain chain,
        String inviteName, String ticket) {
        exchange.getResponse().beforeCommit(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            if (status == null || !status.isError()) {
                if (ticket != null) {
                    ticketService.invalidate(ticket);
                    clearTicketCookie(exchange);
                }
            }
            return Mono.empty();
        });
        return chain.filter(exchange)
            .doOnSuccess(ignored -> {
                HttpStatusCode status = exchange.getResponse().getStatusCode();
                if ((status == null || !status.isError()) && inviteName != null) {
                    inviteCodeService.consume(inviteName).subscribe();
                }
            });
    }

    private boolean isInviteValidationRequest(Map<String, Object> payload) {
        Object action = payload.get(INVITE_VALIDATE_ACTION);
        return action != null && INVITE_VALIDATE_VALUE.equals(String.valueOf(action));
    }

    private Mono<byte[]> readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
            .map(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                return bytes;
            })
            .switchIfEmpty(Mono.just(new byte[0]));
    }

    private Map<String, Object> parseBody(byte[] bodyBytes) {
        if (bodyBytes.length == 0) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(bodyBytes, MAP_TYPE);
        } catch (Exception ex) {
            return parseFormBody(bodyBytes);
        }
    }

    private Map<String, Object> parseFormBody(byte[] bodyBytes) {
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        if (body.isBlank() || !body.contains("=")) {
            return Map.of();
        }
        Map<String, Object> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decodeFormComponent(parts[0]);
            if (key.isBlank()) {
                continue;
            }
            String value = parts.length > 1 ? decodeFormComponent(parts[1]) : "";
            form.put(key, value);
        }
        return form;
    }

    private String decodeFormComponent(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String extractInviteCode(Map<String, Object> payload, String fieldName) {
        if (payload.containsKey(fieldName)) {
            Object value = payload.get(fieldName);
            return value == null ? null : String.valueOf(value);
        }
        Object nestedData = payload.get("data");
        if (nestedData instanceof Map<?, ?> nestedMap && nestedMap.containsKey(fieldName)) {
            Object value = nestedMap.get(fieldName);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private boolean matches(String path, List<String> patterns) {
        return patterns.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private boolean isRegistrationPage(String path, InviteRegistrationSettings settings) {
        return NATIVE_SIGNUP_PATH.equals(path) || matches(path, settings.getRegistrationPagePatternList());
    }

    private boolean hasValidTicket(ServerWebExchange exchange) {
        return ticketService.getInviteName(ticketToken(exchange)) != null;
    }

    private String ticketToken(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(InviteSignupTicketService.COOKIE_NAME);
        return cookie == null ? null : cookie.getValue();
    }

    private String queryError(ServerWebExchange exchange) {
        return exchange.getRequest().getQueryParams().getFirst("error");
    }

    private Mono<Void> writeBridgePage(ServerWebExchange exchange, InviteRegistrationSettings settings,
        String errorMessage) {
        return resolveCsrfToken(exchange)
            .map(csrfToken -> buildBridgePage(settings, errorMessage, csrfToken))
            .switchIfEmpty(Mono.fromSupplier(() -> buildBridgePage(settings, errorMessage, null)))
            .flatMap(html -> writeHtml(exchange, html));
    }

    private Mono<CsrfToken> resolveCsrfToken(ServerWebExchange exchange) {
        Object csrfAttr = exchange.getAttribute(CsrfToken.class.getName());
        if (csrfAttr instanceof Mono<?> mono) {
            return mono.filter(CsrfToken.class::isInstance).cast(CsrfToken.class);
        }
        if (csrfAttr instanceof CsrfToken token) {
            return Mono.just(token);
        }
        return Mono.empty();
    }

    private Mono<Void> writeHtml(ServerWebExchange exchange, String html) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private String buildBridgePage(InviteRegistrationSettings settings, String errorMessage,
        CsrfToken csrfToken) {
        String title = "邀请码注册";
        String description = "请输入有效的邀请码，验证通过后继续完成注册。";
        String label = escapeHtml(defaultIfBlank(settings.getInputLabel(), "邀请码"));
        String placeholder = escapeHtml(defaultIfBlank(settings.getInputPlaceholder(), "请输入邀请码"));
        String helpText = escapeHtml(defaultIfBlank(settings.getInputHelpText(), ""));
        String errorBlock = (errorMessage == null || errorMessage.isBlank())
            ? ""
            : "<div class=\"invite-toast invite-toast--error\" role=\"alert\">"
                + escapeHtml(errorMessage) + "</div>";
        String csrfInput = csrfToken == null
            ? ""
            : "<input type=\"hidden\" name=\"" + escapeHtml(csrfToken.getParameterName())
                + "\" value=\"" + escapeHtml(csrfToken.getToken()) + "\" />";
        String helpBlock = helpText.isBlank()
            ? ""
            : "<div class=\"invite-help\">" + helpText + "</div>";

        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>%s</title>
                <style>
                    *{box-sizing:border-box;}
                    body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px;background-color:#f8fafc;background-image:radial-gradient(circle at 50%% 0%%,#ffffff 0%%,transparent 70%%);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#0f172a;}
                    .invite-card{width:min(100%%,420px);padding:32px;border:1px solid #f1f5f9;border-radius:24px;background:#ffffff;box-shadow:0 8px 30px rgba(0,0,0,.04);}
                    .invite-brand{display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:24px;}
                    .invite-brand__mark{display:flex;align-items:center;justify-content:center;width:32px;height:32px;border-radius:10px;background:#0f172a;color:#ffffff;font-size:18px;font-weight:700;line-height:1;}
                    .invite-brand__text{color:#334155;font-size:18px;font-weight:600;letter-spacing:.02em;}
                    h1{margin:0 0 8px;text-align:center;color:#0f172a;font-size:28px;font-weight:700;}
                    .invite-desc{margin:0 0 28px;text-align:center;color:#64748b;font-size:14px;line-height:1.75;}
                    .invite-toast{margin-bottom:16px;padding:12px 14px;border-radius:14px;font-size:13px;font-weight:600;}
                    .invite-toast--error{background:#fff1f2;border:1px solid #ffe4e6;color:#be123c;}
                    .invite-form{display:flex;flex-direction:column;gap:20px;}
                    .invite-label{display:block;margin-bottom:10px;color:#0f172a;font-size:14px;font-weight:600;}
                    .invite-input-wrap{position:relative;}
                    input{width:100%%;padding:13px 44px 13px 16px;border:1px solid #e2e8f0;border-radius:16px;background:#f8fafc;color:#0f172a;font-size:14px;outline:none;transition:border-color .18s ease,box-shadow .18s ease,background-color .18s ease;}
                    input::placeholder{color:#94a3b8;}
                    input:focus{background:#ffffff;border-color:#3b82f6;box-shadow:0 0 0 4px rgba(59,130,246,.12);}
                    .invite-input-icon{position:absolute;top:50%%;right:16px;display:flex;align-items:center;color:#94a3b8;transform:translateY(-50%%);pointer-events:none;}
                    .invite-help{margin-top:10px;color:#64748b;font-size:13px;line-height:1.7;}
                    .invite-submit{display:flex;align-items:center;justify-content:center;width:100%%;padding:13px 16px;border:0;border-radius:16px;background:#0f172a;color:#ffffff;font-size:14px;font-weight:600;cursor:pointer;transition:background-color .18s ease,opacity .18s ease;}
                    .invite-submit:hover{background:#1e293b;}
                    .invite-submit:disabled{cursor:not-allowed;opacity:.74;}
                    .invite-footer{margin-top:24px;text-align:center;}
                    .back-link{color:#94a3b8;font-size:12px;text-decoration:none;transition:color .18s ease;}
                    .back-link:hover{color:#475569;}
                    @media (prefers-reduced-motion:reduce){input,.invite-submit,.back-link{transition:none;}}
                </style>
            </head>
            <body>
                <section class="invite-card">
                    <div class="invite-brand">
                        <div class="invite-brand__mark">H</div>
                        <span class="invite-brand__text">Halo</span>
                    </div>
                    <h1>%s</h1>
                    <p class="invite-desc">%s</p>
                    %s
                    <form class="invite-form" action="%s" method="post">
                        %s
                        <input type="hidden" name="%s" value="%s" />
                        <div>
                            <label class="invite-label" for="inviteCode">%s</label>
                            <div class="invite-input-wrap">
                                <input
                                    id="inviteCode"
                                    name="%s"
                                    type="text"
                                    placeholder="%s"
                                    autocomplete="one-time-code"
                                    autofocus
                                    required
                                />
                                <span class="invite-input-icon">
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                        <path stroke-linecap="round" stroke-linejoin="round" d="M15 7a2 2 0 0 1 2 2m4 0a6 6 0 0 1-7.743 5.743L11 17H9v2H7v2H4a1 1 0 0 1-1-1v-2.586a1 1 0 0 1 .293-.707l5.964-5.964A6 6 0 1 1 21 9Z"/>
                                    </svg>
                                </span>
                            </div>
                            %s
                        </div>
                        <button id="inviteSubmit" class="invite-submit" type="submit">立即验证</button>
                    </form>
                    <div class="invite-footer">
                        <a class="back-link" href="/">不想使用邀请码？返回首页</a>
                    </div>
                </section>
                <script>
                    const form = document.querySelector(".invite-form");
                    const submitButton = document.getElementById("inviteSubmit");
                    if (form && submitButton) {
                        form.addEventListener("submit", () => {
                            submitButton.disabled = true;
                            submitButton.classList.add("is-loading");
                            submitButton.textContent = "验证中...";
                        });
                    }
                </script>
            </body>
            </html>
            """.formatted(
            title,
            title,
            description,
            errorBlock,
            NATIVE_SIGNUP_PATH,
            csrfInput,
            INVITE_VALIDATE_ACTION,
            INVITE_VALIDATE_VALUE,
            label,
            escapeHtml(settings.getInviteFieldNameSafely()),
            placeholder,
            helpBlock
        );
    }

    private Mono<Void> redirect(ServerWebExchange exchange, String location) {
        exchange.getResponse().setStatusCode(HttpStatus.SEE_OTHER);
        exchange.getResponse().getHeaders().set(HttpHeaders.LOCATION, location);
        return exchange.getResponse().setComplete();
    }

    private ResponseCookie ticketCookie(String ticket) {
        return ResponseCookie.from(InviteSignupTicketService.COOKIE_NAME, ticket)
            .path("/")
            .httpOnly(true)
            .sameSite("Lax")
            .build();
    }

    private void clearTicketCookie(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(ResponseCookie.from(InviteSignupTicketService.COOKIE_NAME, "")
            .path("/")
            .maxAge(0)
            .httpOnly(true)
            .sameSite("Lax")
            .build());
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
