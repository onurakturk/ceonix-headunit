package com.andrerinas.headunitrevived.main.ride

import android.content.Context
import android.location.Location
import android.view.View
import androidx.annotation.LayoutRes

/**
 * One page of the Ride dashboard's third card slot.
 *
 * The lifecycle is split the way it is for concrete reasons:
 *  - [bind] / [unbind] follow the RecyclerView **holder**, not the window. A card is unbound only
 *    when its holder is recycled; a scrap detach/reattach during re-layout never rebinds, so
 *    dropping the refs on detach would leave the card permanently blank.
 *  - [onLocation] is dispatched to *every* card, visible or not, so a card that keeps derived state
 *    (the sunset times, say) is already correct the moment the rider swipes to it.
 *  - [render] runs on the fragment's ~60 Hz frame loop and only while the page is attached, so it
 *    must not allocate.
 *  - [onPageActive] / [onPageInactive] mark the *selected* page and are the place to acquire and
 *    release anything expensive, such as sensors. A card that needs nothing can ignore them.
 */
abstract class RideCard(val id: String, @LayoutRes val layoutRes: Int) {

    protected var view: View? = null
        private set

    fun bind(v: View) {
        view = v
        onBind(v)
    }

    fun unbind() {
        onUnbind()
        view = null
    }

    protected abstract fun onBind(v: View)

    protected open fun onUnbind() {}

    /** Best available position. [loc] is call-scoped — read what you need, do not retain it. */
    open fun onLocation(loc: Location, precise: Boolean) {}

    abstract fun render(t: RideTelemetry)

    open fun onPageActive(context: Context) {}

    open fun onPageInactive() {}
}
