package com.tower.dam

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tower.dam.data.AppItem
import com.tower.dam.data.DataManager
import kotlin.concurrent.thread

class AppListActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private var allApps = listOf<AppItem>() // 原始全量列表（包含所有应用，不做过滤）
    private var filteredApps = listOf<AppItem>() // 经过系统应用过滤后的列表
    private val selectedUids = mutableSetOf<Int>() // 当前选中的 UID 集合
    private var showSystemApps = false // 显示系统应用开关状态
    private var showUid = false // 显示 UID 开关状态

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        // 1. 初始化数据：加载已保存的勾选状态
        selectedUids.addAll(DataManager.getUids(this))

        // 2. 初始化 RecyclerView
        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        rvApps.layoutManager = LinearLayoutManager(this)

        adapter = AppAdapter({ item ->
            // 当某一项被点击时的回调
            if (item.isSelected) {
                selectedUids.add(item.uid)
            } else {
                selectedUids.remove(item.uid)
            }
            // 实时保存到本地
            DataManager.saveUids(this, selectedUids)
            
            // 更新allApps列表中对应AppItem的isSelected状态
            // 只更新被点击的特定应用，而不是所有相同UID的应用
            allApps = allApps.map { if (it.packageName == item.packageName) item else it }
            updateFilteredApps()
        }, showUid)
        rvApps.adapter = adapter

        // 3. 异步加载 App 列表（避免卡顿）
        loadSystemApps()

        // 4. 搜索框逻辑
        initSearchBar()

        // 5. 初始化显示系统应用开关
        initSystemAppsSwitch()
        
        // 6. 初始化显示 UID 开关
        initShowUidSwitch()
    }

    private fun loadSystemApps() {
        thread {
            val pm = packageManager
            val packages = pm.getInstalledApplications(0)

            // 只过滤掉当前应用，不过滤系统应用，留待后续根据开关状态处理
            allApps = packages.filter { it.packageName != "com.tower.dam" }
                .map { info ->
                    AppItem(
                        name = info.loadLabel(pm).toString(),
                        packageName = info.packageName,
                        icon = info.loadIcon(pm),
                        uid = info.uid,
                        flags = info.flags,
                        isSelected = selectedUids.contains(info.uid)
                    )
                }.sortedWith(compareBy({ !it.isSelected }, { it.name.lowercase() }))

            runOnUiThread {
                updateFilteredApps()
            }
        }
    }

    // 根据开关状态和搜索关键词更新显示的应用列表
    private fun updateFilteredApps() {
        // 1. 获取所有勾选的应用（无论系统还是用户）
        val selectedApps = allApps.filter { it.isSelected }
        
        // 2. 获取当前模式下的非勾选应用：
        //    - 开关开启：显示系统应用中的非勾选应用
        //    - 开关关闭：显示用户应用中的非勾选应用
        val modeApps = if (showSystemApps) {
            allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && !it.isSelected }
        } else {
            allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && !it.isSelected }
        }
        
        // 3. 合并列表：勾选的应用 + 当前模式下的非勾选应用
        // 使用distinctBy确保每个应用只显示一次（通过packageName去重）
        val combinedApps = (selectedApps + modeApps).distinctBy { it.packageName }
        
        // 4. 应用搜索过滤
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
        
        // 5. 排序：勾选的应用始终在顶部，然后按名称字母顺序排列
        val sortedList = finalList.sortedWith(compareBy({ !it.isSelected }, { it.name.lowercase() }))
        adapter.updateList(sortedList)
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

    // 初始化显示系统应用开关
    private fun initSystemAppsSwitch() {
        val switch = findViewById<Switch>(R.id.showSystemAppsSwitch)
        switch.setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            updateFilteredApps()
        }
    }
    
    // 初始化显示UID开关
    private fun initShowUidSwitch() {
        val switch = findViewById<Switch>(R.id.showUidSwitch)
        switch.setOnCheckedChangeListener { _, isChecked ->
            showUid = isChecked
            adapter.setShowUid(isChecked)
        }
    }
}

// --- 适配器类 ---
class AppAdapter(private val onCheckChanged: (AppItem) -> Unit, private var showUid: Boolean = false) :
    RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private var displayList = listOf<AppItem>()

    fun updateList(newList: List<AppItem>) {
        displayList = newList
        notifyDataSetChanged()
    }

    fun setShowUid(show: Boolean) {
        if (showUid != show) {
            showUid = show
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = displayList[position]
        holder.name.text = item.name
        holder.pkg.text = item.packageName
        holder.icon.setImageDrawable(item.icon)
        holder.checkBox.isChecked = item.isSelected
        
        // 设置UID显示
        holder.uid.text = "UID: ${item.uid}"
        holder.uid.visibility = if (showUid) View.VISIBLE else View.GONE

        // 点击整行触发勾选
        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.checkBox.isChecked = item.isSelected
            onCheckChanged(item)
        }
    }

    override fun getItemCount() = displayList.size

    class AppViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.appName)
        val pkg: TextView = v.findViewById(R.id.appPackage)
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val checkBox: CheckBox = v.findViewById(R.id.appCheckBox)
        val uid: TextView = v.findViewById(R.id.appUid)
    }
}