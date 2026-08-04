#!/usr/bin/env python3

import difflib
import json
import os
import pathlib
import re
import subprocess
import time
import urllib.parse
import urllib.request

OLD = os.environ.get("OLD_UPSTREAM", "7eeb00cc4da8c857f2a046c4c1c056610e2c0b16")
KO = os.environ.get("KOREAN_BASE", "dd68bdd452877d888e37fe7f09d1a668d4022abc")
ROOTS = [
    pathlib.Path("app/src/main/kotlin/fuck/andes/ui"),
    pathlib.Path("app/src/main/kotlin/fuck/andes/agent/overlay"),
    pathlib.Path("app/src/main/kotlin/fuck/andes/systemizer"),
    pathlib.Path("app/src/main/res"),
]
QUOTE_RE = re.compile(r'"(?:\\.|[^"\\])*"')
CHINESE_RE = re.compile(r"[\u3400-\u9fff]")
KOREAN_RE = re.compile(r"[가-힣]")
PLACEHOLDER_RE = re.compile(r"\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\\[nrt\"\\]")


def git_show(ref: str, path: str) -> str | None:
    process = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        text=True,
        capture_output=True,
        check=False,
    )
    return process.stdout if process.returncode == 0 else None


def in_scope(path: str) -> bool:
    candidate = pathlib.Path(path)
    return any(candidate == root or root in candidate.parents for root in ROOTS)


def literals(text: str) -> list[str]:
    return [match.group(0) for match in QUOTE_RE.finditer(text)]


manual = {
    "未选择模型": "모델을 선택하지 않음",
    "未配置": "설정되지 않음",
    "设置": "설정",
    "LSPosed 服务未连接": "LSPosed 서비스가 연결되지 않음",
    "模型提供商": "모델 제공업체",
    "默认启用深度思考": "기본으로 심층 사고 사용",
    "记忆": "메모리",
    "工具": "도구",
    "启用网页浏览工具": "웹 브라우징 도구 사용",
    "启用设备直达工具": "기기 직접 제어 도구 사용",
    "允许读取敏感设备信息": "민감한 기기 정보 읽기 허용",
    "允许敏感设备操作": "민감한 기기 작업 허용",
    "启用终端/文件工具": "터미널/파일 도구 사용",
    "Linux 工具环境": "Linux 도구 환경",
    "系统助手接管": "시스템 어시스턴트 연동",
    "启用系统助手自定义模型": "시스템 어시스턴트에서 사용자 모델 사용",
    "仅 /agent 前缀接管": "/agent 접두사가 있을 때만 처리",
    "长按电源键唤起 Gemini": "전원 버튼을 길게 눌러 Gemini 실행",
    "自动设置 Google 为默认助理": "Google을 기본 어시스턴트로 자동 설정",
    "息屏后维持 Hey Google 检测": "화면이 꺼져도 Hey Google 감지 유지",
    "锁屏唤起自动语音输入": "잠금 화면에서 실행 시 음성 입력 자동 시작",
    "亮屏唤起自动语音输入": "화면이 켜진 상태에서 실행 시 음성 입력 자동 시작",
    "将 Google App 转为系统应用": "Google 앱을 시스템 앱으로 전환",
    "一圈即搜": "서클 투 서치",
    "手势条长按触发一圈即搜": "제스처 바를 길게 눌러 서클 투 서치 실행",
    "双指长按触发一圈即搜": "두 손가락으로 길게 눌러 서클 투 서치 실행",
    "权限": "권한",
    "悬浮窗权限": "다른 앱 위에 표시 권한",
    "已授权": "허용됨",
    "未授权": "허용되지 않음",
    "无障碍服务": "접근성 서비스",
    "无障碍保护": "접근성 보호",
    "启用无障碍保护": "접근성 보호 사용",
    "长期记忆": "장기 메모리",
    "记忆内容": "메모리 내용",
    "编辑记忆": "메모리 편집",
    "保存": "저장",
    "取消": "취소",
    "删除": "삭제",
    "清空记忆": "메모리 비우기",
    "暂无记忆": "저장된 메모리가 없음",
    "思考强度": "사고 강도",
    "自动": "자동",
    "关闭": "끄기",
    "低": "낮음",
    "中": "보통",
    "高": "높음",
    "加载中": "불러오는 중",
    "重试": "다시 시도",
    "完成": "완료",
    "错误": "오류",
    "名称": "이름",
    "描述": "설명",
    "启用": "사용",
    "禁用": "사용 안 함",
}

mapping: dict[str, str] = {}
old_files = subprocess.check_output(
    ["git", "ls-tree", "-r", "--name-only", OLD], text=True
).splitlines()
for path in old_files:
    if not in_scope(path) or not path.endswith((".kt", ".xml")):
        continue
    old_text = git_show(OLD, path)
    ko_text = git_show(KO, path)
    if old_text is None or ko_text is None:
        continue
    old_literals = literals(old_text)
    ko_literals = literals(ko_text)
    matcher = difflib.SequenceMatcher(a=old_literals, b=ko_literals, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag != "replace" or i2 - i1 != j2 - j1:
            continue
        for source, target in zip(old_literals[i1:i2], ko_literals[j1:j2]):
            if CHINESE_RE.search(source) and KOREAN_RE.search(target):
                mapping[source] = target

for source, target in manual.items():
    mapping[json.dumps(source, ensure_ascii=False)] = json.dumps(target, ensure_ascii=False)

translated_cache: dict[str, str] = {}


def translate_plain(text: str) -> str:
    if text in manual:
        return manual[text]
    if text in translated_cache:
        return translated_cache[text]
    protected: dict[str, str] = {}

    def protect(match: re.Match[str]) -> str:
        token = f"__ETA_PH_{len(protected)}__"
        protected[token] = match.group(0)
        return token

    query = PLACEHOLDER_RE.sub(protect, text)
    url = (
        "https://translate.googleapis.com/translate_a/single?client=gtx"
        "&sl=zh-CN&tl=ko&dt=t&q=" + urllib.parse.quote(query)
    )
    translated = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=20) as response:
                payload = json.loads(response.read().decode("utf-8"))
            translated = "".join(part[0] for part in payload[0] if part and part[0])
            break
        except Exception:
            time.sleep(2**attempt)
    if not translated:
        return text
    for token, original in protected.items():
        translated = translated.replace(token, original)
    translated_cache[text] = translated
    return translated


def translate_literal(literal: str) -> str:
    if literal in mapping:
        return mapping[literal]
    inner = literal[1:-1]
    if not CHINESE_RE.search(inner):
        return literal
    translated = translate_plain(inner)
    translated = translated.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{translated}"'


changed_files = 0
for root in ROOTS:
    if not root.exists():
        continue
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".xml"}:
            continue
        original = path.read_text(encoding="utf-8")
        updated = original
        for source, target in sorted(mapping.items(), key=lambda item: -len(item[0])):
            updated = updated.replace(source, target)
        if path.suffix == ".kt":
            updated = QUOTE_RE.sub(lambda match: translate_literal(match.group(0)), updated)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed_files += 1

strings_path = pathlib.Path("app/src/main/res/values/strings.xml")
strings_path.write_text(
    '<resources>\n'
    '    <string name="app_name">Eta</string>\n'
    '    <string name="xposed_description">ColorOS에서 시스템 수준 AI 에이전트를 구현하고 Breeno를 대체하며 Gemini와 서클 투 서치를 활성화합니다.</string>\n'
    '    <string name="agent_accessibility_label">Eta 기기 제어</string>\n'
    '    <string name="agent_accessibility_description">Eta가 화면 내용을 읽고 탭, 스와이프, 텍스트 입력, 시스템 작업을 수행할 수 있도록 합니다.</string>\n'
    '</resources>\n',
    encoding="utf-8",
)

remaining: list[str] = []
for root in ROOTS:
    if not root.exists():
        continue
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".xml"}:
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for match in QUOTE_RE.finditer(line):
                literal = match.group(0)
                if CHINESE_RE.search(literal):
                    remaining.append(f"{path}:{line_number}: {literal}")

pathlib.Path("/tmp/eta-localization-report.md").write_text(
    "## Eta 2.5.0 현지화 결과\n\n"
    f"- 기존 번역 매핑: {len(mapping)}개\n"
    f"- 수정된 파일: {changed_files}개\n"
    f"- 남은 중국어 UI 문자열: {len(remaining)}개\n\n"
    + (
        "```text\n" + "\n".join(remaining[:300]) + "\n```\n"
        if remaining
        else "남은 중국어 UI 문자열이 없습니다.\n"
    ),
    encoding="utf-8",
)
pathlib.Path("/tmp/eta-remaining-count").write_text(str(len(remaining)), encoding="utf-8")
print(f"mapped={len(mapping)} changed_files={changed_files} remaining={len(remaining)}")
