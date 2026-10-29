package com.mcarplay.app.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.mcarplay.app.BuildConfig
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.carlife.CarLifeDiagnostics
import com.mcarplay.app.i18n.AppLanguage
import com.mcarplay.app.ui.WhatsNewDialog
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DebugFileLog
import java.io.File

/** Manual, foreground-only update flow. The Android installer remains the final authority. */
class UpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var progress: ProgressBar
    private lateinit var check: Button
    private lateinit var download: Button
    private lateinit var install: Button
    private lateinit var cancel: Button
    private lateinit var preview: CheckBox
    private var candidate: GitHubRelease? = null
    private var verified: DownloadedUpdate? = null
    private var activeJob: UpdateJob? = null
    private var busy = false
    private var pendingInstall = false

    private val unknownSources =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingInstall) {
                pendingInstall = false
                if (packageManager.canRequestPackageInstalls()) {
                    requestInstall()
                } else {
                    status.setText(R.string.update_source_denied)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        status = findViewById(R.id.updateStatus)
        notes = findViewById(R.id.updateNotes)
        progress = findViewById(R.id.updateProgress)
        check = findViewById(R.id.updateCheck)
        download = findViewById(R.id.updateDownload)
        install = findViewById(R.id.updateInstall)
        cancel = findViewById(R.id.updateCancel)
        preview = findViewById(R.id.updatePreview)
        findViewById<TextView>(R.id.updateCurrent).text =
            getString(
                R.string.update_current,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.RELEASE_TAG.ifBlank { getString(R.string.update_unknown_tag) },
                UpdateSource.REPOSITORY,
            )
        val preferences = getSharedPreferences("updates", MODE_PRIVATE)
        preview.isChecked = preferences.getBoolean("includePreviews", true)
        preview.setOnCheckedChangeListener { _, value ->
            preferences.edit().putBoolean("includePreviews", value).apply()
            requestCheck()
        }
        check.setOnClickListener { requestCheck() }
        download.setOnClickListener { requestDownload() }
        install.setOnClickListener { requestInstall() }
        cancel.setOnClickListener {
            activeJob?.cancel()
            status.setText(R.string.update_cancelling)
            cancel.isEnabled = false
        }
        findViewById<Button>(R.id.updateBrowser).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate?.pageUrl ?: UpdateSource.RELEASES_URL))) }
                .onFailure { status.text = getString(R.string.update_browser_failed, UpdateSource.RELEASES_URL) }
        }
        findViewById<View>(R.id.updateBack).setOnClickListener { finish() }
        refresh()
        requestCheck()
    }

    private fun connectionIdle(): Boolean {
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get() || CarLifeDiagnostics.isRunning()) {
            status.setText(R.string.update_connection_active)
            return false
        }
        return true
    }

    private fun requestCheck() {
        if (busy || !connectionIdle()) return
        candidate = null
        verified = null
        notes.text = ""
        val includePreviews = preview.isChecked
        status.setText(R.string.update_checking)
        task({ job ->
            UpdateSource.fetch(GitHubHttp, includePreviews, job)
        }) { release ->
            candidate = release
            if (release == null) {
                status.setText(R.string.update_no_release)
            } else {
                val relation = relation(release)
                status.text =
                    when (relation) {
                        UpdateRelation.NEWER ->
                            getString(if (release.preview) R.string.update_new_preview else R.string.update_new_stable, release.version.tag)
                        UpdateRelation.SAME -> getString(R.string.update_latest, release.version.tag)
                        UpdateRelation.OLDER -> getString(R.string.update_older, release.version.tag)
                        UpdateRelation.UNKNOWN_SAME_BASE -> getString(R.string.update_same_base, release.version.tag)
                    }
                // 发行说明是中英双语的 CHANGELOG 条目：只显示当前界面语言那一半
                val body =
                    ReleaseNotes.localize(release.notes, AppLanguage.current(this))?.let(WhatsNewDialog::render)
                        ?: release.notes.ifBlank { getString(R.string.update_no_notes) }
                notes.text =
                    SpannableStringBuilder(getString(R.string.update_release_notes, release.title, release.apk.size / 1024))
                        .append(body)
                if (BuildConfig.DEBUG) notes.append(getString(R.string.update_debug_warning))
                if (BuildConfig.FLAVOR != "standard") notes.append(getString(R.string.update_remote_warning))
            }
        }
    }

    private fun relation(release: GitHubRelease): UpdateRelation {
        val base =
            ReleaseVersion.parse(BuildConfig.BASE_VERSION)
                ?: throw UpdateException("LOCAL_VERSION", "当前版本格式无法识别，请打开发行页")
        return compareRelease(release.version, base, BuildConfig.RELEASE_TAG)
    }

    private fun requestDownload() {
        val release = candidate ?: return
        if (!connectionIdle() || relation(release) != UpdateRelation.NEWER) return
        status.text = getString(R.string.update_downloading, release.version.tag)
        verified = null
        task({ job ->
            val directory = File(cacheDir, "updates")
            directory.listFiles()?.filter { it.name.startsWith("update-") && it.extension == "part" }?.forEach { it.delete() }
            directory
                .listFiles()
                ?.filter { it.name.startsWith("mcarplay-update-") && it.extension == "apk" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(2)
                ?.forEach { it.delete() }
            var lastProgress = 0L
            val artifact =
                UpdateDownloader().download(release, directory, job) { received, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 250 || received == total) {
                        lastProgress = now
                        ui {
                            progress.isIndeterminate = false
                            progress.progress = (received * 100 / total).toInt()
                            status.text = getString(R.string.update_download_progress, received / 1024, total / 1024)
                        }
                    }
                }
            try {
                ApkUpdateVerifier.verify(applicationContext, artifact, release, job)
                check(artifact.file.setReadOnly()) { "Cannot protect verified update file" }
                artifact
            } catch (error: Exception) {
                artifact.file.delete()
                throw error
            }
        }) { artifact ->
            verified = artifact
            status.setText(R.string.update_verified)
            notes.append(getString(R.string.update_hash, artifact.sha256))
        }
    }

    private fun requestInstall() {
        val artifact = verified ?: return
        val release = candidate ?: return
        if (!connectionIdle()) return
        status.setText(R.string.update_verifying)
        task({ job -> ApkUpdateVerifier.verify(applicationContext, artifact, release, job) }) {
            if (!connectionIdle()) return@task
            if (!packageManager.canRequestPackageInstalls()) {
                pendingInstall = true
                runCatching {
                    unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }.onFailure {
                    pendingInstall = false
                    status.setText(R.string.update_source_failed)
                }
            } else {
                runCatching {
                    val uri = FileProvider.getUriForFile(this, "$packageName.updates", artifact.file)
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                    status.setText(R.string.update_installer_opened)
                }.onFailure { status.setText(R.string.update_installer_failed) }
            }
        }
    }

    private fun <T> task(
        work: (UpdateJob) -> T,
        success: (T) -> Unit,
    ) {
        if (busy) return
        val job = UpdateJobs.acquire()
        if (job == null) {
            status.setText(R.string.update_busy)
            return
        }
        activeJob = job
        busy = true
        refresh()
        Bg.run("github-update") {
            val result = runCatching { work(job).also { job.check() } }
            result.exceptionOrNull()?.let { error ->
                DebugFileLog.append(
                    applicationContext,
                    "UPDATE: failed type=${error.javaClass.simpleName} code=${(error as? UpdateException)?.code}",
                )
            }
            UpdateJobs.release(job)
            ui {
                activeJob = null
                busy = false
                val outcome = if (job.isCancelled) Result.failure<T>(UpdateException("CANCELLED", "更新操作已取消")) else result
                outcome.fold(success) { error ->
                    status.text =
                        if (error is UpdateException) {
                            getString(R.string.update_error, UpdateErrorText.message(this, error), error.code)
                        } else {
                            getString(R.string.update_failed)
                        }
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        check.isEnabled = !busy
        preview.isEnabled = !busy
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        cancel.isEnabled = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = true
        download.isEnabled = !busy &&
            verified == null &&
            BuildConfig.FLAVOR == "standard" &&
            candidate?.let { relation(it) == UpdateRelation.NEWER } == true
        install.isEnabled = !busy && verified != null
        listOf(check, download, install, cancel, preview).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        super.onDestroy()
    }
}
