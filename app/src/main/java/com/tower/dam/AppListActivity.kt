package com.tower.dam

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tower.dam.data.AppItem
import com.tower.dam.data.DataManager
import kotlin.concurrent.thread

private data class AppRow(val item: AppItem, val showUid: Boolean)

private object AppRowDiffCallback : DiffUtil.ItemCallback<AppRow>() {
    override fun areItemsTheSame(oldItem: AppRow, newItem: AppRow) =
        oldItem.item.packageName == newItem.item.packageName

    override fun areContentsTheSame(oldItem: AppRow, newItem: AppRow) =
        oldItem.item.name == newItem.item.name &&
            oldItem.item.packageName == newItem.item.packageName &&
            oldItem.item.isSelected == newItem.item.isSelected &&
            oldItem.item.uid == newItem.item.uid &&
            oldItem.showUid == newItem.showUid
}

class AppListActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val selectedUids = mutableSetOf<Int>()
    private var allApps = listOf<AppItem>()
    private var showSystemApps = false
    private var showUid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        rvApps.layoutManager = LinearLayoutManager(this)

        (application as DamApp).ensureIptablesProbed {
            selectedUids.clear()
            selectedUids.addAll(DataManager.getUids(this))
            adapter = AppAdapter(
                { item ->
                    if (item.isSelected) selectedUids.add(item.uid)
                    else selectedUids.remove(item.uid)
                    DataManager.saveUids(this, selectedUids)
                    allApps = allApps.map { if (it.packageName == item.packageName) item else it }
                    updateFilteredApps()
                },
                showUid
            )
            rvApps.adapter = adapter
            initSearchBar()
            initSystemAppsSwitch()
            initShowUidSwitch()
            loadSystemApps()
        }
    }

    private fun loadSystemApps() {
        thread {
            val pm = packageManager
            val packages = pm.getInstalledApplications(0)
            val uidSnapshot = selectedUids.toSet()

            allApps = packages.filter { it.packageName != "com.tower.dam" }
                .map { info ->
                    AppItem(
                        name = info.loadLabel(pm).toString(),
                        packageName = info.packageName,
                        icon = info.loadIcon(pm),
                        uid = info.uid,
                        flags = info.flags,
                        isSelected = uidSnapshot.contains(info.uid)
                    )
                }.sortedWith(compareBy({ !it.isSelected }, { it.name.lowercase() }))

            runOnUiThread {
                updateFilteredApps()
            }
        }
    }

    private fun updateFilteredApps() {
        val selectedApps = allApps.filter { it.isSelected }
        val modeApps = if (showSystemApps) {
            allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && !it.isSelected }
        } else {
            allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && !it.isSelected }
        }
        val combinedApps = (selectedApps + modeApps).distinctBy { it.packageName }
        val searchText = findViewById<EditText>(R.id.searchBar).text.toString().lowercase()
        val finalList = if (searchText.isNotEmpty()) {
            combinedApps.filter {
                it.name.lowercase().contains(searchText) ||
                    it.packageName.lowercase().contains(searchText) ||
                    it.uid.toString().contains(searchText)
            }
        } else {
            combinedApps
        }
        val sortedList = finalList.sortedWith(compareBy({ !it.isSelected }, { it.name.lowercase() }))
        adapter.submitList(sortedList.map { AppRow(it, showUid) })
    }

    private fun initSearchBar() {
        findViewById<EditText>(R.id.searchBar).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateFilteredApps()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun initSystemAppsSwitch() {
        findViewById<Switch>(R.id.showSystemAppsSwitch).setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            updateFilteredApps()
        }
    }

    private fun initShowUidSwitch() {
        findViewById<Switch>(R.id.showUidSwitch).setOnCheckedChangeListener { _, isChecked ->
            showUid = isChecked
            adapter.setShowUid(isChecked)
            val current = adapter.currentList.map { AppRow(it.item, showUid) }
            adapter.submitList(current)
        }
    }
}

private class AppAdapter(
    private val onCheckChanged: (AppItem) -> Unit,
    private var showUid: Boolean
) : ListAdapter<AppRow, AppAdapter.AppViewHolder>(AppRowDiffCallback) {

    fun setShowUid(show: Boolean) {
        showUid = show
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val row = getItem(position)
        val item = row.item
        holder.name.text = item.name
        holder.pkg.text = item.packageName
        holder.icon.setImageDrawable(item.icon)
        holder.checkBox.isChecked = item.isSelected
        holder.uid.text = "UID: ${item.uid}"
        holder.uid.visibility = if (row.showUid) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.checkBox.isChecked = item.isSelected
            onCheckChanged(item)
        }
    }

    class AppViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.appName)
        val pkg: TextView = v.findViewById(R.id.appPackage)
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val checkBox: CheckBox = v.findViewById(R.id.appCheckBox)
        val uid: TextView = v.findViewById(R.id.appUid)
    }
}
