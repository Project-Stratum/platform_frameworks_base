package com.android.systemui.qs.tileimpl

import android.animation.ArgbEvaluator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources.ID_NULL
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Trace
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSIconViewImpl.QS_ANIM_LENGTH
import java.util.Objects
import android.content.SharedPreferences

private const val TAG = "QSTileViewImpl"

open class QSTileViewImpl @JvmOverloads constructor(
    context: Context,
    private val _icon: QSIconView,
    private val collapsed: Boolean = false
) : QSTileView(context), HeightOverrideable, LaunchableView {

    companion object {
        private const val INVALID = -1
        private const val BACKGROUND_NAME = "background"
        private const val LABEL_NAME = "label"
        private const val SECONDARY_LABEL_NAME = "secondaryLabel"
        private const val CHEVRON_NAME = "chevron"
        const val UNAVAILABLE_ALPHA = 0.3f
        @VisibleForTesting
        internal const val TILE_STATE_RES_PREFIX = "tile_states_"

        const val SHAPE_PREFS_NAME = "qs_tile_config"
        const val SHAPE_KEY_PREFIX = "tile_is_circle_"
    }

    private var mTileSpec: String? = null
    private var _position: Int = INVALID

    private var isCircle: Boolean = false
    private var isEditMode: Boolean = false

    private val HANDLE_SIZE_DP = 24
    private val HANDLE_MARGIN_DP = 2

    override fun setPosition(position: Int) {
        _position = position
    }

    override var heightOverride: Int = HeightOverrideable.NO_OVERRIDE
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    override var squishinessFraction: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    private val colorActive =
        Utils.getColorAttrDefaultColor(context, R.attr.shadeActive)
    private val colorInactive =
        Utils.getColorAttrDefaultColor(context, R.attr.shadeInactive)
    private val colorUnavailable =
        Utils.getColorAttrDefaultColor(context, R.attr.shadeDisabled)

    private val colorLabelActive =
        Utils.getColorAttrDefaultColor(context, R.attr.onShadeActive)
    private val colorLabelInactive =
        Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactive)
    private val colorLabelUnavailable =
        Utils.getColorAttrDefaultColor(context, R.attr.outline)

    private val colorSecondaryLabelActive =
        Utils.getColorAttrDefaultColor(context, R.attr.onShadeActiveVariant)
    private val colorSecondaryLabelInactive =
        Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactiveVariant)
    private val colorSecondaryLabelUnavailable =
        Utils.getColorAttrDefaultColor(context, R.attr.outline)

    private lateinit var label: TextView
    protected lateinit var secondaryLabel: TextView
    private lateinit var labelContainer: IgnorableChildLinearLayout
    protected lateinit var sideView: ViewGroup
    private lateinit var customDrawableView: ImageView
    private lateinit var chevronView: ImageView
    private lateinit var dividerView: View

    private lateinit var rootContainer: FrameLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var handleContainer: FrameLayout
    private lateinit var resizeHandle: ImageView

    private var mQsLogger: QSLogger? = null
    protected var showRippleEffect = true
    private lateinit var ripple: RippleDrawable
    private lateinit var colorBackgroundDrawable: Drawable
    private var paintColor: Int = 0

    private val singleAnimator: ValueAnimator = ValueAnimator().apply {
        setDuration(QS_ANIM_LENGTH)
        addUpdateListener { animation ->
            setAllColors(
                animation.getAnimatedValue(BACKGROUND_NAME) as Int,
                animation.getAnimatedValue(LABEL_NAME) as Int,
                0,
                0
            )
        }
    }

    private var accessibilityClass: String? = null
    private var stateDescriptionDeltas: CharSequence? = null
    private var lastStateDescription: CharSequence? = null
    private var tileState = false
    private var lastState = INVALID
    private val launchableViewDelegate = LaunchableViewDelegate(
        this,
        superSetVisibility = { super.setVisibility(it) },
    )
    private var lastDisabledByPolicy = false
    private val locInScreen = IntArray(2)

    private var mHandlesLongClick: Boolean = false

    init {
        val typedValue = TypedValue()
        if (!getContext().theme.resolveAttribute(R.attr.isQsTheme, typedValue, true)) {
            throw IllegalStateException(
                "QSViewImpl must be inflated with a theme that contains Theme.SystemUI.QuickSettings")
        }
        setId(generateViewId())

        clipChildren = false
        clipToPadding = false

        rootContainer = FrameLayout(context)
        rootContainer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        rootContainer.clipChildren = false
        rootContainer.clipToPadding = false

        contentContainer = LinearLayout(context)
        contentContainer.orientation = LinearLayout.HORIZONTAL
        contentContainer.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        contentContainer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentContainer.clipChildren = false
        contentContainer.clipToPadding = false

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true

        contentContainer.background = createTileBackground()
        setColor(getBackgroundColorForState(QSTile.State.DEFAULT_STATE))

        val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
        val startPadding = resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
        contentContainer.setPaddingRelative(startPadding, padding, padding, padding)

        val iconSize = resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.gravity = Gravity.CENTER_VERTICAL
        contentContainer.addView(_icon, iconParams)

        createAndAddDivider()
        createAndAddLabels()
        createAndAddSideView()

        sideView.visibility = GONE

        rootContainer.addView(contentContainer)
        createAndAddEditHandles()
        super.addView(rootContainer)
    }

    private fun createAndAddEditHandles() {
        handleContainer = FrameLayout(context)
        handleContainer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        handleContainer.clipChildren = false
        handleContainer.clipToPadding = false
        handleContainer.visibility = GONE

        val handleSizePx = (HANDLE_SIZE_DP * resources.displayMetrics.density).toInt()
        val marginPx = (HANDLE_MARGIN_DP * resources.displayMetrics.density).toInt()

        resizeHandle = ImageView(context)
        resizeHandle.setImageResource(R.drawable.ic_qs_resize_handle)
        resizeHandle.setColorFilter(Color.WHITE)
        resizeHandle.scaleType = ImageView.ScaleType.CENTER_INSIDE

        val handlePadding = (4 * resources.displayMetrics.density).toInt()
        resizeHandle.setPadding(handlePadding, handlePadding, handlePadding, handlePadding)

        val resizeBg = GradientDrawable()
        resizeBg.shape = GradientDrawable.OVAL
        resizeBg.setColor(Color.parseColor("#99000000"))
        resizeHandle.background = resizeBg
        resizeHandle.elevation = 10f * resources.displayMetrics.density

        val resizeParams = FrameLayout.LayoutParams(handleSizePx, handleSizePx)
        resizeParams.gravity = Gravity.BOTTOM or Gravity.END
        resizeParams.setMargins(0, 0, marginPx, marginPx)
        handleContainer.addView(resizeHandle, resizeParams)
        rootContainer.addView(handleContainer)
    }

    fun setTileMode(circle: Boolean) {
        if (isCircle == circle) return
        isCircle = circle
        updateResources()
        mTileSpec?.let { spec ->
            context.getSharedPreferences(SHAPE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SHAPE_KEY_PREFIX + spec, circle)
                .apply()
        }
    }

    fun setEditMode(enabled: Boolean) {
        if (isEditMode != enabled) {
            isEditMode = enabled
            handleContainer.visibility = if (enabled) VISIBLE else GONE
            isClickable = true
            isLongClickable = true
            if (enabled) {
                handleContainer.alpha = 0f
                handleContainer.scaleX = 0.5f
                handleContainer.scaleY = 0.5f
                handleContainer.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    /**
     * Animate the resize handle from scale 0 → 1, growing from its bottom-right corner.
     * Called by InPlaceEditController when entering edit mode, staggered per tile.
     *
     * @param delayMs stagger delay in milliseconds
     */
    fun animateHandleIn(delayMs: Long) {
        if (!isEditMode) {
            isEditMode = true
            handleContainer.visibility = VISIBLE
        }
        handleContainer.scaleX = 0f
        handleContainer.scaleY = 0f
        handleContainer.alpha  = 0f
        // Set pivot to bottom-right after layout so the handle grows from that corner
        handleContainer.post {
            handleContainer.pivotX = handleContainer.width.toFloat()
            handleContainer.pivotY = handleContainer.height.toFloat()
        }
        handleContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setStartDelay(delayMs)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /**
     * Animate the resize handle from scale 1 → 0, shrinking back to nothing at bottom-right.
     * Called by InPlaceEditController when exiting edit mode.
     */
    fun animateHandleOut() {
        handleContainer.post {
            handleContainer.pivotX = handleContainer.width.toFloat()
            handleContainer.pivotY = handleContainer.height.toFloat()
        }
        handleContainer.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                handleContainer.visibility = GONE
                isEditMode = false
            }
            .start()
    }

    fun setOnResizeClickListener(listener: View.OnClickListener?) {
        resizeHandle.setOnClickListener(listener)
    }

    fun getContentContainer(): View = contentContainer
    fun getLabelContainerView(): View? = if (::labelContainer.isInitialized) labelContainer else null
    fun getDividerView(): View? = if (::dividerView.isInitialized) dividerView else null
    fun getIconView(): View = _icon as View

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        Trace.traceBegin(Trace.TRACE_TAG_APP, "QSTileViewImpl#onMeasure")
        val fixedHeight = context.resources.getDimensionPixelSize(R.dimen.qs_tile_height)
        val targetWidth = if (isCircle && !isEditMode) fixedHeight else MeasureSpec.getSize(widthMeasureSpec)
        val newWidthSpec = MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY)
        val newHeightSpec = MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY)
        super.onMeasure(newWidthSpec, newHeightSpec)
        setMeasuredDimension(targetWidth, fixedHeight)
        Trace.endSection()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateHeight()
    }

    override fun resetOverride() {
        heightOverride = HeightOverrideable.NO_OVERRIDE
        updateHeight()
    }

    fun setQsLogger(qsLogger: QSLogger) {
        mQsLogger = qsLogger
    }

    fun updateResources() {
        val textSizePx = context.resources.getDimensionPixelSize(R.dimen.qs_tile_text_size).toFloat()
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        secondaryLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)

        val iconSize = context.resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        val iconParams = _icon.layoutParams as LinearLayout.LayoutParams
        iconParams.height = iconSize
        iconParams.width = iconSize

        if (isCircle) {
            iconParams.gravity = Gravity.CENTER
            iconParams.marginStart = 0
            contentContainer.setPaddingRelative(0, 0, 0, 0)
            contentContainer.gravity = Gravity.CENTER
            dividerView.visibility = GONE
            labelContainer.visibility = GONE
        } else {
            iconParams.gravity = Gravity.CENTER_VERTICAL
            val startPadding = resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
            val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
            contentContainer.setPaddingRelative(startPadding, padding, padding, padding)
            contentContainer.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            dividerView.visibility = VISIBLE
            labelContainer.visibility = VISIBLE
        }
        _icon.layoutParams = iconParams

        val divWidth  = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_width)
        val divHeight = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_height)
        val divStart  = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_margin_start)
        val divEnd    = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_margin_end)
        val divParams = dividerView.layoutParams as LinearLayout.LayoutParams
        divParams.width        = divWidth
        divParams.height       = divHeight
        divParams.marginStart  = divStart
        divParams.marginEnd    = divEnd
        divParams.gravity      = Gravity.CENTER_VERTICAL
        dividerView.layoutParams = divParams

        val labelMargin  = resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        val labelParams  = labelContainer.layoutParams as LinearLayout.LayoutParams
        labelParams.marginStart = labelMargin
        labelParams.height      = ViewGroup.LayoutParams.WRAP_CONTENT
        labelParams.gravity     = Gravity.CENTER_VERTICAL
        labelContainer.layoutParams = labelParams

        (sideView.layoutParams as MarginLayoutParams).apply { marginStart = labelMargin }
        (chevronView.layoutParams as MarginLayoutParams).apply { height = iconSize; width = iconSize }
        val endMargin = resources.getDimensionPixelSize(R.dimen.qs_drawable_end_margin)
        (customDrawableView.layoutParams as MarginLayoutParams).apply { height = iconSize; marginEnd = endMargin }

        requestLayout()
    }

    private fun createAndAddDivider() {
        dividerView = View(context)
        val width  = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_width)
        val height = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_height)
        val start  = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_margin_start)
        val end    = resources.getDimensionPixelSize(R.dimen.qs_pill_divider_margin_end)
        val params = LinearLayout.LayoutParams(width, height)
        params.gravity      = Gravity.CENTER_VERTICAL
        params.marginStart  = start
        params.marginEnd    = end
        dividerView.layoutParams = params
        dividerView.alpha   = 0.4f
        contentContainer.addView(dividerView)
    }

    private fun createAndAddLabels() {
        labelContainer = LayoutInflater.from(context).inflate(R.layout.qs_tile_label, null, false) as IgnorableChildLinearLayout
        label         = labelContainer.requireViewById(R.id.tile_label)
        secondaryLabel = labelContainer.requireViewById(R.id.app_label)
        secondaryLabel.visibility = GONE

        if (collapsed) {
            labelContainer.ignoreLastView        = true
            labelContainer.forceUnspecifiedMeasure = true
            secondaryLabel.alpha                 = 0f
        }
        setLabelColor(getLabelColorForState(QSTile.State.DEFAULT_STATE))
        setSecondaryLabelColor(getSecondaryLabelColorForState(QSTile.State.DEFAULT_STATE))

        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        params.gravity = Gravity.CENTER_VERTICAL
        contentContainer.addView(labelContainer, params)
    }

    private fun createAndAddSideView() {
        sideView           = LayoutInflater.from(context).inflate(R.layout.qs_tile_side_icon, null, false) as ViewGroup
        customDrawableView = sideView.requireViewById(R.id.customDrawable)
        chevronView        = sideView.requireViewById(R.id.chevron)
        setChevronColor(getChevronColorForState(QSTile.State.DEFAULT_STATE))
        contentContainer.addView(sideView)
    }

    fun createTileBackground(): Drawable {
        ripple = mContext.getDrawable(R.drawable.qs_tile_background) as RippleDrawable
        colorBackgroundDrawable = ripple.findDrawableByLayerId(R.id.background)
        return ripple
    }

    private fun updateHeight() {
        bottom  = top + measuredHeight
        scrollY = 0
    }

    override fun updateAccessibilityOrder(previousView: View?): View {
        accessibilityTraversalAfter = previousView?.id ?: ID_NULL
        return this
    }

    override fun getIcon(): QSIconView = _icon
    override fun getIconWithBackground(): View = icon

    override fun init(tile: QSTile) {
        mTileSpec = tile.tileSpec

        val prefs: SharedPreferences = context.getSharedPreferences(
            SHAPE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val savedCircle = prefs.getBoolean(SHAPE_KEY_PREFIX + tile.tileSpec, true)
        if (isCircle != savedCircle) {
            isCircle = savedCircle
            updateResources()
        }

        init({ _ ->
            if (!isEditMode) {
                try { tile.click(this) }
                catch (e: Exception) { Log.e(TAG, "Error handling tile click: ${tile.tileSpec}", e) }
            }
        }, { _ ->
            if (isEditMode) {
                false
            } else if (mHandlesLongClick) {
                try { tile.longClick(this); true }
                catch (e: Exception) { Log.e(TAG, "Error handling long click: ${tile.tileSpec}", e); true }
            } else {
                false
            }
        })
    }

    private fun init(click: OnClickListener?, longClick: OnLongClickListener?) {
        setOnClickListener(click)
        onLongClickListener = longClick
    }

    override fun onStateChanged(state: QSTile.State) {
        val runnable = StateChangeRunnable(state.copy())
        removeCallbacks(runnable)
        post(runnable)
    }

    override fun getDetailY(): Int = top + height / 2
    override fun hasOverlappingRendering(): Boolean = false

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        contentContainer.background = if (clickable && showRippleEffect) {
            ripple.also { colorBackgroundDrawable.callback = it }
        } else {
            colorBackgroundDrawable
        }
    }

    override fun getLabelContainer(): View = labelContainer
    override fun getLabel(): View = label
    override fun getSecondaryLabel(): View = secondaryLabel
    override fun getSecondaryIcon(): View = sideView

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        launchableViewDelegate.setShouldBlockVisibilityChanges(block)
    }

    override fun setVisibility(visibility: Int) {
        launchableViewDelegate.setVisibility(visibility)
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!TextUtils.isEmpty(accessibilityClass)) event.className = accessibilityClass
        if (event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
                && stateDescriptionDeltas != null) {
            event.text.add(stateDescriptionDeltas)
            stateDescriptionDeltas = null
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isSelected = false
        info.text = "${label.text}"
        if (lastDisabledByPolicy) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                resources.getString(
                    R.string.accessibility_tile_disabled_by_policy_action_description)))
        }
        if (!TextUtils.isEmpty(accessibilityClass)) {
            info.className = if (lastDisabledByPolicy) Button::class.java.name else accessibilityClass
            if (Switch::class.java.name == accessibilityClass) {
                info.isChecked  = tileState
                info.isCheckable = true
                if (isLongClickable) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                        resources.getString(R.string.accessibility_long_click_tile)))
                }
            }
        }
        if (_position != INVALID) {
            info.collectionItemInfo =
                AccessibilityNodeInfo.CollectionItemInfo(_position, 1, 0, 1, false)
        }
    }

    protected open fun handleStateChanged(state: QSTile.State) {
        val allowAnimations = animationsEnabled()
        isClickable  = state.state != Tile.STATE_UNAVAILABLE
        mHandlesLongClick = state.handlesLongClick
        isLongClickable   = state.handlesLongClick

        icon.setIcon(state, allowAnimations)
        contentDescription = state.contentDescription

        if (state is BooleanState) {
            if (tileState != state.value) tileState = state.value
        }

        if (!Objects.equals(label.text, state.label)) label.text = state.label
        secondaryLabel.visibility = GONE

        if (state.state != lastState || state.disabledByPolicy != lastDisabledByPolicy) {
            singleAnimator.cancel()
            val bgColor  = getBackgroundColorForState(state.state, state.disabledByPolicy)
            val lblColor = getLabelColorForState(state.state, state.disabledByPolicy)

            if (allowAnimations) {
                singleAnimator.setValues(
                    colorValuesHolder(BACKGROUND_NAME, paintColor, bgColor),
                    colorValuesHolder(LABEL_NAME, label.currentTextColor, lblColor),
                    colorValuesHolder(SECONDARY_LABEL_NAME,
                        secondaryLabel.currentTextColor,
                        getSecondaryLabelColorForState(state.state, state.disabledByPolicy)),
                    colorValuesHolder(CHEVRON_NAME,
                        chevronView.imageTintList?.defaultColor ?: 0,
                        getChevronColorForState(state.state, state.disabledByPolicy))
                )
                singleAnimator.start()
            } else {
                setAllColors(bgColor, lblColor,
                    getSecondaryLabelColorForState(state.state, state.disabledByPolicy),
                    getChevronColorForState(state.state, state.disabledByPolicy))
            }
        }

        customDrawableView.visibility = GONE
        chevronView.visibility        = GONE
        label.isEnabled               = !state.disabledByPolicy
        lastState           = state.state
        lastDisabledByPolicy = state.disabledByPolicy
    }

    private fun setAllColors(bg: Int, lbl: Int, sec: Int, chev: Int) {
        setColor(bg); setLabelColor(lbl); setSecondaryLabelColor(sec); setChevronColor(chev)
        dividerView.setBackgroundColor(lbl)
    }

    private fun setColor(color: Int) {
        colorBackgroundDrawable.mutate().setTint(color)
        paintColor = color
    }
    private fun setLabelColor(color: Int)         { label.setTextColor(color) }
    private fun setSecondaryLabelColor(color: Int) { secondaryLabel.setTextColor(color) }
    private fun setChevronColor(color: Int)        { chevronView.imageTintList = ColorStateList.valueOf(color) }

    private fun loadSideViewDrawableIfNecessary(state: QSTile.State) {}

    protected open fun animationsEnabled(): Boolean {
        if (!isShown) return false
        if (alpha != 1f)  return false
        getLocationOnScreen(locInScreen)
        return locInScreen[1] >= -height
    }

    private fun getBackgroundColorForState(state: Int, disabledByPolicy: Boolean = false): Int =
        when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorUnavailable
            state == Tile.STATE_ACTIVE                          -> colorActive
            state == Tile.STATE_INACTIVE                        -> colorInactive
            else -> 0
        }

    private fun getLabelColorForState(state: Int, disabledByPolicy: Boolean = false): Int =
        when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorLabelUnavailable
            state == Tile.STATE_ACTIVE                          -> colorLabelActive
            state == Tile.STATE_INACTIVE                        -> colorLabelInactive
            else -> 0
        }

    private fun getSecondaryLabelColorForState(state: Int, disabledByPolicy: Boolean = false): Int =
        when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorSecondaryLabelUnavailable
            state == Tile.STATE_ACTIVE                          -> colorSecondaryLabelActive
            state == Tile.STATE_INACTIVE                        -> colorSecondaryLabelInactive
            else -> 0
        }

    private fun getChevronColorForState(state: Int, disabledByPolicy: Boolean = false): Int =
        getSecondaryLabelColorForState(state, disabledByPolicy)

    @VisibleForTesting
    internal fun getCurrentColors(): List<Int> = listOf(
        paintColor,
        label.currentTextColor,
        secondaryLabel.currentTextColor,
        chevronView.imageTintList?.defaultColor ?: 0
    )

    inner class StateChangeRunnable(private val state: QSTile.State) : Runnable {
        override fun run()             = handleStateChanged(state)
        override fun equals(other: Any?) = other is StateChangeRunnable
        override fun hashCode()        = StateChangeRunnable::class.hashCode()
    }
}

fun constrainSquishiness(squish: Float): Float = 0.1f + squish * 0.9f

private fun colorValuesHolder(name: String, vararg values: Int): PropertyValuesHolder =
    PropertyValuesHolder.ofInt(name, *values).apply { setEvaluator(ArgbEvaluator.getInstance()) }
    