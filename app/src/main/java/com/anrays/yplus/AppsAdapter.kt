package com.anrays.yplus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppsAdapter(
    private val onClick: (VirtualAppItem) -> Unit,
    private val onLongClick: (VirtualAppItem) -> Unit
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    private val items = ArrayList<VirtualAppItem>()

    fun submit(list: List<VirtualAppItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.ivAppIcon)
        private val name: TextView = view.findViewById(R.id.tvAppName)
        private val pkg: TextView = view.findViewById(R.id.tvAppPackage)

        fun bind(item: VirtualAppItem) {
            val drawable = item.icon
            if (drawable != null) {
                icon.setImageDrawable(drawable)
            } else {
                icon.setImageResource(R.drawable.ic_app_placeholder)
            }
            name.text = item.label
            pkg.text = item.packageName
        }
    }
}
