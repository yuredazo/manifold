# Builds both hubs from the current commit, checks them, and publishes a GitHub release with the APK and the Windows zip.
# Without -Publish it stops after the checks, so a release can be rehearsed. The GitHub token is the login Git Credential
# Manager already holds for github.com, and it is never printed.
# Usage: ./release.ps1 [-Publish] [-Notes <text>]
param([switch]$Publish, [string]$Notes = '')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }

function Invoke-Checked([string]$What, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}

Step 'Versions'
$android = (Select-String -Path 'hub/build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$windows = (Select-String -Path 'windows/hub/Cargo.toml' -Pattern '^version = "([^"]+)"').Matches[0].Groups[1].Value
if ($android -ne $windows) { throw "The Android hub is $android but the Windows hub is $windows; both updaters compare against the same tag." }
$version = $android
$tag = "v$version"
Write-Host "Releasing $tag"

Step 'Repository'
if (git status --porcelain) { throw 'Commit or stash every change first: the release is built from, and tagged on, the current commit.' }
Invoke-Checked 'git fetch' { git fetch origin --quiet --tags }
$commit = (git rev-parse HEAD).Trim()
if (-not (git branch -r --contains $commit)) { throw "Push $commit first: GitHub tags the release on a commit it already has." }
if (git ls-remote --tags origin "refs/tags/$tag") { throw "$tag already exists on GitHub. Raise the version in hub/build.gradle.kts and windows/hub/Cargo.toml." }
if (-not (Test-Path 'keystore.properties')) { throw 'keystore.properties is missing, so the APK would be signed with the debug key.' }

$out = Join-Path $PSScriptRoot 'build/release'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$apk = Join-Path $out "manifold-hub-$tag.apk"
$zip = Join-Path $out "manifold-hub-windows-$tag.zip"

Step 'Android hub'
Invoke-Checked 'The Android tests and build' { ./gradlew.bat :sdk:testDebugUnitTest :hub:testDebugUnitTest :sdk:apiCheck :hub:assembleRelease }
Copy-Item 'hub/build/outputs/apk/release/hub-release.apk' $apk
$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $line = Select-String -Path 'local.properties' -Pattern '^sdk\.dir=(.+)$'
    $sdk = $line.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\'
}
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools/*/apksigner.bat') | Sort-Object { [version]$_.Directory.Name } | Select-Object -Last 1
if (-not $apksigner) { throw "No apksigner under $sdk\build-tools" }
$certs = & $apksigner.FullName verify --print-certs $apk
if ($LASTEXITCODE -ne 0 -or -not ($certs -match 'CN=Manifold, O=mkzk')) { throw 'The APK does not carry the release signature.' }

Step 'Windows hub'
Push-Location 'windows'
try {
    Invoke-Checked 'Clippy' { cargo clippy --workspace --all-targets -- -D warnings }
    Invoke-Checked 'The protocol tests' { cargo test -p manifold-protocol }
    Invoke-Checked 'The Windows build' { cargo build --release -p manifold-hub }
    ./package.ps1 -Out $zip
} finally {
    Pop-Location
}

Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize
if (-not $Publish) {
    Write-Host "Everything checked. Run ./release.ps1 -Publish to release $tag." -ForegroundColor Green
    return
}

Step 'GitHub'
# Windows PowerShell would put a byte order mark in front of what it pipes to git, which git then cannot read.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$answer = "protocol=https`nhost=github.com`n`n" | git credential fill
$token = ($answer | Where-Object { $_ -like 'password=*' } | Select-Object -First 1) -replace '^password=', ''
if (-not $token) { throw 'Git Credential Manager has no GitHub login. Sign in with any git push to github.com first.' }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28'; 'User-Agent' = 'manifold-release' }
$repo = ((git remote get-url origin) -replace '^https://github\.com/', '' -replace '^git@github\.com:', '' -replace '\.git$', '').Trim()
$api = "https://api.github.com/repos/$repo"

# A draft first, so nobody is offered a release that carries only one of the two files.
$body = @{ tag_name = $tag; target_commitish = $commit; name = "Manifold Hub $version"; body = $Notes; draft = $true } | ConvertTo-Json -Compress
$release = Invoke-RestMethod -Method Post -Uri "$api/releases" -Headers $headers -Body $body -ContentType 'application/json'
try {
    $uploads = @(@{ Path = $apk; Type = 'application/vnd.android.package-archive' }, @{ Path = $zip; Type = 'application/zip' })
    foreach ($upload in $uploads) {
        $name = Split-Path $upload.Path -Leaf
        Write-Host "Uploading $name"
        $asset = Invoke-RestMethod -Method Post -Uri "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$name" -Headers $headers -InFile $upload.Path -ContentType $upload.Type
        # Both updaters only install a file whose checksum GitHub lists.
        $expected = 'sha256:' + (Get-FileHash $upload.Path -Algorithm SHA256).Hash.ToLower()
        if ($asset.digest -ne $expected) { throw "GitHub lists $($asset.digest) for $name, expected $expected" }
    }
    $published = Invoke-RestMethod -Method Patch -Uri "$api/releases/$($release.id)" -Headers $headers -Body (@{ draft = $false } | ConvertTo-Json -Compress) -ContentType 'application/json'
} catch {
    Write-Host "Publishing failed, so the draft release $($release.id) is deleted again." -ForegroundColor Yellow
    Invoke-RestMethod -Method Delete -Uri "$api/releases/$($release.id)" -Headers $headers | Out-Null
    throw
}
git fetch origin --quiet --tags
Write-Host "Released $tag at $($published.html_url)" -ForegroundColor Green
