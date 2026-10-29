#!/usr/bin/env python3
"""生成带内嵌封面 + 内嵌歌词的测试文件，用来验证 MetaExtractor。

生成:
  work/fixture/flac_ok.flac   FLAC: VORBIS_COMMENT(LYRICS/TITLE/ARTIST/ALBUM) + PICTURE(3, png)
  work/fixture/id3_ok.mp3     ID3v2.3: TIT2/TPE1/TALB + APIC(png) + USLT
  work/fixture/flac_nolyric.flac  只有封面没有歌词
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "work", "fixture")

# 1x1 红色 PNG
def png_1x1(r=255, g=0, b=0):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    raw = b"\x00" + bytes([r, g, b])
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = png_1x1()
LYRICS = "[00:01.00]第一行歌词\n[00:05.50]第二行歌词\n[00:09.00]第三行歌词"
TITLE, ARTIST, ALBUM = "测试曲目", "测试歌手", "测试专辑"


def flac_block(block_type, data, last=False):
    head = bytes([(0x80 if last else 0) | block_type]) + len(data).to_bytes(3, "big")
    return head + data


def streaminfo():
    # min/max blocksize, min/max framesize, 20bit rate + 3bit ch-1 + 5bit bps-1 + 36bit total
    body = struct.pack(">HH", 4096, 4096) + b"\x00\x00\x00" + b"\x00\x00\x00"
    packed = (44100 << 44) | ((2 - 1) << 41) | ((16 - 1) << 36) | 0
    body += packed.to_bytes(8, "big")
    body += b"\x00" * 16  # md5
    return body


def vorbis_comment(comments):
    vendor = b"qmc-fixture"
    data = struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", len(comments))
    for c in comments:
        raw = c.encode("utf-8")
        data += struct.pack("<I", len(raw)) + raw
    return data


def flac_picture(img, mime=b"image/png", pic_type=3, desc=b""):
    data = struct.pack(">I", pic_type)
    data += struct.pack(">I", len(mime)) + mime
    data += struct.pack(">I", len(desc)) + desc
    data += struct.pack(">IIII", 1, 1, 24, 0)
    data += struct.pack(">I", len(img)) + img
    return data


def write_flac(path, with_lyrics=True, lyric_key="LYRICS"):
    comments = [f"TITLE={TITLE}", f"ARTIST={ARTIST}", f"ALBUM={ALBUM}"]
    if with_lyrics:
        comments.append(lyric_key + "=" + LYRICS)
    body = b"fLaC"
    body += flac_block(0, streaminfo())
    body += flac_block(4, vorbis_comment(comments))
    body += flac_block(6, flac_picture(PNG), last=True)
    with open(path, "wb") as f:
        f.write(body)
    return len(body)


def id3_frame(fid, payload, syncsafe=False):
    size = len(payload)
    if syncsafe:
        sz = bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F,
                    (size >> 7) & 0x7F, size & 0x7F])
    else:
        sz = size.to_bytes(4, "big")
    return fid + sz + b"\x00\x00" + payload


def text_frame(fid, text, enc=3, syncsafe=False):
    if enc == 3:
        payload = b"\x03" + text.encode("utf-8")
    else:
        payload = b"\x00" + text.encode("latin-1", "replace")
    return id3_frame(fid, payload, syncsafe)


def write_mp3(path, txxx=False):
    frames = b""
    frames += text_frame(b"TIT2", TITLE)
    frames += text_frame(b"TPE1", ARTIST)
    frames += text_frame(b"TALB", ALBUM)
    # APIC: enc + mime\0 + type + desc\0 + data
    apic = b"\x03" + b"image/png\x00" + bytes([3]) + b"\x00" + PNG
    frames += id3_frame(b"APIC", apic)
    if txxx:
        # TXXX: enc + desc\0 + value —— 很多标签软件把歌词写在这里
        frames += id3_frame(b"TXXX", b"\x03" + b"LYRICS" + b"\x00" + LYRICS.encode("utf-8"))
    else:
        # USLT: enc + lang(3) + desc\0 + text
        uslt = b"\x03" + b"chi" + b"\x00" + LYRICS.encode("utf-8")
        frames += id3_frame(b"USLT", uslt)

    size = len(frames)
    hdr = b"ID3\x03\x00\x00" + bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F,
                                      (size >> 7) & 0x7F, size & 0x7F])
    with open(path, "wb") as f:
        f.write(hdr + frames + b"\xff\xfb\x90\x00" + b"\x00" * 200)
    return len(hdr) + size


def main():
    os.makedirs(OUT, exist_ok=True)
    a = write_flac(os.path.join(OUT, "flac_ok.flac"), True)
    b = write_flac(os.path.join(OUT, "flac_nolyric.flac"), False)
    c = write_mp3(os.path.join(OUT, "id3_ok.mp3"))
    # 现实里的各种写法：非标准键名 / 中文键 / 内容像 LRC 的怪键
    d = write_flac(os.path.join(OUT, "flac_lrckey.flac"), True, "LRC")
    e = write_flac(os.path.join(OUT, "flac_unknownkey.flac"), True, "NOTES")
    f = write_mp3(os.path.join(OUT, "id3_txxx.mp3"), txxx=True)
    print("flac_ok.flac        %6d bytes" % a)
    print("flac_nolyric.flac   %6d bytes" % b)
    print("id3_ok.mp3          %6d bytes" % c)
    print("flac_lrckey.flac    %6d bytes" % d)
    print("flac_unknownkey.flac%6d bytes" % e)
    print("id3_txxx.mp3        %6d bytes" % f)
    print("输出目录:", OUT)


if __name__ == "__main__":
    main()
