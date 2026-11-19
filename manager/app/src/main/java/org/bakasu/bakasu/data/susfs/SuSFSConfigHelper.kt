package org.bakasu.bakasu.data.susfs

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.shell.KsuCliRepository

class SuSFSConfigHelper(
    private val ksuCliRepository: KsuCliRepository,
) {
    companion object {
        private const val TAG = "SuSFSConfigHelper"
        const val CURRENT_VERSION: Int = 2
    }

    private val gson = Gson()

    @Volatile
    private var cachedConfig: SuSFSConfig? = null

    @Volatile
    private var cachedStatusInfo: SuSFSStatusInfo? = null

    private val configMutex = Mutex()
    private val statusInfoMutex = Mutex()

    private data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
    )

    suspend fun loadConfig(): SuSFSConfig = configMutex.withLock {
        loadConfigLocked()
    }

    suspend fun refreshConfig(): SuSFSConfig = configMutex.withLock {
        cachedConfig = null
        loadConfigLocked()
    }

    private suspend fun loadConfigLocked(): SuSFSConfig = withContext(Dispatchers.IO) {
        cachedConfig?.let { return@withContext it }

        val result = executeSusfsCommand("config list_all")
        if (!result.success || result.stdout.isBlank()) {
            Log.e(TAG, "Failed to list SUSFS config: ${result.stderr}")
            return@withContext SuSFSConfig.createDefault().also { cachedConfig = it }
        }

        try {
            val config = gson.fromJson(result.stdout, SuSFSConfig::class.java)
            if (config.version != CURRENT_VERSION) {
                Log.e(
                    TAG,
                    "Incompatible SUSFS config version: ${config.version}, expected: $CURRENT_VERSION",
                )
                return@withContext SuSFSConfig.createDefault().also { cachedConfig = it }
            }

            config.also { cachedConfig = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SUSFS config", e)
            SuSFSConfig.createDefault().also { cachedConfig = it }
        }
    }

    suspend fun restoreDefaultConfig(): Boolean = executeConfigMutation("restore")

    suspend fun setConfigEnabled(enabled: Boolean): Boolean = executeConfigMutation(if (enabled) "enable" else "disable")

    suspend fun loadStatusInfo(forceRefresh: Boolean = false): SuSFSStatusInfo {
        if (!forceRefresh) {
            cachedStatusInfo?.let { return it }
        }

        return statusInfoMutex.withLock {
            if (!forceRefresh) {
                cachedStatusInfo?.let { return@withLock it }
            }

            val statusInfo = withContext(Dispatchers.IO) {
                val version = executeSusfsCommand("show version")
                val enabledFeatures = executeSusfsCommand("show enabled_features")
                val variant = executeSusfsCommand("show variant")

                SuSFSStatusInfo(
                    version = version.stdout.takeIf { version.success }.orEmpty(),
                    enabledFeatures = enabledFeatures.stdout.takeIf { enabledFeatures.success }
                        .orEmpty(),
                    variant = variant.stdout.takeIf { variant.success }.orEmpty(),
                )
            }

            cachedStatusInfo = statusInfo
            statusInfo
        }
    }

    suspend fun addSusPath(path: String): Boolean = executeConfigMutation(
        command = "sus_path add ${shellQuote(path)}",
        currentKernelCommands = listOf("add_sus_path ${shellQuote(path)}"),
    )

    suspend fun addSusPathLoop(path: String): Boolean = executeConfigMutation(
        command = "sus_path add ${shellQuote(path)} --loop",
        currentKernelCommands = listOf("add_sus_path_loop ${shellQuote(path)}"),
    )

    suspend fun removeSusPath(path: String): Boolean = executeConfigMutation("sus_path remove ${shellQuote(path)}")

    suspend fun addSusKstat(path: String): Boolean {
        val quotedPath = shellQuote(path)
        return executeConfigMutation(
            command = "sus_kstat add $quotedPath normal",
            currentKernelCommands = listOf(
                "add_sus_kstat $quotedPath",
                "update_sus_kstat $quotedPath",
            ),
        )
    }

    suspend fun addSusKstatFullClone(path: String): Boolean {
        val quotedPath = shellQuote(path)
        return executeConfigMutation(
            command = "sus_kstat add $quotedPath full_clone",
            currentKernelCommands = listOf(
                "add_sus_kstat $quotedPath",
                "update_sus_kstat_full_clone $quotedPath",
            ),
        )
    }

    suspend fun addSusKstatStatically(
        path: String,
        ino: Long? = null,
        dev: Long? = null,
        nlink: Long? = null,
        size: Long? = null,
        atime: Long? = null,
        atime_nsec: Long? = null,
        mtime: Long? = null,
        mtime_nsec: Long? = null,
        ctime: Long? = null,
        ctime_nsec: Long? = null,
        blocks: Long? = null,
        blksize: Long? = null,
    ): Boolean {
        val values = listOf(
            ino,
            dev,
            nlink,
            size,
            atime,
            atime_nsec,
            mtime,
            mtime_nsec,
            ctime,
            ctime_nsec,
            blocks,
            blksize,
        ).joinToString(" ") { it?.toString() ?: "default" }

        val quotedPath = shellQuote(path)
        return executeConfigMutation(
            command = "sus_kstat add $quotedPath statically $values",
            currentKernelCommands = listOf("add_sus_kstat_statically $quotedPath $values"),
        )
    }

    suspend fun removeSusKstat(path: String): Boolean = executeConfigMutation("sus_kstat remove ${shellQuote(path)}")

    suspend fun setUname(release: String, version: String): Boolean {
        val arguments = "${shellQuote(release)} ${shellQuote(version)}"
        return executeConfigMutation(
            command = "uname add $arguments",
            currentKernelCommands = listOf("set_uname $arguments"),
        )
    }

    suspend fun loadSlotInfo(): List<SuSFSSlotInfo>? {
        val result = executeSusfsCommand("slot_info")
        if (!result.success || result.stdout.isBlank()) {
            Log.e(TAG, "Failed to load SUSFS slot info: ${result.stderr}")
            return null
        }

        return try {
            val slots = checkNotNull(
                gson.fromJson(result.stdout, Array<SuSFSSlotInfo>::class.java),
            )
            check(
                slots.all { slot ->
                    slot.slotName.isNotBlank() &&
                        slot.uname.isNotBlank() &&
                        slot.buildTime.isNotBlank()
                },
            )
            slots.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SUSFS slot info", e)
            null
        }
    }

    suspend fun enableLog(enabled: Boolean): Boolean = executeConfigMutation(
        command = "logging ${if (enabled) "add" else "remove"}",
        currentKernelCommands = listOf("enable_log ${if (enabled) 1 else 0}"),
    )

    suspend fun hideSusMntsForNonSuProcs(enabled: Boolean): Boolean = executeConfigMutation(
        command = "hide_sus_mnts_for_non_su_procs ${if (enabled) "add" else "remove"}",
        currentKernelCommands = listOf(
            "hide_sus_mnts_for_non_su_procs ${if (enabled) 1 else 0}",
        ),
    )

    suspend fun enableAvcLogSpoofing(enabled: Boolean): Boolean = executeConfigMutation(
        command = "avc_log_spoofing ${if (enabled) "add" else "remove"}",
        currentKernelCommands = listOf(
            "enable_avc_log_spoofing ${if (enabled) 1 else 0}",
        ),
    )

    suspend fun setCmdlineOrBootconfig(path: String): Boolean {
        val command = if (path.isBlank()) {
            "cmdline_or_bootconfig remove"
        } else {
            "cmdline_or_bootconfig add ${shellQuote(path)}"
        }
        return if (path.isBlank()) {
            executeConfigMutation(command)
        } else {
            executeConfigMutation(
                command = command,
                currentKernelCommands = listOf(
                    "set_cmdline_or_bootconfig ${shellQuote(path)}",
                ),
            )
        }
    }

    suspend fun addOpenRedirect(
        targetPath: String,
        redirectedPath: String,
        uidScheme: UidScheme,
    ): Boolean {
        val arguments =
            "${shellQuote(targetPath)} ${shellQuote(redirectedPath)} ${uidScheme.value}"
        return executeConfigMutation(
            command = "open_redirect add $arguments",
            currentKernelCommands = listOf("add_open_redirect $arguments"),
        )
    }

    suspend fun removeOpenRedirect(targetPath: String): Boolean = executeConfigMutation("open_redirect remove ${shellQuote(targetPath)}")

    suspend fun addSusMap(path: String): Boolean = executeConfigMutation(
        command = "sus_map add ${shellQuote(path)}",
        currentKernelCommands = listOf("add_sus_map ${shellQuote(path)}"),
    )

    suspend fun removeSusMap(path: String): Boolean = executeConfigMutation("sus_map remove ${shellQuote(path)}")

    suspend fun showVersion(): String = loadStatusInfo().version

    suspend fun showEnabledFeatures(): String = loadStatusInfo().enabledFeatures

    suspend fun exportConfigToUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeSusfsCommand("config backup")
            if (!result.success || result.stdout.isBlank()) {
                Log.e(TAG, "Failed to back up SUSFS config: ${result.stderr}")
                return@withContext false
            }

            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(result.stdout)
                writer.newLine()
            } ?: return@withContext false

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export SUSFS config", e)
            false
        }
    }

    suspend fun importConfigFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("susfs_restore", ".json", context.cacheDir)
        try {
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
            if (!fileName.endsWith(".json", ignoreCase = true)) {
                Log.e(TAG, "Rejected SUSFS backup with invalid extension: $fileName")
                return@withContext false
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            executeConfigMutation("restore ${shellQuote(tempFile.absolutePath)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore SUSFS config", e)
            false
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun executeConfigMutation(
        command: String,
        currentKernelCommands: List<String> = emptyList(),
    ): Boolean = configMutex.withLock {
        executeConfigMutationLocked(command, currentKernelCommands)
    }

    private suspend fun executeConfigMutationLocked(
        command: String,
        currentKernelCommands: List<String>,
    ): Boolean {
        currentKernelCommands.forEach { currentKernelCommand ->
            val result = executeSusfsCommand(currentKernelCommand)
            if (!result.success) {
                Log.e(
                    TAG,
                    "SUSFS kernel command failed: $currentKernelCommand: ${result.stderr}",
                )
                return false
            }
        }

        val result = executeSusfsCommand("config $command")
        if (result.success) {
            cachedConfig = null
            cachedStatusInfo = null
        } else {
            Log.e(TAG, "SUSFS config command failed: $command: ${result.stderr}")
        }
        return result.success
    }

    private suspend fun executeSusfsCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val stdout = ArrayList<String>()
            val stderr = ArrayList<String>()
            val result = ksuCliRepository.withNewRootShell {
                newJob()
                    .add("${shellQuote(ksuCliRepository.getKsuDaemonPath())} susfs $command")
                    .to(stdout, stderr)
                    .exec()
            }

            CommandResult(
                success = result.isSuccess,
                stdout = stdout.joinToString("\n").trim(),
                stderr = stderr.joinToString("\n").trim(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute ksud susfs $command", e)
            CommandResult(false, "", e.message.orEmpty())
        }
    }

    // === HuaYu custom: priority uid filter（ksud 无此操作，直调 ksu_susfs 二进制）===

    private suspend fun execRootCapture(command: String): String? = withContext(Dispatchers.IO) {
        try {
            val stdout = ArrayList<String>()
            val stderr = ArrayList<String>()
            val result = ksuCliRepository.withNewRootShell {
                newJob()
                    .add(command)
                    .to(stdout, stderr)
                    .exec()
            }
            if (result.isSuccess) stdout.joinToString("\n") else null
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun execRootSimple(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val stdout = ArrayList<String>()
            val stderr = ArrayList<String>()
            ksuCliRepository.withNewRootShell {
                newJob()
                    .add(command)
                    .to(stdout, stderr)
                    .exec()
            }.isSuccess
        } catch (t: Throwable) {
            false
        }
    }

    /** 应用 UID 过滤名单到内核，并持久化到 /data/adb/susfs4ksu/priority_uid_filter.conf */
    suspend fun setPriorityUidFilter(packages: List<String>): Boolean {
        val joined = packages.joinToString(",")
        val applied = execRootSimple(
            "${shellQuote("/data/adb/ksu/bin/ksu_susfs")} set_priority_uid_filter ${shellQuote(joined)}"
        )
        val persist = if (packages.isEmpty()) {
            execRootSimple("mkdir -p /data/adb/susfs4ksu && rm -f /data/adb/susfs4ksu/priority_uid_filter.conf")
        } else {
            execRootSimple(
                "mkdir -p /data/adb/susfs4ksu && printf '%s\\n' " +
                    packages.joinToString(" ") { shellQuote(it) } +
                    " > /data/adb/susfs4ksu/priority_uid_filter.conf"
            )
        }
        return applied
    }

    /** 读取持久化的 UID 过滤名单 */
    suspend fun loadPriorityUidFilter(): List<String> {
        val out = execRootCapture("cat /data/adb/susfs4ksu/priority_uid_filter.conf 2>/dev/null") ?: return emptyList()
        return out.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * 按包名自动生成 sus_path 候选集（HuaYu custom）：
     * 存储 6 条 + 该 App 当前全部进程的 /proc/<pid>（loop 型，App 启动自动重标记）+
     * /dev、/sys 下属主为该 App UID 的节点。
     * 返回 (路径, 是否 loop)。
     */
    suspend fun generateAppSusPaths(pkg: String): List<Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<Pair<String, Boolean>>()
        listOf(
            "/data/data/$pkg",
            "/data/user/0/$pkg",
            "/storage/emulated/0/Android/data/$pkg",
            "/storage/emulated/0/Android/obb/$pkg",
            "/data/media/0/Android/data/$pkg",
            "/data/media/0/Android/obb/$pkg",
        ).forEach { paths.add(it to false) }
        val ps = execRootCapture("ps -A -o PID,USER,ARGS") ?: ""
        ps.split("\n").forEach { line ->
            if (line.contains(pkg) && !line.contains("PID")) {
                val pid = line.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                if (pid != null && pid > 0) {
                    paths.add("/proc/$pid" to true)
                }
            }
        }
        val uidOut = execRootCapture("stat -c %u /data/data/$pkg 2>/dev/null")?.trim()
        val uid = uidOut?.toIntOrNull()
        if (uid != null && uid > 10000) {
            val devOut = execRootCapture("/data/adb/ksu/bin/busybox find /dev -uid $uid 2>/dev/null") ?: ""
            devOut.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { paths.add(it to false) }
            val sysOut = execRootCapture("/data/adb/ksu/bin/busybox find /sys -uid $uid 2>/dev/null | head -50") ?: ""
            sysOut.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { paths.add(it to true) }
        }
        paths.distinctBy { it.first }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}

enum class SusKstatType {
    @SerializedName("Normal")
    Normal,

    @SerializedName("FullClone")
    FullClone,

    @SerializedName("Statically")
    Statically,
}

enum class UidScheme(val value: Int) {
    @SerializedName("NonApp")
    NonApp(0),

    @SerializedName("RootExceptSu")
    RootExceptSu(1),

    @SerializedName("NonSu")
    NonSu(2),

    @SerializedName("UnmountedApp")
    UnmountedApp(3),

    @SerializedName("Unmounted")
    Unmounted(4),
}

data class UnameConfig(
    @SerializedName("version")
    val version: String,
    @SerializedName("release")
    val release: String,
)

data class SuSFSSlotInfo(
    @SerializedName("slot_name")
    val slotName: String,
    @SerializedName("uname")
    val uname: String,
    @SerializedName("build_time")
    val buildTime: String,
)

data class SuSFSStatusInfo(
    val version: String,
    val enabledFeatures: String,
    val variant: String,
)

data class SusKstatStatically(
    @SerializedName("ino")
    val ino: Long?,
    @SerializedName("dev")
    val dev: Long?,
    @SerializedName("nlink")
    val nlink: Long?,
    @SerializedName("size")
    val size: Long?,
    @SerializedName("atime")
    val atime: Long?,
    @SerializedName("atime_nsec")
    val atime_nsec: Long?,
    @SerializedName("mtime")
    val mtime: Long?,
    @SerializedName("mtime_nsec")
    val mtime_nsec: Long?,
    @SerializedName("ctime")
    val ctime: Long?,
    @SerializedName("ctime_nsec")
    val ctime_nsec: Long?,
    @SerializedName("blocks")
    val blocks: Long?,
    @SerializedName("blksize")
    val blksize: Long?,
)

data class SusKstatItem(
    @SerializedName("path")
    val path: String,
    @SerializedName("spoof_type")
    val spoof_type: SusKstatType,
    @SerializedName("statically")
    val statically: SusKstatStatically?,
)

data class SusPathItem(
    @SerializedName("path")
    val path: String,
    @SerializedName("is_loop")
    val is_loop: Boolean,
)

data class OpenRedirectItem(
    @SerializedName("target_path")
    val target_path: String,
    @SerializedName("redirected_path")
    val redirected_path: String,
    @SerializedName("uid_scheme")
    val uid_scheme: UidScheme,
)

data class SuSFSConfig(
    @SerializedName("version")
    val version: Int,
    @SerializedName("enabled")
    val enabled: Boolean,
    @SerializedName("cmdline_or_bootconfig")
    val cmdline_or_bootconfig: String,
    @SerializedName("avc_log_spoofing")
    val avc_log_spoofing: Boolean,
    @SerializedName("logging")
    val logging: Boolean,
    @SerializedName("hide_sus_mnts_for_non_su_procs")
    val hide_sus_mnts_for_non_su_procs: Boolean,
    @SerializedName("uname")
    val uname: UnameConfig,
    @SerializedName("sus_path")
    val sus_path: Set<SusPathItem>,
    @SerializedName("sus_kstat")
    val sus_kstat: Set<SusKstatItem>,
    @SerializedName("open_redirect")
    val open_redirect: Set<OpenRedirectItem>,
    @SerializedName("sus_map")
    val sus_map: Set<String>,
) {
    companion object {
        fun createDefault(): SuSFSConfig = SuSFSConfig(
            version = SuSFSConfigHelper.CURRENT_VERSION,
            enabled = true,
            cmdline_or_bootconfig = "",
            avc_log_spoofing = false,
            logging = false,
            hide_sus_mnts_for_non_su_procs = false,
            uname = UnameConfig(version = "default", release = "default"),
            sus_path = emptySet(),
            sus_kstat = emptySet(),
            open_redirect = emptySet(),
            sus_map = emptySet(),
        )
    }
}
