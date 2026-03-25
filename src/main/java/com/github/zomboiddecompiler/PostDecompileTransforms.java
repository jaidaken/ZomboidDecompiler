package com.github.zomboiddecompiler;

/**
 * Post-decompilation source transforms. All B41 transforms have been eliminated
 * by fixing root causes in the Vineflower fork. This stub preserves the API
 * for callers that still reference it.
 */
public final class PostDecompileTransforms {

    private PostDecompileTransforms() {}

    public static final String BUILD_41 = "b41";
    public static final String BUILD_42 = "b42";

    public static String apply(String content, String buildVersion) {
        return content;
    }
}
