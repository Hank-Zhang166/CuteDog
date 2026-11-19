package com.cutedog.app.pet.animation

/** Explicit art acceptance scope independent of the asset manifest being checked. */
object ReleasedArtwork {
    private val directional = setOf("stand", "walk_start", "walk", "walk_stop", "walk_to_trot",
        "trot", "trot_to_walk", "backstep", "chase",
        "jump", "jump_takeoff", "land", "pause", "rebound", "slide_correct")
    val REQUIRED_ACTIONS = ClipManifest.REQUIRED_ACTIONS + setOf(
        "walk_to_trot", "trot_to_walk", "side_lie_idle",
        "side_to_lie", "curl_idle", "uncurl", "look_around", "chase", "jump_takeoff", "turn_to_left",
    ) + directional.map { "${it}_right" }
}
