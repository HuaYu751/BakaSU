package org.bakasu.bakasu.ui.screen.susfs.subpages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.bakasu.bakasu.data.susfs.SuSFSConfigHelper
import org.bakasu.bakasu.ui.component.settings.SettingsJumpPageWidget
import org.bakasu.bakasu.ui.component.settings.SettingsTextFieldWidget
import org.bakasu.bakasu.ui.screen.susfs.component.AppPickerDialog
import org.bakasu.bakasu.ui.screen.susfs.component.EntryDetailDialog
import org.bakasu.bakasu.ui.screen.susfs.component.ManualAddDialog
import org.bakasu.bakasu.ui.screen.susfs.component.SuSFSDescriptionCard
import org.bakasu.bakasu.ui.screen.susfs.component.toImportedEntryLines
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.koin.compose.koinInject

/**
 * UID 过滤页签（HuaYu custom）。
 * 调用 susfs 2.4.0 的 set_priority_uid_filter：名单内 App 的 UID 对
 * getpriority/setpriority/ioprio 枚举"不存在"（fgetpriority/HMA-uidfake 的官方替代）。
 * 配置持久化于 /data/adb/susfs4ksu/priority_uid_filter.conf，由 susfs4ksu 模块开机重放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SusUidFilterTab(
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
) {
    val helper = koinInject<SuSFSConfigHelper>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var showAppPicker by remember { mutableStateOf(false) }
    var showManualAdd by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<String?>(null) }
    val manualText = remember { TextFieldState() }

    LaunchedEffect(Unit) {
        isLoading = true
        entries = helper.loadPriorityUidFilter()
        isLoading = false
    }

    fun applyFilter(list: List<String>, closeDialog: (() -> Unit)? = null) {
        scope.launch {
            isLoading = true
            val ok = helper.setPriorityUidFilter(list)
            if (ok) {
                entries = list
                closeDialog?.invoke()
            } else {
                snackbarHost.showReplacingSnackbar("应用失败，请检查内核是否支持 priority_uid_filter")
            }
            isLoading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
    ) {
        item {
            Spacer(Modifier.height(innerPadding.calculateTopPadding()))
        }

        item {
            SuSFSDescriptionCard(
                title = "UID 过滤",
                description = "名单内的应用对其 UID 的 getpriority/setpriority/ioprio 枚举返回\"不存在\"（-ESRCH）。" +
                    "用于隐藏工作资料/应用分身等次级用户。配置持久化于 /data/adb/susfs4ksu/priority_uid_filter.conf，开机自动重放。",
            )
        }

        item {
            SettingsJumpPageWidget(
                iconPlaceholder = false,
                title = "从已装应用添加",
                onClick = { showAppPicker = true },
            )
        }

        item {
            SettingsJumpPageWidget(
                iconPlaceholder = false,
                title = "手动输入包名",
                onClick = { showManualAdd = true },
            )
        }

        item {
            SettingsJumpPageWidget(
                iconPlaceholder = false,
                title = "清空全部",
                onClick = { applyFilter(emptyList()) },
            )
        }

        item {
            SettingsJumpPageWidget(
                iconPlaceholder = false,
                title = "当前名单（${entries.size}）",
            )
        }

        entries.forEach { pkg ->
            item {
                SettingsJumpPageWidget(
                    iconPlaceholder = false,
                    title = pkg,
                    onClick = { detailItem = pkg },
                )
            }
        }

        item {
            Spacer(Modifier.height(innerPadding.calculateBottomPadding()))
        }
    }

    AppPickerDialog(
        showDialog = showAppPicker,
        title = "选择要隐藏 UID 的应用",
        onDismiss = { showAppPicker = false },
        onPick = { pkg ->
            if (!entries.contains(pkg)) {
                applyFilter(entries + pkg)
            }
        },
    )

    ManualAddDialog(
        showDialog = showManualAdd,
        title = "手动输入包名",
        subtypes = listOf("包名"),
        selectedSubtype = "包名",
        onSubtypeChange = {},
        onDismiss = { showManualAdd = false },
        showImportFromFile = true,
        onImportFromFile = { imported -> manualText.setTextAndPlaceCursorAtEnd(imported) },
        onConfirm = {
            val pkgs = manualText.text.toString().toImportedEntryLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (pkgs.isEmpty()) return@ManualAddDialog
            val merged = (entries + pkgs).distinct()
            applyFilter(merged) { showManualAdd = false }
        },
        formContent = {
            item {
                SettingsTextFieldWidget(
                    state = manualText,
                    title = "包名（可多行，一行一个）",
                    useLabelAsPlaceholder = true,
                    enabled = !isLoading,
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 2,
                        maxHeightInLines = 6,
                    ),
                    renderBackgroundBlur = false,
                )
            }
        },
    )

    detailItem?.let { pkg ->
        EntryDetailDialog(
            showDialog = true,
            title = "条目详情",
            fields = listOf("包名" to pkg),
            onDismiss = { detailItem = null },
            onDelete = {
                applyFilter(entries - pkg)
                detailItem = null
            },
        )
    }
}
