#!/usr/bin/env python3
"""Stamp a distribution channel into an APK Signing Block. No re-signing.

On-disk contract for the reader (do not change without that side):

  ID    0x7A616672 ("zafr")
  value ASCII JSON {"channel":"<name>"} with no whitespace
  name  [a-z0-9_]{1,32}

v2/v3/v3.1 signature pairs and every other pair are copied unchanged.
The verity padding pair (0x42726577), when present, is resized with zero
bytes so the signing block stays a multiple of 4096. When the new channel
fits in that padding, the block length and the ZIP central-directory
offset stay the same.

Usage:
  python3 tools/apk_channel.py stamp IN.apk OUT.apk CHANNEL
  python3 tools/apk_channel.py read APK
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from io import BytesIO
from pathlib import Path

MAGIC = b"APK Sig Block 42"
CHANNEL_ID = 0x7A616672
PADDING_ID = 0x42726577
PAGE = 4096
CHANNEL_RE = re.compile(r"[a-z0-9_]{1,32}")
ZIP64_LOCATOR = b"PK\x06\x07"
EOCD_SIGNATURE = b"PK\x05\x06"


class ChannelError(Exception):
    """The APK cannot be stamped or the stamp broke an invariant."""


def channel_value(name: str) -> bytes:
    if not isinstance(name, str) or CHANNEL_RE.fullmatch(name) is None:
        raise ChannelError(
            f"channel name must match [a-z0-9_]{{1,32}}, got {name!r}"
        )
    return json.dumps({"channel": name}, separators=(",", ":")).encode("ascii")


def find_eocd(data: bytes) -> int:
    if len(data) < 22:
        raise ChannelError("file is too small to be a ZIP")
    window_start = max(0, len(data) - (65535 + 22))
    window = data[window_start:]
    for index in range(len(window) - 21, -1, -1):
        if window[index : index + 4] != EOCD_SIGNATURE:
            continue
        comment_len = struct.unpack_from("<H", window, index + 20)[0]
        if index + 22 + comment_len == len(window):
            return window_start + index
    raise ChannelError("ZIP end of central directory not found")


def locate(data: bytes) -> tuple[int, int, int]:
    """Return (eocd_offset, central_directory_offset, signing_block_start)."""
    eocd = find_eocd(data)
    if eocd >= 20 and data[eocd - 20 : eocd - 16] == ZIP64_LOCATOR:
        raise ChannelError("ZIP64 APKs are not supported")
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset == 0xFFFFFFFF:
        raise ChannelError("ZIP64 APKs are not supported")
    if cd_offset > eocd or cd_offset < 32:
        raise ChannelError("central directory offset is out of range")
    if data[cd_offset - 16 : cd_offset] != MAGIC:
        raise ChannelError("no APK Signing Block; sign the APK with v2 or v3 first")
    size_tail = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    block_start = cd_offset - (size_tail + 8)
    if block_start < 0 or block_start + 8 > cd_offset:
        raise ChannelError("APK Signing Block size is out of range")
    size_head = struct.unpack_from("<Q", data, block_start)[0]
    if size_head != size_tail:
        raise ChannelError("APK Signing Block size fields disagree")
    return eocd, cd_offset, block_start


def parse_pairs(data: bytes, block_start: int, cd_offset: int) -> list[tuple[int, bytes]]:
    cursor = block_start + 8
    end = cd_offset - 24
    pairs: list[tuple[int, bytes]] = []
    while cursor < end:
        if cursor + 12 > end:
            raise ChannelError("truncated ID-value pair in the APK Signing Block")
        pair_len, pair_id = struct.unpack_from("<QI", data, cursor)
        if pair_len < 4 or cursor + 8 + pair_len > end:
            raise ChannelError("ID-value pair length is out of range")
        value = data[cursor + 12 : cursor + 8 + pair_len]
        pairs.append((pair_id, value))
        cursor += 8 + pair_len
    if cursor != end:
        raise ChannelError("ID-value pairs do not fill the APK Signing Block")
    return pairs


def _encode_pair(pair_id: int, value: bytes) -> bytes:
    return struct.pack("<QI", len(value) + 4, pair_id) + value


def _build_block(
    pairs: list[tuple[int, bytes]],
    *,
    pad: bool,
    previous_total: int | None,
) -> bytes:
    body = b"".join(_encode_pair(pair_id, value) for pair_id, value in pairs)
    if pad:
        # 8 byte leading size + 12 byte padding header + 8 byte trailing size + 16 byte magic.
        overhead = len(body) + 44
        if (
            previous_total is not None
            and previous_total % PAGE == 0
            and previous_total >= overhead
        ):
            pad_len = previous_total - overhead
        else:
            pad_len = (-overhead) % PAGE
        body += _encode_pair(PADDING_ID, b"\x00" * pad_len)
    size_field = len(body) + 24
    return struct.pack("<Q", size_field) + body + struct.pack("<Q", size_field) + MAGIC


def transform(data: bytes, name: str) -> bytes:
    value = channel_value(name)
    eocd, cd_offset, block_start = locate(data)
    pairs = parse_pairs(data, block_start, cd_offset)
    had_padding = any(pair_id == PADDING_ID for pair_id, _ in pairs)
    kept = [
        (pair_id, pair_value)
        for pair_id, pair_value in pairs
        if pair_id not in (CHANNEL_ID, PADDING_ID)
    ]
    kept.append((CHANNEL_ID, value))
    block = _build_block(
        kept,
        pad=had_padding,
        previous_total=(cd_offset - block_start) if had_padding else None,
    )
    new_cd = block_start + len(block)
    if new_cd > 0xFFFFFFFF:
        raise ChannelError("central directory offset no longer fits a ZIP32 APK")
    eocd_bytes = bytearray(data[eocd:])
    struct.pack_into("<I", eocd_bytes, 16, new_cd)
    return data[:block_start] + block + data[cd_offset:eocd] + bytes(eocd_bytes)


def read_channel(data: bytes) -> str | None:
    try:
        _eocd, cd_offset, block_start = locate(data)
        pairs = parse_pairs(data, block_start, cd_offset)
    except ChannelError:
        return None
    values = [value for pair_id, value in pairs if pair_id == CHANNEL_ID]
    if len(values) != 1:
        return None
    raw = values[0]
    try:
        obj = json.loads(raw.decode("ascii"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(obj, dict) or list(obj) != ["channel"]:
        return None
    name = obj["channel"]
    if not isinstance(name, str):
        return None
    try:
        canonical = channel_value(name)
    except ChannelError:
        return None
    if raw != canonical:
        return None
    return name


def _zip_identity(data: bytes) -> list[tuple[str, int, int, int, int]]:
    try:
        with zipfile.ZipFile(BytesIO(data)) as archive:
            return [
                (
                    info.filename,
                    info.CRC,
                    info.compress_type,
                    info.file_size,
                    info.compress_size,
                )
                for info in archive.infolist()
            ]
    except zipfile.BadZipFile as exc:
        raise ChannelError(f"ZIP central directory is unreadable: {exc}") from exc


def assert_preserved(before: bytes, after: bytes, name: str) -> None:
    before_eocd, before_cd, before_start = locate(before)
    after_eocd, after_cd, after_start = locate(after)
    if before[:before_start] != after[:after_start]:
        raise ChannelError("bytes before the APK Signing Block changed")
    if before[before_cd:before_eocd] != after[after_cd:after_eocd]:
        raise ChannelError("ZIP central directory bytes changed")
    if (
        before[before_eocd : before_eocd + 16] != after[after_eocd : after_eocd + 16]
        or before[before_eocd + 20 :] != after[after_eocd + 20 :]
    ):
        raise ChannelError("ZIP end record changed outside the central directory offset")
    if struct.unpack_from("<I", after, after_eocd + 16)[0] != after_cd:
        raise ChannelError("central directory offset does not point at the central directory")
    if _zip_identity(before) != _zip_identity(after):
        raise ChannelError("ZIP entry names or checksums changed")

    before_pairs = parse_pairs(before, before_start, before_cd)
    after_pairs = parse_pairs(after, after_start, after_cd)
    before_kept = [
        item for item in before_pairs if item[0] not in (CHANNEL_ID, PADDING_ID)
    ]
    after_kept = [item for item in after_pairs if item[0] not in (CHANNEL_ID, PADDING_ID)]
    if before_kept != after_kept:
        raise ChannelError("signature pairs in the APK Signing Block changed")
    if [value for pair_id, value in after_pairs if pair_id == CHANNEL_ID] != [
        channel_value(name)
    ]:
        raise ChannelError("channel pair does not match the requested name")

    before_pads = [value for pair_id, value in before_pairs if pair_id == PADDING_ID]
    after_pads = [value for pair_id, value in after_pairs if pair_id == PADDING_ID]
    if before_pads:
        if len(after_pads) != 1 or any(byte != 0 for byte in after_pads[0]):
            raise ChannelError("verity padding is no longer a single zero-filled pair")
        if (after_cd - after_start) % PAGE != 0:
            raise ChannelError("APK Signing Block is no longer a multiple of 4096 bytes")
    elif after_pads:
        raise ChannelError("verity padding was added to an APK that had none")


def _version_key(path: Path) -> tuple:
    parts = []
    for piece in re.split(r"[.\-]", path.parent.name):
        if piece.isdigit():
            parts.append((0, int(piece)))
        else:
            parts.append((1, piece))
    return tuple(parts)


def find_apksigner() -> Path:
    override = os.environ.get("APKSIGNER", "").strip()
    if override:
        path = Path(override)
        if not path.is_file():
            raise ChannelError(f"APKSIGNER is not a file: {override}")
        return path
    found: list[Path] = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var, "").strip()
        if root:
            found.extend(Path(root).glob("build-tools/*/apksigner"))
    which = shutil.which("apksigner")
    if which:
        found.append(Path(which))
    files = [path for path in found if path.is_file() and os.access(path, os.X_OK)]
    if not files:
        raise ChannelError(
            "apksigner not found; set APKSIGNER, or ANDROID_HOME to an SDK with build-tools"
        )
    return max(files, key=_version_key)


def _run_apksigner(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(find_apksigner()), *args],
        check=False,
        capture_output=True,
        text=True,
    )


def _verify_same_signer(source: Path, stamped: Path) -> None:
    verified = _run_apksigner(["verify", "--verbose", str(stamped)])
    if verified.returncode != 0:
        detail = (verified.stdout + verified.stderr).strip()
        raise ChannelError(f"apksigner verify failed:\n{detail}")
    before = _run_apksigner(["verify", "--print-certs", str(source)])
    after = _run_apksigner(["verify", "--print-certs", str(stamped)])
    if before.returncode != 0 or after.returncode != 0 or before.stdout != after.stdout:
        raise ChannelError("signer certificate changed or could not be read")


def stamp(source: Path, dest: Path, name: str) -> str:
    """Write `name` into a copy of `source` and publish it at `dest` after checks."""
    channel_value(name)
    before = source.read_bytes()
    after = transform(before, name)
    assert_preserved(before, after, name)
    if read_channel(after) != name:
        raise ChannelError("channel did not read back")
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(
        prefix=".channel-",
        suffix=".apk",
        dir=dest.parent,
    )
    temp_path = Path(temp_name)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(after)
        _verify_same_signer(source, temp_path)
        os.replace(temp_path, dest)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise
    digest = hashlib.sha256(after).hexdigest()
    return digest


def _main(argv: list[str]) -> int:
    if len(argv) < 2 or argv[1] in {"-h", "--help"}:
        print(__doc__.strip().split("Usage:", 1)[1].strip())
        return 0 if len(argv) > 1 else 2
    command = argv[1]
    try:
        if command == "read" and len(argv) == 3:
            name = read_channel(Path(argv[2]).read_bytes())
            if name is None:
                raise ChannelError("no channel in APK Signing Block")
            print(name)
            return 0
        if command == "stamp" and len(argv) == 5:
            source = Path(argv[2])
            dest = Path(argv[3])
            digest = stamp(source, dest, argv[4])
            print(f"stamped channel={argv[4]} sha256={digest} apk={dest}")
            return 0
    except ChannelError as exc:
        print(f"apk_channel: {exc}", file=sys.stderr)
        return 1
    print(__doc__.strip().split("Usage:", 1)[1].strip(), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(_main(sys.argv))
