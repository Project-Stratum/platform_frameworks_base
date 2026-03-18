/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Scroller;

import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanelControllerBase.TileRecord;
import com.android.systemui.qs.logging.QSLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PagedTileLayout extends ViewPager implements QSTileLayout {

    private static final String CURRENT_PAGE = "current_page";
    private static final int NO_PAGE = -1;

    private static final String PREFS_FILE = "qs_tile_config";
    private static final String PREF_PREFIX_SHAPE = "tile_is_circle_";

    private static final int REVEAL_SCROLL_DURATION_MILLIS = 750;
    private static final float BOUNCE_ANIMATION_TENSION = 1.3f;
    private static final long BOUNCE_ANIMATION_DURATION = 450L;
    private static final int TILE_ANIMATION_STAGGER_DELAY = 85;
    private static final Interpolator SCROLL_CUBIC = (t) -> {
        t -= 1.0f;
        return t * t * t + 1.0f;
    };

    private final ArrayList<TileRecord> mTiles = new ArrayList<>();
    private final ArrayList<TileLayout> mPages = new ArrayList<>();

    private QSLogger mLogger;
    @Nullable
    private PageIndicator mPageIndicator;
    private float mPageIndicatorPosition;

    @Nullable
    private PageListener mPageListener;

    private boolean mListening;
    private Scroller mScroller;

    @Nullable
    private AnimatorSet mBounceAnimatorSet;
    private float mLastExpansion;
    private boolean mDistributeTiles = false;
    private int mPageToRestore = -1;
    private int mLayoutOrientation;
    private int mLayoutDirection;
    private final UiEventLogger mUiEventLogger = QSEvents.INSTANCE.getQsUiEventsLogger();
    private int mExcessHeight;
    private int mLastExcessHeight;
    private int mMinRows = 1;
    private int mMaxColumns = TileLayout.NO_MAX_COLUMNS;

    private int mLastMaxHeight = -1;

    public PagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScroller = new Scroller(context, SCROLL_CUBIC);
        setAdapter(mAdapter);
        setOnPageChangeListener(mOnPageChangeListener);
        setCurrentItem(0, false);
        mLayoutOrientation = getResources().getConfiguration().orientation;
        mLayoutDirection = getLayoutDirection();
    }

    private boolean isTileCircle(String tileSpec) {
        if (tileSpec == null) return true;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_PREFIX_SHAPE + tileSpec, true);
    }

    private final TileLayout.OnRequestLayoutListener mRedistributionListener =
            new TileLayout.OnRequestLayoutListener() {
        @Override
        public void onRequestDistribution() {
            forceTilesRedistribution("Tile resized by user");
            requestLayout();
        }
    };

    @Override
    public void setPageMargin(int marginPixels) {
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.setMarginStart(-marginPixels);
        lp.setMarginEnd(-marginPixels);
        setLayoutParams(lp);

        int nPages = mPages.size();
        for (int i = 0; i < nPages; i++) {
            View v = mPages.get(i);
            v.setPadding(marginPixels, v.getPaddingTop(), marginPixels, v.getPaddingBottom());
        }
    }

    public void saveInstanceState(Bundle outState) {
        int resolvedPage = mPageToRestore != NO_PAGE ? mPageToRestore : getCurrentPageNumber();
        outState.putInt(CURRENT_PAGE, resolvedPage);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        mPageToRestore = savedInstanceState.getInt(CURRENT_PAGE, NO_PAGE);
    }

    @Override
    public int getTilesHeight() {
        int height = 0;
        for (int i = 0; i < mPages.size(); i++) {
            TileLayout tileLayout = mPages.get(i);
            if (tileLayout != null) {
                height = Math.max(height, tileLayout.getTilesHeight());
            }
        }
        return height;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int numPages = mPages.size();
        for (int i = 0; i < numPages; i++) {
            View page = mPages.get(i);
            if (page.getParent() == null) {
                page.dispatchConfigurationChanged(newConfig);
            }
        }
        if (mLayoutOrientation != newConfig.orientation) {
            mLayoutOrientation = newConfig.orientation;
            forceTilesRedistribution("orientation changed to " + mLayoutOrientation);
            setCurrentItem(0, false);
            mPageToRestore = 0;
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        final int page = getPageNumberForDirection(mLayoutDirection == LAYOUT_DIRECTION_RTL);
        super.onRtlPropertiesChanged(layoutDirection);
        if (mLayoutDirection != layoutDirection) {
            mLayoutDirection = layoutDirection;
            setAdapter(mAdapter);
            setCurrentItem(page, false);
        }
    }

    @Override
    public void setCurrentItem(int item, boolean smoothScroll) {
        if (isLayoutRtl()) {
            item = mPages.size() - 1 - item;
        }
        super.setCurrentItem(item, smoothScroll);
    }

    private int getCurrentPageNumber() {
        return getPageNumberForDirection(isLayoutRtl());
    }

    private int getPageNumberForDirection(boolean isLayoutRTL) {
        int page = getCurrentItem();
        if (isLayoutRTL) {
            page = mPages.size() - 1 - page;
        }
        return page;
    }

    private void logVisibleTiles(TileLayout page) {
        for (int i = 0; i < page.mRecords.size(); i++) {
            QSTile t = page.mRecords.get(i).tile;
            mUiEventLogger.logWithInstanceId(QSEvent.QS_TILE_VISIBLE, 0, t.getMetricsSpec(),
                    t.getInstanceId());
        }
    }

    @Override
    public void setListening(boolean listening, @Nullable UiEventLogger uiEventLogger) {
        if (mListening == listening) return;
        mListening = listening;

        for (TileLayout tilePage : mPages) {
            if (tilePage.getParent() == null) {
                continue;
            }
            tilePage.setListening(mListening, uiEventLogger);
        }
    }

    @Override
    public void setSquishinessFraction(float squishinessFraction) {
        int nPages = mPages.size();
        for (int i = 0; i < nPages; i++) {
            mPages.get(i).setSquishinessFraction(squishinessFraction);
        }
    }

    @Override
    public void fakeDragBy(float xOffset) {
        try {
            super.fakeDragBy(xOffset);
            postInvalidateOnAnimation();
        } catch (NullPointerException e) {
            final int lastPageNumber = mPages.size() - 1;
            post(() -> {
                setCurrentItem(lastPageNumber, true);
                if (mBounceAnimatorSet != null) {
                    mBounceAnimatorSet.start();
                }
                setOffscreenPageLimit(1);
            });
        }
    }

    @Override
    public void endFakeDrag() {
        try {
            super.endFakeDrag();
        } catch (NullPointerException e) {
        }
    }

    @Override
    public void computeScroll() {
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            if (!isFakeDragging()) {
                beginFakeDrag();
            }
            fakeDragBy(getScrollX() - mScroller.getCurrX());
        } else if (isFakeDragging()) {
            endFakeDrag();
            if (mBounceAnimatorSet != null) {
                mBounceAnimatorSet.start();
            }
            setOffscreenPageLimit(1);
        }
        super.computeScroll();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPages.add(createTileLayout());
        mAdapter.notifyDataSetChanged();
    }

    private TileLayout createTileLayout() {
        TileLayout page = (TileLayout) LayoutInflater.from(getContext())
                .inflate(R.layout.qs_paged_page, this, false);
        page.setMinRows(mMinRows);
        page.setMaxColumns(mMaxColumns);
        page.setSelected(false);
        page.setOnRequestLayoutListener(mRedistributionListener);
        return page;
    }

    public void setPageIndicator(PageIndicator indicator) {
        mPageIndicator = indicator;
        mPageIndicator.setNumPages(mPages.size());
        mPageIndicator.setLocation(mPageIndicatorPosition);
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        final ViewGroup parent = (ViewGroup) tile.tileView.getParent();
        if (parent == null) return 0;
        return parent.getTop() + getTop();
    }

    @Override
    public void addTile(TileRecord tile) {
        mTiles.add(tile);
        forceTilesRedistribution("adding new tile");
        requestLayout();
    }

    @Override
    public void removeTile(TileRecord tile) {
        if (mTiles.remove(tile)) {
            forceTilesRedistribution("removing tile");
            requestLayout();
        }
    }

    @Override
    public void setExpansion(float expansion, float proposedTranslation) {
        mLastExpansion = expansion;
        updateSelected();
    }

    private void updateSelected() {
        if (mLastExpansion > 0f && mLastExpansion < 1f) {
            return;
        }
        boolean selected = mLastExpansion == 1f;

        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int currentItem = getCurrentPageNumber();
        for (int i = 0; i < mPages.size(); i++) {
            TileLayout page = mPages.get(i);
            page.setSelected(i == currentItem ? selected : false);
            if (page.isSelected()) {
                logVisibleTiles(page);
            }
        }
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public void setPageListener(PageListener listener) {
        mPageListener = listener;
    }

    public List<String> getSpecsForPage(int page) {
        ArrayList<String> out = new ArrayList<>();
        if (page < 0) return out;
        if (page >= mPages.size()) return out;

        TileLayout layout = mPages.get(page);
        for (TileRecord tr : layout.mRecords) {
            out.add(tr.tile.getTileSpec());
        }
        return out;
    }

    /**
     * Returns the TileLayout for a given page index, or null if out of bounds.
     * Used by InPlaceEditController to animate resize handles on the current page.
     */
    @Nullable
    public TileLayout getPageAt(int page) {
        if (page < 0 || page >= mPages.size()) return null;
        return mPages.get(page);
    }

    /**
     * Returns the total number of pages currently in the pager.
     * Used by InPlaceEditController to animate handles out on all pages when exiting edit mode.
     */
    public int getPageCount() {
        return mPages.size();
    }

    private void distributeTiles() {
        emptyAndInflateOrRemovePages();

        int pageIndex = 0;
        int currentRow = 0;
        int currentColumn = 0;
        int maxColumns = 4;
        int maxRows = 4;

        for (int i = 0; i < mTiles.size(); i++) {
            TileRecord tile = mTiles.get(i);

            boolean isCircle = isTileCircle(tile.tile.getTileSpec());
            int span = isCircle ? 1 : 2;

            if (currentColumn + span > maxColumns) {
                currentRow++;
                currentColumn = 0;
            }

            if (currentRow >= maxRows) {
                pageIndex++;
                if (mPages.size() <= pageIndex) {
                    mPages.add(createTileLayout());
                }
                currentRow = 0;
                currentColumn = 0;
            }

            mPages.get(pageIndex).addTile(tile);

            currentColumn += span;

            if (currentColumn >= maxColumns) {
                currentRow++;
                currentColumn = 0;
            }
        }

        while (mPages.size() > pageIndex + 1) {
            mPages.remove(mPages.size() - 1);
        }

        if (mPageIndicator != null) {
            mPageIndicator.setNumPages(mPages.size());
        }
        mAdapter.notifyDataSetChanged();
    }

    private void emptyAndInflateOrRemovePages() {
        final int numPages = getNumPages();
        final int NP = mPages.size();
        for (int i = 0; i < NP; i++) {
            mPages.get(i).removeAllViews();
        }
        if (NP == numPages) {
            return;
        }
        while (mPages.size() < numPages) {
            mPages.add(createTileLayout());
        }
        while (mPages.size() > numPages) {
            mPages.remove(mPages.size() - 1);
        }
        mPageIndicator.setNumPages(mPages.size());
        setAdapter(mAdapter);
        mAdapter.notifyDataSetChanged();
        if (mPageToRestore != NO_PAGE) {
            setCurrentItem(mPageToRestore, false);
            mPageToRestore = NO_PAGE;
        }
    }

    @Override
    public boolean updateResources() {
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            changed |= mPages.get(i).updateResources();
        }
        if (changed) {
            forceTilesRedistribution("resources in pages changed");
            requestLayout();
        }
        return changed;
    }

    @Override
    public boolean setMinRows(int minRows) {
        mMinRows = minRows;
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            if (mPages.get(i).setMinRows(minRows)) {
                changed = true;
                forceTilesRedistribution("minRows changed in page");
            }
        }
        return changed;
    }

    @Override
    public boolean setMaxColumns(int maxColumns) {
        mMaxColumns = maxColumns;
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            if (mPages.get(i).setMaxColumns(maxColumns)) {
                changed = true;
                forceTilesRedistribution("maxColumns in pages changed");
            }
        }
        return changed;
    }

    public void setExcessHeight(int excessHeight) {
        mExcessHeight = excessHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int nTiles = mTiles.size();

        if (mDistributeTiles || mLastMaxHeight != MeasureSpec.getSize(heightMeasureSpec)
                || mLastExcessHeight != mExcessHeight) {

            mLastMaxHeight = MeasureSpec.getSize(heightMeasureSpec);
            mLastExcessHeight = mExcessHeight;

            mDistributeTiles = false;
            distributeTiles();

            final int nRows = mPages.get(0).mRows;
            for (int i = 0; i < mPages.size(); i++) {
                TileLayout t = mPages.get(i);
                t.mRows = nRows;
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int maxHeight = 0;
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            int height = getChildAt(i).getMeasuredHeight();
            if (height > maxHeight) {
                maxHeight = height;
            }
        }
        if (mPages.get(0).getParent() == null) {
            mPages.get(0).measure(widthMeasureSpec, heightMeasureSpec);
            int height = mPages.get(0).getMeasuredHeight();
            if (height > maxHeight) {
                maxHeight = height;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), maxHeight + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mPages.get(0).getParent() == null) {
            mPages.get(0).layout(l, t, r, b);
        }
    }

    public int getColumnCount() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mColumns;
    }

    public int getNumPages() {
        int pageIndex = 0;
        int currentRow = 0;
        int currentColumn = 0;
        int maxColumns = 4;
        int maxRows = 4;

        for (int i = 0; i < mTiles.size(); i++) {
            TileRecord tile = mTiles.get(i);
            boolean isCircle = isTileCircle(tile.tile.getTileSpec());
            int span = isCircle ? 1 : 2;

            if (currentColumn + span > maxColumns) {
                currentRow++;
                currentColumn = 0;
            }

            if (currentRow >= maxRows) {
                pageIndex++;
                currentRow = 0;
                currentColumn = 0;
            }

            currentColumn += span;

            if (currentColumn >= maxColumns) {
                currentRow++;
                currentColumn = 0;
            }
        }

        return Math.max(pageIndex + 1, 1);
    }

    public int getNumVisibleTiles() {
        if (mPages.size() == 0) return 0;
        TileLayout currentPage = mPages.get(getCurrentPageNumber());
        return currentPage.mRecords.size();
    }

    public int getNumTilesFirstPage() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mRecords.size();
    }

    public void startTileReveal(Set<String> tilesToReveal, final Runnable postAnimation) {
        if (shouldNotRunAnimation(tilesToReveal)) {
            return;
        }
        if (!beginFakeDrag()) {
            return;
        }

        final int lastPageNumber = mPages.size() - 1;
        final TileLayout lastPage = mPages.get(lastPageNumber);
        final ArrayList<Animator> bounceAnims = new ArrayList<>();
        for (TileRecord tr : lastPage.mRecords) {
            if (tilesToReveal.contains(tr.tile.getTileSpec())) {
                bounceAnims.add(setupBounceAnimator(tr.tileView, bounceAnims.size()));
            }
        }

        if (bounceAnims.isEmpty()) {
            endFakeDrag();
            return;
        }

        mBounceAnimatorSet = new AnimatorSet();
        mBounceAnimatorSet.playTogether(bounceAnims);
        mBounceAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBounceAnimatorSet = null;
                postAnimation.run();
            }
        });
        setOffscreenPageLimit(lastPageNumber);
        int dx = getWidth() * lastPageNumber;
        mScroller.startScroll(getScrollX(), getScrollY(), isLayoutRtl() ? -dx : dx, 0,
                REVEAL_SCROLL_DURATION_MILLIS);
        postInvalidateOnAnimation();
    }

    private boolean shouldNotRunAnimation(Set<String> tilesToReveal) {
        boolean noAnimationNeeded = tilesToReveal.isEmpty() || mPages.size() < 2;
        boolean scrollingInProgress = getScrollX() != 0 || !isFakeDragging();
        return noAnimationNeeded || scrollingInProgress || ActivityManager.isRunningInTestHarness();
    }

    private int sanitizePageAction(int action) {
        int pageLeftId = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.getId();
        int pageRightId = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.getId();
        if (action == pageLeftId || action == pageRightId) {
            if (!isLayoutRtl()) {
                if (action == pageLeftId) {
                    return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                } else {
                    return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                }
            } else {
                if (action == pageLeftId) {
                    return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                } else {
                    return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                }
            }
        }
        return action;
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        action = sanitizePageAction(action);
        boolean performed = super.performAccessibilityAction(action, arguments);
        if (performed && (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            requestAccessibilityFocus();
        }
        return performed;
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        if (getCurrentItem() != 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT);
        }
        if (getCurrentItem() != mPages.size() - 1) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mAdapter != null && mAdapter.getCount() > 0) {
            event.setItemCount(mAdapter.getCount());
            event.setFromIndex(getCurrentPageNumber());
            event.setToIndex(getCurrentPageNumber());
        }
    }

    private static Animator setupBounceAnimator(View view, int ordinal) {
        view.setAlpha(0f);
        view.setScaleX(0f);
        view.setScaleY(0f);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1));
        animator.setDuration(BOUNCE_ANIMATION_DURATION);
        animator.setStartDelay(ordinal * TILE_ANIMATION_STAGGER_DELAY);
        animator.setInterpolator(new OvershootInterpolator(BOUNCE_ANIMATION_TENSION));
        return animator;
    }

    private final ViewPager.OnPageChangeListener mOnPageChangeListener =
            new ViewPager.SimpleOnPageChangeListener() {

                private int mCurrentScrollState = SCROLL_STATE_IDLE;
                private boolean mIsScrollJankTraceBegin = false;

                @Override
                public void onPageSelected(int position) {
                    updateSelected();
                    if (mPageIndicator == null) return;
                    if (mPageListener != null) {
                        int pageNumber = isLayoutRtl() ? mPages.size() - 1 - position : position;
                        mPageListener.onPageChanged(pageNumber == 0, pageNumber);
                    }
                }

                @Override
                public void onPageScrolled(int position, float positionOffset,
                        int positionOffsetPixels) {

                    if (!mIsScrollJankTraceBegin && mCurrentScrollState == SCROLL_STATE_DRAGGING) {
                        InteractionJankMonitor.getInstance().begin(PagedTileLayout.this,
                                CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE);
                        mIsScrollJankTraceBegin = true;
                    }

                    if (mPageIndicator == null) return;
                    mPageIndicatorPosition = position + positionOffset;
                    mPageIndicator.setLocation(mPageIndicatorPosition);
                    if (mPageListener != null) {
                        int pageNumber = isLayoutRtl() ? mPages.size() - 1 - position : position;
                        mPageListener.onPageChanged(
                                positionOffsetPixels == 0 && pageNumber == 0,
                                positionOffsetPixels == 0 ? pageNumber : PageListener.INVALID_PAGE
                        );
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    if (state != mCurrentScrollState && state == SCROLL_STATE_IDLE) {
                        InteractionJankMonitor.getInstance().end(
                                CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE);
                        mIsScrollJankTraceBegin = false;
                    }
                    mCurrentScrollState = state;
                }
            };

    private final PagerAdapter mAdapter = new PagerAdapter() {
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            if (isLayoutRtl()) {
                position = mPages.size() - 1 - position;
            }
            ViewGroup view = mPages.get(position);
            if (view.getParent() != null) {
                container.removeView(view);
            }
            container.addView(view);
            return view;
        }

        @Override
        public int getCount() {
            return mPages.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    };

    public void forceTilesRedistribution(String reason) {
        mDistributeTiles = true;
    }

    public void setLogger(QSLogger qsLogger) {
        mLogger = qsLogger;
    }

    public interface PageListener {
        int INVALID_PAGE = -1;
        void onPageChanged(boolean isFirst, int pageNumber);
    }
}
