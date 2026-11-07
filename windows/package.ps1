# Packs a release build into the zip that releases carry and the hubs' updaters install.
# The exe sits at the top of the zip, which is where the 1.4.0 updater looks for manifold_hub.exe.
# Usage, from the windows folder after `cargo build --release -p manifold-hub`: ./package.ps1 -Out <zip>
param([Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'

$exe = 'target/release/manifold_hub.exe'
if (-not (Test-Path $exe)) { throw "Build the hub first: $exe is missing" }
$spout = Get-ChildItem 'target/release/build/manifold-hub-*/out/Spout2-*/LICENSE' | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $spout) { throw 'The Spout licence is missing from the build output' }

$stage = Join-Path ([IO.Path]::GetTempPath()) "manifold-package-$PID"
New-Item -ItemType Directory -Force (Join-Path $stage 'licenses') | Out-Null
try {
    Copy-Item $exe $stage
    Copy-Item '../LICENSE' (Join-Path $stage 'licenses/Manifold-LICENSE.txt')
    Copy-Item $spout.FullName (Join-Path $stage 'licenses/Spout2-LICENSE.txt')
    python notices.py (Join-Path $stage 'licenses/THIRD-PARTY-NOTICES.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Could not write the licence notices' }
    if (Test-Path $Out) { Remove-Item $Out }
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $Out
} finally {
    Remove-Item -Recurse -Force $stage
}
Get-Item $Out | Select-Object Name, Length
