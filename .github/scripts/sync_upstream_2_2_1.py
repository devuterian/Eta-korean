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

readme = pathlib.Path("README.md")
readme_text = readme.read_text(encoding="utf-8")
readme_text = re.sub(r"Eta 2\.2\.0", "Eta 2.2.1", readme_text)
readme.write_text(readme_text, encoding="utf-8")
git("add", "--", str(readme))

replacements: dict[str, dict[str, str]] = {
    "app/src/main/kotlin/fuck/andes/agent/accessibility/AgentAccessibilityKeeper.kt": {
        "Eta 无障碍服务未连接；请在设置中开启服务或启用“强制保持无障碍”": "Eta 접근성 서비스가 연결되지 않았습니다. 설정에서 서비스를 켜거나 ‘접근성 강제 유지’를 활성화해 주세요.",
        "无障碍保护后端不可用；本次 GUI 操作未执行": "접근성 보호 백엔드를 사용할 수 없어 이번 GUI 작업을 실행하지 않았습니다.",
        "Eta 无障碍服务未在恢复时限内连接；本次 GUI 操作未执行": "복구 제한 시간 안에 Eta 접근성 서비스가 연결되지 않아 이번 GUI 작업을 실행하지 않았습니다.",
    },
    "app/src/main/kotlin/fuck/andes/agent/model/OpenAiChatCompletionsProvider.kt": {
        "模型接口 SSE 以 error 结束": "모델 API의 SSE가 오류로 종료되었습니다",
        "未提供错误信息": "오류 정보가 제공되지 않았습니다",
        "模型接口 SSE 返回错误": "모델 API의 SSE가 오류를 반환했습니다",
    },
    "app/src/main/kotlin/fuck/andes/agent/overlay/AgentOverlayContent.kt": {
        "已完成": "완료",
    },
    "app/src/main/kotlin/fuck/andes/hook/xiaoai/XiaoAiHandoff.kt": {
        "超级小爱对话": "슈퍼 샤오아이 대화",
        "超级小爱：": "슈퍼 샤오아이: ",
    },
    "app/src/main/kotlin/fuck/andes/hook/xiaoai/XiaoAiStreamRenderer.kt": {
        "Eta 正在思考…": "Eta가 생각하고 있어요…",
        "Eta 正在执行任务…": "Eta가 작업을 수행하고 있어요…",
        "Eta 已完成本轮任务": "Eta가 이번 작업을 완료했어요",
        "Eta 处理失败，请稍后重试": "Eta가 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        "超级小爱结果卡片管理器暂不可用": "슈퍼 샤오아이 결과 카드 관리자를 현재 사용할 수 없습니다",
        "超级小爱结果卡创建失败": "슈퍼 샤오아이 결과 카드 생성 실패",
        "超级小爱 Eta 结果朗读失败": "슈퍼 샤오아이 Eta 결과 읽기 실패",
    },
    "app/src/main/kotlin/fuck/andes/ui/SettingsScreen.kt": {
        "系统助手接管": "시스템 어시스턴트 연동",
        "启用系统助手自定义模型": "시스템 어시스턴트에서 사용자 지정 모델 사용",
        "小布与超级小爱共用此开关": "Breeno와 슈퍼 샤오아이가 이 설정을 함께 사용합니다",
        "同时适用于小布与超级小爱": "Breeno와 슈퍼 샤오아이에 모두 적용됩니다",
        "强制保持无障碍": "접근성 강제 유지",
        "由 system_server 保护 Eta 服务并在断连时有限重绑；关闭后不再干预系统设置": "system_server가 Eta 서비스를 보호하고 연결이 끊기면 제한적으로 다시 연결합니다. 끄면 시스템 설정에 개입하지 않습니다.",
        "无障碍保护后端不可用，请确认 system 作用域已启用并重启": "접근성 보호 백엔드를 사용할 수 없습니다. system 범위를 활성화하고 재부팅했는지 확인해 주세요.",
        "无障碍保护请求被系统拒绝": "시스템이 접근성 보호 요청을 거부했습니다",
        "系统应用享有语音唤醒权限、更少的自启限制，体验接近原生。将通过 Magisk / KernelSU 模块安装，重启后生效。": "시스템 앱은 음성 호출 권한과 완화된 자동 실행 제한을 적용받습니다. Magisk 또는 KernelSU 모듈로 설치되며 재부팅 후 적용됩니다.",
        "处理中...": "처리 중...",
    },
    "app/src/main/kotlin/fuck/andes/ui/app/AgentAppRoot.kt": {
        "对话名称": "대화 이름",
        "删除对话": "대화 삭제",
        "删除": "삭제",
    },
    "app/src/main/kotlin/fuck/andes/ui/components/AgentChatBody.kt": {
        "回到底部": "맨 아래로 이동",
    },
    "app/src/main/kotlin/fuck/andes/ui/components/AgentChatInputBar.kt": {
        "停止": "중지",
        "发送": "보내기",
    },
    "app/src/main/kotlin/fuck/andes/ui/components/ChatMessageItem.kt": {
        "思考已完成": "생각 완료",
        "用时 ": "소요 시간 ",
        " 秒": "초",
    },
    "app/src/main/kotlin/fuck/andes/ui/components/MiuixDialogActions.kt": {
        "取消": "취소",
    },
    "app/src/main/kotlin/fuck/andes/ui/pages/providers/ModelProviderDetailScreen.kt": {
        "失败：": "실패: ",
        "删除「": "‘",
        "」后将不可恢复。": "’을 삭제하면 복구할 수 없습니다.",
        "删除失败": "삭제 실패",
        "将恢复「": "‘",
        "」的默认配置和官方模型列表，API Key 会保留。": "’의 기본 설정과 공식 모델 목록을 복원합니다. API Key는 유지됩니다.",
        "重置失败": "초기화 실패",
        "同步失败": "동기화 실패",
        "删除选中的 ": "선택한 ",
        " 个模型后将不可恢复。": "개 모델을 삭제하면 복구할 수 없습니다.",
        "已选 ": "선택됨: ",
        " 个": "개",
    },
    "app/src/main/kotlin/fuck/andes/ui/pages/providers/ModelProviderListScreen.kt": {
        "删除「": "‘",
        "」后将不可恢复。": "’을 삭제하면 복구할 수 없습니다.",
    },
    "app/src/main/kotlin/fuck/andes/ui/screens/browser/AgentBrowserScreen.kt": {
        "重新加载": "다시 불러오기",
    },
    "app/src/main/kotlin/fuck/andes/ui/screens/skills/AgentSkillsScreen.kt": {
        "删除用户技能": "사용자 스킬 삭제",
        "删除": "삭제",
    },
}

for file_name, mapping in replacements.items():
    path = pathlib.Path(file_name)
    if not path.exists():
        continue
    text = path.read_text(encoding="utf-8")
    for source, target in mapping.items():
        text = text.replace(source, target)
    path.write_text(text, encoding="utf-8")
    git("add", "--", file_name)

changed = git("diff", "--cached", "--name-only").stdout.splitlines()
leftovers: list[str] = []
for file_name in changed:
    if not file_name.endswith(".kt") or file_name.startswith("app/src/test/"):
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
