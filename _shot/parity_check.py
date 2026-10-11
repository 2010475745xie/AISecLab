"""对照实验：把端上 L1PatternRules.kt 回退到 HEAD 版本，重跑两个失败测试类，
确认那 4 个失败是本轮改动引入的、还是既有的。跑完自动恢复。"""
import subprocess
import os
import sys

ENV = dict(os.environ)
ENV['JAVA_HOME'] = r'C:\jdk21'
ENV['GRADLE_USER_HOME'] = r'C:\Users\谢国健\.gradle'
G = r'D:\android\gradle-8.7\bin\gradle.bat'
CWD = r'C:\AISecLab'
PY = sys.executable
TARGET = 'app/src/main/java/com/xieguojian/aiseclab/core/judge/L1PatternRules.kt'


def run(*a):
    return subprocess.run(list(a), cwd=CWD, env=ENV, capture_output=True)


print('== 1) stash 端上 L1PatternRules.kt ==')
r = run('git', 'stash', 'push', '--', TARGET)
print('  exit', r.returncode, (r.stdout or b'').decode('utf-8', 'replace').strip())

print('== 2) 编译 debugUnitTest（旧版 L1）==')
r = run(G, ':app:compileDebugUnitTestKotlin', '--offline')
print('  compile exit', r.returncode)

print('== 3) 跑 LocalGuardJudgeTest + ReportDiffTest ==')
r = subprocess.run(
    [PY, os.path.join(CWD, 'tools', 'run-unit-tests.py'),
     'com.xieguojian.aiseclab.LocalGuardJudgeTest',
     'com.xieguojian.aiseclab.ReportDiffTest'],
    cwd=CWD, capture_output=True, text=True, encoding='utf-8', errors='replace')
print(r.stdout[-3000:])

print('== 4) 恢复工作区 ==')
r = run('git', 'stash', 'pop')
print('  exit', r.returncode, (r.stdout or b'').decode('utf-8', 'replace').strip())

print('== 5) 重新编译（新版 L1）==')
r = run(G, ':app:compileDebugUnitTestKotlin', '--offline')
print('  recompile exit', r.returncode)
