package com.smartark.gateway.agent.step;

/**
 * Shared file-to-group classification logic used by both RequirementAnalyzeStep and AbstractCodegenStep.
 */
public final class FileGroupDetector {

    private FileGroupDetector() {}

    public static String detect(String path) {
        String p = path == null ? "" : path.toLowerCase().replace("\\", "/");

        // Prefix-based: unambiguous directory matches first
        if (p.contains("backend/")) return "backend";
        if (p.contains("frontend/")) return "frontend";
        if (p.contains("frontend-mobile/")) return "frontend";
        if (p.contains("frontend-web/")) return "frontend";
        if (p.startsWith("app/") || p.startsWith("components/") || p.startsWith("lib/")) return "frontend";

        // Database group
        if (p.endsWith(".sql") || p.contains("/db/") || p.startsWith("database/") || p.startsWith("prisma/")) return "database";

        // Docs group
        if (p.startsWith("docs/") || p.equals("readme.md")) return "docs";

        // Infra group: scripts, docker, config files at root level
        if (p.startsWith("scripts/") || p.endsWith(".sh") || p.endsWith(".bat")) return "infra";
        if (p.startsWith("docker-compose") || p.equals("dockerfile") || p.startsWith(".docker")) return "infra";

        // Root-level config/meta files → infra (not backend)
        if (!p.contains("/")) {
            if (p.endsWith(".yml") || p.endsWith(".yaml") || p.equals(".gitignore") || p.equals(".env") || p.startsWith(".env")) {
                return "infra";
            }
            if (p.equals("package.json") || p.startsWith("tsconfig") || p.startsWith("next.config") || p.equals("next-env.d.ts")) {
                return "frontend";
            }
        }

        return "backend";
    }
}
