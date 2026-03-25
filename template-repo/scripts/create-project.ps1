param(
  [Parameter(Mandatory = $true)]
  [string]$Template,

  [Parameter(Mandatory = $true)]
  [string]$TargetPath,

  [Parameter(Mandatory = $false)]
  [string]$ProjectName = "smart-template-app"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoDir = Split-Path -Parent $scriptDir
$templateDir = Join-Path $repoDir "templates\$Template"

if (-not (Test-Path $templateDir)) {
  throw "Template not found: $Template"
}

if (Test-Path $TargetPath) {
  throw "Target path already exists: $TargetPath"
}

New-Item -ItemType Directory -Force -Path $TargetPath | Out-Null
Copy-Item -Path (Join-Path $templateDir "*") -Destination $TargetPath -Recurse -Force

$displayName = ($ProjectName -replace "[-_]+", " ").Trim()
if ([string]::IsNullOrWhiteSpace($displayName)) {
  $displayName = "Smart Template App"
}

$replacements = @{
  "__PROJECT_NAME__" = $ProjectName
  "__DISPLAY_NAME__" = $displayName
}

$textExtensions = @(
  ".md", ".json", ".ts", ".tsx", ".js", ".jsx", ".css", ".scss", ".sass", ".less",
  ".vue", ".html", ".yml", ".yaml", ".properties", ".sql", ".java", ".xml", ".env",
  ".example", ".sh", ".ps1", ".gitignore"
)

Get-ChildItem -Path $TargetPath -Recurse -File | ForEach-Object {
  $ext = $_.Extension.ToLowerInvariant()
  $isTextFile = $textExtensions -contains $ext -or $_.Name -eq ".env.example" -or $_.Name -eq ".gitignore"
  if (-not $isTextFile) {
    return
  }

  $content = Get-Content -Path $_.FullName -Raw
  foreach ($entry in $replacements.GetEnumerator()) {
    $content = $content.Replace($entry.Key, $entry.Value)
  }
  [System.IO.File]::WriteAllText($_.FullName, $content, [System.Text.Encoding]::UTF8)
}

Write-Host "Project created successfully."
Write-Host "Template   : $Template"
Write-Host "TargetPath : $TargetPath"
Write-Host "ProjectName: $ProjectName"
