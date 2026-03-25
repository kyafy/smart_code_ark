param(
    [string]$BaseUrl = "http://localhost:18080"
)

$ErrorActionPreference = "Stop"
$endpoint = "$BaseUrl/v1/graph/paper/run"
$healthEndpoint = "$BaseUrl/v1/health"

function Assert-RuntimeReady {
    try {
        $health = Invoke-RestMethod -Method Get -Uri $healthEndpoint -TimeoutSec 5
        if (-not $health -or $health.status -ne "ok") {
            throw "Unexpected health response from $healthEndpoint"
        }
    } catch {
        throw "langchain-runtime is not reachable at $BaseUrl. Start it first, then rerun this script. (Example: docker compose up -d langchain-runtime)"
    }
}

function Invoke-PaperStage {
    param(
        [string]$Stage,
        [hashtable]$PayloadInput,
        [scriptblock]$AssertResult
    )

    $payload = @{
        taskId    = "smoke-$Stage"
        projectId = "smoke-project"
        userId    = "smoke-user"
        input     = $PayloadInput
    }

    $json = $payload | ConvertTo-Json -Depth 20
    $response = Invoke-RestMethod -Method Post -Uri $endpoint -ContentType "application/json" -Body $json

    if (-not $response) {
        throw "Stage $Stage failed: empty response"
    }
    if ($response.status -ne "completed") {
        throw "Stage $Stage failed: status=$($response.status)"
    }
    if (-not $response.result) {
        throw "Stage $Stage failed: missing result"
    }

    & $AssertResult $response.result
    Write-Host "[PASS] $Stage"
}

Assert-RuntimeReady

Invoke-PaperStage "topic_clarify" @{
    stage = "topic_clarify"
    topic = "AI assisted software engineering"
    discipline = "software engineering"
    degreeLevel = "master"
    methodPreference = "experiment"
} {
    param($result)
    if (-not $result.topic_clarify_json) { throw "Missing topic_clarify_json" }
    if (-not $result.topic_clarify_json.topicRefined) { throw "Missing topicRefined" }
    if (-not ($result.topic_clarify_json.researchQuestions -is [System.Array])) { throw "researchQuestions should be array" }
}

Invoke-PaperStage "outline_generate" @{
    stage = "outline_generate"
    topic = "AI assisted software engineering"
    topicRefined = "AI assisted software engineering for multi-agent coding"
    researchQuestionsJson = "[`"RQ1`",`"RQ2`"]"
} {
    param($result)
    if (-not $result.outline_json) { throw "Missing outline_json" }
    if (-not ($result.outline_json.chapters -is [System.Array])) { throw "outline_json.chapters should be array" }
}

Invoke-PaperStage "outline_expand" @{
    stage = "outline_expand"
    topic = "AI assisted software engineering"
    topicRefined = "AI assisted software engineering for multi-agent coding"
    researchQuestionsJson = "[`"RQ1`",`"RQ2`"]"
    outline = @{
        chapters = @(
            @{
                title = "Introduction"
                sections = @(
                    @{ title = "Background" }
                )
            }
        )
    }
} {
    param($result)
    if (-not $result.expanded_json) { throw "Missing expanded_json" }
    if (-not ($result.expanded_json.chapters -is [System.Array])) { throw "expanded_json.chapters should be array" }
}

Invoke-PaperStage "outline_quality_check" @{
    stage = "outline_quality_check"
    topic = "AI assisted software engineering"
    topicRefined = "AI assisted software engineering for multi-agent coding"
    citationStyle = "GB/T 7714"
    outline = @{
        chapters = @(
            @{
                title = "Introduction"
                sections = @(
                    @{ title = "Background" }
                )
            }
        )
    }
} {
    param($result)
    if (-not $result.quality_report_json) { throw "Missing quality_report_json" }
    if ($null -eq $result.quality_report_json.overallScore) { throw "Missing overallScore" }
}

Invoke-PaperStage "quality_rewrite" @{
    stage = "quality_rewrite"
    topic = "AI assisted software engineering"
    topicRefined = "AI assisted software engineering for multi-agent coding"
    stableManuscript = @{
        chapters = @(
            @{
                title = "Introduction"
                sections = @(
                    @{
                        title = "Background"
                        content = "Old content"
                        coreArgument = "Old argument"
                        citations = @()
                    }
                )
            }
        )
    }
    qualityReport = @{
        overallScore = 62
        issues = @("Need stronger argument")
    }
} {
    param($result)
    if (-not $result.rewrite_json) { throw "Missing rewrite_json" }
    if (-not $result.rewrite_json.manuscript) { throw "Missing rewritten manuscript" }
}

Write-Host ""
Write-Host "All paper graph smoke checks passed."
