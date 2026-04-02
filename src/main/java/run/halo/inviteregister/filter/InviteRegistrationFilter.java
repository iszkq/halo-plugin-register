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
        String shellInlineStyle = buildShellInlineStyle(settings);
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
                    body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:40px 20px;background-color:#f8fafc;background-image:radial-gradient(circle at 50%% -10%%,rgba(255,255,255,.98),transparent 36%%),radial-gradient(circle at 0%% 18%%,rgba(191,219,254,.5),transparent 28%%),radial-gradient(circle at 100%% 82%%,rgba(221,214,254,.36),transparent 32%%),linear-gradient(180deg,#f8fbff 0%%,#f3f7ff 54%%,#eef4ff 100%%);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#0f172a;}
                    .invite-shell{width:min(100%%,460px);display:flex;flex-direction:column;align-items:center;--peek-pupil-x:0px;--peek-pupil-y:0px;--peek-rise:0px;--peek-tilt:0deg;}
                    .invite-brand{display:flex;align-items:center;justify-content:center;width:100%%;margin-bottom:30px;padding-inline:8px;}
                    .invite-brand__logo{display:block;width:var(--invite-brand-logo-width,auto);max-width:90%%;max-height:var(--invite-brand-logo-height,120px);height:auto;object-fit:contain;filter:drop-shadow(0 12px 24px rgba(15,23,42,.08));}
                    .invite-brand__fallback{display:flex;align-items:center;justify-content:center;width:72px;height:72px;border-radius:24px;background:linear-gradient(180deg,#1e293b,#0f172a);color:#ffffff;font-size:32px;font-weight:700;line-height:1;box-shadow:0 22px 36px rgba(15,23,42,.18);}
                    .invite-brand--with-text{gap:16px;flex-wrap:nowrap;}
                    .invite-brand__name{min-width:0;max-width:min(100%%,300px);color:#0f172a;font-size:30px;font-weight:800;line-height:1.2;letter-spacing:.01em;word-break:break-word;overflow-wrap:anywhere;text-align:left;}
                    .invite-card{position:relative;width:100%%;padding:40px 38px 36px;border:1px solid rgba(226,232,240,.88);border-radius:30px;background:rgba(255,255,255,.94);box-shadow:0 24px 48px rgba(15,23,42,.09),inset 0 1px 0 rgba(255,255,255,.8);backdrop-filter:blur(10px);overflow:hidden;}
                    .invite-card::before{content:"";position:absolute;top:-78px;right:-48px;width:180px;height:180px;border-radius:999px;background:radial-gradient(circle,rgba(96,165,250,.18) 0%%,rgba(96,165,250,0) 72%%);pointer-events:none;}
                    .invite-toast{margin-bottom:18px;padding:12px 14px;border-radius:16px;font-size:13px;font-weight:600;}
                    .invite-toast--error{background:#fff1f2;border:1px solid #ffe4e6;color:#be123c;}
                    .invite-form{display:flex;flex-direction:column;gap:28px;}
                    .invite-field{display:flex;flex-direction:column;gap:14px;}
                    .invite-field__top{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;padding-bottom:4px;}
                    .invite-label{display:block;color:#0f172a;font-size:16px;font-weight:700;letter-spacing:.01em;}
                    .invite-peek{position:relative;display:flex;align-items:flex-end;justify-content:flex-end;min-width:184px;height:58px;flex:1;}
                    .invite-peek__track{position:absolute;left:0;right:0;bottom:14px;height:8px;border-radius:999px;background:linear-gradient(90deg,rgba(226,232,240,0),#e2e8f0 16%%,#dbeafe 60%%,rgba(191,219,254,.16) 100%%);}
                    .invite-peek__crew{position:relative;z-index:1;display:flex;align-items:flex-end;gap:10px;padding-right:4px;transition:transform .32s cubic-bezier(.2,.75,.35,1);}
                    .invite-mascot{position:relative;width:42px;height:54px;transform:translate3d(0,calc(var(--peek-rise) * -1),0) rotate(var(--peek-tilt));transform-origin:center bottom;will-change:transform,filter;filter:drop-shadow(0 8px 12px rgba(250,204,21,.12));transition:transform .38s cubic-bezier(.2,.75,.35,1),filter .28s ease;}
                    .invite-mascot--2{width:44px;height:56px;}
                    .invite-mascot--3{width:40px;height:52px;}
                    .invite-mascot__shadow{position:absolute;left:50%%;bottom:0;width:28px;height:8px;border-radius:999px;background:rgba(148,163,184,.18);transform:translateX(-50%%);animation:inviteMascotShadow 3.6s ease-in-out infinite;animation-delay:var(--delay,0s);}
                    .invite-mascot__inner{position:absolute;inset:0;animation:inviteMascotFloat 3.6s cubic-bezier(.45,.05,.35,1) infinite;animation-delay:var(--delay,0s);transform-origin:center bottom;will-change:transform;}
                    .invite-mascot--2 .invite-mascot__inner{animation-duration:4.1s;}
                    .invite-mascot--3 .invite-mascot__inner{animation-duration:3.2s;}
                    .invite-mascot__body{position:absolute;left:50%%;bottom:7px;width:34px;height:39px;transform:translateX(-50%%);border-radius:18px 18px 14px 14px;background:linear-gradient(180deg,#ffe89a 0%%,#ffd84d 36%%,#ffc423 100%%);border:2px solid #c58b00;box-shadow:inset 0 2px 0 rgba(255,255,255,.38),0 10px 16px rgba(250,204,21,.20);}
                    .invite-mascot__body::before{content:"";position:absolute;left:5px;right:5px;top:3px;height:12px;border-radius:999px;background:linear-gradient(180deg,rgba(255,255,255,.26),rgba(255,255,255,0));}
                    .invite-mascot__body::after{content:"";position:absolute;left:50%%;bottom:-2px;width:28px;height:19px;transform:translateX(-50%%);border-radius:12px 12px 11px 11px;background:linear-gradient(180deg,#6f8cff 0%%,#5577ef 100%%);border:2px solid #4c63d6;}
                    .invite-mascot__strap{position:absolute;left:50%%;bottom:23px;width:28px;height:9px;transform:translateX(-50%%);border-radius:999px;background:#556fe2;box-shadow:0 0 0 2px rgba(91,122,229,.15);}
                    .invite-mascot__pocket{position:absolute;left:50%%;bottom:14px;width:14px;height:10px;transform:translateX(-50%%);border-radius:5px 5px 7px 7px;background:#89a2ff;border:2px solid #4c63d6;}
                    .invite-mascot__eye-band{position:absolute;left:50%%;top:9px;width:29px;height:13px;transform:translateX(-50%%);border-radius:999px;background:linear-gradient(180deg,#7c8798,#556070);}
                    .invite-mascot__eye{position:absolute;top:5px;width:13px;height:13px;border-radius:999px;background:#ffffff;border:2px solid #475569;overflow:hidden;box-shadow:inset 0 1px 0 rgba(255,255,255,.65);}
                    .invite-mascot__eye::before{content:"";position:absolute;left:-2px;right:-2px;top:-2px;height:0;border-radius:999px;background:linear-gradient(180deg,#94a3b8 0%%,#64748b 100%%);z-index:2;animation:inviteMascotBlink 7.2s infinite;animation-delay:calc(var(--delay,0s) + .6s);}
                    .invite-mascot__eye::after{content:"";position:absolute;top:50%%;left:50%%;width:4px;height:4px;border-radius:999px;background:#0f172a;box-shadow:0 0 0 1px rgba(15,23,42,.06);transform:translate(calc(-50%% + var(--peek-pupil-x)),calc(-50%% + var(--peek-pupil-y)));}
                    .invite-mascot__eye--left{left:7px;}
                    .invite-mascot__eye--right{right:7px;}
                    .invite-mascot__shine{position:absolute;top:7px;left:50%%;width:18px;height:6px;transform:translateX(-50%%);border-radius:999px;background:linear-gradient(180deg,rgba(255,255,255,.55),rgba(255,255,255,0));opacity:.78;}
                    .invite-mascot__mouth{position:absolute;left:50%%;top:24px;width:12px;height:6px;transform:translateX(-50%%);border-bottom:2px solid rgba(111,53,0,.68);border-radius:0 0 12px 12px;opacity:.72;}
                    .invite-mascot--2 .invite-mascot__mouth{width:10px;height:4px;border-bottom-color:rgba(111,53,0,.58);}
                    .invite-mascot--3 .invite-mascot__mouth{width:13px;height:7px;}
                    .invite-mascot__cheek{position:absolute;top:23px;width:5px;height:5px;border-radius:999px;background:rgba(249,115,22,.18);}
                    .invite-mascot__cheek--left{left:8px;}
                    .invite-mascot__cheek--right{right:8px;}
                    .invite-mascot__arm{position:absolute;bottom:17px;width:9px;height:20px;border-radius:999px;background:linear-gradient(180deg,#ffe27a,#ffc92f);border:2px solid #c58b00;transform-origin:center 3px;box-shadow:0 5px 8px rgba(197,139,0,.14);}
                    .invite-mascot__arm--left{left:2px;transform:rotate(18deg);}
                    .invite-mascot__arm--right{right:2px;transform:rotate(-18deg);}
                    .invite-mascot--3 .invite-mascot__arm--right{animation:inviteMascotWave 1.9s ease-in-out infinite;animation-delay:calc(var(--delay,0s) + .35s);}
                    .invite-mascot__leg{position:absolute;bottom:5px;width:6px;height:9px;border-radius:999px;background:#4c63d6;}
                    .invite-mascot__leg--left{left:14px;}
                    .invite-mascot__leg--right{right:14px;}
                    .invite-mascot__shoe{position:absolute;bottom:1px;width:11px;height:5px;border-radius:999px;background:#334155;}
                    .invite-mascot__shoe--left{left:11px;}
                    .invite-mascot__shoe--right{right:11px;}
                    .invite-mascot__tuft{position:absolute;left:50%%;top:3px;width:2px;height:7px;border-radius:999px;background:#475569;transform-origin:center bottom;}
                    .invite-mascot__tuft--1{transform:translateX(-5px) rotate(-18deg);}
                    .invite-mascot__tuft--2{transform:translateX(-1px) rotate(-2deg);}
                    .invite-mascot__tuft--3{transform:translateX(3px) rotate(16deg);}
                    .invite-mascot__prop{position:absolute;opacity:0;pointer-events:none;will-change:transform,opacity;transition:opacity .26s ease,transform .34s cubic-bezier(.2,.75,.35,1);}
                    .invite-mascot__magnifier{left:-8px;top:13px;width:20px;height:20px;transform:translate3d(-5px,7px,0) rotate(-18deg) scale(.82);}
                    .invite-mascot__magnifier::before{content:"";position:absolute;inset:0;border-radius:999px;border:3px solid #7c8798;background:rgba(255,255,255,.68);backdrop-filter:blur(2px);box-shadow:inset 0 0 0 2px rgba(255,255,255,.28);}
                    .invite-mascot__magnifier::after{content:"";position:absolute;right:-2px;bottom:-7px;width:4px;height:12px;border-radius:999px;background:#7c8798;transform:rotate(-38deg);transform-origin:top center;}
                    .invite-mascot__tablet{left:50%%;bottom:11px;width:20px;height:16px;border-radius:6px;background:linear-gradient(180deg,#ffffff,#eef4ff);border:2px solid #8aa0f8;box-shadow:0 8px 14px rgba(96,165,250,.12);transform:translate3d(-50%%,9px,0) rotate(8deg) scale(.84);}
                    .invite-mascot__tablet-line{position:absolute;left:4px;right:4px;height:2px;border-radius:999px;background:#93c5fd;}
                    .invite-mascot__tablet-line--1{top:4px;}
                    .invite-mascot__tablet-line--2{top:8px;right:7px;}
                    .invite-mascot__tablet-line--3{top:12px;right:9px;}
                    .invite-mascot__bubble{right:-9px;top:-4px;display:flex;align-items:center;justify-content:center;min-width:20px;height:20px;padding:0 5px;border-radius:999px;background:linear-gradient(180deg,#ffffff,#eef6ff);border:2px solid rgba(96,165,250,.36);box-shadow:0 10px 16px rgba(96,165,250,.14);transform:translate3d(3px,8px,0) scale(.7);}
                    .invite-mascot__bubble::after{content:"";position:absolute;left:5px;bottom:-5px;width:8px;height:8px;background:inherit;border-left:inherit;border-bottom:inherit;transform:rotate(-35deg);border-radius:0 0 0 4px;}
                    .invite-mascot__bubble-icon{position:relative;z-index:1;color:#2563eb;font-size:11px;font-weight:900;line-height:1;}
                    .invite-mascot--scout{z-index:1;}
                    .invite-mascot--analyst{z-index:2;}
                    .invite-mascot--captain{z-index:3;}
                    .invite-mascot--captain .invite-mascot__mouth{width:14px;height:8px;}
                    .invite-mascot--captain .invite-mascot__cheek{background:rgba(244,114,182,.2);}
                    .invite-mascot--analyst .invite-mascot__mouth{opacity:.62;}
                    .invite-mascot--scout .invite-mascot__mouth{width:10px;}
                    .invite-shell[data-story="hunt"] .invite-mascot--scout{transform:translate3d(0,calc(var(--peek-rise) * -1 - 2px),0) rotate(calc(var(--peek-tilt) - 4deg));}
                    .invite-shell[data-story="hunt"] .invite-mascot--scout .invite-mascot__magnifier{opacity:1;transform:translate3d(0,0,0) rotate(-12deg) scale(1);}
                    .invite-shell[data-story="hunt"] .invite-mascot--analyst{transform:translate3d(0,calc(var(--peek-rise) * -1 + 1px),0) rotate(calc(var(--peek-tilt) - .8deg));}
                    .invite-shell[data-story="hunt"] .invite-mascot--captain .invite-mascot__arm--right{animation:none;transform:rotate(-14deg);}
                    .invite-shell[data-story="decode"] .invite-peek__crew{transform:translateY(-1px);}
                    .invite-shell[data-story="decode"] .invite-mascot--scout{transform:translate3d(0,calc(var(--peek-rise) * -1 - 1px),0) rotate(calc(var(--peek-tilt) - 3deg));}
                    .invite-shell[data-story="decode"] .invite-mascot--scout .invite-mascot__magnifier{opacity:.92;transform:translate3d(-1px,-1px,0) rotate(-18deg) scale(.98);}
                    .invite-shell[data-story="decode"] .invite-mascot--analyst{transform:translate3d(0,calc(var(--peek-rise) * -1 - 3px),0) rotate(calc(var(--peek-tilt) - 1deg));}
                    .invite-shell[data-story="decode"] .invite-mascot--analyst .invite-mascot__tablet{opacity:1;transform:translate3d(-50%%,0,0) rotate(4deg) scale(1);}
                    .invite-shell[data-story="decode"] .invite-mascot--captain .invite-mascot__bubble{opacity:.82;transform:translate3d(1px,1px,0) scale(.88);}
                    .invite-shell[data-story="ready"] .invite-peek__crew{transform:translateY(-2px);}
                    .invite-shell[data-story="ready"] .invite-mascot--scout .invite-mascot__magnifier{opacity:.56;transform:translate3d(-2px,3px,0) rotate(-24deg) scale(.9);}
                    .invite-shell[data-story="ready"] .invite-mascot--analyst .invite-mascot__tablet{opacity:1;transform:translate3d(-50%%,-1px,0) rotate(-2deg) scale(1);}
                    .invite-shell[data-story="ready"] .invite-mascot--captain{transform:translate3d(0,calc(var(--peek-rise) * -1 - 4px),0) rotate(calc(var(--peek-tilt) + 2deg));}
                    .invite-shell[data-story="ready"] .invite-mascot--captain .invite-mascot__bubble{opacity:1;transform:translate3d(0,-3px,0) scale(1);}
                    .invite-shell[data-story="ready"] .invite-mascot--captain .invite-mascot__arm--right{animation:inviteMascotWave .88s ease-in-out infinite;}
                    .invite-shell.is-peeking .invite-peek__track{background:linear-gradient(90deg,rgba(226,232,240,0),#d7e5ff 14%%,#93c5fd 52%%,#60a5fa 100%%);}
                    .invite-shell.is-peeking[data-story="ready"] .invite-peek__track{background:linear-gradient(90deg,rgba(226,232,240,0),#bfdbfe 10%%,#60a5fa 48%%,#38bdf8 100%%);}
                    .invite-shell.is-peeking .invite-mascot{filter:drop-shadow(0 12px 18px rgba(250,204,21,.18));}
                    .invite-input-wrap{position:relative;}
                    input{width:100%%;padding:16px 50px 16px 20px;border:1px solid #d9e4f4;border-radius:20px;background:linear-gradient(180deg,#ffffff,#f8fbff);color:#0f172a;font-size:15px;outline:none;box-shadow:inset 0 1px 0 rgba(255,255,255,.75);transition:border-color .18s ease,box-shadow .18s ease,background-color .18s ease,transform .18s ease;}
                    input::placeholder{color:#94a3b8;}
                    input:focus{background:#ffffff;border-color:#60a5fa;box-shadow:0 0 0 4px rgba(96,165,250,.14),0 16px 28px rgba(148,163,184,.14);transform:translateY(-1px);}
                    .invite-input-icon{position:absolute;top:50%%;right:16px;display:flex;align-items:center;color:#94a3b8;transform:translateY(-50%%);pointer-events:none;}
                    .invite-help{margin-top:8px;color:#64748b;font-size:13px;line-height:1.8;}
                    .invite-submit{display:flex;align-items:center;justify-content:center;width:100%%;margin-top:12px;padding:16px 18px;border:0;border-radius:22px;background:linear-gradient(180deg,#1c2747 0%%,#0f172a 100%%);color:#ffffff;font-size:15px;font-weight:700;letter-spacing:.02em;cursor:pointer;box-shadow:0 18px 26px rgba(15,23,42,.14);transition:transform .18s ease,opacity .18s ease,box-shadow .18s ease,filter .18s ease;will-change:transform;}
                    .invite-submit:hover{transform:translateY(-1px);box-shadow:0 24px 34px rgba(15,23,42,.20);filter:brightness(1.03);}
                    .invite-submit:focus-visible{box-shadow:0 0 0 4px rgba(96,165,250,.18),0 22px 32px rgba(15,23,42,.18);}
                    .invite-submit:disabled{cursor:not-allowed;opacity:.74;}
                    .invite-footer{width:100%%;margin-top:24px;text-align:center;}
                    .invite-footer__actions{display:flex;flex-direction:column;align-items:center;gap:16px;}
                    .back-link{color:#94a3b8;font-size:13px;text-decoration:none;transition:color .18s ease;}
                    .back-link:hover{color:#475569;}
                    .invite-contact-entry{display:flex;align-items:center;justify-content:center;gap:10px;flex-wrap:wrap;margin-top:22px;color:#64748b;font-size:13px;line-height:1.6;}
                    .invite-contact-entry__hint{font-weight:600;}
                    .invite-contact-link{display:inline-flex;align-items:center;gap:8px;max-width:100%%;padding:9px 14px;border:1px solid rgba(148,163,184,.28);border-radius:999px;background:rgba(255,255,255,.82);color:#334155;font-size:13px;font-weight:700;cursor:pointer;box-shadow:0 10px 18px rgba(15,23,42,.05);transition:transform .18s ease,border-color .18s ease,background-color .18s ease,box-shadow .18s ease,color .18s ease;}
                    .invite-contact-link:hover{transform:translateY(-1px);border-color:rgba(59,130,246,.26);background:#ffffff;color:#0f172a;box-shadow:0 12px 22px rgba(15,23,42,.08);}
                    .invite-contact-link__icon{display:flex;align-items:center;justify-content:center;color:#2563eb;flex-shrink:0;}
                    .invite-contact-link__text{white-space:nowrap;}
                    .invite-contact-link__arrow{display:flex;align-items:center;justify-content:center;color:#94a3b8;transition:transform .18s ease,color .18s ease;}
                    .invite-contact-link:hover .invite-contact-link__arrow{transform:translateX(1px);color:#2563eb;}
                    .invite-contact-dialog[hidden]{display:none;}
                    .invite-contact-dialog{position:fixed;inset:0;z-index:20;display:flex;align-items:center;justify-content:center;padding:20px;}
                    .invite-contact-dialog__backdrop{position:absolute;inset:0;background:rgba(15,23,42,.42);}
                    .invite-contact-dialog__card{position:relative;width:min(100%%,360px);padding:24px;border-radius:22px;background:#ffffff;box-shadow:0 24px 60px rgba(15,23,42,.18);}
                    .invite-contact-dialog__close{position:absolute;top:14px;right:14px;display:flex;align-items:center;justify-content:center;width:32px;height:32px;border:0;border-radius:999px;background:#f8fafc;color:#475569;font-size:20px;cursor:pointer;}
                    .invite-contact-dialog__title{margin:0 0 16px;color:#0f172a;font-size:20px;font-weight:700;text-align:left;}
                    .invite-contact-dialog__image{display:block;width:100%%;max-height:260px;object-fit:contain;border-radius:16px;background:#f8fafc;}
                    .invite-contact-dialog__text{margin-top:16px;color:#334155;font-size:14px;line-height:1.8;text-align:left;word-break:break-word;}
                    @keyframes inviteMascotFloat{0%%,100%%{transform:translate3d(0,0,0) rotate(-1deg);}50%%{transform:translate3d(0,-4px,0) rotate(1.5deg);}}
                    @keyframes inviteMascotShadow{0%%,100%%{transform:translateX(-50%%) scaleX(1);opacity:.18;}50%%{transform:translateX(-50%%) scaleX(.9);opacity:.12;}}
                    @keyframes inviteMascotWave{0%%,100%%{transform:rotate(-18deg);}35%%{transform:rotate(-42deg);}60%%{transform:rotate(-12deg);}}
                    @keyframes inviteMascotBlink{0%%,42%%,100%%{height:0;}44%%,46%%{height:100%%;}45%%{height:78%%;}}
                    @media (max-width:480px){
                        body{padding:28px 14px;}
                        .invite-shell{width:min(100%%,400px);}
                        .invite-card{padding:32px 20px 28px;border-radius:26px;}
                        .invite-brand{margin-bottom:20px;}
                        .invite-brand__logo{max-width:92%%;max-height:min(var(--invite-brand-logo-height,88px),88px);}
                        .invite-brand--with-text{gap:12px;}
                        .invite-brand__fallback{width:64px;height:64px;border-radius:20px;font-size:28px;}
                        .invite-brand__name{max-width:min(100%%,240px);font-size:24px;}
                        .invite-field__top{flex-direction:column;align-items:flex-start;gap:10px;}
                        .invite-peek{width:100%%;min-width:0;height:50px;}
                        .invite-peek__crew{margin-left:auto;}
                        .invite-contact-entry{gap:8px;}
                        .invite-contact-link{padding:8px 12px;font-size:12px;}
                    }
                    @media (prefers-reduced-motion:reduce){input,.invite-submit,.back-link,.invite-contact-link,.invite-contact-link__arrow,.invite-mascot,.invite-mascot__inner,.invite-mascot__shadow,.invite-mascot__eye::before,.invite-mascot__arm--right{transition:none;animation:none;}}
                </style>
            </head>
            <body>
                <main class="invite-shell"%s>
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
                            %s
                        </form>
                    </section>
                    <div class="invite-footer">
                        <div class="invite-footer__actions">
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
                        let targetPupilX = 0;
                        let targetPupilY = 0;
                        let targetRise = 0;
                        let targetTilt = 0;
                        let currentPupilX = 0;
                        let currentPupilY = 0;
                        let currentRise = 0;
                        let currentTilt = 0;
                        let pointerRatioX = null;
                        let pointerRatioY = null;

                        const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
                        const mix = (from, to, amount) => from + (to - from) * amount;

                        const updatePeekTarget = () => {
                            const hasFocus = document.activeElement === inviteInput;
                            const valueLength = inviteInput.value.trim().length;
                            const storyStage = valueLength >= 8
                                ? "ready"
                                : valueLength >= 4
                                    ? "decode"
                                    : valueLength > 0
                                        ? "hunt"
                                        : "idle";
                            const basePupilX = hasFocus || valueLength > 0
                                ? clamp(valueLength * 0.18 - 0.25, -0.8, 1.05)
                                : 0;
                            const basePupilY = hasFocus || valueLength > 0
                                ? clamp(0.12 - valueLength * 0.025, -0.22, 0.18)
                                : 0;
                            const pointerPupilX = pointerRatioX === null
                                ? 0
                                : clamp((pointerRatioX - 0.5) * 1.7, -0.72, 0.72);
                            const pointerPupilY = pointerRatioY === null
                                ? 0
                                : clamp((pointerRatioY - 0.5) * 0.7, -0.22, 0.24);
                            targetPupilX = clamp(basePupilX + pointerPupilX, -1.18, 1.18);
                            targetPupilY = clamp(basePupilY + pointerPupilY, -0.34, 0.32);
                            const storyRiseBoost = storyStage === "ready" ? 2.2 : storyStage === "decode" ? 1.2 : storyStage === "hunt" ? .5 : 0;
                            const storyTiltBoost = storyStage === "ready" ? 1.1 : storyStage === "decode" ? .4 : 0;
                            targetRise = hasFocus || valueLength > 0
                                ? Math.min(9.8, 4 + valueLength * 0.3 + storyRiseBoost)
                                : 0;
                            targetTilt = hasFocus || valueLength > 0
                                ? clamp(valueLength * 0.24 - 0.35 + storyTiltBoost, -1.4, 3.6)
                                : 0;
                            shell.classList.toggle("is-peeking", hasFocus || valueLength > 0);
                            shell.dataset.story = storyStage;
                        };

                        const animatePeekState = () => {
                            currentPupilX = mix(currentPupilX, targetPupilX, 0.18);
                            currentPupilY = mix(currentPupilY, targetPupilY, 0.18);
                            currentRise = mix(currentRise, targetRise, 0.14);
                            currentTilt = mix(currentTilt, targetTilt, 0.14);
                            shell.style.setProperty("--peek-pupil-x", currentPupilX.toFixed(2) + "px");
                            shell.style.setProperty("--peek-pupil-y", currentPupilY.toFixed(2) + "px");
                            shell.style.setProperty("--peek-rise", currentRise.toFixed(2) + "px");
                            shell.style.setProperty("--peek-tilt", currentTilt.toFixed(2) + "deg");
                            window.requestAnimationFrame(animatePeekState);
                        };

                        ["focus", "blur", "input", "change"].forEach((eventName) => {
                            inviteInput.addEventListener(eventName, updatePeekTarget);
                        });
                        inviteInput.addEventListener("pointermove", (event) => {
                            const rect = inviteInput.getBoundingClientRect();
                            pointerRatioX = clamp((event.clientX - rect.left) / rect.width, 0, 1);
                            pointerRatioY = clamp((event.clientY - rect.top) / rect.height, 0, 1);
                            updatePeekTarget();
                        });
                        inviteInput.addEventListener("pointerleave", () => {
                            pointerRatioX = null;
                            pointerRatioY = null;
                            updatePeekTarget();
                        });
                        updatePeekTarget();
                        animatePeekState();
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
            shellInlineStyle,
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

    private String buildShellInlineStyle(InviteRegistrationSettings settings) {
        if (!"custom".equals(settings.getBrandSourceSafely())) {
            return "";
        }
        int brandLogoScale = settings.getBrandLogoScaleSafely();
        int logoWidth = Math.max(102, Math.min(414, Math.round(256f * brandLogoScale / 100f)));
        int logoHeight = Math.max(32, Math.min(160, Math.round(120f * brandLogoScale / 100f)));
        return (" style=\"--invite-brand-logo-width:%dpx;--invite-brand-logo-height:%dpx;\"")
            .formatted(logoWidth, logoHeight);
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
            buildPeekMascot("invite-mascot invite-mascot--scout", "0s", "scout"),
            buildPeekMascot("invite-mascot invite-mascot--2 invite-mascot--analyst", ".25s", "analyst"),
            buildPeekMascot("invite-mascot invite-mascot--3 invite-mascot--captain", ".5s", "captain")
        );
    }

    private String buildPeekMascot(String className, String delay, String role) {
        String accessory = switch (role) {
            case "scout" -> "<span class=\"invite-mascot__prop invite-mascot__magnifier\"></span>";
            case "analyst" -> """
                <span class="invite-mascot__prop invite-mascot__tablet">
                    <span class="invite-mascot__tablet-line invite-mascot__tablet-line--1"></span>
                    <span class="invite-mascot__tablet-line invite-mascot__tablet-line--2"></span>
                    <span class="invite-mascot__tablet-line invite-mascot__tablet-line--3"></span>
                </span>
                """;
            case "captain" -> """
                <span class="invite-mascot__prop invite-mascot__bubble">
                    <span class="invite-mascot__bubble-icon">&#10003;</span>
                </span>
                """;
            default -> "";
        };
        return """
            <span class="%s" style="--delay:%s;">
                <span class="invite-mascot__shadow"></span>
                <span class="invite-mascot__inner">
                    <span class="invite-mascot__tuft invite-mascot__tuft--1"></span>
                    <span class="invite-mascot__tuft invite-mascot__tuft--2"></span>
                    <span class="invite-mascot__tuft invite-mascot__tuft--3"></span>
                    <span class="invite-mascot__arm invite-mascot__arm--left"></span>
                    <span class="invite-mascot__arm invite-mascot__arm--right"></span>
                    <span class="invite-mascot__body"></span>
                    <span class="invite-mascot__strap"></span>
                    <span class="invite-mascot__pocket"></span>
                    <span class="invite-mascot__eye-band"></span>
                    <span class="invite-mascot__shine"></span>
                    <span class="invite-mascot__eye invite-mascot__eye--left"></span>
                    <span class="invite-mascot__eye invite-mascot__eye--right"></span>
                    <span class="invite-mascot__cheek invite-mascot__cheek--left"></span>
                    <span class="invite-mascot__cheek invite-mascot__cheek--right"></span>
                    <span class="invite-mascot__mouth"></span>
                    <span class="invite-mascot__leg invite-mascot__leg--left"></span>
                    <span class="invite-mascot__leg invite-mascot__leg--right"></span>
                    <span class="invite-mascot__shoe invite-mascot__shoe--left"></span>
                    <span class="invite-mascot__shoe invite-mascot__shoe--right"></span>
                    %s
                </span>
            </span>
            """.formatted(className, delay, accessory);
    }

    private String buildContactTrigger(InviteRegistrationSettings settings) {
        if (!settings.hasContactConfig()) {
            return "";
        }
        return """
            <div class="invite-contact-entry">
                <span class="invite-contact-entry__hint">没有邀请码？</span>
                <button id="contactAdminToggle" class="invite-contact-link" type="button" aria-expanded="false">
                    <span class="invite-contact-link__icon" aria-hidden="true">
                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                        </svg>
                    </span>
                    <span class="invite-contact-link__text">联系站长获取邀请码</span>
                    <span class="invite-contact-link__arrow" aria-hidden="true">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M5 12h14M13 6l6 6-6 6"/>
                        </svg>
                    </span>
                </button>
            </div>
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
