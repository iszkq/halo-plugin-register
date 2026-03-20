package run.halo.inviteregister;

import static run.halo.app.extension.index.IndexAttributeFactory.simpleAttribute;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpec;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.inviteregister.extension.InviteCode;

@Component
public class InviteRegisterPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public InviteRegisterPlugin(PluginContext context, SchemeManager schemeManager) {
        super(context);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(InviteCode.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("spec.normalizedCode")
                .setUnique(true)
                .setIndexFunc(simpleAttribute(
                    InviteCode.class,
                    inviteCode -> inviteCode.getSpec() == null ? null
                        : inviteCode.getSpec().getNormalizedCode()
                )));
        });
    }

    @Override
    public void stop() {
        try {
            Scheme scheme = schemeManager.get(InviteCode.class);
            if (scheme != null) {
                schemeManager.unregister(scheme);
            }
        } catch (Exception ignored) {
        }
    }
}
