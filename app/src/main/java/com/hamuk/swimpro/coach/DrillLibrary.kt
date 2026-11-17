package com.hamuk.swimpro.coach

data class Drill(val name: String, val stroke: String, val cue: String)

/**
 * Common, widely taught swim drills (descriptions written for this app). The progressions draw
 * on ideas popularised by Swim Smooth, Total Immersion and Swim England's adult programmes.
 */
object DrillLibrary {
    val freestyle = listOf(
        Drill("Kick on side", "Freestyle", "lie on your side, lower arm extended, look down; balance comes from pressing your chest."),
        Drill("6-kick switch", "Freestyle", "6 kicks on your side, then one stroke to roll to the other side."),
        Drill("Catch-up", "Freestyle", "one hand waits out front until the other arrives — long, unhurried stroke."),
        Drill("Single-arm", "Freestyle", "one arm only, other arm at your side; breathe to the non-stroking side."),
        Drill("Fingertip drag", "Freestyle", "drag fingertips along the surface on recovery — high, relaxed elbow."),
        Drill("Fist", "Freestyle", "swim with closed fists so your forearm learns to hold the water."),
        Drill("Sculling", "Freestyle", "arms out front, small in/out sweeps; feel pressure on palms and forearms."),
        Drill("Bubble-bubble-breathe", "Freestyle", "exhale steadily underwater for two strokes, breathe on the third (bilateral)."),
        Drill("Zipper", "Freestyle", "thumb traces up your side to armpit on recovery — keeps the elbow high."),
        Drill("Head-up (Tarzan)", "Freestyle", "a few strokes looking forward — builds a strong, quick catch."),
    )
    val breaststroke = listOf(
        Drill("2 kicks, 1 pull", "Breaststroke", "glide in streamline after each kick — feel the kick drive you forward."),
        Drill("Breaststroke kick on back", "Breaststroke", "knees stay under the water; heels to bottom, feet turned out."),
        Drill("Pull with flutter kick", "Breaststroke", "small, fast 'heart-shaped' pull; hands never pass your shoulders."),
        Drill("Glide count", "Breaststroke", "pull–breathe–kick–glide: hold the glide for a count of 2."),
    )
    val backstroke = listOf(
        Drill("Kick on back, arms at sides", "Backstroke", "head still, ears in the water, hips high."),
        Drill("Single-arm backstroke", "Backstroke", "pinky enters first, rotate the shoulder with each stroke."),
        Drill("Double-arm backstroke", "Backstroke", "both arms together — feel the body's rotation timing."),
        Drill("Cup on forehead", "Backstroke", "imagine balancing a cup on your forehead — no head movement."),
    )
    val butterfly = listOf(
        Drill("Body dolphin", "Butterfly", "arms at sides, the wave starts from the chest, not the knees."),
        Drill("Single-arm fly", "Butterfly", "one arm, breathe to the side; two kicks per stroke."),
        Drill("3-3-3 fly", "Butterfly", "3 left-arm, 3 right-arm, 3 full strokes."),
        Drill("Dolphin kick on side", "Butterfly", "small, fast kicks from the core, both directions."),
    )

    fun forStroke(stroke: String): List<Drill> = when (stroke) {
        "Breaststroke" -> breaststroke
        "Backstroke" -> backstroke
        "Butterfly" -> butterfly
        else -> freestyle
    }

    /** Picks [count] drills for [stroke], rotating through the list by [offset]. */
    fun rotate(stroke: String, offset: Int, count: Int): List<Drill> {
        val list = forStroke(stroke)
        return List(count) { list[(offset + it).mod(list.size)] }.distinct()
    }

    val all: List<Drill> get() = freestyle + breaststroke + backstroke + butterfly
}
