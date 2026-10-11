import subprocess
import os
import sys

ENV = dict(os.environ)
ENV['JAVA_HOME'] = r'C:\jdk21'
ENV['GRADLE_USER_HOME'] = r'C:\Users\谢国健\.gradle'
GRADLE = r'D:\android\gradle-8.7\bin\gradle.bat'
CWD = r'C:\AISecLab'
LOG = r'C:\AISecLab\_shot\gradle_compile.log'

r0 = subprocess.run([GRADLE, '--stop'], cwd=CWD, env=ENV,
                    capture_output=True, text=True, timeout=180)
print('STOP exit=%s' % r0.returncode)

r = subprocess.run([GRADLE, ':app:assembleDebug', '--offline'], cwd=CWD, env=ENV,
                   capture_output=True, text=True, timeout=1500)
with open(LOG, 'w', encoding='utf-8') as f:
    f.write(r.stdout or '')
    f.write('\n---STDERR---\n')
    f.write(r.stderr or '')
print('EXIT=%s' % r.returncode)
tail = (r.stdout or '').strip().splitlines()[-25:]
for line in tail:
    print('|', line)
err = (r.stderr or '').strip().splitlines()[-15:]
for line in err:
    print('E|', line)
