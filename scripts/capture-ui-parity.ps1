param([ValidateSet('reference','before','after')][string]$Stage='reference')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package=if($Stage -eq 'reference'){'com.lexinote.app'}else{'com.lexinote.nativeapp'}
$labels=if($Stage -eq 'before'){@('查询','猜词','笔记','复习','设置')}else{@('查询记录','猜词辅助','笔记','复习','我的')}
$output=Join-Path (Split-Path $PSScriptRoot) "docs/ui-parity/$Stage"
New-Item -ItemType Directory -Path $output -Force | Out-Null
function Read-Ui {
 $dump=& $adb shell uiautomator dump /sdcard/parity.xml 2>&1
 if(-not ($dump -match 'dumped')){throw "UI snapshot unavailable: $dump"}
 & $adb pull /sdcard/parity.xml (Join-Path $output 'current.xml') 2>$null | Out-Null
 [xml](Get-Content (Join-Path $output 'current.xml') -Raw)
}
function Tap-Label([string]$label,[bool]$navigation=$false) {
 $tree=Read-Ui
 $nodes=$tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('text') -eq $label -or $_.GetAttribute('content-desc') -eq $label}
 if($navigation){$nodes=$nodes | Where-Object {[int]([regex]::Match($_.GetAttribute('bounds'),'\[(\d+),(\d+)\]').Groups[2].Value) -gt 2000}}
 $node=$nodes | Select-Object -Last 1
 if($null -eq $node){throw "Missing visible label: $label"}
 $coords=[regex]::Matches($node.GetAttribute('bounds'),'\d+') | ForEach-Object {[int]$_.Value}
 & $adb shell input tap ([int](($coords[0]+$coords[2])/2)) ([int](($coords[1]+$coords[3])/2)) | Out-Null
}
function Capture([string]$name){
 $tree=Read-Ui
 $tree.Save((Join-Path $output "$name.xml"))
 & $adb shell screencap -p /sdcard/parity.png
 & $adb pull /sdcard/parity.png (Join-Path $output "$name.png") 2>$null | Out-Null
 Write-Output "$Stage/$name captured"
}
& $adb shell am force-stop $package
& $adb shell am start -W -n "$package/.MainActivity" | Out-Null
Capture 'home'
if($Stage -ne 'before'){
 Tap-Label '图片查询'; Capture 'query-image'
 Tap-Label '图文+提问'; Capture 'query-mixed'
 Tap-Label '文本查询'
}
$pages=@('home','guess','notebooks','review','settings')
for($i=1;$i -lt 5;$i++){Tap-Label $labels[$i] $true;Capture $pages[$i]}
Tap-Label $labels[0] $true
if($Stage -ne 'before'){Tap-Label '＋ 新建'; Capture 'assistant-empty'}
Get-Item (Join-Path $output 'current.xml') -ErrorAction SilentlyContinue | Remove-Item
