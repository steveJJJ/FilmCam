#!/usr/bin/env python3
"""
神牛 A2 · PacketLogger 抓包 → Godox 候选帧提取器

用途：把 iPhone PacketLogger 导出的 .pklg / .btsnoop，或 Wireshark 转的 .pcapng，
还原成「ATT 写帧」序列，并自动标注 Godox 协议特征帧：

  - ASCII 时间戳帧：形如 "1413,Sync" / "2113,Time" / "551772,Psub,0000" / "1234,Test"
    （A2 心跳已实测是 xxxxx,Time；Petri 逆向的老款 Godox 引闪器试闪帧是 {t},Test）
  - F0 系命令帧：F0A0..（蜂鸣/造型灯）、F0A1..（功率），带 CRC8Maxim 尾字节
  - A2 握手帧：b00dff / e106.. / e126.. / b00e1d / a00301fd（已知，标注但不算新发现）

抓包方法见 tmp/a2_packetlogger/抓取操作卡.md

用法：
    python scripts/parse_a2_frames.py tmp/a2_packetlogger/a2_godox_app.pklg
    python scripts/parse_a2_frames.py <file> --ascii-only     # 只看 ASCII 帧
    python scripts/parse_a2_frames.py <file> --new-only       # 排除已知握手帧
    python scripts/parse_a2_frames.py <file> --group-gap 5000 # 动作分组间隔(ms)

依赖：仅标准库。pcapng 需 tshark（自动探测 E:/Wireshark/tshark.exe）。
"""
import argparse
import os
import re
import struct
import subprocess
import sys
from collections import Counter

OPCODES = {0x12: "WRITE_REQ", 0x52: "WRITE_CMD", 0x1B: "NOTIFY", 0x0A: "READ_REQ", 0x0B: "READ_RSP"}
WRITE_OPS = (0x12, 0x52)

# A2 已知握手帧（抓包实测 11-17），列出用于排除
KNOWN_A2 = ("b00dff", "e106", "e126", "b00e1d", "a00301fd")


def read_btsnoop(buf):
    """yield (ts_us, dir, hci_type, payload)。兼容 pklg/btsnoop。"""
    if buf[:8] not in (b"btsnoop\0", b"btsnoop\x01"):
        return None
    version, datalink = struct.unpack(">II", buf[8:16])
    off, n = 16, len(buf)
    out = []
    while off + 24 <= n:
        orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIQ", buf[off:off + 24])
        off += 24
        data = buf[off:off + incl_len]
        off += incl_len
        direction = "S" if (flags & 1) == 0 else "R"
        if datalink in (1002, 201):  # H4：首字节 packet type
            if not data:
                continue
            hci_type, payload = data[0], data[1:]
        else:
            hci_type, payload = (flags >> 1) & 0x3, data
        out.append((ts, direction, hci_type, payload))
    return out


def iter_att(records):
    """拼 ACL→L2CAP，吐 (ts, dir, op, handle, value)。"""
    acc = {}
    for ts, direction, hci_type, payload in records:
        if hci_type != 0x02 or len(payload) < 4:
            continue
        hf, dlen = struct.unpack("<HH", payload[0:4])
        pb = (hf >> 12) & 0x3
        body = payload[4:4 + dlen]
        if pb in (0x0, 0x2):
            acc[direction] = bytearray(body)
        elif direction in acc:
            acc[direction] += body
        else:
            continue
        cur = acc[direction]
        while len(cur) >= 4:
            l2len, cid = struct.unpack("<HH", cur[0:4])
            if len(cur) < 4 + l2len:
                break
            frame = bytes(cur[4:4 + l2len])
            acc[direction] = bytearray(cur[4 + l2len:])
            if cid != 0x0004 or not frame:
                continue
            op = frame[0]
            if op in OPCODES and len(frame) >= 3:
                yield ts, direction, op, struct.unpack("<H", frame[1:3])[0], frame[3:]


def to_pcapng(path):
    """用 tshark 把 pklg/pcapng 统一转成 pcapng，再用 -T fields 直接吐 ATT。"""
    candidates = [r"E:/Wireshark/tshark.exe", "tshark"]
    tshark = next((c for c in candidates if c == "tshark" or os.path.exists(c)), None)
    if not tshark:
        sys.exit("需要 tshark 解析该格式。请安装 Wireshark 或先把文件另存为 btsnoop。")
    cmd = [tshark, "-r", path, "-Y", "btatt",
           "-T", "fields", "-e", "frame.time_epoch", "-e", "btatt.opcode",
           "-e", "btatt.handle", "-e", "btatt.value"]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        sys.exit(f"tshark 解析失败：{r.stderr[:300]}")
    out = []
    for line in r.stdout.splitlines():
        f = line.split("\t")
        if len(f) < 4 or not f[1]:
            continue
        try:
            ts = int(float(f[0]) * 1_000_000)
            op = int(f[1], 16)
            handle = int(f[2], 16) if f[2] else 0
            value = bytes.fromhex(f[3].replace(":", "")) if f[3] else b""
            out.append((ts, "S", op, handle, value))
        except ValueError:
            continue
    return out


def classify(value):
    """返回 (类别, 可读文本)。"""
    h = value.hex()
    try:
        s = value.decode("ascii")
        if all(32 <= c < 127 for c in value):
            if re.match(r"^\d+,(Sync|Time|Test|Psub|P|Set|Get)", s):
                return "ASCII-时间戳帧", s
            if "," in s:
                return "ASCII-其他", s
    except UnicodeDecodeError:
        pass
    if h.startswith("f0a0"):
        return "F0A0-蜂鸣/造型灯", h
    if h.startswith("f0a1"):
        return "F0A1-功率", h
    if any(h.startswith(k) for k in KNOWN_A2):
        return "已知A2握手", h
    return "其他", h


def hexs(b, limit=48):
    h = " ".join(f"{x:02X}" for x in b[:limit])
    return h + (" …" if len(b) > limit else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--ascii-only", action="store_true", help="只看 ASCII 时间戳帧")
    ap.add_argument("--new-only", action="store_true", help="排除已知 A2 握手帧")
    ap.add_argument("--group-gap", type=int, default=5000, help="动作分组间隔 ms（默认 5000）")
    ap.add_argument("--limit", type=int, default=300)
    args = ap.parse_args()

    with open(args.log, "rb") as f:
        buf = f.read()

    records = read_btsnoop(buf)
    if records is not None:
        packets = list(iter_att(records))
    else:
        packets = to_pcapng(args.log)

    if not packets:
        sys.exit("没解析到 ATT 包。确认抓的是 iOS Trace 且手机蓝牙日志描述文件已启用。")

    writes = [(ts, d, op, h, v) for ts, d, op, h, v in packets if op in WRITE_OPS]
    print(f"ATT 包 {len(packets)} 个，其中写帧 {len(writes)} 个")
    cnt = Counter(h for _, _, _, h, _ in writes)
    print("写帧 handle 分布：" + ", ".join(f"0x{h:04X}×{c}" for h, c in cnt.most_common(10)))

    printed, prev_ts, group = 0, None, 0
    for ts, direction, op, handle, value in writes:
        kind, readable = classify(value)
        if args.ascii_only and not kind.startswith("ASCII"):
            continue
        if args.new_only and kind == "已知A2握手":
            continue
        if prev_ts is None or (ts - prev_ts) / 1000.0 > args.group_gap:
            group += 1
            print(f"\n--- 动作组 {group} ---")
        prev_ts = ts
        flag = " <<< 候选触发帧" if kind in ("ASCII-时间戳帧", "F0A1-功率", "F0A0-蜂鸣/造型灯") else ""
        print(f"  h=0x{handle:04X} {OPCODES.get(op, hex(op)):<10} [{kind}] {readable}{flag}")
        if kind not in ("ASCII-时间戳帧", "F0A1-功率", "F0A0-蜂鸣/造型灯", "已知A2握手"):
            print(f"      hex: {hexs(value)}")
        printed += 1
        if printed >= args.limit:
            print(f"\n（到 {args.limit} 条上限）")
            break
    if not printed:
        print("过滤后无匹配。试试去掉 --ascii-only / --new-only。")


if __name__ == "__main__":
    try:
        main()
    except FileNotFoundError as e:
        sys.exit(f"找不到文件：{e.filename}")
