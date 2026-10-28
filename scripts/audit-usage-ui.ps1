param([string]$Root='C:/Users/ALmerb/Desktop/MyProgram/LexiNote-Android-Java')
. (Join-Path $Root 'scripts/device-ui.ps1')
$uiRoot=Join-Path $Root 'docs/verification/usage-experience'
New-Item -ItemType Directory -Force -Path $uiRoot | Out-Null
# DocumentsUI and Activity transitions can briefly return a null accessibility root.
function Read-DeviceUi {
 for($n=0;$n -lt 3;$n++){
  $dump=& $adb shell uiautomator dump /sdcard/usage-experience.xml 2>&1
  if($dump -match 'dumped'){
   & $adb pull /sdcard/usage-experience.xml (Join-Path $uiRoot 'current.xml') 2>$null | Out-Null
   return [xml](Get-Content (Join-Path $uiRoot 'current.xml') -Raw)
  }
  if($dump -notmatch 'null root node'){throw "UI snapshot unavailable: $dump"}
  Start-Sleep -Milliseconds 700
 }
 throw 'UI snapshot remained unavailable after three bounded attempts'
}
function Get-AuditNode([string]$label,[string]$class='') {
 $tree=Read-DeviceUi
 $tree.SelectNodes('//node') | Where-Object {
  ($_.GetAttribute('text') -eq $label -or $_.GetAttribute('content-desc') -eq $label -or $_.GetAttribute('hint') -eq $label) -and
  (-not $class -or $_.GetAttribute('class') -eq $class)
 } | Select-Object -First 1
}
function Tap-Audit([string]$label,[string]$class='') {
 $node=Get-AuditNode $label $class
 if(-not $node){throw "Visible node missing: $label"}
 $c=[regex]::Matches($node.GetAttribute('bounds'),'\d+') | ForEach-Object {[int]$_.Value}
 if($c.Count -ne 4 -or $c[3] -le $c[1]){throw "Node has no visible bounds: $label"}
 & $adb shell input tap ([int](($c[0]+$c[2])/2)) ([int](($c[1]+$c[3])/2))
}
function Set-AuditText([string]$label,[string]$value,[switch]$Submit) {
 if($value -notmatch '^[A-Za-z0-9 _-]*$'){throw 'This helper accepts simple ASCII test text only'}
 Tap-Audit $label 'android.widget.EditText'
 & $adb shell input keycombination 113 29
 & $adb shell input keyevent 67
 if($value){ & $adb shell input text ($value.Replace(' ','%s')) }
 if($Submit){ & $adb shell input keyevent 66 }
}
function Seek-Audit([string]$label,[int]$limit=6) {
 for($n=0;$n -lt $limit;$n++){
  if(Get-AuditNode $label){return}
  & $adb shell input swipe 600 1900 600 710 450
 }
 throw "Scroll target unavailable: $label"
}
function Snapshot-Audit([string]$name){ Save-DeviceScreen 'screens' $name }
function Export-AuditState([string]$name){
 $raw=& $adb exec-out run-as com.lexinote.nativeapp cat files/library.json
 if($LASTEXITCODE -ne 0){throw 'Cannot inspect test library'}
 $data=$raw | ConvertFrom-Json
 if(@($data.vocabularies | Where-Object {$_.id -notlike 'usage-*'}).Count -gt 0){throw 'Unexpected non-fixture data; do not publish state'}
 $summary=[ordered]@{
  vocabulary_count=@($data.vocabularies).Count
  notebook_count=@($data.notebooks).Count
  recent_count=@($data.recent_queries).Count
  saved_count=@($data.conversations | Where-Object {$_.metadata.retention -eq 'saved'}).Count
  temporary_count=@($data.conversations | Where-Object {$_.metadata.retention -eq 'temporary'}).Count
  message_count=(@($data.conversations | ForEach-Object {@($_.ai_messages).Count}) | Measure-Object -Sum).Sum
  mastered_count=@($data.vocabularies | Where-Object {$_.review_count -ge 3}).Count
  conversations=@($data.conversations | ForEach-Object {[ordered]@{id=$_.id;title=$_.metadata.title;retention=$_.metadata.retention;origin=$_.metadata.origin;last_used_at=$_.metadata.last_used_at}})
  recent=@($data.recent_queries | ForEach-Object {[ordered]@{id=$_.id;word=$_.query_results.word;created_at=$_.created_at}})
  review=@($data.vocabularies | Where-Object {$_.content -in @('agenda','budget')} | ForEach-Object {[ordered]@{word=$_.content;review_count=$_.review_count;review_grade=$_.review_grade;review_due=$_.review_due}})
 }
 $summary | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $uiRoot ($name+'.json')) -Encoding utf8NoBOM
}
