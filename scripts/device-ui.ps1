# Small adb UI helpers: every tap is resolved from the current accessibility tree.
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$uiRoot=Join-Path (Split-Path $PSScriptRoot) 'docs/ui-parity'
function Read-DeviceUi {
 $dump=& $adb shell uiautomator dump /sdcard/parity.xml 2>&1
 if(-not ($dump -match 'dumped')){throw "UI snapshot unavailable: $dump"}
 & $adb pull /sdcard/parity.xml (Join-Path $uiRoot 'current.xml') 2>$null | Out-Null
 [xml](Get-Content (Join-Path $uiRoot 'current.xml') -Raw)
}
function Find-DeviceLabel([string]$label) {
 $tree=Read-DeviceUi
 $tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('text') -eq $label -or $_.GetAttribute('content-desc') -eq $label} | Select-Object -Last 1
}
function Tap-DeviceLabel([string]$label) {
 $node=Find-DeviceLabel $label
 if($null -eq $node){throw "Visible label missing: $label"}
 $coords=[regex]::Matches($node.GetAttribute('bounds'),'\d+') | ForEach-Object {[int]$_.Value}
 & $adb shell input tap ([int](($coords[0]+$coords[2])/2)) ([int](($coords[1]+$coords[3])/2)) | Out-Null
}
function Save-DeviceScreen([string]$stage,[string]$name) {
 $folder=Join-Path $uiRoot $stage
 New-Item -ItemType Directory -Path $folder -Force | Out-Null
 $tree=Read-DeviceUi
 $tree.Save((Join-Path $folder "$name.xml"))
 & $adb shell screencap -p /sdcard/parity.png
 & $adb pull /sdcard/parity.png (Join-Path $folder "$name.png") 2>$null | Out-Null
}
function Show-DeviceLabels {
 $tree=Read-DeviceUi
 $tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('text') -or $_.GetAttribute('content-desc')} | ForEach-Object { '{0} | {1} | {2}' -f $_.GetAttribute('text'),$_.GetAttribute('content-desc'),$_.GetAttribute('bounds') }
}
