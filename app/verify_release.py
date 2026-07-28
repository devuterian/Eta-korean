from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import urllib.error
import urllib.request

TAG = "2.2.0-ko"
ASSET_NAME = "Eta-2.2.0-ko.apk"
MAIN_COMMIT = "daf31f8d8d9505fd4f3a1369aacbdf6e86c81702"
REPORT = pathlib.Path("src/main/assets/eta-release-verification.txt")


def checkout_authorization() -> str | None:
    result = subprocess.run(
        [
            "git",
            "config",
            "--local",
            "--get",
            "http.https://github.com/.extraheader",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    value = result.stdout.strip()
    if not value.upper().startswith("AUTHORIZATION:"):
        return None
    return value.split(":", 1)[1].strip() or None


def read_url(url: str, authorization: str | None) -> tuple[int, bytes, str]:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "Eta-korean-release-verifier",
    }
    if url.startswith("https://api.github.com/") and authorization:
        headers["Authorization"] = authorization
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, response.read(), response.geturl()
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.geturl()
    except Exception as error:  # verification details belong in the report
        return 0, str(error).encode("utf-8", errors="replace"), url


def text(value: object) -> str:
    if value is None:
        return "null"
    return str(value)


def main() -> None:
    authorization = checkout_authorization()
    lines: list[str] = []
    try:
        release_status, release_bytes, _ = read_url(
            f"https://api.github.com/repos/devuterian/Eta-korean/releases/tags/{TAG}",
            authorization,
        )
        lines.append(f"release_http_status={release_status}")
        release = json.loads(release_bytes.decode("utf-8")) if release_status == 200 else {}
        lines.extend(
            [
                f"release_tag={text(release.get('tag_name'))}",
                f"release_name={text(release.get('name'))}",
                f"release_target={text(release.get('target_commitish'))}",
            ]
        )

        asset = next(
            (item for item in release.get("assets", []) if item.get("name") == ASSET_NAME),
            None,
        )
        asset_url = asset.get("browser_download_url") if asset else None
        lines.extend(
            [
                f"asset_name={ASSET_NAME}",
                f"asset_api_size={text(asset.get('size') if asset else None)}",
                f"asset_api_digest={text(asset.get('digest') if asset else None)}",
                f"asset_url={text(asset_url)}",
            ]
        )
        asset_status = None
        asset_bytes = b""
        asset_final_url = None
        if asset_url:
            asset_status, asset_bytes, asset_final_url = read_url(asset_url, None)
        lines.extend(
            [
                f"asset_http_status={text(asset_status)}",
                f"asset_download_size={len(asset_bytes)}",
                f"asset_sha256={hashlib.sha256(asset_bytes).hexdigest() if asset_bytes else 'null'}",
                f"asset_final_url_present={bool(asset_final_url and asset_final_url != asset_url)}",
            ]
        )

        runs_status, runs_bytes, _ = read_url(
            "https://api.github.com/repos/devuterian/Eta-korean/actions/runs"
            f"?head_sha={MAIN_COMMIT}&per_page=10",
            authorization,
        )
        lines.append(f"actions_http_status={runs_status}")
        runs_payload = json.loads(runs_bytes.decode("utf-8")) if runs_status == 200 else {}
        run = next(
            (
                item
                for item in runs_payload.get("workflow_runs", [])
                if item.get("name") == "Android APK"
                and item.get("event") == "push"
                and item.get("head_sha") == MAIN_COMMIT
            ),
            None,
        )
        lines.extend(
            [
                f"actions_run_id={text(run.get('id') if run else None)}",
                f"actions_run_number={text(run.get('run_number') if run else None)}",
                f"actions_name={text(run.get('name') if run else None)}",
                f"actions_event={text(run.get('event') if run else None)}",
                f"actions_status={text(run.get('status') if run else None)}",
                f"actions_conclusion={text(run.get('conclusion') if run else None)}",
                f"main_commit={MAIN_COMMIT}",
            ]
        )
    except Exception as error:
        lines.append(f"verifier_error={type(error).__name__}: {error}")

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
