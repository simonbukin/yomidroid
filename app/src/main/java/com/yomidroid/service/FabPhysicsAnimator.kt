package com.yomidroid.service

import android.view.View
import android.view.WindowManager
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Handles physics-based FAB animations for natural fling and edge bounce.
 */
class FabPhysicsAnimator(
    private val view: View,
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val onPositionUpdate: () -> Unit
) {
    private var flingAnimationX: FlingAnimation? = null
    private var flingAnimationY: FlingAnimation? = null
    private var springAnimationX: SpringAnimation? = null
    private var springAnimationY: SpringAnimation? = null

    // Screen bounds (updated before each fling)
    var screenWidth: Int = 0
    var screenHeight: Int = 0
    var fabWidth: Int = 0
    var fabHeight: Int = 0

    companion object {
        // Fling friction - lower = slides further (1.0 is minimal friction)
        private const val FLING_FRICTION = 1.5f

        // Spring stiffness for edge bounce - lower = bouncier
        private const val EDGE_SPRING_STIFFNESS = 400f

        // Spring damping for edge bounce - lower = more oscillation
        private const val EDGE_SPRING_DAMPING = 0.6f

        // Margin from screen edge
        private const val EDGE_MARGIN = 20

        // Velocity threshold to trigger fling
        const val MIN_FLING_VELOCITY = 200f
    }

    /**
     * Start a physics-based fling from current position with given velocity.
     */
    fun fling(velocityX: Float, velocityY: Float) {
        cancelAll()

        val startX = params.x.toFloat()
        val startY = params.y.toFloat()

        // Calculate bounds
        val minX = (-fabWidth + EDGE_MARGIN).toFloat()
        val maxX = (screenWidth - EDGE_MARGIN).toFloat()
        val minY = (-fabHeight + EDGE_MARGIN).toFloat()
        val maxY = (screenHeight - EDGE_MARGIN).toFloat()

        // Check if already out of bounds and need spring-back
        if (startX < minX || startX > maxX || startY < minY || startY > maxY) {
            springBack(startX, startY, minX, maxX, minY, maxY)
            return
        }

        // Create X fling animation
        val xHolder = FloatValueHolder(startX)
        flingAnimationX = FlingAnimation(xHolder).apply {
            setStartVelocity(velocityX)
            friction = FLING_FRICTION
            setMinValue(minX)
            setMaxValue(maxX)

            addUpdateListener { _, value, _ ->
                params.x = value.toInt()
                updateViewAndNotify()
            }

            addEndListener { _, canceled, value, _ ->
                if (!canceled && (value <= minX || value >= maxX)) {
                    springToEdgeX(value, minX, maxX)
                }
            }
        }

        // Create Y fling animation
        val yHolder = FloatValueHolder(startY)
        flingAnimationY = FlingAnimation(yHolder).apply {
            setStartVelocity(velocityY)
            friction = FLING_FRICTION
            setMinValue(minY)
            setMaxValue(maxY)

            addUpdateListener { _, value, _ ->
                params.y = value.toInt()
                updateViewAndNotify()
            }

            addEndListener { _, canceled, value, _ ->
                if (!canceled && (value <= minY || value >= maxY)) {
                    springToEdgeY(value, minY, maxY)
                }
            }
        }

        flingAnimationX?.start()
        flingAnimationY?.start()
    }

    /**
     * Spring back from rubber-banded position to valid bounds.
     */
    private fun springBack(
        currentX: Float, currentY: Float,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float
    ) {
        // Spring X if out of bounds
        if (currentX < minX || currentX > maxX) {
            val targetX = currentX.coerceIn(minX, maxX)
            val xHolder = FloatValueHolder(currentX)
            springAnimationX = SpringAnimation(xHolder).apply {
                spring = SpringForce(targetX).apply {
                    stiffness = EDGE_SPRING_STIFFNESS
                    dampingRatio = EDGE_SPRING_DAMPING
                }
                addUpdateListener { _, value, _ ->
                    params.x = value.toInt()
                    updateViewAndNotify()
                }
                start()
            }
        }

        // Spring Y if out of bounds
        if (currentY < minY || currentY > maxY) {
            val targetY = currentY.coerceIn(minY, maxY)
            val yHolder = FloatValueHolder(currentY)
            springAnimationY = SpringAnimation(yHolder).apply {
                spring = SpringForce(targetY).apply {
                    stiffness = EDGE_SPRING_STIFFNESS
                    dampingRatio = EDGE_SPRING_DAMPING
                }
                addUpdateListener { _, value, _ ->
                    params.y = value.toInt()
                    updateViewAndNotify()
                }
                start()
            }
        }
    }

    /**
     * Apply spring bounce when hitting horizontal edge.
     */
    private fun springToEdgeX(currentValue: Float, minX: Float, maxX: Float) {
        val targetX = when {
            currentValue <= minX -> minX
            currentValue >= maxX -> maxX
            else -> return
        }

        springAnimationX?.cancel()
        val xHolder = FloatValueHolder(currentValue)
        springAnimationX = SpringAnimation(xHolder).apply {
            spring = SpringForce(targetX).apply {
                stiffness = EDGE_SPRING_STIFFNESS
                dampingRatio = EDGE_SPRING_DAMPING
            }
            addUpdateListener { _, value, _ ->
                params.x = value.toInt()
                updateViewAndNotify()
            }
            start()
        }
    }

    /**
     * Apply spring bounce when hitting vertical edge.
     */
    private fun springToEdgeY(currentValue: Float, minY: Float, maxY: Float) {
        val targetY = when {
            currentValue <= minY -> minY
            currentValue >= maxY -> maxY
            else -> return
        }

        springAnimationY?.cancel()
        val yHolder = FloatValueHolder(currentValue)
        springAnimationY = SpringAnimation(yHolder).apply {
            spring = SpringForce(targetY).apply {
                stiffness = EDGE_SPRING_STIFFNESS
                dampingRatio = EDGE_SPRING_DAMPING
            }
            addUpdateListener { _, value, _ ->
                params.y = value.toInt()
                updateViewAndNotify()
            }
            start()
        }
    }

    private fun updateViewAndNotify() {
        try {
            windowManager.updateViewLayout(view, params)
            onPositionUpdate()
        } catch (e: Exception) {
            // View may have been removed
        }
    }

    fun cancelAll() {
        flingAnimationX?.cancel()
        flingAnimationY?.cancel()
        springAnimationX?.cancel()
        springAnimationY?.cancel()
    }

    fun isRunning(): Boolean {
        return flingAnimationX?.isRunning == true ||
               flingAnimationY?.isRunning == true ||
               springAnimationX?.isRunning == true ||
               springAnimationY?.isRunning == true
    }
}
