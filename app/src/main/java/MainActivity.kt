package dev.tvspecialist.hy300light

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale
import java.util.concurrent.Executors

data class AppModel(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    private lateinit var appGrid: GridView
    private lateinit var tvRamStatus: TextView
    private val appList = mutableListOf<AppModel>()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appGrid = findViewById(R.id.appGrid)
        tvRamStatus = findViewById(R.id.tvRamStatus)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnAtualizarRam = findViewById<Button>(R.id.btnAtualizarRam)

        // Atalho direto para Configurações do Android (Wi-Fi, Bluetooth, Keystone)
        btnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        btnAtualizarRam.setOnClickListener {
            updateRamMonitor()
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