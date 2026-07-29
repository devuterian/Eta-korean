from __future__ import annotations

import difflib
import pathlib
import re
import subprocess

STRING_RE = re.compile(r'"(?:\\.|[^"\\])*"')
CJK_RE = re.compile(r'[\u3400-\u9fff]')
HANGUL_RE = re.compile(r'[\uac00-\ud7a3]')


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def stage(path: str, number: int) -> str | None:
    result = git("show", f":{number}:{path}", check=False)
    return result.stdout if result.returncode == 0 else None


def normalized(line: str) -> str:
    return STRING_RE.sub('""', line)


def collect_translation_map(base: str, ours: str) -> dict[str, str]:
    mapping: dict[str, str] = {}
    base_lines = base.splitlines()
    ours_lines = ours.splitlines()

    def map_line(base_line: str, ours_line: str) -> None:
        base_strings = STRING_RE.findall(base_line)
        ours_strings = STRING_RE.findall(ours_line)
        if len(base_strings) != len(ours_strings):
            return
        for source, target in zip(base_strings, ours_strings):
            if source != target and CJK_RE.search(source) and HANGUL_RE.search(target):
                mapping[source] = target

    base_index: dict[str, list[str]] = {}
    ours_index: dict[str, list[str]] = {}
    for line in base_lines:
        base_index.setdefault(normalized(line), []).append(line)
    for line in ours_lines:
        ours_index.setdefault(normalized(line), []).append(line)
    for key in base_index.keys() & ours_index.keys():
        if len(base_index[key]) == 1 and len(ours_index[key]) == 1:
            map_line(base_index[key][0], ours_index[key][0])

    matcher = difflib.SequenceMatcher(
        a=[normalized(line) for line in base_lines],
        b=[normalized(line) for line in ours_lines],
        autojunk=False,
    )
    for block in matcher.get_matching_blocks():
        for offset in range(block.size):
            map_line(base_lines[block.a + offset], ours_lines[block.b + offset])
    return mapping


conflicts = [p for p in git("diff", "--name-only", "--diff-filter=U").stdout.splitlines() if p]
binary_suffixes = {".jpg", ".jpeg", ".png", ".gif", ".webp"}

for path in conflicts:
    suffix = pathlib.Path(path).suffix.lower()
    if path == "README.md":
        git("checkout", "--ours", "--", path)
        git("add", "--", path)
        continue
    if suffix in binary_suffixes:
        if stage(path, 3) is None:
            git("rm", "--", path)
        else:
            git("checkout", "--theirs", "--", path)
            git("add", "--", path)
        continue

    base = stage(path, 1)
    ours = stage(path, 2)
    theirs = stage(path, 3)
    if theirs is None:
        git("rm", "--", path)
        continue
    if base is None or ours is None:
        pathlib.Path(path).write_text(theirs, encoding="utf-8")
        git("add", "--", path)
        continue

    merged = theirs
    for source, target in collect_translation_map(base, ours).items():
        merged = merged.replace(source, target)
    pathlib.Path(path).write_text(merged, encoding="utf-8")
    git("add", "--", path)

build_file = pathlib.Path("app/build.gradle.kts")
build_text = build_file.read_text(encoding="utf-8")
if 'versionName = "2.2.1"' not in build_text:
    raise SystemExit("upstream versionName 2.2.1 not found")
if 'versionNameSuffix = "-ko"' not in build_text:
    build_text = build_text.replace(
        '        versionName = "2.2.1"\n',
        '        versionName = "2.2.1"\n        versionNameSuffix = "-ko"\n',
    )
build_file.write_text(build_text, encoding="utf-8")
git("add", "--", str(build_file))

# Keep Korean README while updating the version-specific support notes.
readme = pathlib.Path("README.md")
readme_text = readme.read_text(encoding="utf-8")
readme_text = re.sub(r"Eta 2\.2\.0", "Eta 2.2.1", readme_text)
readme.write_text(readme_text, encoding="utf-8")
git("add", "--", str(readme))

# Report Chinese string literals introduced into changed Kotlin source files.
changed = git("diff", "--cached", "--name-only").stdout.splitlines()
leftovers: list[str] = []
for file_name in changed:
    if not file_name.endswith(".kt"):
        continue
    path = pathlib.Path(file_name)
    if not path.exists():
        continue
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        code = line.split("//", 1)[0]
        if any(CJK_RE.search(literal) for literal in STRING_RE.findall(code)):
            leftovers.append(f"{file_name}:{number}: {line.strip()}")

report = pathlib.Path(".github/eta-2.2.1-localization-report.txt")
report.write_text("\n".join(leftovers) + ("\n" if leftovers else ""), encoding="utf-8")
git("add", "--", str(report))
