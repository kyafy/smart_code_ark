#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const scriptDir = path.dirname(__filename);
const repoRootDefault = path.resolve(scriptDir, "..");
const workspaceRootDefault = path.resolve(repoRootDefault, "..");
const sourceRootDefault = path.join(
  workspaceRootDefault,
  "JeecgBoot",
  "jeecg-boot",
  "jeecg-module-system",
  "jeecg-system-biz",
  "src",
  "main",
  "resources",
  "jeecg",
  "code-template-online"
);

function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      continue;
    }
    if (token.includes("=")) {
      const [key, value] = token.slice(2).split("=", 2);
      result[key] = value;
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith("--")) {
      result[key] = "true";
      continue;
    }
    result[key] = next;
    i += 1;
  }
  return result;
}

function toBoolean(value, defaultValue = false) {
  if (value === undefined || value === null) {
    return defaultValue;
  }
  const normalized = String(value).trim().toLowerCase();
  if (["1", "true", "yes", "y", "on"].includes(normalized)) {
    return true;
  }
  if (["0", "false", "no", "n", "off"].includes(normalized)) {
    return false;
  }
  return defaultValue;
}

function splitCsv(value) {
  if (!value) {
    return [];
  }
  return String(value)
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function writeJson(filePath, payload) {
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function safeTemplateKey(prefix, profileId) {
  const raw = `${prefix}${profileId}`.toLowerCase();
  return raw
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function toPosix(relativePath) {
  return relativePath.split(path.sep).join("/");
}

function ensureDirectory(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function copyDirectory(sourceDir, targetDir) {
  ensureDirectory(path.dirname(targetDir));
  fs.cpSync(sourceDir, targetDir, {
    recursive: true,
    force: true,
  });
}

function printHelp() {
  console.log(`Usage:
  node template-repo/scripts/import-jeecg-templates.mjs [options]

Options:
  --apply                          Apply import changes (default is dry-run)
  --overwrite <true|false>         Overwrite existing imported template keys (default: false)
  --source <path>                  Jeecg code-template-online source root
  --repo-root <path>               template-repo root path
  --map <path>                     Import profile mapping JSON
  --prefix <value>                 Imported template key prefix (default: jeecg-)
  --include <id1,id2>              Import only listed profile ids
  --exclude <id1,id2>              Exclude listed profile ids
  --help                           Show help

Examples:
  node template-repo/scripts/import-jeecg-templates.mjs
  node template-repo/scripts/import-jeecg-templates.mjs --apply --overwrite=false
  node template-repo/scripts/import-jeecg-templates.mjs --apply --include=default-one,jvxe-onetomany
`);
}

function createTemplateManifest(profile, templateKey, sourceDir, sourceRoot) {
  return {
    key: templateKey,
    name: profile.name || `Jeecg Imported ${profile.id}`,
    stack: {
      backend: "jeecg-online",
      frontend: "jeecg-online",
      db: "mysql",
    },
    run: {
      importMode: "jeecg-template-importer",
      renderHint: "Use internal_service provider with Jeecg-compatible params",
    },
    exampleFiles: {},
    source: {
      type: "jeecg",
      profileId: profile.id,
      sourceRelativePath: toPosix(path.relative(sourceRoot, sourceDir)),
      importedAt: new Date().toISOString(),
      importerVersion: "0.1.0",
    },
  };
}

function createTemplateReadme(profile, templateKey) {
  return `# ${profile.name || templateKey}

This template is imported from Jeecg online template library by \`import-jeecg-templates.mjs\`.

- templateKey: \`${templateKey}\`
- profileId: \`${profile.id}\`
- source: \`${profile.source}\`
- category: \`${profile.category || "jeecg-online"}\`

Notes:
- Imported resources are stored under \`upstream/\`.
- This is a scaffold for template consolidation and can be customized for Smart Code Ark local rendering rules.
`;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (toBoolean(args.help, false)) {
    printHelp();
    process.exit(0);
  }

  const repoRoot = path.resolve(args["repo-root"] || repoRootDefault);
  const sourceRoot = path.resolve(args.source || sourceRootDefault);
  const mapPath = path.resolve(args.map || path.join(scriptDir, "jeecg-import-map.json"));
  const catalogPath = path.join(repoRoot, "catalog.json");
  const templatesRoot = path.join(repoRoot, "templates");
  const reportPath = path.join(scriptDir, "jeecg-import-report.json");
  const prefix = (args.prefix || "jeecg-").trim();
  const applyChanges = toBoolean(args.apply, false);
  const overwrite = toBoolean(args.overwrite, false);
  const includeSet = new Set(splitCsv(args.include));
  const excludeSet = new Set(splitCsv(args.exclude));

  if (!fs.existsSync(mapPath)) {
    throw new Error(`mapping file not found: ${mapPath}`);
  }
  if (!fs.existsSync(catalogPath)) {
    throw new Error(`catalog file not found: ${catalogPath}`);
  }
  if (!fs.existsSync(sourceRoot)) {
    throw new Error(`Jeecg source root not found: ${sourceRoot}`);
  }
  ensureDirectory(templatesRoot);

  const mapRoot = readJson(mapPath);
  const profiles = Array.isArray(mapRoot.profiles) ? mapRoot.profiles : [];
  const selectedProfiles = profiles.filter((profile) => {
    const id = String(profile.id || "").trim();
    if (!id) {
      return false;
    }
    if (includeSet.size > 0 && !includeSet.has(id)) {
      return false;
    }
    if (excludeSet.has(id)) {
      return false;
    }
    return true;
  });

  const catalog = readJson(catalogPath);
  const catalogTemplates = Array.isArray(catalog.templates) ? [...catalog.templates] : [];
  const catalogIndex = new Map();
  for (let i = 0; i < catalogTemplates.length; i += 1) {
    const key = String(catalogTemplates[i]?.key || "").trim();
    if (key) {
      catalogIndex.set(key, i);
    }
  }

  const report = {
    dryRun: !applyChanges,
    overwrite,
    sourceRoot,
    repoRoot,
    processedAt: new Date().toISOString(),
    selectedProfileCount: selectedProfiles.length,
    imported: [],
    skipped: [],
    conflicts: [],
    missingSource: [],
  };

  const commonSourceDir = path.join(sourceRoot, "common");
  const commonExists = fs.existsSync(commonSourceDir) && fs.statSync(commonSourceDir).isDirectory();

  for (const profile of selectedProfiles) {
    const profileId = String(profile.id || "").trim();
    const sourceRel = String(profile.source || "").trim();
    if (!profileId || !sourceRel) {
      report.skipped.push({
        profileId,
        reason: "invalid profile mapping (id/source required)",
      });
      continue;
    }

    const templateKey = safeTemplateKey(prefix, profileId);
    const sourceDir = path.join(sourceRoot, ...sourceRel.split("/"));
    const templateDir = path.join(templatesRoot, templateKey);
    const upstreamDir = path.join(templateDir, "upstream");
    const profileTargetDir = path.join(upstreamDir, ...sourceRel.split("/"));

    if (!(fs.existsSync(sourceDir) && fs.statSync(sourceDir).isDirectory())) {
      report.missingSource.push({
        profileId,
        source: sourceDir,
      });
      continue;
    }

    const catalogEntry = {
      key: templateKey,
      name: profile.name || `Jeecg Imported ${profileId}`,
      category: profile.category || "jeecg-online",
      description: profile.description || `Imported from Jeecg profile ${profileId}`,
      paths: profile.paths && typeof profile.paths === "object" ? profile.paths : { app: "upstream" },
    };

    const existed = catalogIndex.has(templateKey);
    if (existed && !overwrite) {
      report.conflicts.push({
        profileId,
        templateKey,
        reason: "catalog key already exists",
      });
      continue;
    }

    const manifest = createTemplateManifest(profile, templateKey, sourceDir, sourceRoot);
    const readme = createTemplateReadme(profile, templateKey);

    if (applyChanges) {
      if (fs.existsSync(templateDir) && overwrite) {
        fs.rmSync(templateDir, { recursive: true, force: true });
      }
      ensureDirectory(templateDir);
      ensureDirectory(upstreamDir);
      if (commonExists) {
        copyDirectory(commonSourceDir, path.join(upstreamDir, "common"));
      }
      copyDirectory(sourceDir, profileTargetDir);
      writeJson(path.join(templateDir, "template.json"), manifest);
      fs.writeFileSync(path.join(templateDir, "README.md"), readme, "utf8");

      if (existed) {
        const index = catalogIndex.get(templateKey);
        catalogTemplates[index] = catalogEntry;
      } else {
        catalogTemplates.push(catalogEntry);
        catalogIndex.set(templateKey, catalogTemplates.length - 1);
      }
    }

    report.imported.push({
      profileId,
      templateKey,
      source: sourceDir,
      target: templateDir,
      mode: applyChanges ? "applied" : "planned",
    });
  }

  if (applyChanges) {
    catalog.templates = catalogTemplates;
    writeJson(catalogPath, catalog);
  }

  writeJson(reportPath, report);

  console.log(`[jeecg-import] mode=${applyChanges ? "apply" : "dry-run"}`);
  console.log(`[jeecg-import] selectedProfiles=${report.selectedProfileCount}`);
  console.log(`[jeecg-import] imported=${report.imported.length}`);
  console.log(`[jeecg-import] conflicts=${report.conflicts.length}`);
  console.log(`[jeecg-import] missingSource=${report.missingSource.length}`);
  console.log(`[jeecg-import] report=${reportPath}`);
}

try {
  main();
} catch (error) {
  console.error("[jeecg-import] failed:", error instanceof Error ? error.message : String(error));
  process.exit(1);
}

