package com.anrays.yplus

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

class DeviceAppsAdapter(
    private val context: Context,
    private val items: List<DeviceAppItem>
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): DeviceAppItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_device_app, parent, false)
        val item = items[position]
        val icon = view.findViewById<ImageView>(R.id.ivDevIcon)
        val title = view.findViewById<TextView>(R.id.tvDevName)
        val pkg = view.findViewById<TextView>(R.id.tvDevPackage)

        if (item.icon != null) icon.setImageDrawable(item.icon) else icon.setImageResource(R.drawable.ic_app_placeholder)
        title.text = item.label
        pkg.text = item.packageName
        return view
    }
}
