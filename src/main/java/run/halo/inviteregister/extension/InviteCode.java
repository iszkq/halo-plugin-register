package run.halo.inviteregister.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "invitecode.plugin.halo.local",
    version = "v1alpha1",
    kind = "InviteCode",
    plural = "invitecodes",
    singular = "invitecode"
)
public class InviteCode extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "InviteCodeSpec")
    public static class Spec {

        @Schema(description = "原始邀请码", requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 1, maxLength = 64)
        private String code;

        @Schema(description = "归一化邀请码", requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 1, maxLength = 64)
        private String normalizedCode;

        @Schema(description = "是否启用")
        private Boolean enabled;

        @Schema(description = "最大使用次数，空或小于等于 0 表示不限制")
        private Integer maxUses;

        @Schema(description = "已使用次数")
        private Integer usedCount;

        @Schema(description = "过期时间")
        private OffsetDateTime expireAt;

        @Schema(description = "备注", maxLength = 255)
        private String note;
    }
}
