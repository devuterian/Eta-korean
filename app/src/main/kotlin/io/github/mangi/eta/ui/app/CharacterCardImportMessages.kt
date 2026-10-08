package io.github.mangi.eta.ui.app

import io.github.mangi.eta.i18n.ko

/** 导入只公开受控分类，文件内容与底层异常文本不进入界面。 */
internal fun characterCardImportMessage(code: String?): String? = when (code) {
    "CARD_V3_INVALID" -> ko("PNG 中优先使用的 V3 角色数据损坏，未回退到 V2。请重新下载原始角色卡。", "PNG의 V3 캐릭터 데이터가 손상되어 V2로 대체하지 않았습니다. 원본 캐릭터 카드를 다시 다운로드하세요.")
    "CARD_METADATA_MISSING" -> ko("这张 PNG 没有角色卡信息，请选择原始角色卡图片，而不是截图或压缩后的图片。", "이 PNG에는 캐릭터 카드 정보가 없습니다. 스크린샷이나 압축된 이미지가 아닌 원본 캐릭터 카드 이미지를 선택하세요.")
    "CARD_INVALID_JSON" -> ko("角色卡 JSON 格式无效，请检查文件是否完整。", "캐릭터 카드 JSON 형식이 올바르지 않습니다. 파일이 온전한지 확인하세요.")
    "CARD_TOO_LARGE" -> ko("角色卡超出大小限制，请简化设定或选择更小的文件。", "캐릭터 카드가 크기 제한을 초과했습니다. 설정을 줄이거나 더 작은 파일을 선택하세요.")
    "CARD_INVALID_UTF8" -> ko("角色卡文本编码无效，需要 UTF-8 编码的 JSON 文件。", "캐릭터 카드의 텍스트 인코딩이 올바르지 않습니다. UTF-8로 인코딩된 JSON 파일이 필요합니다.")
    "CARD_UNSUPPORTED_SPEC" -> ko("暂不支持这份角色卡声明的格式，请导出为 V2 或 V3 角色卡后重试。", "이 캐릭터 카드의 형식은 아직 지원하지 않습니다. V2 또는 V3 캐릭터 카드로 내보낸 후 다시 시도하세요.")
    "CARD_INVALID_PNG" -> ko("PNG 文件损坏或结构不完整，请重新下载原始角色卡。", "PNG 파일이 손상되었거나 구조가 불완전합니다. 원본 캐릭터 카드를 다시 다운로드하세요.")
    "CARD_INVALID_DATA" -> ko("角色卡字段格式无效，请检查名称、文本、开场白及内嵌世界书数据。", "캐릭터 카드 필드 형식이 올바르지 않습니다. 이름, 텍스트, 인사말, 내장 월드북 데이터를 확인하세요.")
    else -> null
}
