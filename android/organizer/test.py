"""Run every synthetic JVM regression for the Android organizer (no device).

Requires JDK 21 (or a compatible javac supporting --release 8). Does not import
personal backups or execute Android instrumentation. Build outputs stay ignored.
"""
import os
from pathlib import Path
import shutil
import subprocess


def main():
    base = Path(__file__).resolve().parent
    root = base.parents[1]
    home = os.environ.get('JAVA_HOME')
    javac = str(Path(home)/'bin/javac') if home else shutil.which('javac')
    java = str(Path(home)/'bin/java') if home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('JDK required; set JAVA_HOME')
    out = base/'build/jvm-tests'
    out.mkdir(parents=True, exist_ok=True)
    sources = sorted((base/'src/dev/local/oneuifolders/core').glob('*.java'))
    sources += sorted((base/'src/dev/local/oneuifolders/planning').glob('*.java'))
    tests = sorted((base/'tests').rglob('*Test.java'))
    if not sources or not tests:
        raise SystemExit('Java regression sources not found')
    subprocess.run([javac, '--release', '8', '-encoding', 'UTF-8', '-d', str(out),
                    *map(str, sources+tests)], check=True, timeout=120)
    for test in tests:
        classname = '.'.join(test.relative_to(base/'tests').with_suffix('').parts)
        print('TEST', classname, flush=True)
        subprocess.run([java, '-cp', str(out), classname, str(root/'examples/synthetic.exml')],
                       cwd=root, check=True, timeout=120)
    print('All synthetic JVM test mains passed:', len(tests), flush=True)


if __name__ == '__main__':
    main()
