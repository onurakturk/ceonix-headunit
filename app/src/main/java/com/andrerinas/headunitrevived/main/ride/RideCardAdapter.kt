package com.andrerinas.headunitrevived.main.ride

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Pages for the Ride card pager. A plain view adapter rather than a FragmentStateAdapter — the
 * pages are readouts with no lifecycle of their own, and the fragment already drives them per frame.
 */
class RideCardAdapter(private val cards: List<RideCard>) : RecyclerView.Adapter<RideCardAdapter.VH>() {

    class VH(val card: RideCard, view: View) : RecyclerView.ViewHolder(view)

    /** Holders currently attached to the window: one at rest, two mid-swipe. */
    private val attached = ArrayList<VH>(2)

    override fun getItemCount() = cards.size

    /**
     * One view type per position, so RecyclerView can never hand a holder built for one card to a
     * different one. With a handful of pages the extra pool entries cost nothing and the invariant
     * becomes structural instead of a convention.
     */
    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = cards[viewType]
        // attachToRoot=false so the page keeps the match_parent LayoutParams from its XML;
        // ViewPager2 throws "Pages must fill the whole ViewPager2" otherwise.
        val v = LayoutInflater.from(parent.context).inflate(card.layoutRes, parent, false)
        return VH(card, v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.card.bind(holder.itemView)

    /** Refs die on recycle, not on detach: a scrap detach/reattach never rebinds. */
    override fun onViewRecycled(holder: VH) {
        holder.card.unbind()
    }

    override fun onViewAttachedToWindow(holder: VH) {
        if (!attached.contains(holder)) attached.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        attached.remove(holder)
    }

    /** Per-frame fan-out to whatever is on screen. Indexed loop: no iterator, no allocation. */
    fun render(t: RideTelemetry) {
        for (i in attached.indices) attached[i].card.render(t)
    }
}
