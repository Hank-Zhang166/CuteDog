#!/usr/bin/env bash
# One shell invocation for emulator-runner: retain instrumentation and profiling outcomes separately.
set -uo pipefail
output="${RUNNER_TEMP:?RUNNER_TEMP is required}/instrumentation-evidence"
scripts/run-device-tests.sh --disposable --output "$output"
tests_rc=$?
profile_rc=0
profile_status=not_applicable
if [[ "${TEST_API_LEVEL:-}" == 35 ]]; then
  profile_status=executed
  mkdir -p "$output/performance"
  # The instrumentation runner preserves the original APKs and restores its permissions.
  # If setup failed before installation, profile-device records that real failure itself.
  python3 scripts/profile-device.py --disposable --duration 12 --cycles 3 \
    --output "$output/performance" >"$output/performance/host.log" 2>&1
  profile_rc=$?
  tail -50 "$output/performance/host.log"
fi
python3 - "$output" "$tests_rc" "$profile_rc" "$profile_status" <<'PY'
import json,sys
from pathlib import Path
output=Path(sys.argv[1]);output.mkdir(parents=True,exist_ok=True)
value={'instrumentationExitCode':int(sys.argv[2]),'performanceExitCode':int(sys.argv[3]) if sys.argv[4]=='executed' else None,
       'performanceExecution':sys.argv[4],
       'note':'Partial profiling without real rig assets is not full animation/performance acceptance. Lifecycle failures still fail the job.'}
value['status']='passed' if int(sys.argv[2])==0 and int(sys.argv[3])==0 else 'failed'
(output/'validation-outcomes.json').write_text(json.dumps(value,indent=2)+'\n')
PY
summary_rc=$?
if ((tests_rc != 0)); then exit "$tests_rc"; fi
if ((profile_rc != 0)); then exit "$profile_rc"; fi
exit "$summary_rc"
