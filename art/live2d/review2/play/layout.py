"""Project-relative source and configurable paths for interaction frames and native tools."""
from pathlib import Path
import os
import shutil
import subprocess

SOURCE=Path(__file__).resolve().parent
ROOT=SOURCE.parents[2]
REPO=next((p for p in SOURCE.parents if (p/'settings.gradle.kts').is_file()),ROOT/'work/CuteDog')
IN_REPO=SOURCE.is_relative_to(REPO)
HERE=Path(os.environ.get('CUTEDOG_PLAY_OUTPUT',SOURCE)).resolve()
HERE.mkdir(parents=True,exist_ok=True)
ART=REPO/'art/live2d/poses'
RIG=SOURCE/'rig'
RENDER_HOME=REPO/'tools/cubism'
GAIT=Path(os.environ.get('CUTEDOG_GAIT_FRAMES',SOURCE.parent/'gait/frames')).resolve()
PERFORM=Path(os.environ.get('CUTEDOG_POSTURE_FRAMES',SOURCE.parent/'perform')).resolve()
INSTALL=Path(os.environ.get('CUTEDOG_CUBISM_APP',r'C:\Program Files\Live2D Cubism 5.3\app'))

def exporter():
    explicit=os.environ.get('CUTEDOG_MODEL_EXPORTER')
    path=Path(explicit) if explicit else ROOT/'work/ayatsuri/target/debug/examples/dog_project.exe'
    if not path.is_file():
        raise FileNotFoundError('Set CUTEDOG_MODEL_EXPORTER to the compiled dog_project example.')
    return path

def java_classes():
    classes=HERE/'.cubism-java';classes.mkdir(exist_ok=True)
    src=RENDER_HOME/'CoreBake.java';target=classes/'CoreBake.class'
    if not target.exists() or src.stat().st_mtime>target.stat().st_mtime:
        javac=os.environ.get('CUTEDOG_JAVAC') or shutil.which('javac')
        if not javac and os.environ.get('JAVA_HOME'):
            javac=str(Path(os.environ['JAVA_HOME'])/'bin/javac')
        if not javac and Path('C:/tools/jdk17/jdk-17.0.2/bin/javac.exe').is_file():
            javac='C:/tools/jdk17/jdk-17.0.2/bin/javac.exe'
        if not javac:raise FileNotFoundError('Set CUTEDOG_JAVAC or JAVA_HOME to JDK 17 or later.')
        subprocess.run([javac,'--release','17','-cp',str(INSTALL/'lib/Live2DCubismCore.jar'),
                        '-d',str(classes),str(src)],check=True)
    return classes
