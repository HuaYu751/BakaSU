package org.bakasu.bakasu.ui.screen.susfs.component

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用选择器（HuaYu custom）。
 * 返回选中的包名。供 UID 过滤页签与隐藏路径「按 App 添加」共用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(
    showDialog: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    if (!showDialog) return
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(showDialog) {
        if (showDialog) {
            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val acts = pm.queryIntentActivities(intent, 0)
                    apps = acts.mapNotNull { ai ->
                        val pkg = ai.activityInfo.applicationInfo.packageName
                        val label = try { ai.loadLabel(pm).toString() } catch (t: Throwable) { pkg }
                        if (pkg.isNotBlank()) pkg to label else null
                    }.distinctBy { it.first }.sortedBy { it.second }
                } catch (t: Throwable) {
                    apps = emptyList()
                }
            }
        }
    }

    val filtered = if (query.isBlank()) {
        apps
    } else {
        apps.filter { it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索应用") },
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    items(filtered) { (pkg, label) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(pkg)
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(pkg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
