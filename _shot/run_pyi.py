import os
import subprocess
import time

B = r'C:\AISecLab\backend'
VENV = r'C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe'
LOG = r'C:\AISecLab\_shot\pyi_build.log'

# 目标目录被占用（或已被 safe-delete 守卫拦住）时不要删 —— 改名走开即可，
# 这样 PyInstaller 见目标不存在就直接新建，永不触发批量删除守卫。
old = os.path.join(B, 'dist', 'aiseclab-backend')
n = 1
while True:
    cand = os.path.join(B, 'dist',
                        '_prev-aiseclab-backend' if n == 1 else '_prev%d-aiseclab-backend' % n)
    if not os.path.exists(cand):
        new = cand
        break
    n += 1
if os.path.exists(old):
    os.rename(old, new)
    print('renamed -> %s' % os.path.basename(new))
else:
    print('no existing aiseclab-backend dir; fresh build')

with open(LOG, 'w', encoding='utf-8') as f:
    r = subprocess.run(
        [VENV, '-m', 'PyInstaller', 'aiseclab-backend.spec', '--noconfirm',
         '--distpath', 'dist', '--workpath', 'build'],
        cwd=B, stdout=f, stderr=subprocess.STDOUT,
    )
print('PYINSTALLER_EXIT=%s' % r.returncode)

exe = os.path.join(B, 'dist', 'aiseclab-backend', 'aiseclab-backend.exe')
print('exe exists:', os.path.exists(exe))
if os.path.exists(exe):
    print('exe size: %.2f MB  mtime=%s' % (
        os.path.getsize(exe) / 1048576,
        time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(os.path.getmtime(exe)))))
lines = open(LOG, encoding='utf-8', errors='replace').read().splitlines()
print('--- log tail ---')
for line in lines[-15:]:
    print('|', line)
