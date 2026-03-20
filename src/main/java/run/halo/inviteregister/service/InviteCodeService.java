package run.halo.inviteregister.service;

import static run.halo.app.extension.index.query.QueryFactory.equal;

import java.time.OffsetDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.inviteregister.config.InviteRegistrationSettings;
import run.halo.inviteregister.extension.InviteCode;

@Service
@RequiredArgsConstructor
public class InviteCodeService {

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;

    public Mono<InviteRegistrationSettings> getSettings() {
        return settingFetcher.fetch("general", InviteRegistrationSettings.class)
            .onErrorResume(ignored -> Mono.empty())
            .defaultIfEmpty(InviteRegistrationSettings.defaults());
    }

    public Mono<InviteValidationResult> validate(String rawCode) {
        return getSettings()
            .flatMap(settings -> {
                String normalized = normalize(rawCode);
                if (normalized == null) {
                    return Mono.just(InviteValidationResult.invalid(
                        settings.getInvalidMessageSafely()));
                }
                return findByNormalizedCode(normalized)
                    .map(inviteCode -> evaluate(inviteCode, settings))
                    .switchIfEmpty(Mono.just(
                        InviteValidationResult.invalid(settings.getInvalidMessageSafely())
                    ));
            });
    }

    public Mono<Void> consume(String inviteName) {
        if (inviteName == null || inviteName.isBlank()) {
            return Mono.empty();
        }
        return client.get(InviteCode.class, inviteName)
            .flatMap(inviteCode -> {
                InviteCode.Spec spec = inviteCode.getSpec();
                if (spec == null) {
                    return Mono.empty();
                }
                int usedCount = defaultInt(spec.getUsedCount());
                int maxUses = defaultInt(spec.getMaxUses());
                if (maxUses > 0 && usedCount >= maxUses) {
                    return Mono.empty();
                }
                spec.setUsedCount(usedCount + 1);
                inviteCode.setSpec(spec);
                return client.update(inviteCode).then();
            })
            .onErrorResume(ignored -> Mono.empty());
    }

    private Mono<InviteCode> findByNormalizedCode(String normalizedCode) {
        return client.listBy(
                InviteCode.class,
                ListOptions.builder()
                    .fieldQuery(equal("spec.normalizedCode", normalizedCode))
                    .build(),
                PageRequestImpl.ofSize(1)
            )
            .flatMap(listResult -> listResult.getItems().isEmpty()
                ? Mono.empty()
                : Mono.just(listResult.getItems().getFirst()));
    }

    private InviteValidationResult evaluate(InviteCode inviteCode,
        InviteRegistrationSettings settings) {
        InviteCode.Spec spec = inviteCode.getSpec();
        if (spec == null) {
            return InviteValidationResult.invalid(settings.getInvalidMessageSafely());
        }
        if (!Boolean.TRUE.equals(spec.getEnabled())) {
            return InviteValidationResult.invalid(settings.getInvalidMessageSafely());
        }
        OffsetDateTime expireAt = spec.getExpireAt();
        if (expireAt != null && expireAt.isBefore(OffsetDateTime.now())) {
            return InviteValidationResult.invalid(settings.getInvalidMessageSafely());
        }
        int usedCount = defaultInt(spec.getUsedCount());
        int maxUses = defaultInt(spec.getMaxUses());
        if (maxUses > 0 && usedCount >= maxUses) {
            return InviteValidationResult.invalid(settings.getInvalidMessageSafely());
        }
        return InviteValidationResult.valid(
            inviteCode.getMetadata().getName(),
            settings.getInvalidMessageSafely()
        );
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    public String normalize(String rawCode) {
        if (rawCode == null) {
            return null;
        }
        String normalized = rawCode.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public record InviteValidationResult(boolean valid, String name, String message) {
        public static InviteValidationResult invalid(String message) {
            return new InviteValidationResult(false, null, message);
        }

        public static InviteValidationResult valid(String name, String message) {
            return new InviteValidationResult(true, name, message);
        }
    }
}
