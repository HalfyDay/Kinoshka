param(
    [Parameter(Mandatory = $true)]
    [int]$ShikimoriId,

    [Parameter(Mandatory = $false)]
    [string]$AnimeTitle = ""
)

$ErrorActionPreference = "Stop"

$userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"

$checks = New-Object System.Collections.Generic.List[object]

function Decrypt-KodikToken {
    param([string]$Token)

    $half = [int]($Token.Length / 2)
    $first = ($Token.Substring(0, $half)).ToCharArray()
    [array]::Reverse($first)
    $second = ($Token.Substring($half)).ToCharArray()
    [array]::Reverse($second)

    $firstDecoded = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String((-join $first)))
    $secondDecoded = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String((-join $second)))
    return "$secondDecoded$firstDecoded"
}

function Get-KodikTokens {
    try {
        $json = curl.exe -sS -L -A $userAgent "https://raw.githubusercontent.com/YaNesyTortiK/AnimeParsers/main/kdk_tokns/tokens.json"
        if (-not $json) { return @() }
        $root = $json | ConvertFrom-Json
        $tokens = @()
        foreach ($group in @("stable", "unstable")) {
            foreach ($entry in $root.$group) {
                if ($entry.tokn) {
                    $tokens += (Decrypt-KodikToken $entry.tokn)
                }
            }
        }
        return $tokens | Select-Object -Unique
    } catch {
        return @()
    }
}

$kodikTokens = Get-KodikTokens
if (-not $kodikTokens -or $kodikTokens.Count -eq 0) {
    $kodikTokens = @(
        "01c44b54fe97004956a768d08f430919",
        "09d6c71182237a2541dfd1f84c21719b",
        "qwe456asd123zxc789",
        "ed231f2b4b123a123"
    )
}

function Add-Check {
    param(
        [string]$Source,
        [string]$Url,
        [string]$Status,
        [string]$ContentType,
        [string]$Body,
        [string]$ErrorText = ""
    )

    $snippet = if ($Body.Length -gt 500) { $Body.Substring(0, 500) } else { $Body }
    $checks.Add([pscustomobject]@{
        source = $Source
        status = $Status
        contentType = $ContentType
        url = $Url
        error = $ErrorText
        body = $snippet
    }) | Out-Null
}

function Invoke-CheckedRequest {
    param(
        [string]$Source,
        [string]$Url,
        [string]$Referer = ""
    )

    try {
        $bodyFile = [System.IO.Path]::GetTempFileName()
        try {
            $args = @(
                "-sS", "-L",
                "-A", $userAgent,
                "-H", "Accept: application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "-o", $bodyFile,
                "-w", "%{http_code}",
                $Url
            )
            if ($Referer) {
                $args = @("-sS", "-L", "-A", $userAgent, "-H", "Accept: application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "-H", "Referer: $Referer", "-o", $bodyFile, "-w", "%{http_code}", $Url)
            }
            $status = & curl.exe @args
            $content = ""
            if (Test-Path $bodyFile) {
                $content = [string](Get-Content -LiteralPath $bodyFile -Raw -ErrorAction SilentlyContinue)
            }
            Add-Check -Source $Source -Url $Url -Status $status -ContentType "" -Body $content
        } finally {
            Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
        }
    } catch {
        $message = $_.Exception.Message
        $inner = $_.Exception.InnerException
        if ($null -ne $inner -and $inner.Message) {
            $message = "$message | $($inner.Message)"
        }
        Add-Check -Source $Source -Url $Url -Status "ERROR" -ContentType "" -Body "" -ErrorText $message
    }
}

foreach ($token in $kodikTokens) {
    Invoke-CheckedRequest -Source "Kodik search by id" -Url "https://kodik-api.com/search?token=$token&shikimori_id=$ShikimoriId&with_episodes_data=true&with_page_links=true" -Referer "https://kodik.info/"
    if ($AnimeTitle) {
        $encodedTitle = [uri]::EscapeDataString($AnimeTitle)
        Invoke-CheckedRequest -Source "Kodik search by title" -Url "https://kodik-api.com/search?token=$token&title=$encodedTitle&with_episodes_data=true&with_page_links=true" -Referer "https://kodik.info/"
    }
}

if ($AnimeTitle) {
    $encodedTitle = [uri]::EscapeDataString($AnimeTitle)
    Invoke-CheckedRequest -Source "AniLiberty search" -Url "https://anilibria.top/api/v1/app/search/releases?query=$encodedTitle" -Referer "https://anilibria.top/"
    $slug = ($AnimeTitle.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    if ($slug) {
        Invoke-CheckedRequest -Source "AniLiberty release by alias" -Url "https://anilibria.top/api/v1/anime/releases/list?aliases=$slug" -Referer "https://anilibria.top/"
    }
}

if ($AnimeTitle) {
    try {
        $bodyFile = [System.IO.Path]::GetTempFileName()
        $encodedTitle = [uri]::EscapeDataString($AnimeTitle)
        $args = @(
            "-sS", "-L",
            "-A", $userAgent,
            "-H", "Accept: application/json,text/javascript,*/*;q=0.01",
            "-H", "Referer: https://api.anilib.moe/",
            "-H", "X-Requested-With: XMLHttpRequest",
            "-o", $bodyFile,
            "-w", "%{http_code}",
            "--data", "search=$encodedTitle&small=1",
            "https://api.anilib.moe/public/search.php"
        )
        $status = & curl.exe @args
        $searchBody = ""
        if (Test-Path $bodyFile) {
            $searchBody = [string](Get-Content -LiteralPath $bodyFile -Raw -ErrorAction SilentlyContinue)
        }
        Add-Check -Source "AniLib search body" -Url "https://api.anilib.moe/public/search.php" -Status $status -ContentType "" -Body $searchBody
        Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
        if ($searchBody -match "href='([^']+)'") {
            $releasePath = ($Matches[1] -replace '\\/', '/')
            Invoke-CheckedRequest -Source "AniLib release page" -Url ("https://api.anilib.moe" + $releasePath) -Referer "https://api.anilib.moe/"
        }
    } catch {
        Add-Check -Source "AniLib search body" -Url "https://api.anilib.moe/public/search.php" -Status "ERROR" -ContentType "" -Body "" -ErrorText $_.Exception.Message
    }
}

$checks | Format-List
