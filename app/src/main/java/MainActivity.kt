package dev.tvspecialist.hy300light

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

data class AppModel(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    companion object {
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/derickksantiago/hy300-launcher/releases/latest"
        private const val RELEASE_ASSET_NAME = "hy300-ultralight.apk"
        private const val MAX_UPDATE_BYTES = 200L * 1024 * 1024
        private const val TAG = "HY300Launcher"
    }

    private lateinit var appGrid: GridView
    private lateinit var tvRamStatus: TextView
    private val appList = mutableListOf<AppModel>()
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingUpdateInstall: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appGrid = findViewById(R.id.appGrid)
        tvRamStatus = findViewById(R.id.tvRamStatus)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnAtualizarRam = findViewById<Button>(R.id.btnAtualizarRam)
        val btnAtualizarLauncher = findViewById<Button>(R.id.btnAtualizarLauncher)

        // Atalho direto para Configurações do Android (Wi-Fi, Bluetooth, Keystone)
        btnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        btnAtualizarRam.setOnClickListener {
            updateRamMonitor()
        }

        btnAtualizarLauncher.setOnClickListener {
            checkForLauncherUpdate(btnAtualizarLauncher)
        }

        // Clique curto: Abre o aplicativo
        appGrid.setOnItemClickListener { _, _, position, _ ->
            val app = appList[position]
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(app.packageName, app.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(launchIntent)
        }

        // Clique longo (segurar botão OK no controle): Abre tela de Forçar Parada / Limpar Cache do App
        appGrid.setOnItemLongClickListener { _, _, position, _ ->
            val app = appList[position]
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
            }
            startActivity(intent)
            true
        }

        loadInstalledApps()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateRamMonitor()
        if (packageManager.canRequestPackageInstalls()) {
            pendingUpdateInstall?.let { apk ->
                pendingUpdateInstall = null
                openUpdateInstaller(apk)
            }
        }
    }

    private fun checkForLauncherUpdate(button: Button) {
        button.isEnabled = false
        button.text = "Verificando..."

        executor.execute {
            try {
                val release = fetchLatestRelease()
                val asset = findUpdateAsset(release)
                val apk = downloadAndValidateUpdate(asset)
                val installedVersion = packageManager.getPackageInfo(packageName, 0).versionCode
                val updateVersion = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                    ?.versionCode
                    ?: throw IllegalStateException("O APK baixado não pôde ser validado.")

                if (updateVersion <= installedVersion) {
                    apk.delete()
                    showUpdateResult(button, "Seu launcher já está atualizado.", "Atualizar launcher")
                    return@execute
                }

                showUpdateResult(button, "Atualização baixada. Preparando instalação...", "Atualizar launcher") {
                    beginUpdateInstall(apk)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Falha ao verificar ou baixar atualização", error)
                val message = when (error) {
                    is NoPublishedReleaseException ->
                        "Ainda não há uma atualização publicada no GitHub."
                    is NoUpdateAssetException ->
                        "A versão publicada não contém o arquivo $RELEASE_ASSET_NAME."
                    else -> "Não foi possível baixar a atualização. Verifique a conexão e tente novamente."
                }
                showUpdateResult(button, message, "Atualizar launcher")
            }
        }
    }

    private fun fetchLatestRelease(): JSONObject {
        val connection = URL(RELEASE_API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "HY300-UltraLight-Updater")

            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw NoPublishedReleaseException()
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub respondeu HTTP ${connection.responseCode}.")
            }

            val body = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    if (result.length + count > 1_000_000) {
                        throw IllegalStateException("A resposta do GitHub excedeu o limite permitido.")
                    }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun findUpdateAsset(release: JSONObject): JSONObject {
        val assets = release.optJSONArray("assets") ?: throw NoUpdateAssetException()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == RELEASE_ASSET_NAME) {
                val downloadUrl = Uri.parse(asset.optString("browser_download_url"))
                if (downloadUrl.scheme != "https" ||
                    downloadUrl.host != "github.com" ||
                    !downloadUrl.path.orEmpty().startsWith(
                        "/derickksantiago/hy300-launcher/releases/download/"
                    )
                ) {
                    throw IllegalStateException("O link do APK não pertence ao GitHub Releases esperado.")
                }
                return asset
            }
        }
        throw NoUpdateAssetException()
    }

    private fun downloadAndValidateUpdate(asset: JSONObject): File {
        val expectedSize = asset.optLong("size", -1L)
        if (expectedSize <= 0 || expectedSize > MAX_UPDATE_BYTES) {
            throw IllegalStateException("O tamanho do APK publicado é inválido.")
        }

        val directory = File(filesDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Não foi possível preparar o armazenamento da atualização.")
        }
        val temporaryFile = File(directory, "$RELEASE_ASSET_NAME.part")
        val updateFile = File(directory, RELEASE_ASSET_NAME)
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IllegalStateException("Não foi possível remover um download incompleto anterior.")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(asset.getString("browser_download_url"))
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "HY300-UltraLight-Updater")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("O download do APK falhou (HTTP ${connection.responseCode}).")
            }

            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        downloadedBytes += count
                        if (downloadedBytes > MAX_UPDATE_BYTES || downloadedBytes > expectedSize) {
                            throw IllegalStateException("O download excedeu o tamanho informado pelo GitHub.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }

            if (downloadedBytes != expectedSize) {
                throw IllegalStateException("O APK baixado está incompleto.")
            }
            val expectedDigest = asset.optString("digest")
            if (!expectedDigest.matches(Regex("(?i)^sha256:[a-f0-9]{64}$"))) {
                throw IllegalStateException("O GitHub não forneceu uma verificação SHA-256 válida para o APK.")
            }
            val actualDigest = digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            if (!actualDigest.equals(expectedDigest.substringAfter(':'), ignoreCase = true)) {
                throw IllegalStateException("A verificação de integridade do APK falhou.")
            }

            val updateInfo = packageManager.getPackageArchiveInfo(temporaryFile.absolutePath, 0)
                ?: throw IllegalStateException("O arquivo publicado não é um APK válido.")
            if (updateInfo.packageName != packageName) {
                throw IllegalStateException("O APK publicado não pertence a este launcher.")
            }

            if (updateFile.exists() && !updateFile.delete()) {
                throw IllegalStateException("Não foi possível substituir o APK anterior.")
            }
            if (!temporaryFile.renameTo(updateFile)) {
                throw IllegalStateException("Não foi possível finalizar o download do APK.")
            }
            return updateFile
        } catch (error: Exception) {
            temporaryFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun beginUpdateInstall(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdateInstall = apk
            AlertDialog.Builder(this)
                .setTitle("Permitir instalação")
                .setMessage("O Android precisa permitir que este launcher instale atualizações. Ative essa permissão e a instalação continuará.")
                .setPositiveButton("Abrir configurações") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (error: ActivityNotFoundException) {
                        pendingUpdateInstall = null
                        Log.e(TAG, "Não foi possível abrir a configuração de instalação", error)
                        Toast.makeText(
                            this,
                            "Não foi possível abrir a configuração de instalação do Android.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    pendingUpdateInstall = null
                }
                .show()
            return
        }
        openUpdateInstaller(apk)
    }

    private fun openUpdateInstaller(apk: File) {
        try {
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Nenhum instalador de APK está disponível", error)
            Toast.makeText(this, "Nenhum instalador de APK está disponível.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdateResult(
        button: Button,
        message: String,
        buttonLabel: String,
        afterToast: (() -> Unit)? = null
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            button.isEnabled = true
            button.text = buttonLabel
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            afterToast?.invoke()
        }
    }

    private fun loadInstalledApps() {
        executor.execute {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            }

            // Une apps de celular e apps de Android TV instalados no HY300 Pro
            val resolveInfos = (pm.queryIntentActivities(mainIntent, 0) +
                    pm.queryIntentActivities(leanbackIntent, 0))
                .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
                .filter { it.activityInfo.packageName != packageName } // Oculta a própria Launcher

            val loadedApps = resolveInfos.map { info ->
                AppModel(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    icon = info.loadIcon(pm)
                )
            }.sortedBy { it.label.lowercase(Locale.ROOT) }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                appList.clear()
                appList.addAll(loadedApps)
                appGrid.adapter = AppGridAdapter(this, appList)
                appGrid.requestFocus()
            }
        }
    }

    private fun updateRamMonitor() {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val availMemMB = memInfo.availMem / (1024 * 1024)
        val totalMemMB = memInfo.totalMem / (1024 * 1024)
        tvRamStatus.text = "RAM Livre: ${availMemMB}MB / ${totalMemMB}MB"
    }

    private class NoPublishedReleaseException : Exception()

    private class NoUpdateAssetException : Exception()

    // Impede que o botão Voltar do controle feche a Home
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private class AppGridAdapter(
        private val context: Context,
        private val apps: List<AppModel>
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)

        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val view: View

            if (convertView == null) {
                view = inflater.inflate(R.layout.item_app, parent, false)
                holder = ViewHolder(
                    view.findViewById(R.id.appIcon),
                    view.findViewById(R.id.appName)
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }

            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label

            // Efeito leve de escala via hardware quando o card recebe foco do controle
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.07f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
            }

            return view
        }

        private class ViewHolder(val icon: ImageView, val name: TextView)
    }
}