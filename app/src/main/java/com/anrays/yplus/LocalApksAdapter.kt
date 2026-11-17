package com.anrays.yplus

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * 「更新应用 → 扫描本地 APK」的列表适配器。
 * 复用 [DeviceAppsAdapter] 的 item 布局（图标 + 两行文字），只是数据换成 [LocalApkItem]。
 */
class LocalApksAdapter(
    private val context: Context,
    private val items: List<LocalApkItem>
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): LocalApkItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_device_app, parent, false)
        val item = items[position]
        val icon = view.findViewById<ImageView>(R.id.ivDevIcon)
        val title = view.findViewById<TextView>(R.id.tvDevName)
        val subtitle = view.findViewById<TextView>(R.id.tvDevPackage)

        if (item.icon != null) icon.setImageDrawable(item.icon) else icon.setImageResource(R.drawable.ic_app_placeholder)
        // 复用的 item 布局默认 contentDescription 是"选择要克隆的本机应用"，这里按条目改掉
        icon.contentDescription = item.label
        title.text = item.label
        subtitle.text = item.subtitle
        return view
    }
}
