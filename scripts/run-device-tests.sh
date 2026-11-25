#!/usr/bin/env bash
# Real current-source Android tests. This script never targets a physical or shared device.
set -Eeuo pipefail

usage() {
  echo 'Usage: scripts/run-device-tests.sh --disposable --output DIR [--serial emulator-N]'
  echo '       scripts/run-device-tests.sh --initialize-only --output DIR'
}
serial="${ANDROID_SERIAL:-}"
output=""
disposable=false
initialize_only=false
while (($#)); do
  case "$1" in
    --serial) serial="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --disposable) disposable=true; shift ;;
    --initialize-only) initialize_only=true; shift ;;
    --help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ -n "$output" ]] || { usage >&2; exit 2; }
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"
output="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$output")"
mkdir -p "$output"
export CUTEDOG_EVIDENCE_DIR="$output"
python3 - "$output" <<'PY'
import datetime,hashlib,json,os,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1]); path=root/'summary.json'
if not path.exists():
    metadata={'schemaVersion':1,'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
              'runId':os.environ.get('GITHUB_RUN_ID'),'runAttempt':os.environ.get('GITHUB_RUN_ATTEMPT'),
              'requestedApi':os.environ.get('TEST_API_LEVEL'),'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'overallStatus':'not_run','phases':{},'note':'No passing result is inferred from setup or APK installation.'}
    metadata['sourceDirty']=bool(subprocess.check_output(['git','status','--porcelain'],text=True).strip())
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z','--','app/src','app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle','gradlew','scripts/run-device-tests.sh','.github/workflows/android.yml']).decode().split('\0')
    digest=hashlib.sha256()
    for name in sorted(set(filter(None,names))):
        file=Path(name)
        if file.is_file():
            digest.update(name.encode());digest.update(b'\0')
            with file.open('rb') as stream:
                while chunk:=stream.read(1024*1024):digest.update(chunk)
    metadata['sourceFilesSha256']=digest.hexdigest()
    path.write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
else:
    previous=json.loads(path.read_text())
    current=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    if previous.get('sourceCommit')!=current or previous.get('phases'):
        raise SystemExit('Use a fresh evidence directory; existing results belong to another source or completed run.')
PY
if "$initialize_only"; then exit 0; fi
"$disposable" || { echo 'Refusing permission changes without --disposable on an isolated emulator.' >&2; exit 2; }
command -v adb >/dev/null
if [[ -z "$serial" ]]; then
  serial="$(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')"
fi
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Select exactly one disposable emulator with --serial; physical devices are refused.' >&2; exit 2; }
all_devices="$(adb devices | awk 'NR>1 && NF>=2 {print $1}')"
[[ "$all_devices" == "$serial" ]] || { echo 'Refusing Gradle installation while any other device is connected.' >&2; exit 2; }
export ANDROID_SERIAL="$serial"
adb_cmd() { adb -s "$serial" "$@"; }
[[ "$(adb_cmd shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Selected device is not an emulator.' >&2; exit 2; }
# ANDROID_SERIAL scopes both adb and Gradle/UTP to this one disposable instance.
api="$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" =~ ^[0-9]+$ ]] || exit 2
package=com.cutedog.app
notification_before=not_applicable
microphone_before=denied
exact_before=not_applicable
restore_needed=false
permission_restore=not_needed
staged_apks=""
current_stage=setup
stage() {
  current_stage="$1"
  printf '%s stage=%s\n' "$(date -u +%FT%TZ)" "$current_stage" >>"$output/host-lifecycle.log"
}
record_failure() {
  printf '%s stage=%s line=%s exit=%s\n' "$(date -u +%FT%TZ)" "$current_stage" "$2" "$1" >>"$output/host-errors.log"
}
ensure_packages() {
  local target apk installed
  for target in "$package" "$package.test"; do
    installed="$(adb_cmd shell pm path "$target" 2>>"$output/host-lifecycle.log" | tr -d '\r')" || installed=""
    if [[ "$installed" == package:* ]]; then
      printf 'package_present %s\n' "$target" >>"$output/host-lifecycle.log"
      continue
    fi
    if [[ "$target" == "$package" ]]; then apk="$staged_apks/application.apk"; else apk="$staged_apks/instrumentation.apk"; fi
    # Only this disposable instance and the exact APK bytes recorded in tested-apks.json.
    printf 'package_missing reinstall_original_apk %s\n' "$target" >>"$output/host-lifecycle.log"
    if [[ -z "$staged_apks" || ! -f "$apk" ]]; then return 1; fi
    if ! adb_cmd install -r -t "$apk" >>"$output/host-lifecycle.log" 2>&1; then return 1; fi
    installed="$(adb_cmd shell pm path "$target" 2>>"$output/host-lifecycle.log" | tr -d '\r')" || installed=""
    if [[ "$installed" != package:* ]]; then return 1; fi
  done
}
finish() {
  original_exit=$?
  trap - EXIT ERR
  set +e
  stage restore_permissions
  if "$restore_needed"; then
    # A cancelled host run may leave instrumentation alive; stop only the selected disposable target.
    adb_cmd shell am force-stop "$package" >>"$output/permission-restore.log" 2>&1
    adb_cmd shell am force-stop "$package.test" >>"$output/permission-restore.log" 2>&1
    permission_restore=restored
    ensure_packages || permission_restore=failed
    if [[ "$microphone_before" == granted ]]; then
      adb_cmd shell pm grant "$package" android.permission.RECORD_AUDIO >>"$output/permission-restore.log" 2>&1 || permission_restore=failed
    else
      adb_cmd shell pm revoke "$package" android.permission.RECORD_AUDIO >>"$output/permission-restore.log" 2>&1 || permission_restore=failed
    fi
    if ((api >= 33)); then
      if [[ "$notification_before" == granted ]]; then
        adb_cmd shell pm grant "$package" android.permission.POST_NOTIFICATIONS >>"$output/permission-restore.log" 2>&1 || permission_restore=failed
      else
        adb_cmd shell pm revoke "$package" android.permission.POST_NOTIFICATIONS >>"$output/permission-restore.log" 2>&1 || permission_restore=failed
      fi
    fi
    if ((api >= 31)); then
      adb_cmd shell cmd appops set "$package" SCHEDULE_EXACT_ALARM "$exact_before" >>"$output/permission-restore.log" 2>&1 || permission_restore=failed
    fi
  fi
  if [[ -n "$staged_apks" && -d "$staged_apks" ]]; then rm -rf -- "$staged_apks"; fi
  python3 - "$output" "$original_exit" "$permission_restore" <<'PY'
import datetime,json,sys
from pathlib import Path
path=Path(sys.argv[1])/'summary.json'; value=json.loads(path.read_text())
value['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
value['hostExitCode']=int(sys.argv[2]); value['permissionRestore']=sys.argv[3]
phases=value.get('phases',{})
value['overallStatus']='passed' if len(phases)==2 and all(p['status']=='passed' for p in phases.values()) and int(sys.argv[2])==0 and sys.argv[3]!='failed' else ('failed' if phases else 'setup_failed')
path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'api':value.get('api'),'status':value['overallStatus'],'phases':{k:v.get('counts') for k,v in phases.items()}},ensure_ascii=False))
PY
  if [[ "$permission_restore" == failed ]]; then exit 1; fi
  exit "$original_exit"
}
trap finish EXIT
trap 'record_failure "$?" "$LINENO"' ERR
trap 'exit 130' INT
trap 'exit 143' TERM

python3 - "$output" "$serial" "$api" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])/'summary.json';x=json.loads(p.read_text());x.update({'device':sys.argv[2],'api':int(sys.argv[3]),'disposableEmulator':True,'overallStatus':'running'});p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
PY
# Build/install before the permission snapshot. This CI emulator starts without user data.
stage build_install
if ! ./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest --console=plain >"$output/install.log" 2>&1; then
  tail -80 "$output/install.log"
  exit 1
fi
staged_apks="$(mktemp -d "${TMPDIR:-/tmp}/cutedog-tested-apks.XXXXXX")"
cp app/build/outputs/apk/debug/app-debug.apk "$staged_apks/application.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$staged_apks/instrumentation.apk"
python3 - "$output" <<'PY'
import hashlib,json,sys
from pathlib import Path
root=Path(sys.argv[1]);values={}
for label,path in [('application','app/build/outputs/apk/debug/app-debug.apk'),('instrumentation','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
    p=Path(path)
    if not p.is_file(): raise SystemExit('Missing actual built APK: '+path)
    values[label]={'path':path,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
(root/'tested-apks.json').write_text(json.dumps(values,indent=2)+'\n')
PY
stage snapshot_permissions
ensure_packages
microphone_before="$(adb_cmd shell dumpsys package "$package" | python3 -c 'import re,sys; print("granted" if re.search(r"android\.permission\.RECORD_AUDIO: granted=true",sys.stdin.read()) else "denied")')"
if ((api >= 33)); then
  notification_before="$(adb_cmd shell dumpsys package "$package" | python3 -c 'import re,sys; text=sys.stdin.read(); print("granted" if re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=true",text) else "denied")')"
fi
if ((api >= 31)); then
  exact_before="$(adb_cmd shell cmd appops get "$package" SCHEDULE_EXACT_ALARM | python3 -c 'import re,sys; text=sys.stdin.read(); match=re.search(r"SCHEDULE_EXACT_ALARM:\s*(allow|ignore|deny|default|foreground)",text); print(match.group(1) if match else "default")')"
fi
restore_needed=true
python3 - "$output" "$notification_before" "$exact_before" "$microphone_before" <<'PY'
import json,sys
from pathlib import Path
(Path(sys.argv[1])/'initial-permissions.json').write_text(json.dumps({'notification':sys.argv[2],'exactAlarmAppOp':sys.argv[3],'microphone':sys.argv[4]},indent=2)+'\n')
PY

denied_methods='com.cutedog.app.reminder.ReminderPersistenceTest#deniedExactAlarmPermissionUsesInexactSchedulingWithoutSecurityFailure,com.cutedog.app.reminder.ReminderReliabilityTest#deniedNotificationPermissionRetainsDurablePendingOccurrence,com.cutedog.app.daily.DailyInstrumentationTest#deniedNotificationPermissionRetainsDailyQuoteWithoutClaimingDelivery'
run_phase() {
  local phase="$1"
  local result_code=0
  local output_phase="$output/$phase"
  mkdir -p "$output_phase/junit"
  stage "${phase}_prepare"
  ensure_packages
  # Move old generated results out of the lookup tree; an interrupted run must not reuse them.
  local retired
  retired="$(mktemp -d "${TMPDIR:-/tmp}/cutedog-prior-results.XXXXXX")"
  if [[ -d app/build/outputs/androidTest-results/connected ]]; then mv app/build/outputs/androidTest-results/connected "$retired/results"; fi
  if [[ -d app/build/reports/androidTests/connected ]]; then mv app/build/reports/androidTests/connected "$retired/reports"; fi
  # Some Android images refuse clearing one log buffer. This optional diagnostic must
  # not prevent instrumentation from running; retain its stderr and exit separately.
  local clear_code=0
  adb_cmd logcat -c >"$output_phase/logcat-clear.txt" 2>&1 || clear_code=$?
  printf '%s\n' "$clear_code" >"$output_phase/logcat-clear-exit-code.txt"
  # AGP 8.9.2 defaults this option to false. Its UTP cleanup otherwise removes both
  # packages after a phase, making subsequent permission changes fail with No UID.
  # Gradle 8.11.1 --rerun targets this test task only. Rebuilding all dependencies
  # between phases could silently replace the APK bytes recorded before installation.
  local args=(:app:connectedDebugAndroidTest --rerun --no-daemon --console=plain --stacktrace -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true)
  args+=("-Pandroid.testInstrumentationRunnerArguments.cuteDogEvidencePhase=$phase")
  # Reached only after the explicit --disposable and single-emulator checks above.
  # Destructive fixture tests must independently require this contract before touching data.
  args+=("-Pandroid.testInstrumentationRunnerArguments.cuteDogDisposable=true")
  args+=("-Pandroid.testInstrumentationRunnerArguments.cuteDogSourceCommit=$(git rev-parse HEAD)")
  args+=("-Pandroid.testInstrumentationRunnerArguments.cuteDogRunId=${GITHUB_RUN_ID:-local}")
  args+=("-Pandroid.testInstrumentationRunnerArguments.cuteDogRunAttempt=${GITHUB_RUN_ATTEMPT:-1}")
  if [[ "$phase" == denied ]]; then args+=("-Pandroid.testInstrumentationRunnerArguments.class=$denied_methods"); fi
  set +e
  stage "${phase}_instrumentation"
  ./gradlew "${args[@]}" >"$output_phase/gradle.log" 2>&1
  result_code=$?
  set -e
  stage "${phase}_collect"
  adb_cmd logcat -d -v brief -s CuteDogPerformance:I CuteDogFrameCache:I CuteDogUiEvidence:I AndroidRuntime:E '*:S' >"$output_phase/selected-logcat.txt" 2>&1 || true
  adb_cmd shell dumpsys meminfo "$package" >"$output_phase/process-memory.txt" 2>&1 || true
  # Failure capture runs inside the test body before UI/fixture teardown. Pull only this phase's
  # test-specific directory; no unrelated app files, settings, chats or credentials are collected.
  local failure_remote="/sdcard/Android/data/$package/files/ui-test-evidence/$phase"
  if adb_cmd shell test -d "$failure_remote" >"$output_phase/ui-evidence-pull.log" 2>&1; then
    adb_cmd pull "$failure_remote" "$output_phase/ui-failures" >>"$output_phase/ui-evidence-pull.log" 2>&1 || true
  fi
  local daily_remote="/sdcard/Android/data/$package/files/daily-test-evidence/$phase"
  if adb_cmd shell test -d "$daily_remote" >"$output_phase/daily-evidence-pull.log" 2>&1; then
    adb_cmd pull "$daily_remote" "$output_phase/daily-evidence" >>"$output_phase/daily-evidence-pull.log" 2>&1 || true
  fi
  python3 - "$output" "$phase" "$result_code" "$api" "$denied_methods" <<'PY'
import datetime,hashlib,json,re,shutil,sys,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(sys.argv[1]);phase=sys.argv[2];rc=int(sys.argv[3]);api=int(sys.argv[4]);out=root/phase
apk_provenance=[]
for label,original in json.loads((root/'tested-apks.json').read_text()).items():
    apk=Path(original['path']);actual=hashlib.sha256(apk.read_bytes()).hexdigest() if apk.is_file() else None
    apk_provenance.append({'label':label,'path':str(apk),'expectedSha256':original['sha256'],'actualSha256':actual,'unchanged':actual==original['sha256']})
(out/'tested-apks.json').write_text(json.dumps(apk_provenance,indent=2)+'\n')
expected_denied=set(sys.argv[5].split(',')); discovered=set()
for source in Path('app/src/androidTest/java').rglob('*.kt'):
    text=source.read_text(); package=re.search(r'^package\s+([\w.]+)',text,re.M); cls=re.search(r'\bclass\s+(\w+)',text)
    if package and cls:
        for method in re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)\s*\(',text):
            discovered.add(package.group(1)+'.'+cls.group(1)+'#'+method)
expected=discovered if phase=='granted' else expected_denied
expected_skip=expected_denied if phase=='granted' else {name for name in expected_denied if (('ExactAlarm' in name and api<31) or ('Notification' in name and api<33))}
results_root=Path('app/build/outputs/androidTest-results/connected')
cases=[]; parse_errors=[]; files=[]
for report in sorted(results_root.rglob('TEST-*.xml')) if results_root.exists() else []:
    relative=report.relative_to(results_root);dest=out/'junit'/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(report,dest);files.append(str(Path('junit')/relative))
    try:
        doc=ET.parse(report).getroot()
        for item in doc.iter('testcase'):
            key=item.get('classname','')+'#'+item.get('name','')
            failure=item.find('failure');error=item.find('error');skip=item.find('skipped')
            state='failed' if failure is not None else 'error' if error is not None else 'skipped' if skip is not None else 'passed'
            evidence=failure if failure is not None else error if error is not None else skip
            cases.append({'id':key,'status':state,'seconds':item.get('time'),'reason':(evidence.get('message','')+' '+(evidence.text or '')).strip()[:4000] if evidence is not None else None,'report':str(Path('junit')/relative)})
    except (ET.ParseError,OSError) as e: parse_errors.append(str(relative)+': '+str(e))
ids=[case['id'] for case in cases];seen=set(ids)
missing=sorted(expected-seen);unexpected=sorted(seen-expected);duplicates=sorted({key for key in ids if ids.count(key)>1})
unexpected_skips=[case['id'] for case in cases if case['status']=='skipped' and case['id'] not in expected_skip]
missing_required_skips=sorted(expected_skip-{case['id'] for case in cases if case['status']=='skipped'})
counts={status:sum(case['status']==status for case in cases) for status in ('passed','failed','error','skipped')};counts['total']=len(cases)
apks_unchanged=all(value['unchanged'] for value in apk_provenance)
passed=rc==0 and bool(files) and apks_unchanged and not any([missing,unexpected,duplicates,parse_errors,unexpected_skips,missing_required_skips]) and counts['failed']==0 and counts['error']==0
ui_files=sorted(str(p.relative_to(out)) for p in (out/'ui-failures').rglob('*') if p.is_file()) if (out/'ui-failures').exists() else []
daily_files=sorted(str(p.relative_to(out)) for p in (out/'daily-evidence').rglob('*') if p.is_file()) if (out/'daily-evidence').exists() else []
summary={'phase':phase,'api':api,'status':'passed' if passed else 'failed','gradleExitCode':rc,'apksUnchanged':apks_unchanged,'counts':counts,'expectedTests':len(expected),'expectedSkipped':sorted(expected_skip),'unexpectedSkipped':unexpected_skips,'expectedSkipNotObserved':missing_required_skips,'missingTests':missing,'unexpectedTests':unexpected,'duplicateTests':duplicates,'xmlParseErrors':parse_errors,'junitFiles':files,'testCases':cases,'uiFailureEvidenceFiles':ui_files,'dailyEvidenceFiles':daily_files,'capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
metadata=json.loads((root/'summary.json').read_text());metadata['phases'][phase]=summary;(root/'summary.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
log=out/'selected-logcat.txt'
if log.exists():
    lines=log.read_text(errors='replace').splitlines();log.write_text('\n'.join(lines[-1000:])+'\n')
print(json.dumps({'phase':phase,'status':summary['status'],'counts':counts,'missing':missing,'unexpectedSkipped':unexpected_skips},ensure_ascii=False))
PY
  tail -60 "$output_phase/gradle.log"
  stage "${phase}_packages_after_instrumentation"
  ensure_packages
}

# Phase 1 runs every current test. Denied-only tests must explicitly skip, not silently pass.
stage grant_permissions
if ((api >= 33)); then adb_cmd shell pm grant "$package" android.permission.POST_NOTIFICATIONS; fi
if ((api >= 31)); then adb_cmd shell cmd appops set "$package" SCHEDULE_EXACT_ALARM allow; fi
run_phase granted
stage revoke_permissions
# The first instrumentation process has exited before revocation can kill the package.
# Also terminate any leftover app/runner process after a failed Gradle run, on this disposable device only.
adb_cmd shell am force-stop "$package"
adb_cmd shell am force-stop "$package.test"
if ((api >= 33)); then adb_cmd shell pm revoke "$package" android.permission.POST_NOTIFICATIONS; fi
if ((api >= 31)); then adb_cmd shell cmd appops set "$package" SCHEDULE_EXACT_ALARM deny; fi
run_phase denied
stage evaluate_phases
python3 - "$output" <<'PY'
import json,sys
from pathlib import Path
value=json.loads((Path(sys.argv[1])/'summary.json').read_text())
raise SystemExit(0 if len(value['phases'])==2 and all(phase['status']=='passed' for phase in value['phases'].values()) else 1)
PY
