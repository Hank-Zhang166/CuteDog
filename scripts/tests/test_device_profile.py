import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('profile_device', str(Path(__file__).resolve().parents[1] / 'profile-device.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

def tree(labels, package=p.PACKAGE):
    root = ET.Element('hierarchy')
    for label in labels:
        ET.SubElement(root, 'node', {'text':label, 'package':package, 'bounds':'[0,0][100,50]', 'enabled':'true'})
    return root

class OfflineProfileRegression(unittest.TestCase):
    def test_notification_parser_requires_exact_user_zero_state_and_flags(self):
        raw = ('requested permissions:\n android.permission.POST_NOTIFICATIONS\n'
               'User 0: installed=true\n runtime permissions:\n'
               ' android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET|USER_SENSITIVE_WHEN_DENIED ]\n'
               'User 10: installed=true\n runtime permissions:\n'
               ' android.permission.POST_NOTIFICATIONS: granted=true, flags=[]\n')
        self.assertEqual({'granted': False, 'flags': ['USER_SENSITIVE_WHEN_DENIED', 'USER_SET']},
                         p.parse_notification_permission(raw))
        for missing in ['User 10: installed=true\n android.permission.POST_NOTIFICATIONS: granted=true, flags=[]',
                        'User 0: installed=true\n requested permissions: android.permission.POST_NOTIFICATIONS']:
            with self.assertRaises(ValueError): p.parse_notification_permission(missing)

    def test_notification_authorization_restores_grant_and_changed_flags(self):
        device = PermissionFixture(granted=False, flags={'USER_SENSITIVE_WHEN_DENIED'})
        permission = p.ProfileNotificationPermission(device)
        original = dict(device.state, flags=sorted(device.state['flags']))
        prepared = permission.prepare()
        self.assertTrue(prepared['observed']['granted'])
        # This is a host-command fixture, not Android evidence: simulate changed flag bits.
        device.state['flags'] = {'USER_SET'}
        restored = permission.restore()
        self.assertEqual(original, restored['observed'])
        self.assertTrue(any('clear-permission-flags' in command for command in device.commands))
        self.assertTrue(any('set-permission-flags' in command for command in device.commands))
        self.assertIn('notification-permission/original.txt', device.evidence_files)
        self.assertIn('notification-permission/restored.json', device.evidence_files)

    def test_already_granted_notification_state_is_not_mutated(self):
        device = PermissionFixture(granted=True, flags={'USER_SET'})
        permission = p.ProfileNotificationPermission(device)
        permission.prepare(); permission.restore()
        self.assertFalse(any(command[:2] == ('shell', 'pm') for command in device.commands))

    def test_failed_grant_after_mutation_still_requires_and_allows_restoration(self):
        device = PermissionFixture(granted=False, flags={'USER_FIXED'})
        device.fail_after_grant = True
        permission = p.ProfileNotificationPermission(device)
        with self.assertRaisesRegex(RuntimeError, 'transport lost after applying grant'): permission.prepare()
        self.assertTrue(permission.restore_required)
        self.assertFalse(permission.restore()['observed']['granted'])

    def test_notification_restore_failure_is_not_reported_as_success(self):
        device = PermissionFixture(granted=False, flags=set())
        permission = p.ProfileNotificationPermission(device)
        permission.prepare(); device.ignore_revoke = True
        with self.assertRaisesRegex(AssertionError, 'did not return'): permission.restore()

    def test_unknown_changed_permission_flags_fail_without_guessing_shell_options(self):
        device = PermissionFixture(granted=False, flags=set())
        permission = p.ProfileNotificationPermission(device)
        permission.prepare(); device.state['flags'].add('UNRECOGNIZED_PLATFORM_FLAG')
        with self.assertRaisesRegex(AssertionError, 'Cannot safely restore changed permission flags'): permission.restore()
        self.assertFalse(any('permission-flags' in command[2] for command in device.commands if command[:2] == ('shell', 'pm')))

    def test_permission_setup_refuses_non_emulator_without_any_mutation(self):
        device = PermissionFixture(granted=False, flags=set()); device.serial = 'physical-device'
        with self.assertRaisesRegex(RuntimeError, 'disposable emulator'):
            p.ProfileNotificationPermission(device).prepare()
        self.assertEqual([], device.commands)

    def test_zero_dump_exit_cannot_read_previous_tree(self):
        with tempfile.TemporaryDirectory() as folder:
            device = p.Device('offline-fixture', Path(folder))
            targets = []
            def fake_run(*args, **kwargs):
                if args[:3] == ('shell','uiautomator','dump'):
                    targets.append(args[3]); return 'ERROR: null root node returned by UiTestAutomationBridge.'
                if args[:2] == ('shell','cat'):
                    raise RuntimeError('fresh path does not exist')
                return ''
            device.run = fake_run
            for _ in range(2):
                with self.assertRaisesRegex(RuntimeError, 'fresh path'):
                    device.tree()
                self.assertNotIn('xml', device.last_ui)
                self.assertIn('ERROR: null root', (Path(folder)/device.last_ui['dump_output']).read_text())
            self.assertEqual(2, len(set(targets)))
            self.assertTrue(all('cutedog-profile-ui.xml' not in path for path in targets))

    def test_timeout_keeps_command_stage_and_partial_output(self):
        with tempfile.TemporaryDirectory() as folder:
            device = p.Device('offline-fixture', Path(folder)); device.stage='test-stage'
            with patch.object(p.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['adb'], 2, output=b'partial evidence', stderr=b'timed out')):
                with self.assertRaises(subprocess.TimeoutExpired): device.run('shell','dumpsys','window',timeout=2)
            self.assertEqual('test-stage',device.calls[-1]['stage'])
            self.assertTrue(device.calls[-1]['timed_out'])
            self.assertEqual('partial evidence',device.calls[-1]['partial_stdout'])

    def test_missing_keyguard_evidence_never_means_unlocked(self):
        self.assertIsNone(p.parse_keyguard('no delegate available')['showing'])
        self.assertFalse(p.parse_keyguard('KeyguardServiceDelegate\n showing=false\n secure=false')['showing'])
        self.assertTrue(p.parse_keyguard('KeyguardServiceDelegate\n showing=true\n secure=false')['showing'])

    def test_current_root_missing_tabs_does_not_mean_activity_missing(self):
        raw={'activities':'mResumedActivity: ActivityRecord{42 u0 com.cutedog.app/.MainActivity t1}',
             'windows':'Window #1 Window{42 com.cutedog.app}:\n ty=2038 mHasSurface=true',
             'policy':'KeyguardServiceDelegate\n showing=false', 'power':'mWakefulness=Awake'}
        observed=p.scene_observation(raw,tree(['小狗，轻抚互动']))
        self.assertTrue(observed['app_resumed'])
        self.assertFalse(observed['ui']['has_navigation'])
        self.assertEqual(1, observed['visible_app_overlay_windows'])
        self.assertIn('app_resumed_but_navigation_absent_from_single_active_window_tree',observed['observations'])
        self.assertTrue(observed['root_cause'].startswith('unknown'))

    def test_tab_label_alone_does_not_verify_page_content(self):
        observed=p.ui_observation(tree(['首页','事项','陪伴','小狗']))
        self.assertTrue(observed['has_navigation'])
        self.assertFalse(any(observed['page_markers'].values()))
        for page, markers in p.PAGE_MARKERS.items():
            self.assertTrue(p.ui_observation(tree(markers))['page_markers'][page])

    def test_foreign_window_cannot_supply_app_control_or_page_markers(self):
        foreign=tree(['首页','小狗','把小事记好'],package='com.android.systemui')
        self.assertIsNone(p.Device.find(foreign,'首页'))
        self.assertFalse(p.ui_observation(foreign)['has_navigation'])
        self.assertFalse(p.ui_observation(foreign)['page_markers']['reminders'])

    def test_scene_survives_probe_failure_without_recovery_input(self):
        with tempfile.TemporaryDirectory() as folder:
            device=p.Device('offline-fixture',Path(folder)); device.stage='stop-overlay-cycle-1'
            operations=[]
            def fake_run(*args,**kwargs):
                operations.append(args)
                if args[:2]==('exec-out','screencap'): return b'\x89PNG\r\n\x1a\nOFFLINE_FIXTURE'
                if args[:4]==('shell','dumpsys','activity','processes'): raise RuntimeError('probe unavailable')
                if args[:3]==('shell','uiautomator','dump'): return 'UI hierarchy dumped'
                if args[:2]==('shell','cat'): return '<?xml version="1.0"?><hierarchy />'
                if args[:3]==('shell','dumpsys','power'): return 'mWakefulness=Awake'
                return ''
            device.run=fake_run
            scene=device.capture_scene('failure-stop-overlay-cycle-1',failure=True)
            self.assertEqual('probe unavailable',scene['errors']['processes'])
            self.assertTrue((Path(folder)/scene['evidence']['screenshot']).exists())
            self.assertTrue((Path(folder)/scene['evidence']['xml']).exists())
            self.assertEqual(1,len(device.failure_captures))
            self.assertFalse(any('force-stop' in op or 'input' in op or 'dismiss-keyguard' in op for op in operations))
            self.assertTrue((Path(folder)/'scenes/failure-stop-overlay-cycle-1/scene.json').exists())

    def test_historical_anr_is_not_current_root_cause(self):
        scene=p.scene_observation({'lastanr':'ANR in com.cutedog.app at an earlier time'},tree(['首页','小狗']))
        self.assertTrue(scene['root_cause'].startswith('unknown'))
        self.assertNotIn('system_error_dialog_text_observed',scene['observations'])

class PermissionFixture:
    """In-memory command behavior only; never emits device/profile passing evidence."""
    def __init__(self, granted, flags):
        self.serial = 'emulator-offline-fixture'
        self.state = {'granted': granted, 'flags': set(flags)}
        self.commands, self.evidence_files = [], {}
        self.fail_after_grant = self.ignore_revoke = False

    def evidence(self, name, value):
        self.evidence_files[name] = value

    def run(self, *args):
        self.commands.append(args)
        if args == ('shell', 'getprop', 'ro.kernel.qemu'): return '1\n'
        if args == ('shell', 'dumpsys', 'package', p.PACKAGE):
            return ('User 0: installed=true\n runtime permissions:\n ' + p.NOTIFICATION_PERMISSION
                    + ': granted=' + str(self.state['granted']).lower()
                    + ', flags=[ ' + '|'.join(sorted(self.state['flags'])) + ' ]\n')
        if args[:2] == ('shell', 'pm'):
            assert args[3:7] == ('--user', '0', p.PACKAGE, p.NOTIFICATION_PERMISSION)
            operation = args[2]
            if operation == 'grant':
                self.state['granted'] = True
                if self.fail_after_grant: raise RuntimeError('transport lost after applying grant')
            elif operation == 'revoke':
                if not self.ignore_revoke: self.state['granted'] = False
            else:
                reverse = {v:k for k,v in p.RESTORABLE_PERMISSION_FLAGS.items()}
                changed = {reverse[flag] for flag in args[7:]}
                if operation == 'clear-permission-flags': self.state['flags'] -= changed
                elif operation == 'set-permission-flags': self.state['flags'] |= changed
                else: raise AssertionError(args)
            return ''
        raise AssertionError(args)


if __name__=='__main__': unittest.main(verbosity=2)
