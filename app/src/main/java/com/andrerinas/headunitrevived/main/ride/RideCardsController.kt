package com.andrerinas.headunitrevived.main.ride

import android.content.Context
import android.location.Location
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

/**
 * Owns the Ride screen's pageable third card slot: the pager, the page indicator, which card holds
 * resources, and the persisted selection.
 *
 * Shaped like StatusBarController - a plain wrapper over an already-inflated view, with
 * attach/detach and start/stop mirrored to the fragment lifecycle - so RideFragment, which sits
 * next to the connection path, keeps a small diff.
 */
class RideCardsController(root: View, private val settings: Settings) {

    private val cards = RideCards.create()
    private val adapter = RideCardAdapter(cards)
    private val pager: ViewPager2 = root.findViewById(R.id.ride_card_pager)
    private val dots: RideCardDotsView = root.findViewById(R.id.ride_card_dots)

    /**
     * ViewPager2 also fires onPageSelected for the initial programmatic settle. Persisting that
     * would rewrite a stored id this build does not recognise (an app update, a restored backup)
     * with the fallback - destroying the rider's choice in exactly the case the stable-id design
     * exists for. A drag always precedes the onPageSelected it causes, so this latch gates the write
     * to real swipes.
     */
    private var persistPageChanges = false

    /** A coarse fix must never overwrite a real GPS one. Moved here from RideFragment. */
    private var haveGpsFix = false

    private var started = false
    private var activeCard: RideCard? = null
    private var appContext: Context? = null

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            dots.setScrollPosition(position, positionOffset)
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) persistPageChanges = true
        }

        override fun onPageSelected(position: Int) {
            dots.selected = position
            swapActiveCard(position)
            if (!persistPageChanges) return
            val card = cards.getOrNull(position) ?: return
            settings.rideCardId = card.id
            AppLog.i("RideCards: page selected -> " + card.id + " (saved)")
        }
    }

    fun attach() {
        dots.count = cards.size
        // Order matters: setAdapter resets the current item, so the restore has to follow it.
        pager.adapter = adapter
        val index = RideCards.indexOfId(cards, settings.rideCardId)
        pager.setCurrentItem(index, false)
        dots.selected = index
        pager.registerOnPageChangeCallback(pageCallback)
    }

    fun detach() {
        stop()
        pager.unregisterOnPageChangeCallback(pageCallback)
        pager.adapter = null
        for (i in cards.indices) cards[i].unbind()
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        started = true
        swapActiveCard(pager.currentItem)
    }

    fun stop() {
        started = false
        activeCard?.onPageInactive()
        activeCard = null
    }

    /** Only the page the rider is actually looking at holds resources (sensors, listeners). */
    private fun swapActiveCard(position: Int) {
        if (!started) return
        val next = cards.getOrNull(position) ?: return
        if (next === activeCard) return
        activeCard?.onPageInactive()
        activeCard = next
        appContext?.let { next.onPageActive(it) }
    }

    /** Every card is fed, visible or not, so a swipe never lands on a stale readout. */
    fun onLocation(loc: Location, precise: Boolean) {
        if (precise) haveGpsFix = true else if (haveGpsFix) return
        for (i in cards.indices) cards[i].onLocation(loc, precise)
    }

    fun render(t: RideTelemetry) = adapter.render(t)
}
