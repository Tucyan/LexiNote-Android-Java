# Temporarily seed the emulator fixture. Never copy private app data to the host.
. (Join-Path $PSScriptRoot 'device-ui.ps1')
$fixture = Join-Path $uiRoot 'fixture.json'
$uiRoot = Join-Path (Split-Path $PSScriptRoot) 'docs/ui-parity-restoration'
New-Item -ItemType Directory -Path $uiRoot -Force | Out-Null
$package = 'com.lexinote.nativeapp'
& $adb shell am force-stop $package
& $adb shell run-as $package cp files/library.json files/restoration-original.json
if ($LASTEXITCODE -ne 0) { throw 'Unable to save library snapshot' }
& $adb shell run-as $package cp shared_prefs/provider_private.xml files/restoration-provider.xml
if ($LASTEXITCODE -ne 0) { throw 'Unable to save provider snapshot; library snapshot retained' }
try {
 & $adb push $fixture /data/local/tmp/lexinote-ui-fixture.json | Out-Null
 & $adb shell chmod 644 /data/local/tmp/lexinote-ui-fixture.json
 & $adb shell run-as $package cp /data/local/tmp/lexinote-ui-fixture.json files/library.json
 if ($LASTEXITCODE -ne 0) { throw 'Unable to install fixture' }
 & $adb shell run-as $package rm shared_prefs/provider_private.xml
 if ($LASTEXITCODE -ne 0) { throw 'Unable to isolate provider settings' }
 & $adb shell am start -W -n "$package/.MainActivity" | Out-Null
 Tap-DeviceLabel '笔记'
 Save-DeviceScreen 'after' 'notebooks'
 Tap-DeviceLabel '批量管理'
 Save-DeviceScreen 'after' 'notebooks-selection'
 Tap-DeviceLabel '退出多选'
 Tap-DeviceLabel '我的'
 Save-DeviceScreen 'after' 'settings'
 Tap-DeviceLabel '高级设置 · 模型与手动配置'
 Save-DeviceScreen 'after' 'model-editor'
 Tap-DeviceLabel 'DeepSeek (Flash 多模态)'
 if ($null -eq (Find-DeviceLabel 'AI 服务连接')) { throw 'Provider selection did not update the service summary' }
 Save-DeviceScreen 'after' 'settings-deepseek'
 Tap-DeviceLabel 'TokenDance (词元跳动)'
 & $adb shell input swipe 550 1900 550 900 500
 Save-DeviceScreen 'after' 'model-editor-scrolled'
 Show-DeviceLabels
} finally {
 & $adb shell am force-stop $package
 & $adb shell run-as $package cp files/restoration-original.json files/library.json
 if ($LASTEXITCODE -ne 0) { throw 'Library restore failed; snapshot retained' }
 & $adb shell run-as $package cp files/restoration-provider.xml shared_prefs/provider_private.xml
 if ($LASTEXITCODE -ne 0) { throw 'Provider restore failed; snapshot retained' }
 & $adb shell run-as $package sha256sum files/restoration-original.json files/library.json files/restoration-provider.xml shared_prefs/provider_private.xml
}
