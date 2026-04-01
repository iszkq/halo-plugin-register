package run.halo.inviteregister.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.inviteregister.config.InviteRegistrationSettings;

@Service
@RequiredArgsConstructor
public class InviteBrandingService {

    private static final String BRAND_SOURCE_CUSTOM = "custom";
    private static final String DEFAULT_BRAND_NAME = "Halo";

    private final ReactiveExtensionClient client;

    public Mono<InviteBranding> resolve(InviteRegistrationSettings settings) {
        return loadSiteBasicSetting()
            .map(basic -> merge(settings, basic))
            .onErrorResume(ignored -> Mono.empty())
            .defaultIfEmpty(merge(settings, null));
    }

    private Mono<SystemSetting.Basic> loadSiteBasicSetting() {
        return Mono.zip(fetchConfigMap(SystemSetting.SYSTEM_CONFIG_DEFAULT),
                fetchConfigMap(SystemSetting.SYSTEM_CONFIG))
            .map(tuple -> mergeBasicSetting(tuple.getT1(), tuple.getT2()));
    }

    private Mono<ConfigMap> fetchConfigMap(String name) {
        return client.fetch(ConfigMap.class, name)
            .switchIfEmpty(Mono.fromSupplier(() -> {
                ConfigMap configMap = new ConfigMap();
                configMap.setData(Map.of());
                return configMap;
            }));
    }

    private SystemSetting.Basic mergeBasicSetting(ConfigMap defaultConfigMap, ConfigMap overrideConfigMap) {
        SystemSetting.Basic defaultBasic = readBasic(defaultConfigMap);
        SystemSetting.Basic overrideBasic = readBasic(overrideConfigMap);

        SystemSetting.Basic basic = new SystemSetting.Basic();
        basic.setTitle(firstNonBlank(
            trimToNull(overrideBasic.getTitle()),
            trimToNull(defaultBasic.getTitle())
        ));
        basic.setSubtitle(firstNonBlank(
            trimToNull(overrideBasic.getSubtitle()),
            trimToNull(defaultBasic.getSubtitle())
        ));
        basic.setLogo(firstNonBlank(
            trimToNull(overrideBasic.getLogo()),
            trimToNull(defaultBasic.getLogo())
        ));
        basic.setFavicon(firstNonBlank(
            trimToNull(overrideBasic.getFavicon()),
            trimToNull(defaultBasic.getFavicon())
        ));
        basic.setLanguage(firstNonBlank(
            trimToNull(overrideBasic.getLanguage()),
            trimToNull(defaultBasic.getLanguage())
        ));
        basic.setExternalUrl(firstNonBlank(
            trimToNull(overrideBasic.getExternalUrl()),
            trimToNull(defaultBasic.getExternalUrl())
        ));
        return basic;
    }

    private SystemSetting.Basic readBasic(ConfigMap configMap) {
        if (configMap == null || configMap.getData() == null) {
            return new SystemSetting.Basic();
        }
        SystemSetting.Basic basic =
            SystemSetting.get(configMap.getData(), SystemSetting.Basic.GROUP, SystemSetting.Basic.class);
        return basic == null ? new SystemSetting.Basic() : basic;
    }

    private InviteBranding merge(InviteRegistrationSettings settings, SystemSetting.Basic basic) {
        String siteName = basic == null ? null : trimToNull(basic.getTitle());
        String siteLogo = basic == null ? null : trimToNull(basic.getLogo());
        String siteFavicon = basic == null ? null : trimToNull(basic.getFavicon());

        String customName = settings.getBrandNameSafely();
        String customLogo = settings.getBrandLogoSafely();

        if (BRAND_SOURCE_CUSTOM.equals(settings.getBrandSourceSafely())) {
            String name = firstNonBlank(customName, DEFAULT_BRAND_NAME);
            String logo = firstNonBlank(customLogo);
            return new InviteBranding(name, logo, buildMark(name));
        }

        String siteBrandLogo = firstNonBlank(siteLogo, siteFavicon);
        if (siteName != null && siteBrandLogo != null) {
            return new InviteBranding(siteName, siteBrandLogo, buildMark(siteName));
        }

        return new InviteBranding(DEFAULT_BRAND_NAME, null, buildMark(DEFAULT_BRAND_NAME));
    }

    private String buildMark(String brandName) {
        String value = trimToNull(brandName);
        if (value == null) {
            return "H";
        }
        int codePoint = value.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(codePoint)));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record InviteBranding(String name, String logo, String mark) {
    }
}
