import re, collections, sys

lines = open('.workbuddy/shiyu-v103.log', encoding='utf-8', errors='ignore').read().splitlines()
pat = re.compile(r'(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) I/ShiyuTts[^:]*: (HIT|MISS|WAIT|FAIL) (\w+) (\d+)ms')

def tosec(ts):
    h, m, rest = ts.split(' ')[1].split(':')
    return int(h) * 3600 + int(m) * 60 + float(rest)

ev = []
for l in lines:
    m = pat.search(l)
    if m:
        ev.append((m.group(1), tosec(m.group(1)), m.group(2), m.group(3), int(m.group(4))))

print('总条数', len(ev))
print('分布', dict(collections.Counter(e[2] for e in ev)))

miss = collections.Counter(e[3] for e in ev if e[2] == 'MISS')
dup = {k: v for k, v in miss.items() if v > 1}
print('MISS 唯一 key 数', len(miss), '| 重复 MISS:', dup if dup else '无（去重未退化）')

ms = [e[4] for e in ev if e[2] == 'MISS']
if ms:
    print('MISS 耗时 min/mean/max = %d / %.0f / %d ms (n=%d)' % (min(ms), sum(ms) / len(ms), max(ms), len(ms)))
print('HIT 耗时取值集合', sorted(set(e[4] for e in ev if e[2] == 'HIT')))

# 并行性：MISS 的"发起时刻"= 完成时刻 - 耗时，看是否成对同时发起
print('\n--- 配对并行分析（发起时刻）---')
starts = [(e[1] - e[4] / 1000.0, e[4], e[3]) for e in ev if e[2] == 'MISS']
starts.sort()
groups = collections.defaultdict(list)
for s, dur, key in starts:
    groups[round(s, 2)].append((dur, key))
paired = 0
total_saved = 0
for s in sorted(groups):
    g = groups[s]
    if len(g) > 1:
        paired += len(g)
        serial = sum(d for d, _ in g)
        parallel = max(d for d, _ in g)
        total_saved += serial - parallel
        print('  并发 %d 条 @%.2fs : %s -> 串行 %.0fms / 并行 %.0fms，省 %.0fms'
              % (len(g), s, [k for _, k in g], serial, parallel, serial - parallel))
print('并行发起 %d / %d 条 MISS，累计省 %.0fms' % (paired, len(starts), total_saved))
