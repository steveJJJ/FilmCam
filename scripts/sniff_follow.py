#!/usr/bin/env python3
"""
强制 nRF Sniffer 跟随指定 BLE 设备抓包。

插件已打了本地增强：启动时读环境变量 NRF_FOLLOW_MAC 并调用 follow_device()，
绕开 Wireshark GUI 的 Device 下拉框（tshark CLI 不建 extcap 控制管道）。

用法：
  python scripts/sniff_follow.py a4:c1:38:3c:23:19 60 output.pcapng
"""
import os
import subprocess
import sys

TSHARK = r"E:\Wireshark\tshark.exe"
INTERFACE = "COM6-4.6"


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    mac, seconds, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    addr = [int(x, 16) for x in mac.split(":")]
    if len(addr) != 6:
        sys.exit("MAC 必须是 6 段十六进制，如 a4:c1:38:3c:23:19")

    env = os.environ.copy()
    env["NRF_FOLLOW_MAC"] = mac
    cmd = [TSHARK, "-i", INTERFACE, "-a", f"duration:{seconds}", "-q", "-w", out]
    print("following " + mac, flush=True)
    proc = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        proc.wait(timeout=seconds + 20)
    except subprocess.TimeoutExpired:
        proc.terminate()
    size = os.path.getsize(out) if os.path.exists(out) else 0
    print(f"saved {out} bytes={size}", flush=True)


if __name__ == "__main__":
    main()
