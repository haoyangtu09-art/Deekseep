package com.dsmod.relay;

/** Pure model-resolution rules for the expert image relay. */
public final class ExpertRelayGate {
    private ExpertRelayGate() {}

    /**
     * DeepSeek includes model_type on the first request in a session, but omits it on
     * later requests. An explicit request value always wins over the model captured
     * from the send-point session object.
     */
    public static String resolveModel(Object explicitModel, String capturedModel) {
        return explicitModel instanceof String ? (String) explicitModel : capturedModel;
    }

    public static boolean matches(Object explicitModel, String capturedModel, boolean hasFiles) {
        return hasFiles && "expert".equals(resolveModel(explicitModel, capturedModel));
    }
}
