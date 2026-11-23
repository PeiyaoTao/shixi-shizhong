param([string]$Name='device-settings',[string]$DisplayId='4630947004648141459',[string]$Version='0.3.2')
$ErrorActionPreference='Stop'
if($Name -notmatch '^[a-z0-9-]+$'){throw 'Invalid filename'}
if($Version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid QA version'}
$destination=Join-Path $PSScriptRoot "..\dist\qa-$Version"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$start=[System.Diagnostics.ProcessStartInfo]::new()
$start.FileName='C:\Users\13673\AppData\Local\Android\Sdk\platform-tools\adb.exe'
foreach($arg in @('exec-out','screencap','-p','-d',$DisplayId)){$start.ArgumentList.Add($arg)}
$start.UseShellExecute=$false
$start.CreateNoWindow=$true
$start.RedirectStandardOutput=$true
$process=[System.Diagnostics.Process]::Start($start)
$stream=[System.IO.File]::Create((Join-Path $destination "$Name.png"))
try{$process.StandardOutput.BaseStream.CopyTo($stream)}finally{$stream.Dispose()}
$process.WaitForExit()
if($process.ExitCode -ne 0){throw 'Screenshot failed'}
$process.Dispose()
Write-Output (Join-Path $destination "$Name.png")
