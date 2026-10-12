"""重编（含打包验证）→ 跑 tools/run-unit-tests.py（本项目跑单测的唯一可用路径）。

## 为什么**不**用 `gradle :app:clean`（2026-10-12 实测推翻旧结论）
`:app:clean` 会逐个 unlink `app/build` 下的所有文件，而本沙箱**每一次 unlink 都要过一遍
safe-delete 守卫**（环境里注入了 `CODEBUDDY_SAFE_DELETE_ENABLED` / `BASH_FUNC_RM%%` 等）。
实测：`--stop` + `:app:clean :app:compileDebugUnitTestKotlin` 跑了 **7 分多钟只删掉 133 个文件**、
daemon 日志停在 SDK 警告之后再无输出 —— 等于卡死在 clean 里。旧笔记说「用 clean 绕开守卫」
是错的：守卫没被绕开，只是从"拦一次"变成"逐文件拦"。

替代做法：
- 源码**有改动**时 Gradle 本来就会重编，不需要 clean；
- 上一轮 clean 把 build 目录删了一半、增量状态可能不一致，但 Gradle 会**发现输出缺失并重跑**
  该任务，不会拿陈旧 class 当结果（"陈旧 class"的成因是源码改动没真落盘，见 MEMORY.md，
  现在每次改完都用 grep 验证落盘）。
- 真要彻底重建，就手动分批删（每批 < 50 项）或换个策略，别走 clean。

## 为什么顺带跑 assembleDebug
`compileDebugKotlin` 不打包资源，res 缺图标也照样绿；`:app:assembleDebug` 才是打包真验证。
两个任务一次 invoke，省一次 Gradle 启动。
"""
import os
import subprocess
import sys

ENV = dict(os.environ)
ENV['JAVA_HOME'] = r'C:\jdk21'
ENV['GRADLE_USER_HOME'] = r'C:\Users\谢国健\.gradle'
GRADLE = r'D:\android\gradle-8.7\bin\gradle.bat'
CWD = r'C:\AISecLab'
PY = r'C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe'
LOG = r'C:\AISecLab\_shot\gradle_recompile.log'


def say(*a):
    print(*a, flush=True)


def gradle(*tasks):
    return subprocess.run([GRADLE, *tasks], cwd=CWD, env=ENV,
                          capture_output=True, text=True, errors='replace',
                          timeout=2400)


r0 = gradle('--stop')
say('STOP exit=%s' % r0.returncode)

# 不再 clean：源码已改 → Gradle 自动增量重编；assembleDebug 顺带完成打包真验证。
r = gradle(':app:compileDebugUnitTestKotlin', ':app:assembleDebug', '--offline')
with open(LOG, 'w', encoding='utf-8') as f:
    f.write(r.stdout or '')
    f.write('\n---STDERR---\n')
    f.write(r.stderr or '')
say('GRADLE EXIT=%s' % r.returncode)
for line in (r.stdout or '').strip().splitlines()[-25:]:
    say('|', line)
for line in (r.stderr or '').strip().splitlines()[-25:]:
    say('E|', line)

if r.returncode != 0:
    sys.exit('编译/打包失败，停止')

t = subprocess.run([PY, os.path.join(CWD, 'tools', 'run-unit-tests.py')],
                   cwd=CWD, env=ENV, capture_output=True, text=True,
                   encoding='utf-8', errors='replace', timeout=900)
say('TEST EXIT=%s' % t.returncode)
say(t.stdout or '')
if (t.stderr or '').strip():
    say('--- TEST STDERR ---')
    say(t.stderr)
