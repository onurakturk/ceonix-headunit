package com.andrerinas.headunitrevived.main

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen app drawer: lists every launchable app on the device (icon + name) and
 * launches the one the user taps. Reached from the home screen's APPS tile.
 */
class AppsDrawerFragment : Fragment() {

    private data class AppEntry(val label: String, val icon: Drawable, val launch: Intent)

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_apps_drawer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { navigateBack() }
        })

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { navigateBack() }

        emptyView = view.findViewById(R.id.apps_empty)
        recycler = view.findViewById(R.id.apps_recycler)
        val spanCount = if (resources.configuration.screenWidthDp >= 720) 4 else 3
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount)

        loadApps()
    }

    private fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun loadApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            if (!isAdded) return@launch
            if (apps.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                recycler.adapter = AppsAdapter(apps)
            }
        }
    }

    private fun queryLaunchableApps(): List<AppEntry> {
        val pm = requireContext().packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ownPackage = requireContext().packageName
        return try {
            pm.queryIntentActivities(mainIntent, 0)
                .asSequence()
                .filter { it.activityInfo != null && it.activityInfo.packageName != ownPackage }
                .map { ri ->
                    val pkg = ri.activityInfo.packageName
                    val cls = ri.activityInfo.name
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pkg, cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    AppEntry(ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch)
                }
                .distinctBy { it.launch.component }
                .sortedBy { it.label.lowercase() }
                .toList()
        } catch (e: Exception) {
            AppLog.w("AppsDrawer: failed to query apps: ${e.message}")
            emptyList()
        }
    }

    private inner class AppsAdapter(private val items: List<AppEntry>) :
        RecyclerView.Adapter<AppsAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.app_icon)
            val name: TextView = itemView.findViewById(R.id.app_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = items[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label
            holder.itemView.setOnClickListener {
                try {
                    startActivity(app.launch)
                } catch (e: Exception) {
                    AppLog.w("AppsDrawer: failed to launch ${app.label}: ${e.message}")
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
