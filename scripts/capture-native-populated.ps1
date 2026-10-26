# Debug emulator only. Temporarily seed the shared fixture and restore the app snapshot afterward.
. (Join-Path $PSScriptRoot 'device-ui.ps1')
$package='com.lexinote.nativeapp'
& $adb shell am force-stop $package
& $adb shell run-as $package cp files/library.json files/ui-parity-original.json
if($LASTEXITCODE -ne 0){throw 'Cannot snapshot debug app data; open the app and save data first.'}
try {
 & $adb push (Join-Path $uiRoot 'fixture.json') /data/local/tmp/lexinote-ui-fixture.json | Out-Null
 & $adb shell chmod 644 /data/local/tmp/lexinote-ui-fixture.json
 & $adb shell run-as $package cp /data/local/tmp/lexinote-ui-fixture.json files/library.json
 if($LASTEXITCODE -ne 0){throw 'Cannot seed debug fixture'}
 & $adb shell am start -W -n "$package/.MainActivity" | Out-Null
 Tap-DeviceLabel '笔记'; Save-DeviceScreen 'after' 'notebooks-filled'
 Tap-DeviceLabel '复习'; Save-DeviceScreen 'after' 'review-filled'
 Tap-DeviceLabel '查询记录'
 Tap-DeviceLabel 'Resilient 的含义与用法'; Save-DeviceScreen 'after' 'assistant'
 Tap-DeviceLabel '返回'
 Tap-DeviceLabel '最近查询'
 Tap-DeviceLabel 'resilient'
 if($null -eq (Find-DeviceLabel '释义与例句 (2 条)')){throw 'History tap did not open the expected result page'}
 Save-DeviceScreen 'after' 'result'
} finally {
 & $adb shell am force-stop $package
 & $adb shell run-as $package cp files/ui-parity-original.json files/library.json
 if($LASTEXITCODE -ne 0){throw 'Snapshot restore failed; files/ui-parity-original.json is preserved'}
}
