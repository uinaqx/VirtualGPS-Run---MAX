package com.virtualrun.app.model

/** Closed routes keep their selected start; open routes exchange their start and finish. */
fun <T> reverseRoute(points: List<T>, closed: Boolean): List<T> {
    if (!closed || points.size < 3) return points.reversed()
    val first = points.first()
    val body = if (points.last() == first) points.drop(1).dropLast(1) else points.drop(1)
    return listOf(first) + body.reversed() + first
}
