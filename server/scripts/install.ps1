# SPDX-License-Identifier: AGPL-3.0-only
# Installs the latest agy-connector release for Windows into %LOCALAPPDATA%\Programs\agy-connector.
#
#   irm https://raw.githubusercontent.com/NarYuki/agy-remote/main/server/scripts/install.ps1 | iex
#
# Options (when run as a file): -Service (start at login via a Scheduled Task), -Dir <path>, -Version <tag>
# Environment: AGY_INSTALL_DIR, AGY_RELEASE_BASE.
param(
    [switch]$Service,
    [string]$Dir = $(if ($env:AGY_INSTALL_DIR) { $env:AGY_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\agy-connector' }),
    [string]$Version = ''
)
$ErrorActionPreference = 'Stop'
$repo = 'NarYuki/agy-remote'
# Release signing key (Ed25519, public half), same as RELEASE_KEY_SSH in install.sh.
$releaseKey = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINxqMoVdK458HJ2Yu6E9p3XnhHG74DZP6+W/8C/nW6fQ'

if ($env:AGY_RELEASE_BASE) { $base = $env:AGY_RELEASE_BASE }
elseif ($Version) { $base = "https://github.com/$repo/releases/download/$Version" }
else { $base = "https://github.com/$repo/releases/latest/download" }

$arch = switch ($env:PROCESSOR_ARCHITECTURE) { 'AMD64' { 'x86_64' } 'ARM64' { 'aarch64' } default { throw "unsupported CPU $env:PROCESSOR_ARCHITECTURE" } }
$asset = "agy-connector-windows-$arch.zip"

$work = Join-Path ([IO.Path]::GetTempPath()) ("agy-install-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $work | Out-Null
try {
    Write-Host "Downloading $asset"
    Invoke-WebRequest "$base/$asset" -OutFile "$work\$asset" -UseBasicParsing
    Invoke-WebRequest "$base/checksums.txt" -OutFile "$work\checksums.txt" -UseBasicParsing
    $want = (Get-Content "$work\checksums.txt" | ForEach-Object { $p = $_ -split '\s+'; if ($p[1].TrimStart('*') -eq $asset) { $p[0] } } | Select-Object -First 1)
    if (-not $want) { throw "checksums.txt has no entry for $asset" }
    $have = (Get-FileHash "$work\$asset" -Algorithm SHA256).Hash.ToLower()
    if ($have -ne $want.ToLower()) { throw "checksum mismatch for $asset (expected $want, got $have)" }
    Write-Host 'Checksum OK'

    Expand-Archive "$work\$asset" -DestinationPath "$work\x" -Force
    if (-not (Test-Path "$work\x\agy-connector.exe")) { throw "$asset does not contain agy-connector.exe" }

    # checksums.txt comes from the same place as the download, so it only catches a damaged file.
    # What decides is the signed manifest: its OpenSSH signature is checked against the release key
    # (RELEASE_PUBLIC_KEY in server/agy-connector/src/update.rs) and the exe must be the one it lists.
    $sshKeygen = Join-Path $env:WINDIR 'System32\OpenSSH\ssh-keygen.exe'
    if (-not (Test-Path $sshKeygen)) { $sshKeygen = (Get-Command ssh-keygen -ErrorAction SilentlyContinue).Source }
    if (-not $sshKeygen) { throw 'need ssh-keygen (Windows OpenSSH Client) to check the release signature' }
    Invoke-WebRequest "$base/manifest.json" -OutFile "$work\manifest.json" -UseBasicParsing
    Invoke-WebRequest "$base/manifest.json.sshsig" -OutFile "$work\manifest.json.sshsig" -UseBasicParsing
    [IO.File]::WriteAllText("$work\allowed_signers", "agy-release $releaseKey`n")
    $verify = Start-Process -FilePath $sshKeygen -NoNewWindow -Wait -PassThru `
        -RedirectStandardInput "$work\manifest.json" -RedirectStandardOutput "$work\verify.out" -RedirectStandardError "$work\verify.err" `
        -ArgumentList @('-Y', 'verify', '-f', "`"$work\allowed_signers`"", '-I', 'agy-release', '-n', 'agy-release', '-s', "`"$work\manifest.json.sshsig`"")
    if ($verify.ExitCode -ne 0) { throw 'the release manifest is not signed by the agy-connector release key; nothing was installed' }
    $signed = (Get-Content -Raw "$work\manifest.json" | ConvertFrom-Json).assets."$arch-windows".sha256
    if (-not $signed) { throw "the signed manifest has no entry for $arch-windows" }
    $exeHash = (Get-FileHash "$work\x\agy-connector.exe" -Algorithm SHA256).Hash.ToLower()
    if ($exeHash -ne $signed.ToLower()) { throw "agy-connector.exe does not match the signed manifest (expected $signed, got $exeHash); nothing was installed" }
    Write-Host 'Signature OK'
    New-Item -ItemType Directory -Force $Dir | Out-Null
    $exe = Join-Path $Dir 'agy-connector.exe'
    # A running connector keeps its file: move it aside, then put the new one in place.
    if (Test-Path $exe) { Move-Item -Force $exe (Join-Path $Dir '.agy-connector.old.exe') }
    Copy-Item "$work\x\agy-connector.exe" $exe
    Write-Host "Installed $exe ($(& $exe --version))"
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($userPath -split ';') -contains $Dir)) {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ";$Dir"), 'User')
    $env:Path += ";$Dir"
    Write-Host "Added $Dir to your PATH (open a new terminal to use it)."
}

if ($Service) {
    $action = New-ScheduledTaskAction -Execute $exe -Argument 'up'
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName 'agy-connector' -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName 'agy-connector'
    Write-Host "agy-connector now starts at login (Scheduled Task 'agy-connector')."
} else {
    Write-Host ''
    Write-Host "Next: run 'agy-connector pair' and scan the QR code with the AGY Remote app."
}
