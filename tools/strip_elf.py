#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
给 aarch64 的 .so 去掉调试信息（自己实现，不依赖 NDK 的 llvm-strip）。

为什么要有这个脚本：工程的 .so 是**预编译好直接放 jniLibs** 的（不是 CMake 编出来的），
AGP 不会替我们 strip —— 实测 libc++_shared.so 带符号 8.81MB，strip 后通常只剩 1MB 出头。
而 APK 里的 lib/ 是 STORED（不压缩），这份体积在「APK 大小 + 机身存储」上是双份的。

做法（只删、不改、不搬）：
  · 只删**不参与装载**的节：.debug_* / .gnu_debugdata / .symtab / .strtab；
    凡是带 SHF_ALLOC 的、或者落在任何 PT_* 段文件范围里的，一律不动；
  · 删掉字节区间后，把后面所有 section 的 sh_offset 与 program header 的 p_offset
    整体前移（不搬动任何保留内容，段内字节一字不动）；
  · 被删的 section header 保留条目、置成 SHT_NULL（改 e_shnum 会牵动 sh_link 等引用，不值得）。

用法：
  python tools/strip_elf.py lib.so            # 就地 strip（原文件备份为 lib.so.orig）
  python tools/strip_elf.py --check lib.so    # 只体检：能省多少、有没有不能删的
"""
import sys
import struct

SHF_ALLOC = 0x2
SHT_NULL = 0
SHT_NOBITS = 8
REMOVE_PREFIXES = ('.debug',)
REMOVE_NAMES = ('.symtab', '.strtab', '.gnu_debugdata', '.gdb_index')


class Elf:
    def __init__(self, data: bytes):
        self.data = data
        if data[:4] != b'\x7fELF' or data[4] != 2:
            raise ValueError('不是 ELF64：%r' % data[:6])
        if data[5] != 1:
            raise ValueError('只处理小端 ELF')
        (self.e_phoff, self.e_shoff) = struct.unpack_from('<QQ', data, 32)
        (self.e_ehsize, self.e_phentsize, self.e_phnum,
         self.e_shentsize, self.e_shnum, self.e_shstrndx) = struct.unpack_from('<HHHHHH', data, 52)
        self.phdrs = [self._phdr(i) for i in range(self.e_phnum)]
        self.shdrs = [self._shdr(i) for i in range(self.e_shnum)]
        shstr = self.shdrs[self.e_shstrndx]
        base = shstr['offset']
        self.names = []
        for sh in self.shdrs:
            end = self.data.index(b'\0', base + sh['name'])
            self.names.append(self.data[base + sh['name']:end].decode('utf-8', 'replace'))

    def _phdr(self, i):
        off = self.e_phoff + i * self.e_phentsize
        t, flags, p_off, vaddr, paddr, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', self.data, off)
        return dict(type=t, flags=flags, offset=p_off, vaddr=vaddr, filesz=filesz, memsz=memsz, align=align, _off=off)

    def _shdr(self, i):
        off = self.e_shoff + i * self.e_shentsize
        name, t, flags, addr, s_off, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', self.data, off)
        return dict(name=name, type=t, flags=flags, addr=addr, offset=s_off, size=size,
                    link=link, info=info, align=align, entsize=entsize, _off=off)

    def removable(self):
        """返回可删的 section 下标（按文件偏移排序）。"""
        out = []
        for i, sh in enumerate(self.shdrs):
            name = self.names[i]
            if sh['type'] in (SHT_NULL, SHT_NOBITS) or sh['size'] == 0:
                continue
            if sh['flags'] & SHF_ALLOC:
                continue
            if not (name.startswith(REMOVE_PREFIXES) or name in REMOVE_NAMES):
                continue
            lo, hi = sh['offset'], sh['offset'] + sh['size']
            # 落在任何段的文件范围里就别动（理论上不会，防一手）
            if any(p['offset'] < hi and lo < p['offset'] + p['filesz'] for p in self.phdrs):
                continue
            out.append(i)
        return sorted(out, key=lambda i: self.shdrs[i]['offset'])

    def strip(self) -> bytes:
        idx = self.removable()
        if not idx:
            return self.data
        ranges = [(self.shdrs[i]['offset'], self.shdrs[i]['offset'] + self.shdrs[i]['size']) for i in idx]

        def new_off(x: int) -> int:
            shift = 0
            for lo, hi in ranges:
                if hi <= x:
                    shift += hi - lo
            return x - shift

        # 1) 拼出删掉这些区间之后的文件体
        out = bytearray()
        pos = 0
        for lo, hi in ranges:
            out += self.data[pos:lo]
            pos = hi
        out += self.data[pos:]

        # 2) 段表里的文件偏移整体前移
        for p in self.phdrs:
            if p['filesz'] > 0:
                struct.pack_into('<Q', out, p['_off'] + 8, new_off(p['offset']))

        # 3) 节表里每个保留节的偏移前移；被删的节置成 SHT_NULL
        removed = set(idx)
        for i, sh in enumerate(self.shdrs):
            base = new_off(sh['_off'])
            if i in removed:
                struct.pack_into('<IIQQQQIIQQ', out, base, sh['name'], SHT_NULL, 0, 0, 0, 0, 0, 0, 0, 0)
            elif sh['size'] > 0:
                struct.pack_into('<Q', out, base + 24, new_off(sh['offset']))

        # 4) e_shoff 本身也要前移
        struct.pack_into('<Q', out, 40, new_off(self.e_shoff))
        return bytes(out)


def verify(orig: bytes, out: bytes) -> None:
    """自检：段内容一字未动、动态符号表完好、结构自洽。

    注意：ELF 头 + 程序头那一块本身也被第一个 PT_LOAD 罩着（p_offset=0），
    而 e_shoff 是**必须**改的，所以比较时跳过这块头部区域。
    """
    a, b = Elf(orig), Elf(out)
    assert a.e_phnum == b.e_phnum and a.e_shnum == b.e_shnum
    hdr_end = max(a.e_ehsize, a.e_phoff + a.e_phnum * a.e_phentsize)
    # 每个 PT_LOAD 的内容必须与原来完全相同（除头部区域）
    for pa, pb in zip(a.phdrs, b.phdrs):
        if pa['filesz'] == 0:
            continue
        assert pb['offset'] + pb['filesz'] <= len(out), '段越界'
        skip = max(0, hdr_end - pa['offset'])          # 段里落在头部区域的字节数
        if skip >= pa['filesz']:
            continue
        sa = pa['offset'] + skip
        sb = pb['offset'] + skip
        n = pa['filesz'] - skip
        assert orig[sa:sa + n] == out[sb:sb + n], '段内容被改动'
    # 装载用的动态表 / 符号表必须还在，且内容一致
    for name in ('.dynsym', '.dynstr', '.dynamic', '.text', '.rodata', '.data', '.plt', '.got'):
        if name in a.names:
            ia, ib = a.names.index(name), b.names.index(name)
            sa, sb = a.shdrs[ia], b.shdrs[ib]
            assert sb['size'] == sa['size'], '%s 大小变了' % name
            if sa['size']:
                assert orig[sa['offset']:sa['offset'] + sa['size']] == \
                    out[sb['offset']:sb['offset'] + sb['size']], '%s 内容变了' % name


def main(argv):
    check_only = '--check' in argv
    paths = [a for a in argv[1:] if not a.startswith('--')]
    if not paths:
        print(__doc__)
        return 2
    for path in paths:
        data = open(path, 'rb').read()
        e = Elf(data)
        idx = e.removable()
        saved = sum(e.shdrs[i]['size'] for i in idx)
        print('%s: %.2f MB -> 可省 %.2f MB (%d 节: %s)' % (
            path, len(data) / 1048576, saved / 1048576, len(idx),
            ', '.join(e.names[i] for i in idx[:6]) + ('…' if len(idx) > 6 else '')))
        if check_only or not idx:
            continue
        out = e.strip()
        verify(data, out)
        if not path.endswith('.orig'):
            open(path + '.orig', 'wb').write(data)
        open(path, 'wb').write(out)
        print('  已 strip: %.2f MB -> %.2f MB' % (len(data) / 1048576, len(out) / 1048576))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
