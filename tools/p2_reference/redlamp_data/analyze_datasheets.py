#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从 redlamp 的 datasheet JSON 计算每支胶卷的物理签名，用于 P2 校准对照。"""
import json, glob, os

rows = []
for f in sorted(glob.glob('*.json')):
    if f == 'README.md': continue
    try:
        d = json.load(open(f, encoding='utf-8'))
    except Exception as e:
        print('SKIP', f, e); continue
    name = d.get('id', f)
    cc = d.get('characteristicCurves') or {}
    dm = cc.get('dMin') or {}
    gam = (d.get('derived') or {}).get('gamma') or {}
    sens = (d.get('derived') or {}).get('sensitivityPeakNm') or {}
    ss = d.get('spectralSensitivity') or {}
    # 光谱灵敏度曲线在各波长上的分离度：同一密度判据下三层所需曝光差
    sep = None
    try:
        wl = ss['wavelength']; r = ss['red']; g = ss['green']; b = ss['blue']
        # 550nm 处绿层灵敏度相对红/蓝层的领先量（log 单位）
        def at(cur, target):
            best = min(range(len(wl)), key=lambda i: abs(wl[i]-target))
            return cur[best]
        sep = at(g, 550) - max(at(r, 550), at(b, 550))
    except Exception:
        pass
    gv = {k: v for k, v in gam.items() if isinstance(v, (int, float))}
    def dmin(ch):
        v = dm.get(ch) if isinstance(dm, dict) else None
        return v if isinstance(v, (int, float)) else None
    dr, dg, db = dmin('red'), dmin('green'), dmin('blue')
    rows.append({
        'id': name, 'type': d.get('type'), 'EI': d.get('exposureIndex'),
        'dMin_R': dr, 'dMin_G': dg, 'dMin_B': db,
        'mask_RB': None if (dr is None or db is None) else db - dr,
        'gam_R': gam.get('red'), 'gam_G': gam.get('green'), 'gam_B': gam.get('blue'),
        'gamSpread': None if not gv else max(gv.values())-min(gv.values()),
        'gamB_m_R': None if not (gam.get('blue') and gam.get('red')) else gam['blue']-gam['red'],
        'peakR': sens.get('red'), 'peakG': sens.get('green'), 'peakB': sens.get('blue'),
        'gSepAt550': sep,
    })

hdr = ['id','type','EI','dMin_R','dMin_G','dMin_B','mask_RB','gam_R','gam_G','gam_B','gamSpread','gamB_m_R','peakR','peakG','peakB','gSepAt550']
print(','.join(hdr))
for r in rows:
    print(','.join('' if r[h] is None else (str(r[h]) if not isinstance(r[h], float) else '%.3f'%r[h]) for h in hdr))
