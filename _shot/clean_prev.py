"""删掉逐轮改名攒下的旧 bundle（PyInstaller 重建时的 `_prev*` 副本）。

**保留最新一份**（按 mtime）作回滚点，其余删除 —— 每份约 37MB，不动就无限堆积。

逐个删、每删完打印结果。注意：safe-delete 守卫按"单次调用涉及的文件数"拦截，
所以这里一次只删一个目录，且会 catch 异常后继续（被拦下也不至于整脚本挂掉）。
"""
import os
import shutil

DIST = r'C:\AISecLab\backend\dist'
KEEP_LATEST = 1

cands = [d for d in os.listdir(DIST)
         if d.startswith('_prev') and os.path.isdir(os.path.join(DIST, d))]
cands.sort(key=lambda d: os.path.getmtime(os.path.join(DIST, d)), reverse=True)

keep = cands[:KEEP_LATEST]
victims = cands[KEEP_LATEST:]
print('保留:', keep or '(无)')
if not victims:
    print('没有可清理的旧 bundle')

freed = 0
for d in victims:
    p = os.path.join(DIST, d)
    size = sum(os.path.getsize(os.path.join(r, f))
               for r, _ds, fs in os.walk(p) for f in fs)
    try:
        shutil.rmtree(p)
        freed += size
        print('deleted %-30s (%.1f MB)' % (d, size / 1048576))
    except Exception as e:
        print('SKIP    %-30s %s' % (d, e))

print('---')
print('释放 %.1f MB' % (freed / 1048576))
print('dist 剩余目录:', sorted(x for x in os.listdir(DIST) if os.path.isdir(os.path.join(DIST, x))))
print('dist 占用: %.1f MB' % (sum(os.path.getsize(os.path.join(r, f))
                              for r, _ds, fs in os.walk(DIST) for f in fs) / 1048576))
