package fuck.andes.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.model.PendingImageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val InputIconSize = 20.dp
private val SendButtonVisualSize = 32.dp
private val ThinkingChipShape = RoundedCornerShape(percent = 50)
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@Composable
fun AgentChatInputBar(
    input: String,
    isStreaming: Boolean,
    thinkingEnabled: Boolean,
    pendingImages: List<PendingImageUi>,
    onInputChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val canSend = input.isNotBlank() || pendingImages.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dropShadow(
                    shape = InputContainerShape,
                    shadow = Shadow(
                        radius = 8.dp,
                        color = Color.Black,
                        alpha = 0.08f,
                    ),
                )
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 20.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                    cornerRadius = 20.dp,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                if (input.isBlank()) {
                    Text(
                        text = if (isStreaming) "Eta가 실행 중…" else "Eta에게 맡기기",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    maxLines = 6,
                    minLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    minWidth = 38.dp,
                    minHeight = 38.dp,
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_plus),
                        contentDescription = "이미지 추가",
                        modifier = Modifier.size(InputIconSize + 2.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                ThinkingToggleChip(
                    checked = thinkingEnabled,
                    enabled = !isStreaming,
                    onClick = { onThinkingChange(!thinkingEnabled) },
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = if (isStreaming) onStop else onSend,
                    enabled = isStreaming || canSend,
                    minWidth = 38.dp,
                    minHeight = 38.dp,
                ) {
                    // The 38dp outer button remains easy to hit; only the visible
                    // circle is reduced so it has the same optical weight as the
                    // adjacent toolbar icons.
                    val sendButtonColor by animateColorAsState(
                        targetValue = when {
                            isStreaming -> MiuixTheme.colorScheme.onSurface
                            canSend -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.surfaceContainerHigh
                        },
                        animationSpec = tween(durationMillis = 160),
                        label = "send_button_color",
                    )
                    Box(
                        modifier = Modifier
                            .size(SendButtonVisualSize)
                            .clip(CircleShape)
                            .background(sendButtonColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isStreaming) {
                                    LucideR.drawable.lucide_ic_square
                                } else {
                                    LucideR.drawable.lucide_ic_arrow_up
                                }
                            ),
                            contentDescription = if (isStreaming) "중지" else "보내기",
                            modifier = Modifier.size(if (isStreaming) 12.dp else 17.dp),
                            tint = when {
                                isStreaming -> MiuixTheme.colorScheme.surface
                                canSend -> MiuixTheme.colorScheme.onPrimary
                                else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                            },
                            label = "send_stop_icon",
                        ) { streaming ->
                            Icon(
                                painter = painterResource(
                                    if (streaming) {
                                        LucideR.drawable.lucide_ic_square
                                    } else {
                                        LucideR.drawable.lucide_ic_arrow_up
                                    }
                                ),
                                contentDescription = if (streaming) "停止" else "发送",
                                modifier = Modifier.size(if (streaming) 12.dp else 17.dp),
                                tint = when {
                                    streaming -> MiuixTheme.colorScheme.surface
                                    canSend -> MiuixTheme.colorScheme.onPrimary
                                    else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 思考模式开关：选中时填充主色淡底，未选中保持描边线框，状态一眼可辨。
 */
@Composable
private fun ThinkingToggleChip(
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (checked) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (checked) {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_background",
    )
    val outlineColor by animateColorAsState(
        targetValue = if (checked) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.outline.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_outline",
    )
    Row(
        modifier = modifier
            .clip(ThinkingChipShape)
            .background(backgroundColor)
            .border(0.5.dp, outlineColor, ThinkingChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_atom),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "생각",
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
        )
    }
}

@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_x),
                        contentDescription = "이미지 제거",
                        modifier = Modifier.size(11.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
