package com.andrerinas.headunitrevived.main.ride

/**
 * The cards available in the Ride screen's third slot, in the order the rider swipes through them.
 *
 * WIRE FORMAT WARNING: the ids are persisted — they are what `Settings.rideCardId` stores so the
 * rider's choice survives an app update. **Never rename an id, and never reuse a retired one.**
 * The *order* may change freely: because the stored value is an id and not an index, inserting a
 * card at the front carries everyone's selection along with it instead of silently pointing it at
 * a different card.
 */
object RideCards {

    fun create(): List<RideCard> = listOf(GForceCard(), SunsetCard())

    /**
     * Index of [id] in [cards], or the default (first) card when the id is unknown.
     *
     * An unknown id is deliberately **not** written back — see `Settings.rideCardId`. A card can be
     * missing because this build is older than the one that wrote the value, or because it is gated
     * behind something; when it comes back, the rider's pick should still be there.
     */
    fun indexOfId(cards: List<RideCard>, id: String): Int {
        val i = cards.indexOfFirst { it.id == id }
        return if (i >= 0) i else 0
    }
}
