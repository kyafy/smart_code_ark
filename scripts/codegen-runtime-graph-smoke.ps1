param(
    [string]$BaseUrl = "http://localhost:18080"
)

$ErrorActionPreference = "Stop"
$healthEndpoint = "$BaseUrl/v1/health"
$endpoint = "$BaseUrl/v1/graph/codegen/run"

try {
    $health = Invoke-RestMethod -Method Get -Uri $healthEndpoint -TimeoutSec 5
    if (-not $health -or $health.status -ne "ok") {
        throw "Unexpected health response"
    }
} catch {
    throw "langchain-runtime is not reachable at $BaseUrl. Start it first, then rerun this script. (Example: docker compose up -d langchain-runtime)"
}

function Invoke-CodegenStage {
    param(
        [string]$Stage,
        [hashtable]$PayloadInput
    )

    $payload = @{
        taskId    = "smoke-codegen-$Stage"
        projectId = "smoke-project"
        userId    = "smoke-user"
        input     = $PayloadInput
    }
    $json = $payload | ConvertTo-Json -Depth 40
    $response = Invoke-RestMethod -Method Post -Uri $endpoint -ContentType "application/json" -Body $json
    if (-not $response) { throw "[$Stage] Empty response from codegen graph." }
    if ($response.status -ne "completed") { throw "[$Stage] Unexpected status=$($response.status)" }
    if (-not $response.result) { throw "[$Stage] Missing result payload." }
    return $response
}

function New-StageInput {
    param(
        [string]$Stage,
        [hashtable]$Extra
    )
    $merged = @{}
    foreach ($item in $baseInput.GetEnumerator()) {
        $null = $merged[$item.Key] = $item.Value
    }
    $null = $merged["stage"] = $Stage
    if ($Extra) {
        foreach ($item in $Extra.GetEnumerator()) {
            $null = $merged[$item.Key] = $item.Value
        }
    }
    return $merged
}

$baseInput = @{
    prd           = "Build a web app with user management and order workflow."
    projectType   = "web"
    stackBackend  = "springboot"
    stackFrontend = "vue3"
    stackDb       = "mysql"
    instructions  = "Generate production-ready outputs."
}

$responseRequirement = Invoke-CodegenStage -Stage "requirement_analyze" -PayloadInput (New-StageInput -Stage "requirement_analyze")
if (-not $responseRequirement.result.structure_json) { throw "[requirement_analyze] Missing structure_json in result." }
if (-not ($responseRequirement.result.structure_json.files -is [System.Array])) { throw "[requirement_analyze] structure_json.files should be an array." }
if ($responseRequirement.result.structure_json.files.Count -lt 5) { throw "[requirement_analyze] structure_json.files count is too small." }
Write-Host "[PASS] requirement_analyze stage via /v1/graph/codegen/run"
Write-Host "Suggested files count: $($responseRequirement.result.structure_json.files.Count)"

$responseArtifactValidate = Invoke-CodegenStage -Stage "artifact_contract_validate" -PayloadInput (New-StageInput -Stage "artifact_contract_validate" -Extra @{
    requiredFiles = @("README.md", "docker-compose.yml")
    maxFileSizeBytes = 1048576
    workspaceFiles = @(
        @{ path = "docker-compose.yml"; isDirectory = $false; size = 120 },
        @{ path = "backend"; isDirectory = $true; size = 0 },
        @{ path = "backend/Dockerfile"; isDirectory = $false; size = 80 }
    )
    dockerComposeContent = "services:`n  backend:`n    build:`n      context: ./backend`n"
})
if (-not ($responseArtifactValidate.result.violations -is [System.Array])) { throw "[artifact_contract_validate] violations should be an array." }
if (-not ($responseArtifactValidate.result.fatal_violations -is [System.Array])) { throw "[artifact_contract_validate] fatal_violations should be an array." }
Write-Host "[PASS] artifact_contract_validate stage via /v1/graph/codegen/run"

$responseBackend = Invoke-CodegenStage -Stage "codegen_backend" -PayloadInput (New-StageInput -Stage "codegen_backend" -Extra @{
    targetFiles = @(
        "backend/src/main/java/com/example/Application.java",
        "backend/src/main/resources/application.yml"
    )
})
if (-not ($responseBackend.result.codegen_files -is [System.Array])) { throw "[codegen_backend] codegen_files should be an array." }
if ($responseBackend.result.codegen_files.Count -lt 1) { throw "[codegen_backend] codegen_files should not be empty." }
Write-Host "[PASS] codegen_backend stage via /v1/graph/codegen/run"

$responseFrontend = Invoke-CodegenStage -Stage "codegen_frontend" -PayloadInput (New-StageInput -Stage "codegen_frontend" -Extra @{
    targetFiles = @(
        "frontend/src/main.ts",
        "frontend/src/App.vue"
    )
})
if (-not ($responseFrontend.result.codegen_files -is [System.Array])) { throw "[codegen_frontend] codegen_files should be an array." }
if ($responseFrontend.result.codegen_files.Count -lt 1) { throw "[codegen_frontend] codegen_files should not be empty." }
Write-Host "[PASS] codegen_frontend stage via /v1/graph/codegen/run"

$responseSql = Invoke-CodegenStage -Stage "sql_generate" -PayloadInput (New-StageInput -Stage "sql_generate" -Extra @{
    targetFiles = @(
        "database/schema.sql",
        "docs/deploy.md",
        "scripts/start.sh"
    )
})
if (-not ($responseSql.result.codegen_files -is [System.Array])) { throw "[sql_generate] codegen_files should be an array." }
if ($responseSql.result.codegen_files.Count -lt 1) { throw "[sql_generate] codegen_files should not be empty." }
Write-Host "[PASS] sql_generate stage via /v1/graph/codegen/run"

$responseBatchAutoFix = Invoke-CodegenStage -Stage "build_verify_batch_autofix" -PayloadInput (New-StageInput -Stage "build_verify_batch_autofix" -Extra @{
    files = @(
        @{
            filePath = "backend/src/main/java/com/example/BrokenA.java"
            currentContent = "public clas BrokenA { }"
            buildLog = "[ERROR] /workspace/backend/src/main/java/com/example/BrokenA.java:[1,8] class, interface, enum, or record expected"
        },
        @{
            filePath = "frontend/src/broken.ts"
            currentContent = "export const value = ;"
            buildLog = "frontend/src/broken.ts(1,22): error TS1109: Expression expected"
        }
    )
    techStack = "Backend: springboot, Frontend: vue3, Database: mysql"
})
if (-not ($responseBatchAutoFix.result.fixed_files -is [System.Array])) { throw "[build_verify_batch_autofix] fixed_files should be an array." }
if ($responseBatchAutoFix.result.fixed_files.Count -lt 1) { throw "[build_verify_batch_autofix] fixed_files should not be empty." }
Write-Host "[PASS] build_verify_batch_autofix stage via /v1/graph/codegen/run"

$responseAutoFix = Invoke-CodegenStage -Stage "build_verify_autofix" -PayloadInput (New-StageInput -Stage "build_verify_autofix" -Extra @{
    filePath = "backend/src/main/java/com/example/Broken.java"
    currentContent = "public clas Broken { }"
    buildLog = "[ERROR] /workspace/backend/src/main/java/com/example/Broken.java:[1,8] class, interface, enum, or record expected"
    techStack = "Backend: springboot, Frontend: vue3, Database: mysql"
})
if (-not $responseAutoFix.result.fixed_content) { throw "[build_verify_autofix] fixed_content should not be empty." }
Write-Host "[PASS] build_verify_autofix stage via /v1/graph/codegen/run"
