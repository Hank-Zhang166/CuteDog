[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Port = 22033)

$ErrorActionPreference = 'Stop'
$node = (Get-Command node.exe).Source
$npm = (Get-Command npm.cmd).Source
$codex = (Get-Command codex.cmd).Source
if (([int](& $node -p 'process.versions.node.split(".")[0]')) -lt 22) {
    throw 'Node.js 22 or newer is required.'
}
$files = @('server.mjs', 'editor.mjs', 'smoke.mjs', 'package.json', 'package-lock.json')
$fingerprint = ($files | ForEach-Object {
    (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot $_)).Hash
}) -join ''
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $hash = $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($fingerprint))
    $revision = [BitConverter]::ToString($hash).Replace('-', '').Substring(0, 16).ToLowerInvariant()
} finally { $sha.Dispose() }
$installRoot = Join-Path $env:LOCALAPPDATA "CuteDogCubismMCP\server-$revision"
[IO.Directory]::CreateDirectory($installRoot) | Out-Null
foreach ($file in $files) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $installRoot $file) -Force
}
Push-Location $installRoot
try {
    if (-not (Test-Path -LiteralPath '.installed')) {
        & $npm ci --ignore-scripts --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Installing locked MCP dependencies failed.' }
        [IO.File]::WriteAllText((Join-Path $installRoot '.installed'), $revision)
    }
} finally { Pop-Location }
& $codex mcp add cutedog-cubism --env "CUBISM_PORT=$Port" -- $node (Join-Path $installRoot 'server.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Codex MCP registration failed.' }
Write-Output "Installed server in $installRoot"
