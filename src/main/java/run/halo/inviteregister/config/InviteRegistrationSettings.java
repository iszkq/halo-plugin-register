package run.halo.inviteregister.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Data;

@Data
public class InviteRegistrationSettings {

    private static final List<String> DEFAULT_REGISTRATION_PAGE_PATTERNS = List.of(
        "/register",
        "/uc/register",
        "/signup"
    );

    private static final List<String> DEFAULT_REGISTRATION_API_PATTERNS = List.of(
        "/signup",
        "/apis/uc.api.registration.halo.run/v1alpha1/register",
        "/apis/uc.api.halo.run/v1alpha1/register",
        "/apis/api.uc.halo.run/v1alpha1/register"
    );

    private Boolean enabled = true;

    private String inviteFieldName = "inviteCode";

    private String inputLabel = "邀请码";

    private String inputPlaceholder = "请输入邀请码";

    private String inputHelpText = "注册前请先向站长申请邀请码。";

    private String invalidMessage = "邀请码无效、已过期或已被用尽。";

    private String registrationPagePatterns = "/register\n/uc/register\n/signup";

    private String registrationApiPatterns =
        "/signup\n"
            + "/apis/uc.api.registration.halo.run/v1alpha1/register\n"
            + "/apis/uc.api.halo.run/v1alpha1/register\n"
            + "/apis/api.uc.halo.run/v1alpha1/register";

    public static InviteRegistrationSettings defaults() {
        return new InviteRegistrationSettings();
    }

    public boolean isEnabledSafely() {
        return Boolean.TRUE.equals(enabled);
    }

    public List<String> getRegistrationPagePatternList() {
        return mergeWithDefaults(registrationPagePatterns, DEFAULT_REGISTRATION_PAGE_PATTERNS);
    }

    public List<String> getRegistrationApiPatternList() {
        return mergeWithDefaults(registrationApiPatterns, DEFAULT_REGISTRATION_API_PATTERNS);
    }

    public String getInviteFieldNameSafely() {
        if (inviteFieldName == null || inviteFieldName.isBlank()) {
            return "inviteCode";
        }
        return inviteFieldName.trim();
    }

    public String getInvalidMessageSafely() {
        if (invalidMessage == null || invalidMessage.isBlank()) {
            return "邀请码无效、已过期或已被用尽。";
        }
        return invalidMessage.trim();
    }

    private List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\r?\\n"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    }

    private List<String> mergeWithDefaults(String value, List<String> defaults) {
        LinkedHashSet<String> patterns = new LinkedHashSet<>(defaults);
        patterns.addAll(splitLines(value));
        return List.copyOf(patterns);
    }
}
