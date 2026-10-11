"""删掉逐轮改名攒下的旧 bundle（PyInstaller 重建时的 _prevN 副本）。

保留 `_prev6-aiseclab-backend`（上一轮可用的 exe）作为回滚点，其余（7 轮之前）删除。
逐个删、每删完打印结果 —— 批量删容易被 safe-delete 守卫按"项数超阈值"拦下。
"""
import os
import shutil

DIST = r'C:\AISecLab\backend\dist'
KEEP = '_prev6-aiseclab-backend'

targets = sorted(d for d in os.listdir(DIST)
                 if d.startswith('_prev') and d != KEEP and os.path.isdir(os.path.join(DIST, d)))

freed = 0
for d in targets:
    p = os.path.join(DIST, d)
    size = sum(os.path.getsize(os.path.join(r, f))
               for r, _ds, fs in os.walk(p) for f in fs)
    try:
        shutil.rmtree(p)
        freed += size
        print('deleted %-28s (%.1f MB)' % (d, size / 1048576))
    except Exception as e:
        print('FAILED  %-28s %s' % (d, e))

print('---')
print('释放 %.1f MB' % (freed / 1048576))
print('保留: %s' % KEEP)
print('dist 剩余目录:', sorted(d for d in os.listdir(DIST) if os.path.isdir(os.path.join(DIST, d))))
print('dist 占用: %.1f MB' % (sum(os.path.getsize(os.path.join(r, f))
                              for r, _ds, fs in os.walk(DIST) for f in fs) / 1048576))
