package com.cutedog.app.pet

import org.junit.Assert.*
import org.junit.Test

class PetSpaceTest {
    private val area = PetSpace.Box(0f, 0f, 1f, 1f)
    @Test fun routesAroundForbiddenRegionWithClearSegments() {
        val obstacles = listOf(PetSpace.Box(.35f, .3f, .65f, .7f))
        val start = PetSpace.Point(.1f, .5f); val end = PetSpace.Point(.9f, .5f)
        val route = PetSpace.route(start, end, area, obstacles)
        assertTrue(route.size >= 3); assertEquals(end, route.last())
        (listOf(start) + route).zipWithNext().forEach { (a,b) -> assertTrue(PetSpace.clearSegment(a,b,obstacles)) }
    }
    @Test fun impenetrableWallReturnsNoRoute() {
        assertTrue(PetSpace.route(PetSpace.Point(.1f,.5f),PetSpace.Point(.9f,.5f),area,listOf(PetSpace.Box(.4f,-.1f,.6f,1.1f))).isEmpty())
    }
    @Test fun petRadiusExpandsObstacleBeforeRouting() {
        val blocked = PetSpace.Box(.4f,.4f,.6f,.6f).inflate(.1f,.1f)
        assertFalse(PetSpace.valid(PetSpace.Point(.32f,.5f),area,listOf(blocked)))
        assertTrue(PetSpace.valid(PetSpace.Point(.29f,.5f),area,listOf(blocked)))
    }
    @Test fun initialPlacementAvoidsOverlappingObstacles() {
        val blocks = listOf(PetSpace.Box(.2f,.2f,.6f,.8f),PetSpace.Box(.5f,.4f,.9f,.6f))
        val point = PetSpace.nearestFree(PetSpace.Point(.55f,.5f),area,blocks)
        assertNotNull(point); assertTrue(PetSpace.valid(point!!,area,blocks))
    }
    @Test fun completelyBlockedAreaHasNoPlacement() {
        assertNull(PetSpace.nearestFree(PetSpace.Point(.5f,.5f),area,listOf(PetSpace.Box(-1f,-1f,2f,2f))))
    }
    @Test fun diagonalCornerCrossingIsNotMistakenForClear() {
        assertFalse(PetSpace.clearSegment(PetSpace.Point(0f,0f),PetSpace.Point(1f,1f),listOf(PetSpace.Box(.4f,.4f,.6f,.6f))))
    }
}
