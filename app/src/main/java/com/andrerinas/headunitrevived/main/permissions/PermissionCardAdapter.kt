package com.andrerinas.headunitrevived.main.permissions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.R

/**
 * The dashboard's card list.
 *
 * The click handler lives on the adapter, not on [PermissionCard], on purpose. The list is rebuilt
 * from scratch on every resume and after every permission result; if each row carried its own
 * freshly created lambda, DiffUtil would see every row as changed and rebind the whole list each
 * time. [com.andrerinas.headunitrevived.main.BssidSetupFragment] documents where that leads —
 * the window never goes idle and accessibility services (and uiautomator) wait forever.
 */
class PermissionCardAdapter(
    private val onCardClick: (PermissionCard) -> Unit
) : ListAdapter<PermissionCard, PermissionCardAdapter.CardViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_permission_card, parent, false)
        return CardViewHolder(view, onCardClick)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CardViewHolder(
        itemView: View,
        private val onCardClick: (PermissionCard) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val card: View = itemView.findViewById(R.id.permission_card)
        private val icon: ImageView = itemView.findViewById(R.id.permission_icon)
        private val title: TextView = itemView.findViewById(R.id.permission_title)
        private val description: TextView = itemView.findViewById(R.id.permission_description)
        private val status: ImageView = itemView.findViewById(R.id.permission_status)

        fun bind(item: PermissionCard) {
            val context = itemView.context
            icon.setImageResource(item.spec.icon)
            title.setText(item.spec.title)

            // A permanently denied permission needs different instructions: tapping no longer
            // brings up a dialog, it opens our page in the system settings.
            description.setText(
                if (item.status == PermissionStatus.PERMANENTLY_DENIED) {
                    R.string.permission_open_app_settings_hint
                } else {
                    item.spec.description
                }
            )

            status.setImageResource(
                if (item.isSatisfied) R.drawable.ic_m3_check else R.drawable.ic_m3_alert
            )
            // Amber for a recommended gap, red only for something that actually blocks a
            // connection. Without the split, Continue turning gold with two red cards on screen
            // reads as a bug.
            val statusColor = when {
                item.isSatisfied -> R.color.m3_headlight
                item.spec.kind == PermissionKind.REQUIRED -> R.color.m3_error
                else -> R.color.m3_sunset
            }
            ImageViewCompat.setImageTintList(
                status,
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, statusColor))
            )

            // Nothing to do on a satisfied card, so it stops advertising itself as tappable.
            card.isClickable = !item.isSatisfied
            card.isFocusable = !item.isSatisfied
            card.setOnClickListener(if (item.isSatisfied) null else View.OnClickListener { onCardClick(item) })
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PermissionCard>() {
            override fun areItemsTheSame(oldItem: PermissionCard, newItem: PermissionCard) =
                oldItem.spec.id == newItem.spec.id

            override fun areContentsTheSame(oldItem: PermissionCard, newItem: PermissionCard) =
                oldItem == newItem
        }
    }
}
