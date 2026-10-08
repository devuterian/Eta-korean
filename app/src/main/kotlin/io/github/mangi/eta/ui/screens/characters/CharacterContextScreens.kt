package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.i18n.ko
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults

@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = ko("我的人设", "내 페르소나"), onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage(ko("设置你在故事中的身份。开始对话时可以选择是否使用；已开始的故事保留当时的人设。", "이야기 속 내 정체성을 설정합니다. 대화를 시작할 때 사용 여부를 선택할 수 있으며, 이미 시작된 이야기는 당시의 페르소나를 유지합니다.")) }
        item(key = "name") { CharacterTextField(ko("称呼", "호칭"), store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true) }
        item(key = "persona") { CharacterTextField(ko("身份与关系", "정체성과 관계"), store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6) }
        item(key = "save") {
            EtaTextButton(ko("保存人设", "페르소나 저장"), onClick = { store.savePersona(onBack) }, enabled = !store.busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = ko("剧情记忆", "스토리 메모리"), onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage(ko("记录这个角色的重要经历、关系与约定。它由此角色的各次对话共享，受记忆总开关控制。", "이 캐릭터의 중요한 경험, 관계, 약속을 기록합니다. 이 캐릭터와의 모든 대화에서 공유되며 메모리 전체 스위치의 영향을 받습니다.")) }
        item(key = "memory") {
            CharacterTextField(ko("剧情与关系", "스토리와 관계"), store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton(ko("重新载入", "다시 불러오기"), onClick = { store.loadMemory(id, force = true) }, enabled = !store.busy, modifier = Modifier.weight(1f))
                EtaTextButton(ko("保存记忆", "메모리 저장"), onClick = { store.saveMemory(id) }, enabled = !store.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
