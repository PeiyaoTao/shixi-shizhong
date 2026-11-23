param([string[]]$Names = @('cover-portrait-1.png','cover-landscape-1.png','inner-portrait-1.png','inner-landscape-0.png','inner-landscape-1.png','inner-landscape-2.png','inner-landscape-3.png','cover-editor-group-2.png','cover-inline-color.png','cover-large-font.png'),[string]$Version='0.3.2')
$ErrorActionPreference = 'Stop'
$adbPath = 'C:\Users\13673\AppData\Local\Android\Sdk\platform-tools\adb.exe'
if($Version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid QA version'}
$destination = Join-Path $PSScriptRoot "..\dist\qa-$Version"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach ($name in $Names) {
    if ($name -notmatch '^[a-z0-9]+(?:[.-][a-z0-9]+)*\.png$') { throw 'Invalid QA filename' }
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adbPath
    foreach ($arg in @('exec-out','run-as','com.foldstandby.clock','cat',"files/qa-$Version/$name")) { $start.ArgumentList.Add($arg) }
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $process = [System.Diagnostics.Process]::Start($start)
    $stream = [System.IO.File]::Create((Join-Path $destination $name))
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or (Get-Item -LiteralPath (Join-Path $destination $name)).Length -eq 0) { throw "Could not export $name" }
    $header = Get-Content -LiteralPath (Join-Path $destination $name) -AsByteStream -TotalCount 8
    if ([BitConverter]::ToString($header) -ne '89-50-4E-47-0D-0A-1A-0A') { throw "Export is not a PNG: $name" }
    $process.Dispose()
}
Write-Output "Exported $($Names.Length) device-rendered PNGs."
