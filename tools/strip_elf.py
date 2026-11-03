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
  · **总删除量必须凑成 8 的倍数**：Android linker 的 ReadSectionHeaders() 会用
    CheckFileRange(e_shoff, shdr_size, alignof(Elf64_Shdr)) 校验节表位置，
    要求 `e_shoff % 8 == 0`。debug 段大小任意，直接前移会把 e_shoff 变成奇数，
    linker 直接拒绝 dlopen：
      "has invalid shdr offset/size: <off>/<size>" → UnsatisfiedLinkError
    （LIEF 解析宽松、看不出来，只有真机 linker 会报）。
    所以在最后一个待删区间末尾留 0~7 个填充字节，把前移量对齐到 8；
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

        # 前移量必须是 8 的倍数，否则 e_shoff 会失去 8 字节对齐，
        # 被 Android linker 的 CheckFileRange(..., alignof(Elf64_Shdr)) 判为非法节表。
        # 注意：决定 e_shoff 的只有「位于它之前的删除区间」，不是总删除量。
        # 所以在最后一个 hi <= e_shoff 的区间末尾留 0~7 字节填充（它们已不属于
        # 任何 section，留在文件里无害），把前移量调到使新 e_shoff 为 8 的倍数。
        shift_at = sum(hi - lo for lo, hi in ranges if hi <= self.e_shoff)
        r = (self.e_shoff - shift_at) % 8
        if r:
            keep = 8 - r
            for j in range(len(ranges) - 1, -1, -1):
                lo, hi = ranges[j]
                if hi <= self.e_shoff:
                    if hi - lo <= keep:
                        raise ValueError('最后一个待删区间太短，无法留出对齐填充')
                    ranges[j] = (lo, hi - keep)
                    break
            else:
                raise ValueError('e_shoff 之前没有可调的待删区间')

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


def verify_linker_layout(data: bytes) -> None:
    """按 Android bionic linker 的实际校验规则自检（LIEF / readelf 都太宽松）。

    ElfReader::ReadSectionHeaders():
        CheckFileRange(e_shoff, e_shnum * sizeof(Shdr), alignof(Elf64_Shdr))
    CheckFileRange(): offset > 0 && 范围在文件内 && (offset % alignment) == 0
    装载期 .dynsym / .dynamic 也会走同一套校验。
    """
    e = Elf(data)
    shdr_size = e.e_shnum * e.e_shentsize
    assert e.e_shoff > 0, 'e_shoff 为 0'
    assert e.e_shoff % 8 == 0, 'e_shoff=%d 未按 8 对齐，linker 会拒绝 dlopen' % e.e_shoff
    assert e.e_shoff + shdr_size <= len(data), 'section header 表越界'
    for name in ('.dynsym', '.dynstr', '.dynamic'):
        if name in e.names:
            sh = e.shdrs[e.names.index(name)]
            align = sh['align'] or 1
            assert sh['offset'] % align == 0, '%s 未按 %d 对齐' % (name, align)
            assert sh['offset'] + sh['size'] <= len(data), '%s 越界' % name
    for p in e.phdrs:
        if p['type'] == 1 and p['filesz']:  # PT_LOAD
            assert p['offset'] % 4096 == p['vaddr'] % 4096, 'PT_LOAD 未页对齐'


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
        verify_linker_layout(out)
        if not path.endswith('.orig'):
            open(path + '.orig', 'wb').write(data)
        open(path, 'wb').write(out)
        print('  已 strip: %.2f MB -> %.2f MB' % (len(data) / 1048576, len(out) / 1048576))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
