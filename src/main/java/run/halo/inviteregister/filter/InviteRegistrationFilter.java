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
import run.halo.inviteregister.service.InviteBrandingService;
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

    private final InviteBrandingService inviteBrandingService;
    private final InviteCodeService inviteCodeService;
    private final InviteSignupTicketService ticketService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public InviteRegistrationFilter(InviteBrandingService inviteBrandingService,
        InviteCodeService inviteCodeService,
        InviteSignupTicketService ticketService) {
        this.inviteBrandingService = inviteBrandingService;
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
        return inviteBrandingService.resolve(settings)
            .flatMap(branding -> resolveCsrfToken(exchange)
                .map(csrfToken -> buildBridgePage(settings, branding, errorMessage, csrfToken))
                .switchIfEmpty(Mono.fromSupplier(
                    () -> buildBridgePage(settings, branding, errorMessage, null)
                )))
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

    private String buildBridgePage(InviteRegistrationSettings settings,
        InviteBrandingService.InviteBranding branding, String errorMessage, CsrfToken csrfToken) {
        String title = "邀请码注册";
        String label = escapeHtml(defaultIfBlank(settings.getInputLabel(), "邀请码"));
        String placeholder = escapeHtml(defaultIfBlank(settings.getInputPlaceholder(), "请输入邀请码"));
        String helpText = escapeHtml(defaultIfBlank(settings.getInputHelpText(), ""));
        String brandBlock = buildBrandBlock(settings, branding);
        String contactBlock = buildContactBlock(settings);
        String mascotRow = buildPeekMascotRow();
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
                    body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:32px 16px;background-color:#f8fafc;background-image:radial-gradient(circle at 50%% -10%%,rgba(255,255,255,.98),transparent 36%%),radial-gradient(circle at 0%% 18%%,rgba(191,219,254,.5),transparent 28%%),radial-gradient(circle at 100%% 82%%,rgba(221,214,254,.36),transparent 32%%),linear-gradient(180deg,#f8fbff 0%%,#f3f7ff 54%%,#eef4ff 100%%);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#0f172a;}
                    .invite-shell{width:min(100%%,460px);display:flex;flex-direction:column;align-items:center;--peek-shift:0px;--peek-rise:0px;--peek-tilt:0deg;}
                    .invite-brand{display:flex;align-items:center;justify-content:center;width:100%%;margin-bottom:26px;}
                    .invite-brand__logo{display:block;max-width:min(100%%,340px);max-height:120px;width:auto;height:auto;object-fit:contain;filter:drop-shadow(0 12px 24px rgba(15,23,42,.08));}
                    .invite-brand__fallback{display:flex;align-items:center;justify-content:center;width:72px;height:72px;border-radius:24px;background:linear-gradient(180deg,#1e293b,#0f172a);color:#ffffff;font-size:32px;font-weight:700;line-height:1;box-shadow:0 22px 36px rgba(15,23,42,.18);}
                    .invite-brand--with-text{gap:16px;flex-wrap:nowrap;}
                    .invite-brand__name{min-width:0;max-width:min(100%%,300px);color:#0f172a;font-size:30px;font-weight:800;line-height:1.2;letter-spacing:.01em;word-break:break-word;overflow-wrap:anywhere;text-align:left;}
                    .invite-card{position:relative;width:100%%;padding:34px 34px 32px;border:1px solid rgba(226,232,240,.88);border-radius:30px;background:rgba(255,255,255,.94);box-shadow:0 24px 48px rgba(15,23,42,.09),inset 0 1px 0 rgba(255,255,255,.8);backdrop-filter:blur(10px);overflow:hidden;}
                    .invite-card::before{content:"";position:absolute;top:-78px;right:-48px;width:180px;height:180px;border-radius:999px;background:radial-gradient(circle,rgba(96,165,250,.18) 0%%,rgba(96,165,250,0) 72%%);pointer-events:none;}
                    .invite-toast{margin-bottom:18px;padding:12px 14px;border-radius:16px;font-size:13px;font-weight:600;}
                    .invite-toast--error{background:#fff1f2;border:1px solid #ffe4e6;color:#be123c;}
                    .invite-form{display:flex;flex-direction:column;gap:24px;}
                    .invite-field{display:flex;flex-direction:column;gap:12px;}
                    .invite-field__top{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;}
                    .invite-label{display:block;color:#0f172a;font-size:16px;font-weight:700;letter-spacing:.01em;}
                    .invite-peek{position:relative;display:flex;align-items:flex-end;justify-content:flex-end;min-width:184px;height:54px;flex:1;}
                    .invite-peek__track{position:absolute;left:0;right:0;bottom:14px;height:8px;border-radius:999px;background:linear-gradient(90deg,rgba(226,232,240,0),#e2e8f0 16%%,#dbeafe 60%%,rgba(191,219,254,.16) 100%%);}
                    .invite-peek__crew{position:relative;z-index:1;display:flex;align-items:flex-end;gap:8px;padding-right:4px;}
                    .invite-mascot{position:relative;width:36px;height:44px;animation:inviteMascotBob 2.8s ease-in-out infinite;animation-delay:var(--delay,0s);transform:translateY(calc(var(--peek-rise) * -1)) rotate(var(--peek-tilt));transform-origin:center bottom;transition:transform .22s ease,filter .22s ease;}
                    .invite-mascot--2{width:38px;height:46px;}
                    .invite-mascot--3{width:34px;height:42px;}
                    .invite-mascot__body{position:absolute;left:50%%;bottom:0;width:32px;height:34px;transform:translateX(-50%%);border-radius:16px 16px 12px 12px;background:linear-gradient(180deg,#ffe066 0%%,#ffd23f 68%%,#ffbf1f 100%%);border:2px solid #c58b00;box-shadow:0 8px 14px rgba(250,204,21,.26);}
                    .invite-mascot__strap{position:absolute;left:50%%;bottom:10px;width:28px;height:14px;transform:translateX(-50%%);border-radius:10px 10px 12px 12px;background:#5b7ae5;opacity:.95;}
                    .invite-mascot__eye-band{position:absolute;left:50%%;top:9px;width:26px;height:12px;transform:translateX(-50%%);border-radius:999px;background:#64748b;}
                    .invite-mascot__eye{position:absolute;top:6px;width:13px;height:13px;border-radius:999px;background:#ffffff;border:2px solid #475569;overflow:hidden;}
                    .invite-mascot__eye::after{content:"";position:absolute;top:50%%;left:50%%;width:5px;height:5px;border-radius:999px;background:#0f172a;transform:translate(calc(-50%% + var(--peek-shift)),calc(-50%% + (var(--peek-rise) * -.04)));}
                    .invite-mascot__eye--left{left:6px;}
                    .invite-mascot__eye--right{right:6px;}
                    .invite-mascot__arm{position:absolute;bottom:10px;width:8px;height:18px;border-radius:999px;background:#ffd23f;border:2px solid #c58b00;}
                    .invite-mascot__arm--left{left:2px;transform:rotate(14deg);}
                    .invite-mascot__arm--right{right:2px;transform:rotate(-14deg);}
                    .invite-shell.is-peeking .invite-peek__track{background:linear-gradient(90deg,rgba(226,232,240,0),#d7e5ff 14%%,#93c5fd 52%%,#60a5fa 100%%);}
                    .invite-shell.is-peeking .invite-mascot{filter:drop-shadow(0 12px 16px rgba(250,204,21,.18));}
                    .invite-input-wrap{position:relative;}
                    input{width:100%%;padding:15px 48px 15px 18px;border:1px solid #d9e4f4;border-radius:20px;background:linear-gradient(180deg,#ffffff,#f8fbff);color:#0f172a;font-size:15px;outline:none;box-shadow:inset 0 1px 0 rgba(255,255,255,.75);transition:border-color .18s ease,box-shadow .18s ease,background-color .18s ease,transform .18s ease;}
                    input::placeholder{color:#94a3b8;}
                    input:focus{background:#ffffff;border-color:#60a5fa;box-shadow:0 0 0 4px rgba(96,165,250,.14),0 16px 28px rgba(148,163,184,.14);transform:translateY(-1px);}
                    .invite-input-icon{position:absolute;top:50%%;right:16px;display:flex;align-items:center;color:#94a3b8;transform:translateY(-50%%);pointer-events:none;}
                    .invite-help{margin-top:2px;color:#64748b;font-size:13px;line-height:1.8;}
                    .invite-submit{display:flex;align-items:center;justify-content:center;width:100%%;padding:15px 16px;border:0;border-radius:20px;background:linear-gradient(180deg,#19223f,#0f172a);color:#ffffff;font-size:15px;font-weight:700;letter-spacing:.02em;cursor:pointer;box-shadow:0 18px 26px rgba(15,23,42,.14);transition:transform .18s ease,background-color .18s ease,opacity .18s ease,box-shadow .18s ease;}
                    .invite-submit:hover{background:#1e293b;}
                    .invite-submit:hover{transform:translateY(-1px);box-shadow:0 22px 30px rgba(15,23,42,.18);}
                    .invite-submit:disabled{cursor:not-allowed;opacity:.74;}
                    .invite-footer{width:100%%;margin-top:24px;text-align:center;}
                    .invite-footer__actions{display:flex;flex-direction:column;align-items:center;gap:14px;}
                    .back-link{color:#94a3b8;font-size:13px;text-decoration:none;transition:color .18s ease;}
                    .back-link:hover{color:#475569;}
                    .invite-contact-link{padding:0;border:0;background:none;color:#475569;font-size:14px;font-weight:700;cursor:pointer;transition:color .18s ease;}
                    .invite-contact-link:hover{color:#0f172a;}
                    .invite-contact-dialog[hidden]{display:none;}
                    .invite-contact-dialog{position:fixed;inset:0;z-index:20;display:flex;align-items:center;justify-content:center;padding:20px;}
                    .invite-contact-dialog__backdrop{position:absolute;inset:0;background:rgba(15,23,42,.42);}
                    .invite-contact-dialog__card{position:relative;width:min(100%%,360px);padding:24px;border-radius:22px;background:#ffffff;box-shadow:0 24px 60px rgba(15,23,42,.18);}
                    .invite-contact-dialog__close{position:absolute;top:14px;right:14px;display:flex;align-items:center;justify-content:center;width:32px;height:32px;border:0;border-radius:999px;background:#f8fafc;color:#475569;font-size:20px;cursor:pointer;}
                    .invite-contact-dialog__title{margin:0 0 16px;color:#0f172a;font-size:20px;font-weight:700;text-align:left;}
                    .invite-contact-dialog__image{display:block;width:100%%;max-height:260px;object-fit:contain;border-radius:16px;background:#f8fafc;}
                    .invite-contact-dialog__text{margin-top:16px;color:#334155;font-size:14px;line-height:1.8;text-align:left;word-break:break-word;}
                    @keyframes inviteMascotBob{0%%,100%%{margin-bottom:0;}50%%{margin-bottom:3px;}}
                    @media (max-width:480px){body{padding:24px 14px;}.invite-shell{width:min(100%%,400px);}.invite-card{padding:28px 20px 26px;border-radius:26px;}.invite-brand{margin-bottom:20px;}.invite-brand__logo{max-width:min(100%%,260px);max-height:88px;}.invite-brand--with-text{gap:12px;}.invite-brand__fallback{width:64px;height:64px;border-radius:20px;font-size:28px;}.invite-brand__name{max-width:min(100%%,240px);font-size:24px;}.invite-field__top{flex-direction:column;align-items:flex-start;gap:10px;}.invite-peek{width:100%%;min-width:0;height:50px;}.invite-peek__crew{margin-left:auto;}}
                    @media (prefers-reduced-motion:reduce){input,.invite-submit,.back-link,.invite-mascot{transition:none;animation:none;}}
                </style>
            </head>
            <body>
                <main class="invite-shell">
                    %s
                    <section class="invite-card">
                        %s
                        <form class="invite-form" action="%s" method="post">
                            %s
                            <input type="hidden" name="%s" value="%s" />
                            <div class="invite-field">
                                <div class="invite-field__top">
                                    <label class="invite-label" for="inviteCode">%s</label>
                                    %s
                                </div>
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
                    </section>
                    <div class="invite-footer">
                        <div class="invite-footer__actions">
                            %s
                            <a class="back-link" href="/">不想使用邀请码？返回首页</a>
                        </div>
                    </div>
                </main>
                %s
                <script>
                    const shell = document.querySelector(".invite-shell");
                    const form = document.querySelector(".invite-form");
                    const inviteInput = document.getElementById("inviteCode");
                    const submitButton = document.getElementById("inviteSubmit");
                    if (form && submitButton) {
                        form.addEventListener("submit", () => {
                            submitButton.disabled = true;
                            submitButton.classList.add("is-loading");
                            submitButton.textContent = "验证中...";
                        });
                    }
                    if (shell && inviteInput) {
                        const updatePeekState = () => {
                            const hasFocus = document.activeElement === inviteInput;
                            const valueLength = inviteInput.value.trim().length;
                            const shift = Math.max(-2, Math.min(8, valueLength * 0.9 - 2));
                            const rise = hasFocus || valueLength > 0 ? Math.min(11, 5 + valueLength * 0.45) : 0;
                            const tilt = hasFocus || valueLength > 0 ? Math.max(-3, Math.min(6, valueLength * 0.5 - 1)) : 0;
                            shell.style.setProperty("--peek-shift", shift.toFixed(2) + "px");
                            shell.style.setProperty("--peek-rise", rise.toFixed(2) + "px");
                            shell.style.setProperty("--peek-tilt", tilt.toFixed(2) + "deg");
                            shell.classList.toggle("is-peeking", hasFocus || valueLength > 0);
                        };
                        ["focus", "blur", "input", "change"].forEach((eventName) => {
                            inviteInput.addEventListener(eventName, updatePeekState);
                        });
                        updatePeekState();
                    }
                    const contactToggle = document.getElementById("contactAdminToggle");
                    const contactDialog = document.getElementById("contactAdminDialog");
                    if (contactToggle && contactDialog) {
                        const closeContactDialog = () => {
                            contactDialog.hidden = true;
                            contactToggle.setAttribute("aria-expanded", "false");
                            document.body.style.overflow = "";
                        };
                        const openContactDialog = () => {
                            contactDialog.hidden = false;
                            contactToggle.setAttribute("aria-expanded", "true");
                            document.body.style.overflow = "hidden";
                        };
                        contactToggle.addEventListener("click", openContactDialog);
                        contactDialog.querySelectorAll("[data-close-contact]").forEach((element) => {
                            element.addEventListener("click", closeContactDialog);
                        });
                        document.addEventListener("keydown", (event) => {
                            if (event.key === "Escape" && !contactDialog.hidden) {
                                closeContactDialog();
                            }
                        });
                    }
                </script>
            </body>
            </html>
            """.formatted(
            title,
            brandBlock,
            errorBlock,
            NATIVE_SIGNUP_PATH,
            csrfInput,
            INVITE_VALIDATE_ACTION,
            INVITE_VALIDATE_VALUE,
            label,
            mascotRow,
            escapeHtml(settings.getInviteFieldNameSafely()),
            placeholder,
            helpBlock,
            buildContactTrigger(settings),
            contactBlock
        );
    }

    private String buildBrandBlock(InviteRegistrationSettings settings,
        InviteBrandingService.InviteBranding branding) {
        String brandName = escapeHtml(defaultIfBlank(branding.name(), "Halo"));
        String brandLogo = escapeHtml(defaultIfBlank(branding.logo(), ""));
        if (!brandLogo.isBlank()) {
            return """
                <div class="invite-brand">
                    <img class="invite-brand__logo" src="%s" alt="%s" />
                </div>
                """.formatted(brandLogo, brandName);
        }
        if ("custom".equals(settings.getBrandSourceSafely())) {
            String brandMark = escapeHtml(defaultIfBlank(branding.mark(), "H"));
            return """
                <div class="invite-brand invite-brand--with-text">
                    <div class="invite-brand__fallback" aria-hidden="true">%s</div>
                    <div class="invite-brand__name">%s</div>
                </div>
                """.formatted(brandMark, brandName);
        }
        String brandMark = escapeHtml(defaultIfBlank(branding.mark(), "H"));
        return """
            <div class="invite-brand">
                <div class="invite-brand__fallback" aria-label="%s" role="img">%s</div>
            </div>
            """.formatted(brandName, brandMark);
    }

    private String buildPeekMascotRow() {
        return """
            <div class="invite-peek" aria-hidden="true">
                <div class="invite-peek__track"></div>
                <div class="invite-peek__crew">
                    %s
                    %s
                    %s
                </div>
            </div>
            """.formatted(
            buildPeekMascot("invite-mascot", "0s"),
            buildPeekMascot("invite-mascot invite-mascot--2", ".25s"),
            buildPeekMascot("invite-mascot invite-mascot--3", ".5s")
        );
    }

    private String buildPeekMascot(String className, String delay) {
        return """
            <span class="%s" style="--delay:%s;">
                <span class="invite-mascot__arm invite-mascot__arm--left"></span>
                <span class="invite-mascot__arm invite-mascot__arm--right"></span>
                <span class="invite-mascot__body"></span>
                <span class="invite-mascot__strap"></span>
                <span class="invite-mascot__eye-band"></span>
                <span class="invite-mascot__eye invite-mascot__eye--left"></span>
                <span class="invite-mascot__eye invite-mascot__eye--right"></span>
            </span>
            """.formatted(className, delay);
    }

    private String buildContactTrigger(InviteRegistrationSettings settings) {
        if (!settings.hasContactConfig()) {
            return "";
        }
        return """
            <button id="contactAdminToggle" class="invite-contact-link" type="button" aria-expanded="false">
                联系站长
            </button>
            """;
    }

    private String buildContactBlock(InviteRegistrationSettings settings) {
        if (!settings.hasContactConfig()) {
            return "";
        }
        String contactInfo = settings.getContactInfoSafely();
        String contactImage = settings.getContactImageSafely();
        String contactImageBlock = contactImage == null
            ? ""
            : "<img class=\"invite-contact-dialog__image\" src=\""
                + escapeHtml(contactImage) + "\" alt=\"站长联系方式\" />";
        String contactTextBlock = contactInfo == null
            ? ""
            : "<div class=\"invite-contact-dialog__text\">"
                + escapeHtmlWithLineBreaks(contactInfo) + "</div>";
        return """
            <div id="contactAdminDialog" class="invite-contact-dialog" hidden>
                <button class="invite-contact-dialog__backdrop" type="button" data-close-contact aria-label="关闭联系方式"></button>
                <div class="invite-contact-dialog__card" role="dialog" aria-modal="true" aria-labelledby="contactAdminDialogTitle">
                    <button class="invite-contact-dialog__close" type="button" data-close-contact aria-label="关闭">×</button>
                    <h2 id="contactAdminDialogTitle" class="invite-contact-dialog__title">联系站长</h2>
                    %s
                    %s
                </div>
            </div>
            """.formatted(contactImageBlock, contactTextBlock);
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

    private String escapeHtmlWithLineBreaks(String value) {
        return escapeHtml(value).replace("\r\n", "\n").replace("\n", "<br />");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
