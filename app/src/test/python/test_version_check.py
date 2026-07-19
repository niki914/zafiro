"""
Version update check logic tests.

The version check flow:
1. Fetch GitHub latest release JSON from:
   https://api.github.com/repos/niki914/agentic-nexus/releases/latest
2. Extract tag_name from the response
3. Parse the semver from tag_name (handle formats: "v0.0.4", "v3-0.0.4", "0.0.4")
4. Compare with the app's current versionName
5. If remote > current, trigger update dialog

These tests validate the parsing and comparison logic before it lands in Kotlin.
"""

import json
import pytest


# ── GitHub API response fixtures ────────────────────────────────────────────

def make_github_release_response(tag_name, draft=False, prerelease=False, html_url=None):
    """Minimal realistic GitHub /releases/latest response."""
    return {
        "tag_name": tag_name,
        "name": f"Release - {tag_name}",
        "draft": draft,
        "prerelease": prerelease,
        "html_url": html_url or f"https://github.com/niki914/agentic-nexus/releases/tag/{tag_name}",
    }


# ── Version extraction ──────────────────────────────────────────────────────

import re

# Match the first semver-like segment in a tag: "0.0.4" from "v3-0.0.4" or "v0.0.4"
_SEMVER_RE = re.compile(r"(\d+\.\d+\.\d+)")


def extract_version(tag_name: str) -> str | None:
    """Extract a semver string from a GitHub release tag_name.

    Returns None if no semver-like pattern is found.
    """
    m = _SEMVER_RE.search(tag_name)
    return m.group(1) if m else None


def parse_semver(version: str) -> tuple[int, ...]:
    """Parse a semver string into a comparable tuple."""
    return tuple(int(p) for p in version.split("."))


def is_newer_than(remote_version: str, current_version: str) -> bool:
    """True if remote_version > current_version, using semver comparison."""
    return parse_semver(remote_version) > parse_semver(current_version)


# ── Main entry: version check from API response ─────────────────────────────

class VersionCheckResult:
    def __init__(self, has_update: bool, remote_version: str | None,
                 current_version: str, release_url: str | None):
        self.has_update = has_update
        self.remote_version = remote_version
        self.current_version = current_version
        self.release_url = release_url

    def __eq__(self, other):
        return (self.has_update == other.has_update and
                self.remote_version == other.remote_version and
                self.current_version == other.current_version and
                self.release_url == other.release_url)


def check_for_update(response_json: dict, current_version: str) -> VersionCheckResult:
    """Parse a GitHub /releases/latest response and compare versions.

    Returns VersionCheckResult indicating whether an update is available.
    Draft and prerelease releases are treated as no-update.
    """
    if response_json.get("draft", False):
        return VersionCheckResult(False, None, current_version, None)
    if response_json.get("prerelease", False):
        return VersionCheckResult(False, None, current_version, None)

    tag_name = response_json.get("tag_name", "")
    remote_version = extract_version(tag_name)
    if remote_version is None:
        return VersionCheckResult(False, None, current_version, None)

    html_url = response_json.get("html_url", "")
    has_update = is_newer_than(remote_version, current_version)
    return VersionCheckResult(has_update, remote_version, current_version, html_url)


# ── Tests: extract_version ──────────────────────────────────────────────────

@pytest.mark.parametrize("tag_name, expected", [
    ("v3-0.0.3", "0.0.3"),
    ("v3-0.0.4", "0.0.4"),
    ("v0.0.3", "0.0.3"),
    ("v0.0.4", "0.0.4"),
    ("0.0.4", "0.0.4"),
    ("v4-1.2.3", "1.2.3"),
    ("v5-10.20.30", "10.20.30"),
    ("release-2.0.0", "2.0.0"),
])
def test_extract_version_matches_semver_pattern(tag_name, expected):
    assert extract_version(tag_name) == expected


@pytest.mark.parametrize("tag_name", [
    "",
    "v",
    "v-no-version",
    "release",
])
def test_extract_version_returns_none_for_invalid(tag_name):
    assert extract_version(tag_name) is None


# ── Tests: parse_semver ─────────────────────────────────────────────────────

def test_parse_semver_simple():
    assert parse_semver("0.0.3") == (0, 0, 3)
    assert parse_semver("1.2.3") == (1, 2, 3)


def test_parse_semver_multi_digit():
    assert parse_semver("10.20.30") == (10, 20, 30)


# ── Tests: is_newer_than ────────────────────────────────────────────────────

def test_is_newer_than_patch():
    assert is_newer_than("0.0.4", "0.0.3") is True
    assert is_newer_than("0.0.3", "0.0.4") is False


def test_is_newer_than_minor():
    assert is_newer_than("0.1.0", "0.0.9") is True
    assert is_newer_than("0.0.9", "0.1.0") is False


def test_is_newer_than_major():
    assert is_newer_than("1.0.0", "0.9.9") is True
    assert is_newer_than("0.9.9", "1.0.0") is False


def test_is_newer_than_equal():
    assert is_newer_than("0.0.3", "0.0.3") is False


def test_is_newer_than_two_digit():
    assert is_newer_than("0.0.10", "0.0.9") is True


# ── Tests: check_for_update end-to-end ──────────────────────────────────────

def test_update_available_patch():
    resp = make_github_release_response("v4-0.0.4")
    result = check_for_update(resp, "0.0.3")
    assert result.has_update is True
    assert result.remote_version == "0.0.4"
    assert result.current_version == "0.0.3"


def test_no_update_same_version():
    resp = make_github_release_response("v3-0.0.3")
    result = check_for_update(resp, "0.0.3")
    assert result.has_update is False


def test_no_update_current_newer():
    resp = make_github_release_response("v2-0.0.2")
    result = check_for_update(resp, "0.0.3")
    assert result.has_update is False


def test_draft_release_ignored():
    resp = make_github_release_response("v4-0.0.4", draft=True)
    result = check_for_update(resp, "0.0.3")
    assert result.has_update is False


def test_prerelease_ignored():
    resp = make_github_release_response("v4-0.0.4", prerelease=True)
    result = check_for_update(resp, "0.0.3")
    assert result.has_update is False


def test_malformed_tag_ignored():
    resp = make_github_release_response("latest")
    result = check_for_update(resp, "0.0.3")
    assert result.has_update is False


def test_url_preserved_in_result():
    url = "https://github.com/niki914/agentic-nexus/releases/tag/v4-0.0.4"
    resp = make_github_release_response("v4-0.0.4", html_url=url)
    result = check_for_update(resp, "0.0.3")
    assert result.release_url == url


# ── Tests: realistic full JSON responses ────────────────────────────────────

def test_realistic_response_has_update():
    """Simulates a realistic GitHub API response with a newer version."""
    response = {
        "tag_name": "v5-1.0.0",
        "name": "Release - 1.0.0",
        "draft": False,
        "prerelease": False,
        "html_url": "https://github.com/niki914/agentic-nexus/releases/tag/v5-1.0.0",
    }
    result = check_for_update(response, "0.0.3")
    assert result.has_update is True
    assert result.remote_version == "1.0.0"


def test_realistic_response_same_version():
    """Simulates the actual current release (tag v3-0.0.3, app 0.0.3)."""
    response = {
        "tag_name": "v3-0.0.3",
        "name": "Release - 0.0.3",
        "draft": False,
        "prerelease": False,
        "html_url": "https://github.com/niki914/agentic-nexus/releases/tag/v3-0.0.3",
    }
    result = check_for_update(response, "0.0.3")
    assert result.has_update is False


def test_tag_without_code_prefix():
    """Tag format might be simpler: v0.0.4 instead of v4-0.0.4."""
    response = {
        "tag_name": "v0.0.4",
        "name": "0.0.4",
        "draft": False,
        "prerelease": False,
        "html_url": "https://github.com/niki914/agentic-nexus/releases/tag/v0.0.4",
    }
    result = check_for_update(response, "0.0.3")
    assert result.has_update is True
    assert result.remote_version == "0.0.4"
