"""清 Kotlin 增量缓存 → 重编单测 → 跑 tools/run-unit-tests.py。

为什么必须清缓存：源码未改动时 Gradle 会跳过重编，复用旧分析结果，
测试跑的是陈旧 class（报错行号与源码对不上就是识别信号）。
"""
import os
import shutil
import subprocess
import sys

ENV = dict(os.environ)
ENV['JAVA_HOME'] = r'C:\jdk21'
ENV['GRADLE_USER_HOME'] = r'C:\Users\谢国健\.gradle'
GRADLE = r'D:\android\gradle-8.7\bin\gradle.bat'
CWD = r'C:\AISecLab'
PY = r'C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe'
LOG = r'C:\AISecLab\_shot\gradle_recompile.log'

for d in (os.path.join(CWD, 'app', 'build', 'kotlin', 'compileDebugUnitTestKotlin'),
          os.path.join(CWD, 'app', 'build', 'tmp', 'kotlin-classes', 'debugUnitTest')):
    if os.path.isdir(d):
        shutil.rmtree(d, ignore_errors=True)
        print('rm -r', d)

r0 = subprocess.run([GRADLE, '--stop'], cwd=CWD, env=ENV,
                    capture_output=True, text=True, timeout=180)
print('STOP exit=%s' % r0.returncode)

r = subprocess.run([GRADLE, ':app:compileDebugUnitTestKotlin', '--offline'],
                   cwd=CWD, env=ENV, capture_output=True, text=True, timeout=1500)
with open(LOG, 'w', encoding='utf-8') as f:
    f.write(r.stdout or '')
    f.write('\n---STDERR---\n')
    f.write(r.stderr or '')
print('COMPILE EXIT=%s' % r.returncode)
for line in (r.stdout or '').strip().splitlines()[-20:]:
    print('|', line)
for line in (r.stderr or '').strip().splitlines()[-20:]:
    print('E|', line)

if r.returncode != 0:
    sys.exit('编译失败，停止')

t = subprocess.run([PY, os.path.join(CWD, 'tools', 'run-unit-tests.py')],
                   cwd=CWD, env=ENV, capture_output=True, text=True,
                   encoding='utf-8', errors='replace', timeout=900)
print('TEST EXIT=%s' % t.returncode)
print(t.stdout or '')
if (t.stderr or '').strip():
    print('--- TEST STDERR ---')
    print(t.stderr)
