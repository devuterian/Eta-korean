package io.github.mangi.eta.i18n

import java.util.Locale

/**
 * 한국어 포크 전용: upstream 코드에 직접 들어 있는 중국어 UI 문구를 한국어 로케일에서만 바꿔 보여 준다.
 * 다른 로케일은 upstream 동작(원문)을 그대로 유지한다.
 */
internal fun ko(original: String, korean: String): String =
    if (Locale.getDefault().language == "ko") korean else original
