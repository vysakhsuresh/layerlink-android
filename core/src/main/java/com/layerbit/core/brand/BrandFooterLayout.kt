package com.layerbit.core.brand

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Root view of view_brand_footer.xml. Its only job is to keep the footer's
 * [AnimatedVectorDrawable] icons (the steaming coffee cup, the live chat bubble) running exactly
 * while the footer is actually on screen - and never a frame longer.
 *
 * An AnimatedVectorDrawable set through `android:src` does NOT start on its own: something has to
 * call [AnimatedVectorDrawable.start]. Doing that from the host Activity would mean every Layerbit
 * app that drops this footer in has to remember to, and to stop again in `onStop`, and again
 * whenever the footer is shown or hidden with `isVisible`. Owning it here keeps the footer a true
 * self-contained `<include>`: the host app writes no animation code at all.
 *
 * [onVisibilityAggregated] (API 24+) is the hook that makes that work. It fires for this view's
 * own visibility, for any ancestor going GONE/INVISIBLE, and for the window becoming invisible -
 * so `binding.brandFooterInclude.root.isVisible = ...` in the host and a plain backgrounded
 * Activity both land here. Starts are idempotent and tracked with a local flag rather than
 * trusting [AnimatedVectorDrawable.isRunning], which is approximate once the animator has been
 * handed off to the RenderThread.
 *
 * Nothing here needs a visible-state fallback for "Remove animations" / battery-saver
 * (`ValueAnimator.areAnimatorsEnabled() == false`): both AVDs are authored so that frame 0 is the
 * static glyph, so a frozen drawable still looks correct.
 */
class BrandFooterLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val animations = mutableListOf<AnimatedVectorDrawable>()
    private var collected = false
    private var running = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Belt and braces: onVisibilityAggregated is also dispatched on attach, but starting here
        // costs nothing because startIcons() is idempotent.
        if (isShown) startIcons()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startIcons() else stopIcons()
    }

    override fun onDetachedFromWindow() {
        // Without this the animator holds the drawable, the drawable holds its callback, and the
        // callback holds this view - a leaked 60fps invalidate loop on a view that is no longer in
        // any hierarchy.
        stopIcons()
        super.onDetachedFromWindow()
    }

    private fun startIcons() {
        if (running) return
        collectAnimations()
        if (animations.isEmpty()) return
        running = true
        animations.forEach { it.start() }
    }

    private fun stopIcons() {
        if (!running) return
        running = false
        // stop(), not reset(): reset() snaps every path back to frame 0, which makes the icons
        // visibly twitch on the way out. stop() just freezes the frame that is already up.
        animations.forEach { it.stop() }
    }

    /**
     * Deliberately id-free: any ImageView under this footer whose drawable is an
     * AnimatedVectorDrawable gets driven, so re-laying the footer out or adding a third animated
     * affordance needs no change here.
     */
    private fun collectAnimations() {
        if (collected) return
        collected = true
        forEachImageView(this) { image ->
            (image.drawable as? AnimatedVectorDrawable)?.let(animations::add)
        }
    }

    private companion object {
        fun forEachImageView(view: View, block: (ImageView) -> Unit) {
            if (view is ImageView) {
                block(view)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) forEachImageView(view.getChildAt(i), block)
            }
        }
    }
}
