#!/usr/bin/env python3
"""Checks for tools/apk_channel.py.

Creates a throwaway keystore under the system temp dir, signs a tiny APK with
apksigner, and stamps channels onto it. Also covers a synthetic signing block
whose padding cannot absorb the channel pair.

    python3 tools/apk_channel_test.py

Optional: CHANNEL_SAMPLE_APK=/path/to/already-signed.apk runs the same checks
against that file (for example a GitHub Release APK). The test never writes
the sample back and never uses the project keystore.
"""

from __future__ import annotations

import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import apk_channel as channel  # noqa: E402

CHANNEL_ID = channel.CHANNEL_ID
PADDING_ID = channel.PADDING_ID
V2_ID = 0x7109871A


def _utf8_pool_string(text: str) -> bytes:
    raw = text.encode("utf-8")

    def length(count: int) -> bytes:
        if count > 0x7F:
            return bytes([(count >> 8) | 0x80, count & 0xFF])
        return bytes([count])

    return length(len(text)) + length(len(raw)) + raw + b"\x00"


def _string_pool(strings: list[str]) -> bytes:
    encoded = [_utf8_pool_string(item) for item in strings]
    offsets: list[int] = []
    data = b""
    for item in encoded:
        offsets.append(len(data))
        data += item
    strings_start = 28 + 4 * len(strings)
    chunk_size = strings_start + len(data)
    chunk_size += (4 - (chunk_size % 4)) % 4
    header = struct.pack(
        "<HHIIIIII",
        0x0001,
        0x001C,
        chunk_size,
        len(strings),
        0,
        0x100,
        strings_start,
        0,
    )
    body = header + b"".join(struct.pack("<I", offset) for offset in offsets) + data
    return body + b"\x00" * (chunk_size - len(body))


def _xml_node(chunk_type: int, ext: bytes) -> bytes:
    size = 16 + len(ext)
    return struct.pack("<HHIII", chunk_type, 16, size, 1, 0xFFFFFFFF) + ext


def _start_element(name: int, namespace: int, attrs: list[bytes]) -> bytes:
    # attributeStart is relative to the bytes after the 16-byte node header.
    ext = struct.pack("<IIHHHHHH", namespace, name, 20, 20, len(attrs), 0, 0, 0)
    return _xml_node(0x0102, ext + b"".join(attrs))


def _attr(namespace: int, name: int, raw: int, data_type: int, data: int) -> bytes:
    return struct.pack("<IIIHBBI", namespace, name, raw, 8, 0, data_type, data)


def minimal_manifest() -> bytes:
    """Binary AndroidManifest with minSdkVersion 26. apksigner verify requires one."""
    strings = [
        "manifest",
        "http://schemas.android.com/apk/res/android",
        "android",
        "package",
        "com.example.channel",
        "uses-sdk",
        "minSdkVersion",
        "26",
    ]
    none = 0xFFFFFFFF
    chunks = [
        _string_pool(strings),
        struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(strings))
        + b"".join(struct.pack("<I", item) for item in (0, 0, 0, 0, 0, 0, 0x0101020C, 0)),
        _xml_node(0x0100, struct.pack("<II", 2, 1)),
        _start_element(0, none, [_attr(none, 3, 4, 0x03, 4)]),
        _start_element(5, none, [_attr(1, 6, 7, 0x10, 26)]),
        _xml_node(0x0103, struct.pack("<II", none, 5)),
        _xml_node(0x0103, struct.pack("<II", none, 0)),
        _xml_node(0x0101, struct.pack("<II", 2, 1)),
    ]
    body = b"".join(chunks)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def _eocd_and_cd(data: bytes) -> tuple[int, int]:
    eocd = channel.find_eocd(data)
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    return eocd, cd_offset


def _zip_with_block(pairs: list[tuple[int, bytes]], *, pad: bool) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("readme.txt", "hello channel")
    raw = buffer.getvalue()
    eocd, cd_offset = _eocd_and_cd(raw)
    block = channel._build_block(pairs, pad=pad, previous_total=None)
    new_cd = cd_offset + len(block)
    eocd_bytes = bytearray(raw[eocd:])
    struct.pack_into("<I", eocd_bytes, 16, new_cd)
    return raw[:cd_offset] + block + raw[cd_offset:eocd] + bytes(eocd_bytes)


class TransformTests(unittest.TestCase):
    def test_rejects_bad_names(self) -> None:
        for name in ("", "GitHub", "xposed-repo", "a" * 33, "has space", "cool.apk", "中文"):
            with self.assertRaises(channel.ChannelError):
                channel.channel_value(name)

    def test_rejects_apk_without_signing_block(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("readme.txt", "unsigned")
        with self.assertRaises(channel.ChannelError):
            channel.transform(buffer.getvalue(), "github")

    def test_readback_and_payload_when_padding_absorbs_channel(self) -> None:
        original = _zip_with_block([(V2_ID, b"signature-bytes")], pad=True)
        stamped = channel.transform(original, "github")
        channel.assert_preserved(original, stamped, "github")
        self.assertEqual(channel.read_channel(stamped), "github")
        self.assertIsNone(channel.read_channel(original))
        before = _eocd_and_cd(original)
        after = _eocd_and_cd(stamped)
        self.assertEqual(before, after)
        self.assertEqual(original[before[0] :], stamped[after[0] :])

    def test_grows_block_by_one_page_when_padding_cannot_absorb(self) -> None:
        # 8 zero bytes of padding cannot absorb the 32-byte github pair.
        # The fixture block is page-aligned, so the writer grows it by exactly one page.
        signature = b"S"
        padding = b"\x00" * 8
        while True:
            body = channel._encode_pair(V2_ID, signature) + channel._encode_pair(
                PADDING_ID, padding
            )
            if (len(body) + 32) % channel.PAGE == 0:
                break
            signature += b"S"
        original = _zip_with_block([(V2_ID, signature), (PADDING_ID, padding)], pad=False)
        _eocd, before_cd, before_start = channel.locate(original)
        self.assertEqual((before_cd - before_start) % channel.PAGE, 0)

        stamped = channel.transform(original, "github")
        channel.assert_preserved(original, stamped, "github")
        _eocd, after_cd, after_start = channel.locate(stamped)
        self.assertEqual((after_cd - after_start) - (before_cd - before_start), channel.PAGE)
        pairs = channel.parse_pairs(stamped, after_start, after_cd)
        self.assertEqual([pair_id for pair_id, _ in pairs], [V2_ID, CHANNEL_ID, PADDING_ID])
        self.assertEqual(pairs[0][1], signature)

    def test_restamp_replaces_channel(self) -> None:
        original = _zip_with_block([(V2_ID, b"signature-bytes"), (0x504B4453, b"deps")], pad=True)
        github = channel.transform(original, "github")
        xposed = channel.transform(github, "xposed")
        channel.assert_preserved(original, xposed, "xposed")
        self.assertEqual(channel.read_channel(xposed), "xposed")
        self.assertEqual(channel.read_channel(channel.transform(xposed, "xposed")), "xposed")
        self.assertEqual(
            channel.transform(xposed, "xposed"),
            channel.transform(github, "xposed"),
        )


class SignedApkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.apksigner = channel.find_apksigner()
        except channel.ChannelError as exc:
            raise unittest.SkipTest(str(exc)) from exc
        cls._tmpdir = tempfile.TemporaryDirectory(prefix="apk-channel-")
        root = Path(cls._tmpdir.name)
        keystore = root / "throwaway.jks"
        subprocess.run(
            [
                "keytool",
                "-genkeypair",
                "-keystore",
                str(keystore),
                "-storepass",
                "secret",
                "-keypass",
                "secret",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=Channel Test",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        unsigned = root / "unsigned.apk"
        with zipfile.ZipFile(unsigned, "w") as archive:
            archive.writestr("AndroidManifest.xml", minimal_manifest())
            archive.writestr("readme.txt", "signed channel fixture")
        cls.signed = root / "signed.apk"
        subprocess.run(
            [
                str(cls.apksigner),
                "sign",
                "--ks",
                str(keystore),
                "--ks-pass",
                "pass:secret",
                "--key-pass",
                "pass:secret",
                "--ks-key-alias",
                "test",
                "--v2-signing-enabled",
                "true",
                "--v3-signing-enabled",
                "true",
                "--out",
                str(cls.signed),
                str(unsigned),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        if hasattr(cls, "_tmpdir"):
            cls._tmpdir.cleanup()

    def test_stamp_github_then_xposed(self) -> None:
        root = Path(self._tmpdir.name)
        github_apk = root / "github.apk"
        xposed_apk = root / "xposed.apk"
        script = Path(__file__).resolve().parent / "apk_channel.py"
        completed = subprocess.run(
            [sys.executable, str(script), "stamp", str(self.signed), str(github_apk), "github"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("channel=github", completed.stdout)
        self.assertRegex(completed.stdout, r"sha256=[0-9a-f]{64}")
        read_back = subprocess.run(
            [sys.executable, str(script), "read", str(github_apk)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(read_back.returncode, 0, read_back.stderr)
        self.assertEqual(read_back.stdout.strip(), "github")
        self._assert_verifies(github_apk)
        self._assert_same_certs(self.signed, github_apk)
        channel.stamp(github_apk, xposed_apk, "xposed")
        self.assertEqual(channel.read_channel(xposed_apk.read_bytes()), "xposed")
        channel.assert_preserved(self.signed.read_bytes(), xposed_apk.read_bytes(), "xposed")
        self._assert_verifies(xposed_apk)
        self._assert_same_certs(self.signed, xposed_apk)
        again = root / "github-again.apk"
        channel.stamp(github_apk, again, "github")
        self.assertEqual(github_apk.read_bytes(), again.read_bytes())

    def test_sample_apk_when_configured(self) -> None:
        sample = os.environ.get("CHANNEL_SAMPLE_APK", "").strip()
        if not sample:
            self.skipTest("CHANNEL_SAMPLE_APK not set")
        source = Path(sample)
        original = source.read_bytes()
        self.assertIsNone(channel.read_channel(original))
        with tempfile.TemporaryDirectory(prefix="apk-channel-sample-") as tmp:
            root = Path(tmp)
            github_apk = root / "github.apk"
            xposed_apk = root / "xposed.apk"
            channel.stamp(source, github_apk, "github")
            channel.stamp(github_apk, xposed_apk, "xposed")
            self.assertEqual(channel.read_channel(xposed_apk.read_bytes()), "xposed")
            channel.assert_preserved(original, xposed_apk.read_bytes(), "xposed")
            self._assert_verifies(xposed_apk)
            self._assert_same_certs(source, xposed_apk)
            before_eocd, before_cd, before_start = channel.locate(original)
            after_eocd, after_cd, after_start = channel.locate(xposed_apk.read_bytes())
            # Official release blocks have enough verity padding to absorb both names.
            if (before_cd - before_start) % channel.PAGE == 0:
                self.assertEqual(before_cd, after_cd)
                self.assertEqual(before_start, after_start)
                self.assertEqual(original[before_eocd:], xposed_apk.read_bytes()[after_eocd:])

    def _assert_verifies(self, apk: Path) -> None:
        result = subprocess.run(
            [str(self.apksigner), "verify", "--verbose", str(apk)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        text = result.stdout
        self.assertIn("Verifies", text)
        self.assertTrue(
            "v2 scheme (APK Signature Scheme v2): true" in text
            or "v3 scheme (APK Signature Scheme v3): true" in text,
            text,
        )

    def _assert_same_certs(self, before: Path, after: Path) -> None:
        left = subprocess.run(
            [str(self.apksigner), "verify", "--print-certs", str(before)],
            check=True,
            capture_output=True,
            text=True,
        )
        right = subprocess.run(
            [str(self.apksigner), "verify", "--print-certs", str(after)],
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(left.stdout, right.stdout)


if __name__ == "__main__":
    unittest.main()
