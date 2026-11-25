#!/usr/bin/env python3
"""Host orchestration regression fixtures; these do NOT execute or validate Android tests.

Runs a copied runner in an isolated temporary Git repository. Fake adb/gradlew deliberately
simulate permission state, UTP cleanup, and generated XML so shell failures are reproducible
without touching a real emulator, APK, Gradle daemon, or the working project's reports.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = """package com.cutedog.app.reminder
class ReminderPersistenceTest {
    @Test fun normalCase() {}
    @Test fun deniedExactAlarmPermissionUsesInexactSchedulingWithoutSecurityFailure() {}
}
"""
SECOND_SOURCE = """package com.cutedog.app.reminder
class ReminderReliabilityTest {
    @Test fun deniedNotificationPermissionRetainsDurablePendingOccurrence() {}
}
"""
DAILY_SOURCE = """package com.cutedog.app.daily
class DailyInstrumentationTest {
    @Test fun deniedNotificationPermissionRetainsDailyQuoteWithoutClaimingDelivery() {}
}
"""

ADB = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
p=Path('device-state.json'); state=json.loads(p.read_text()); args=sys.argv[1:]
with Path('commands.jsonl').open('a') as log: log.write(json.dumps(args)+'\n')
if args[:1]==['-s']: args=args[2:]
def save(): p.write_text(json.dumps(state))
if args==['devices']: print('List of devices attached\nemulator-5554\tdevice\n')
elif args==['shell','getprop','ro.kernel.qemu']: print('1')
elif args==['shell','getprop','ro.build.version.sdk']: print(state['api'])
elif args[:3]==['shell','pm','path']:
    if args[3] in state['installed']: print('package:/data/app/fixture/base.apk')
    else: raise SystemExit(1)
elif args[:1]==['install']:
    package='com.cutedog.app.test' if args[-1].endswith('instrumentation.apk') else 'com.cutedog.app'
    state['installed'].append(package); state['reinstalls']+=1;save();print('Success')
elif args[:3]==['shell','dumpsys','package']:
    print('android.permission.POST_NOTIFICATIONS: granted='+str(state['notification']=='granted').lower())
    print('android.permission.RECORD_AUDIO: granted='+str(state['microphone']=='granted').lower())
elif args[:5]==['shell','cmd','appops','get','com.cutedog.app']:
    print('SCHEDULE_EXACT_ALARM: '+state['exact'])
elif args[:3] in (['shell','pm','grant'],['shell','pm','revoke']):
    if args[3] not in state['installed']: print('package not found',file=sys.stderr);raise SystemExit(1)
    key={'android.permission.POST_NOTIFICATIONS':'notification','android.permission.RECORD_AUDIO':'microphone'}[args[4]]
    state[key]='granted' if args[2]=='grant' else 'denied';save()
elif args[:4]==['shell','cmd','appops','set']:
    if args[4] not in state['installed']: print('No UID',file=sys.stderr);raise SystemExit(1)
    state['exact']=args[-1];save()
elif args==['logcat','-c'] and os.environ.get('FIXTURE_LOGCAT_CLEAR_FAIL')=='1':
    print('fixture: cannot clear one log buffer',file=sys.stderr);raise SystemExit(1)
elif args[:3]==['shell','am','force-stop']: pass
elif args[:2]==['logcat','-d']: print('fixture diagnostics only')
elif args[:3]==['shell','dumpsys','meminfo']: print('fixture memory unavailable')
elif args[:3]==['shell','test','-d']: raise SystemExit(1)  # No UI failure files in this host fixture.
else: raise SystemExit('Unexpected fixture adb call: '+repr(args))
'''

GRADLE = r'''#!/usr/bin/env python3
import json,os,sys,xml.etree.ElementTree as ET
from pathlib import Path
p=Path('device-state.json');state=json.loads(p.read_text());args=sys.argv[1:]
if ':app:installDebug' in args:
    for name in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        target=Path(name);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(b'HOST FIXTURE, NOT AN ANDROID APK')
    state['installed']=['com.cutedog.app','com.cutedog.app.test'];p.write_text(json.dumps(state));print('fixture installation only');raise SystemExit(0)
assert ':app:connectedDebugAndroidTest' in args
assert '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' in args
assert '--rerun' in args and '--rerun-tasks' not in args
denied=any(arg.startswith('-Pandroid.testInstrumentationRunnerArguments.class=') for arg in args)
phase='denied' if denied else 'granted';state['phases'].append(phase)
state['microphone']='granted'
if state['api']>=33: assert state['notification']==('denied' if denied else 'granted')
if state['api']>=31: assert state['exact']==('deny' if denied else 'allow')
failure=os.environ.get('FIXTURE_FAIL_GRANTED')=='1' and not denied
if os.environ.get('FIXTURE_NO_XML')!='1':
    root=ET.Element('testsuite')
    for cls,method in [('ReminderPersistenceTest','normalCase'),('ReminderPersistenceTest','deniedExactAlarmPermissionUsesInexactSchedulingWithoutSecurityFailure'),('ReminderReliabilityTest','deniedNotificationPermissionRetainsDurablePendingOccurrence'),('DailyInstrumentationTest','deniedNotificationPermissionRetainsDailyQuoteWithoutClaimingDelivery')]:
        is_denied=method.startswith('denied')
        if denied and not is_denied: continue
        package='com.cutedog.app.daily.' if cls=='DailyInstrumentationTest' else 'com.cutedog.app.reminder.'
        case=ET.SubElement(root,'testcase',classname=package+cls,name=method,time='0.01')
        if is_denied and (not denied or state['api']==26): ET.SubElement(case,'skipped',message='fixture expected permission phase')
        elif failure and method=='normalCase': ET.SubElement(case,'failure',message='fixture business assertion failure')
    result=Path('app/build/outputs/androidTest-results/connected/debug/TEST-fixture.xml');result.parent.mkdir(parents=True,exist_ok=True);ET.ElementTree(root).write(result)
if os.environ.get('FIXTURE_REMOVE_PACKAGES')=='1': state['installed']=[]
if os.environ.get('FIXTURE_MUTATE_APK')=='1': Path('app/build/outputs/apk/debug/app-debug.apk').write_bytes(b'fixture changed APK bytes')
p.write_text(json.dumps(state));print('host fixture generated XML; not real device evidence');raise SystemExit(1 if failure else 0)
'''


class DeviceRunnerHostTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cutedog-host-fixture-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        shutil.copy2(REPO / 'scripts/run-device-tests.sh', self.root / 'scripts/run-device-tests.sh')
        source = self.root / 'app/src/androidTest/java'
        source.mkdir(parents=True)
        (source / 'ReminderPersistenceTest.kt').write_text(SOURCE)
        (source / 'ReminderReliabilityTest.kt').write_text(SECOND_SOURCE)
        (source / 'DailyInstrumentationTest.kt').write_text(DAILY_SOURCE)
        (self.root / 'bin').mkdir()
        for name, body in [('bin/adb', ADB), ('gradlew', GRADLE)]:
            path = self.root / name
            path.write_text(body)
            path.chmod(0o755)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Host Fixture', '-c',
                        'user.email=fixture@example.invalid', 'commit', '-qm', 'explicit host fixture'], check=True)

    def run_fixture(self, api, **flags):
        initial = {'api': api, 'installed': [], 'notification': 'denied', 'microphone':'denied', 'exact': 'default',
                   'reinstalls': 0, 'phases': []}
        (self.root / 'device-state.json').write_text(json.dumps(initial))
        env = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'], ANDROID_SERIAL='')
        env.update(flags)
        result = subprocess.run(['bash', 'scripts/run-device-tests.sh', '--disposable', '--output', 'evidence'],
                                cwd=self.root, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=30)
        summary = json.loads((self.root / 'evidence/summary.json').read_text())
        state = json.loads((self.root / 'device-state.json').read_text())
        return result, summary, state

    def test_api26_optional_logcat_clear_failure_does_not_suppress_either_phase(self):
        result, summary, state = self.run_fixture(26, FIXTURE_LOGCAT_CLEAR_FAIL='1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(state['phases'], ['granted', 'denied'])
        self.assertEqual(summary['phases']['denied']['counts']['skipped'], 3)
        self.assertEqual(summary['phases']['denied']['counts']['passed'], 0)
        self.assertEqual((self.root / 'evidence/granted/logcat-clear-exit-code.txt').read_text().strip(), '1')

    def test_api35_grant_revoke_and_restore_preserve_exactly_two_phase_results(self):
        result, summary, state = self.run_fixture(35)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(summary['phases']['granted']['counts']['skipped'], 3)
        self.assertEqual(summary['phases']['denied']['counts']['passed'], 3)
        self.assertEqual(state['microphone'], 'denied')
        self.assertEqual(state['notification'], 'denied')
        self.assertEqual(state['exact'], 'default')
        self.assertEqual(state['reinstalls'], 0)

    def test_missing_packages_after_instrumentation_are_reinstalled_before_permission_mutation(self):
        result, summary, state = self.run_fixture(35, FIXTURE_REMOVE_PACKAGES='1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(state['reinstalls'], 4)
        self.assertEqual(summary['permissionRestore'], 'restored')
        self.assertEqual(state['notification'], 'denied')
        self.assertEqual(state['exact'], 'default')

    def test_business_failure_is_retained_and_does_not_skip_denied_phase(self):
        result, summary, state = self.run_fixture(35, FIXTURE_FAIL_GRANTED='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state['phases'], ['granted', 'denied'])
        self.assertEqual(summary['phases']['granted']['gradleExitCode'], 1)
        self.assertEqual(summary['phases']['granted']['counts']['failed'], 1)
        self.assertEqual(summary['phases']['denied']['status'], 'passed')
        self.assertEqual(summary['overallStatus'], 'failed')
        self.assertEqual(summary['permissionRestore'], 'restored')

    def test_zero_xml_cannot_pass_even_when_gradle_returns_zero(self):
        result, summary, state = self.run_fixture(26, FIXTURE_NO_XML='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state['phases'], ['granted', 'denied'])
        self.assertEqual(summary['phases']['granted']['counts']['total'], 0)
        self.assertEqual(len(summary['phases']['granted']['missingTests']), 4)
        self.assertEqual(summary['overallStatus'], 'failed')

    def test_apk_bytes_changing_between_phases_invalidates_provenance(self):
        result, summary, _ = self.run_fixture(35, FIXTURE_MUTATE_APK='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(summary['phases']['granted']['apksUnchanged'])


class ValidationWrapperHostTest(unittest.TestCase):
    def run_wrapper(self, api, test_rc, profile_rc):
        with tempfile.TemporaryDirectory(prefix='cutedog-wrapper-fixture-') as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copy2(REPO / 'scripts/run-device-validation.sh', root / 'scripts/run-device-validation.sh')
            runner = root / 'scripts/run-device-tests.sh'
            runner.write_text('#!/bin/sh\nexit '+str(test_rc)+'\n')
            runner.chmod(0o755)
            (root / 'scripts/profile-device.py').write_text('raise SystemExit('+str(profile_rc)+')\n')
            env = dict(os.environ, TEST_API_LEVEL=str(api), RUNNER_TEMP=str(root / 'output'))
            result = subprocess.run(['bash', 'scripts/run-device-validation.sh'], cwd=root, env=env,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
            summary = json.loads((root / 'output/instrumentation-evidence/validation-outcomes.json').read_text())
            return result.returncode, summary

    def test_api35_instrumentation_failure_still_executes_profile_and_preserves_both_codes(self):
        code, summary = self.run_wrapper(35, 17, 23)
        self.assertEqual(code, 17)
        self.assertEqual(summary['instrumentationExitCode'], 17)
        self.assertEqual(summary['performanceExitCode'], 23)

    def test_profile_lifecycle_failure_cannot_be_hidden_by_passing_instrumentation(self):
        code, summary = self.run_wrapper(35, 0, 23)
        self.assertEqual(code, 23)
        self.assertEqual(summary['status'], 'failed')

    def test_api26_does_not_misreport_profile_as_executed(self):
        code, summary = self.run_wrapper(26, 0, 23)
        self.assertEqual(code, 0)
        self.assertEqual(summary['performanceExecution'], 'not_applicable')
        self.assertIsNone(summary['performanceExitCode'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
