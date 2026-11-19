#!/usr/bin/env python3
"""
btsnoop_hci.log → ATT 写/通知 提取器（找「第三方 App 是怎么点灯」的私有协议用）。

为什么需要它：神牛 A2 这类灯只认厂牌 App 的私有帧，靠猜通用两字节命令永远点不着。
唯一可靠路径是把官方 App 点一次灯的 BLE 报文原样抓下来，再照着在 Relic Cam 的
「手动帧」里重放。Android 的 HCI snoop（开发者选项 → 启用蓝牙 HCI 监听日志）
记录的就是这条链路上的每个 ACL 包，本脚本把它还原成「特征值 + 字节」序列。

用法：
    python scripts/parse_btsnoop_att.py path/to/btsnoop_hci.log
    python scripts/parse_btsnoop_att.py btsnoop_hci.log --handle 0x0021 --group-gap 400
    python scripts/parse_btsnoop_att.py btsnoop_hci.log --stats

抓日志：
    1) 开发者选项 → 打开「启用蓝牙 HCI 监听日志」
    2) 打开官方 App，点亮 / 试闪一次（越干净越好，别的 BLE 设备先别用）
    3) adb bugreport bugreport.zip  → 解压出 FS/data/misc/bluetooth/logs/btsnoop_hci.log
       （或 adb pull /sdcard/btsnoop_hci.log，部分 ROM 仍写这里）
"""
import argparse
import struct
import sys
from collections import Counter, OrderedDict

OPCODES = {
    0x12: "WRITE_REQ",
    0x52: "WRITE_CMD",
    0x1B: "NOTIFY",
    0x1D: "INDICATE",
    0x0A: "READ_REQ",
    0x0B: "READ_RSP",
    0x16: "PREP_WRITE",
    0x18: "EXEC_WRITE",
}
WRITE_OPS = (0x12, 0x52)
VALUE_OPS = (0x12, 0x52, 0x1B, 0x1D)


def read_records(buf):
    """yield (ts_us, direction, hci_type, payload)"""
    if not buf[:8] == b"btsnoop\0":
        raise SystemExit("不是 btsnoop 文件（头部标识不对）")
    version, datalink = struct.unpack(">II", buf[8:16])
    off = 16
    n = len(buf)
    while off + 24 <= n:
        orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIQ", buf[off:off + 24])
        off += 24
        data = buf[off:off + incl_len]
        off += incl_len
        direction = "S" if (flags & 1) == 0 else "R"  # S=手机发给灯, R=灯回给手机
        if datalink == 1002:  # HCI UART (H4)：首字节是 packet type
            if not data:
                continue
            hci_type = data[0]
            payload = data[1:]
        else:  # 1001 HCI 无封装：类型在 flags 的 bit1..2
            hci_type = (flags >> 1) & 0x3
            payload = data
        yield ts, direction, hci_type, payload


def iter_att(buf):
    """把 ACL 分片拼回 L2CAP，再吐 ATT 包： (ts, dir, opcode, handle, value)"""
    acc = {}  # direction -> bytearray
    for ts, direction, hci_type, payload in read_records(buf):
        if hci_type != 0x02 or len(payload) < 4:  # 只要 ACL
            continue
        hf, dlen = struct.unpack("<HH", payload[0:4])
        pb = (hf >> 12) & 0x3
        body = payload[4:4 + dlen]
        if pb in (0x0, 0x2):  # 起始分片（auto-flushable / 非 auto-flushable 都算）
            acc[direction] = bytearray(body)
        else:  # 续片
            if direction in acc:
                acc[direction] += body
            else:
                continue
        cur = acc[direction]
        if len(cur) < 4:
            continue
        l2len, cid = struct.unpack("<HH", cur[0:4])
        if len(cur) < 4 + l2len:
            continue  # 还没收全，等下一片
        frame = bytes(cur[4:4 + l2len])
        acc[direction] = bytearray(cur[4 + l2len:]) if len(cur) > 4 + l2len else bytearray()
        if cid != 0x0004 or not frame:
            continue  # 不是 ATT
        op = frame[0]
        if op not in VALUE_OPS or len(frame) < 3:
            continue
        handle = struct.unpack("<H", frame[1:3])[0]
        yield ts, direction, op, handle, frame[3:]


def hexs(b, limit=64):
    h = " ".join(f"{x:02X}" for x in b[:limit])
    return h + (" …" if len(b) > limit else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--handle", help="只看某个 handle，如 0x0021 / 33")
    ap.add_argument("--writes-only", action="store_true", help="只看手机写出去的包")
    ap.add_argument("--group-gap", type=int, default=400,
                    help="相邻写包间隔 ≤ 该毫秒数视为同一次操作（默认 400ms）")
    ap.add_argument("--stats", action="store_true", help="只打各 handle 的包数统计")
    ap.add_argument("--limit", type=int, default=400, help="最多打印多少包")
    args = ap.parse_args()

    with open(args.log, "rb") as f:
        buf = f.read()

    want = None
    if args.handle:
        want = int(args.handle, 0)

    packets = []
    for ts, direction, op, handle, value in iter_att(buf):
        packets.append((ts, direction, op, handle, value))

    if not packets:
        print("没解析到 ATT 写/通知包。可能这份日志只抓了广播，或者灯的通信走的是加密后的分支。")
        return

    if args.stats:
        print(f"ATT 值包共 {len(packets)} 个")
        cnt = Counter((h, o) for _, _, o, h, _ in packets)
        for (h, o), c in sorted(cnt.items(), key=lambda x: -x[1]):
            dirs = Counter(d for _, d, oo, hh, _ in packets if hh == h and oo == o)
            print(f"  handle 0x{h:04X}  {OPCODES.get(o, hex(o)):<10} {c:5d}   "
                  f"(S={dirs['S']} R={dirs['R']})")
        return

    printed = 0
    prev_ts = None
    group_no = 0
    for ts, direction, op, handle, value in packets:
        if want is not None and handle != want:
            continue
        if args.writes_only and op not in WRITE_OPS:
            continue
        if prev_ts is None or (ts - prev_ts) / 1000.0 > args.group_gap:
            group_no += 1
            print(f"\n--- 组 {group_no} ---")
        prev_ts = ts
        arrow = "手机→灯" if direction == "S" else "灯→手机"
        name = OPCODES.get(op, hex(op))
        print(f"  {arrow}  h=0x{handle:04X}  {name:<10} len={len(value):<3} {hexs(value)}")
        printed += 1
        if printed >= args.limit:
            print(f"\n（已到 {args.limit} 条上限，用 --limit 调大）")
            break

    if not printed:
        print("过滤后没有匹配的包，去掉 --handle / --writes-only 再试。")


if __name__ == "__main__":
    try:
        main()
    except FileNotFoundError as e:
        sys.exit(f"找不到文件：{e.filename}")
