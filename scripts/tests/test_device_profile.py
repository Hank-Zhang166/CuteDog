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

if __name__=='__main__': unittest.main(verbosity=2)
