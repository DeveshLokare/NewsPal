<#
.SYNOPSIS
  Runs each category query from V2__seed_categories.sql against webz.io once and
  writes a summary to target/webz-check.json (no token is ever written).

.DESCRIPTION
  Uses one API call per category (8 calls of the ~1,000/month free quota).
  Reads the token from $env:WEBZ_TOKEN, or prompts for it without echoing.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File backend\scripts\check-webz-queries.ps1
#>
param(
    [string]$BaseUrl = "https://api.webz.io",
    [string]$Endpoint = "/api/news",
    [string]$Only = "",  # optional slug to test a single category
    [int]$Size = 10      # posts per call; compare "cost" across sizes to learn the pricing
)

$ErrorActionPreference = "Stop"
$backend = Split-Path -Parent $PSScriptRoot
$seed = Join-Path $backend "src\main\resources\db\migration\V2__seed_categories.sql"
$outDir = Join-Path $backend "target"
$outFile = Join-Path $outDir "webz-check.json"

$token = $env:WEBZ_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    $secure = Read-Host "webz.io token (input hidden)" -AsSecureString
    $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
if ([string]::IsNullOrWhiteSpace($token)) { throw "No token given." }

$pattern = "\('([a-z]+)',\s*'[^']*',\s*'((?:[^']|'')*)',\s*\d+\)"
$categories = [regex]::Matches((Get-Content $seed -Raw), $pattern) | ForEach-Object {
    [pscustomobject]@{ slug = $_.Groups[1].Value; query = $_.Groups[2].Value -replace "''", "'" }
}
# Apply query updates from later migrations (V3+), in version order.
$updatePattern = "UPDATE categories SET provider_query = '((?:[^']|'')*)' WHERE slug = '([a-z]+)'"
$migrations = Get-ChildItem (Split-Path $seed) -Filter "V*__*.sql" |
    Sort-Object { [int]($_.Name -replace "^V(\d+)__.*", '$1') } |
    Where-Object { [int]($_.Name -replace "^V(\d+)__.*", '$1') -gt 2 }
foreach ($m in $migrations) {
    foreach ($u in [regex]::Matches((Get-Content $m.FullName -Raw), $updatePattern)) {
        $target = $categories | Where-Object { $_.slug -eq $u.Groups[2].Value }
        if ($target) { $target.query = $u.Groups[1].Value -replace "''", "'" }
    }
}
if ($Only) { $categories = $categories | Where-Object { $_.slug -eq $Only } }
if (-not $categories) { throw "No categories found to check." }

$results = @()
$first = $true
foreach ($c in $categories) {
    # webz.io answers HTTP 429 to back-to-back calls.
    if (-not $first) { Start-Sleep -Seconds 2 }
    $first = $false
    # Same parameters as WebzNewsProvider.buildUri (no ts: the default 3-day window).
    $uri = "$BaseUrl$Endpoint" + "?token=" + [uri]::EscapeDataString($token) + "&q=" + [uri]::EscapeDataString($c.query) +
        "&sort=crawled&format=json&size=$Size&includeSyndicated=false"
    $entry = [ordered]@{ slug = $c.slug; query = $c.query }
    try {
        $raw = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 30
        $json = $raw.Content | ConvertFrom-Json
        $posts = @($json.posts)
        $entry.status = [int]$raw.StatusCode
        $entry.topLevelKeys = @($json.PSObject.Properties.Name | Where-Object { $_ -ne "next" }) + $(if ($json.next) { "next(present)" })
        $entry.totalResults = $(if ($null -ne $json.totalResults) { $json.totalResults } else { $json.total_results })
        $entry.balance = $json.balance
        $entry.cost = $json.cost
        $entry.warnings = $json.warnings
        $entry.postCount = $posts.Count
        if ($posts.Count -gt 0) {
            $entry.postKeys = @($posts[0].PSObject.Properties.Name)
            $entry.threadKeys = @($posts[0].thread.PSObject.Properties.Name)
        }
        $entry.samples = @($posts | Select-Object -First 5 | ForEach-Object {
            [ordered]@{
                title = $_.title
                site = $_.thread.site
                domainRank = $_.thread.domain_rank
                published = $_.published
                summaryChars = $(if ($_.summary) { $_.summary.Length } else { 0 })
                hasImage = [bool]$_.thread.main_image
                textChars = $(if ($_.text) { $_.text.Length } else { 0 })
                categories = $_.categories
            }
        })
        Write-Host ("{0,-14} HTTP {1}  posts={2}  total={3}  balance={4}  cost={5}" -f $c.slug, $entry.status, $entry.postCount, $entry.totalResults, $entry.balance, $entry.cost)
    }
    catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $entry.status = $status
        # Keep only the exception type; messages can echo the request URL (and token).
        $entry.error = $_.Exception.GetType().Name
        $body = ""
        if ($_.Exception.Response) {
            try {
                $reader = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
                $body = $reader.ReadToEnd()
            } catch { }
        }
        if ($body) {
            $body = $body.Replace($token, "<token>").Replace([uri]::EscapeDataString($token), "<token>")
            if ($body.Length -gt 500) { $body = $body.Substring(0, 500) }
            $entry.errorBody = $body
        }
        Write-Host ("{0,-14} FAILED  HTTP {1}  {2}  {3}" -f $c.slug, $status, $entry.error, $body) -ForegroundColor Red
    }
    $results += [pscustomobject]$entry
}

New-Item -ItemType Directory -Force $outDir | Out-Null
$results | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 $outFile
Write-Host "Summary written to $outFile"
