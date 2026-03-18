/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.qs;

import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanelControllerBase.TileRecord;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public class TileLayout extends ViewGroup implements QSTileLayout {

    public static final int NO_MAX_COLUMNS = 100;
    private static final String TAG = "TileLayout";

    private static final String PREFS_FILE        = "qs_tile_config";
    private static final String PREF_PREFIX_SHAPE = "tile_is_circle_";

    // ---- Static edit-mode state shared across all TileLayout instances ----
    private static volatile boolean sEditMode = false;
    private static final Set<TileLayout> sActiveLayouts =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<Runnable> sResizeListeners = new ArrayList<>();

    // ---- Phantom grid (drawn behind tiles) ----
    private final PhantomGridDrawable mPhantomGrid = new PhantomGridDrawable();

    // ---- Drag-reorder state ----
    private boolean mIsDragging = false;
    private int mDragFromIndex = -1;
    private int mDragToIndex   = -1;
    private TileRecord mDragRecord = null;
    private float mLastTouchX, mLastTouchY;
    // Long-press threshold: 400ms
    private static final long LONG_PRESS_TIMEOUT_MS = 400;
    private final Handler mLongPressHandler = new Handler(Looper.getMainLooper());
    private int mLongPressCandidateIndex = -1;
    private float mLongPressStartX, mLongPressStartY;
    private static final float LONG_PRESS_SLOP_PX = 20f;

    // ---- Standard layout fields ----
    protected int mColumns;
    protected int mCellWidth;
    protected int mCellHeight;
    protected int mCellMarginHorizontal;
    protected int mCellMarginVertical;
    protected int mSidePadding;
    protected int mRows = 1;

    protected int mResourceCellHeightResId = R.dimen.qs_tile_height;
    protected int mResourceCellHeight;
    protected int mEstimatedCellHeight;

    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    protected boolean mListening;
    protected int mMaxAllowedRows = 4;

    private final boolean mLessRows;
    protected int mMinRows = 1;
    private int mMaxColumns = NO_MAX_COLUMNS;
    protected int mResourceColumns;
    protected int mLastTileBottom;

    protected TextView mTempTextView;

    public interface OnRequestLayoutListener {
        void onRequestDistribution();
    }

    private OnRequestLayoutListener mLayoutRequestListener;

    public void setOnRequestLayoutListener(OnRequestLayoutListener listener) {
        mLayoutRequestListener = listener;
    }

    // -------------------------------------------------------------------------
    // Static edit-mode API
    // -------------------------------------------------------------------------

    /**
     * Enter or exit edit mode globally.
     * All active TileLayout instances are notified.
     */
    public static void setEditMode(boolean enabled) {
        sEditMode = enabled;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (TileLayout layout : sActiveLayouts) {
                if (layout != null) layout.onEditModeChanged(enabled);
            }
        });
    }

    /** @deprecated use setEditMode(false) — kept for compat with old call sites */
    public static void disableEditMode() {
        setEditMode(false);
    }

    public static boolean isEditMode() {
        return sEditMode;
    }

    public static void addLayout(TileLayout layout) {
        if (layout != null) sActiveLayouts.add(layout);
    }

    public static void addResizeListener(Runnable listener) {
        if (!sResizeListeners.contains(listener)) sResizeListeners.add(listener);
    }

    /**
     * Broadcast that one or more tile shapes changed.
     * All active TileLayout instances remeasure and redraw.
     */
    public static void broadcastTileSizeChange() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (TileLayout layout : sActiveLayouts) {
                if (layout == null) continue;
                for (TileRecord record : layout.mRecords) {
                    if (record.tileView instanceof QSTileViewImpl) {
                        boolean isCircle = layout.isTileCircle(record.tile.getTileSpec());
                        ((QSTileViewImpl) record.tileView).setTileMode(isCircle);
                    }
                }
                if (layout.mLayoutRequestListener != null) {
                    layout.mLayoutRequestListener.onRequestDistribution();
                }
                layout.requestLayout();
                layout.invalidate();
            }
            for (Runnable r : sResizeListeners) r.run();
        });
    }

    public TileLayout(Context context) {
        this(context, null);
    }

    public TileLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusableInTouchMode(true);
        mLessRows = ((Settings.System.getInt(context.getContentResolver(), "qs_less_rows", 0) != 0)
                || useQsMediaPlayer(context));
        mTempTextView = new TextView(context);
        setClipChildren(false);
        setClipToPadding(false);
        updateResources();
        sActiveLayouts.add(this);
    }

    private void onEditModeChanged(boolean editMode) {
        for (TileRecord record : mRecords) {
            if (record.tileView instanceof QSTileViewImpl) {
                ((QSTileViewImpl) record.tileView).setEditMode(editMode);
            }
        }
        if (editMode) {
            mPhantomGrid.show();
        } else {
            cancelDrag();
            mPhantomGrid.hide();
        }
        invalidate();
}   
    @Override
    protected void dispatchDraw(Canvas canvas) {
        mPhantomGrid.setBounds(0, 0, getWidth(), getHeight());
        mPhantomGrid.draw(canvas);
        super.dispatchDraw(canvas);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!sEditMode) return super.onInterceptTouchEvent(ev);
        return mIsDragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!sEditMode) return super.onTouchEvent(ev);
        if (!mIsDragging) return false;

        final float x = ev.getX();
        final float y = ev.getY();

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                mLastTouchX = x;
                mLastTouchY = y;
                int slot = getSlotAt(x, y);
                if (slot != mDragToIndex && slot >= 0) {
                    mDragToIndex = slot;
                    mPhantomGrid.setHoverSlot(slot);
                    applyShiftTranslations(mDragFromIndex, mDragToIndex);
                }
                // Move the dragged view with the finger
                if (mDragRecord != null) {
                    View tv = (View) mDragRecord.tileView;
                    tv.setTranslationX(x - tv.getLeft() - tv.getWidth()  / 2f);
                    tv.setTranslationY(y - tv.getTop()  - tv.getHeight() / 2f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                commitDrop();
                return true;
        }
        return false;
    }

    /**
     * Called by QSTileViewImpl's long-press listener to initiate a drag.
     * @param record the tile being dragged
     */
    public void startDragFromRecord(TileRecord record) {
        if (!sEditMode || mIsDragging) return;
        int index = mRecords.indexOf(record);
        if (index < 0) return;

        mDragRecord    = record;
        mDragFromIndex = index;
        mDragToIndex   = index;
        mIsDragging    = true;

        // Elevate dragged tile visually
        View tv = (View) record.tileView;
        tv.setAlpha(0.75f);
        tv.setElevation(16f);
        tv.bringToFront();

        mPhantomGrid.show();
        requestDisallowInterceptTouchEvent(true);
    }

    private void commitDrop() {
        if (!mIsDragging || mDragRecord == null) return;

        int from = mDragFromIndex;
        int to   = (mDragToIndex >= 0 && mDragToIndex < mRecords.size())
                ? mDragToIndex : from;

        // Restore dragged tile visuals
        View tv = (View) mDragRecord.tileView;
        tv.setAlpha(1f);
        tv.setElevation(0f);
        tv.setTranslationX(0f);
        tv.setTranslationY(0f);

        // Reset all translations
        for (TileRecord r : mRecords) {
            View v = (View) r.tileView;
            v.animate().translationX(0).translationY(0).setDuration(120).start();
        }

        // Reorder
        if (from != to) {
            TileRecord moved = mRecords.remove(from);
            mRecords.add(to, moved);
            if (mLayoutRequestListener != null) {
                mLayoutRequestListener.onRequestDistribution();
            }
        }

        cancelDrag();
        mPhantomGrid.hide();
        requestLayout();
    }

    private void cancelDrag() {
        if (mDragRecord != null) {
            View tv = (View) mDragRecord.tileView;
            tv.setAlpha(1f);
            tv.setElevation(0f);
            tv.setTranslationX(0f);
            tv.setTranslationY(0f);
        }
        for (TileRecord r : mRecords) {
            View v = (View) r.tileView;
            v.animate().translationX(0).translationY(0).setDuration(120).start();
        }
        mIsDragging    = false;
        mDragFromIndex = -1;
        mDragToIndex   = -1;
        mDragRecord    = null;
        mPhantomGrid.setHoverSlot(-1);
    }

    /**
     * Shift tiles between fromIndex and toIndex visually (not in mRecords array,
     * just translation) so the user can see where the dragged tile will land.
     */
    private void applyShiftTranslations(int from, int to) {
        if (from < 0 || to < 0 || from == to) {
            for (int i = 0; i < mRecords.size(); i++) {
                if (i != from) {
                    View v = (View) mRecords.get(i).tileView;
                    v.animate().translationX(0).translationY(0).setDuration(120).start();
                }
            }
            return;
        }

        // Direction of shift
        boolean draggingRight = from < to;

        for (int i = 0; i < mRecords.size(); i++) {
            if (i == from) continue; // dragged tile moves with finger, not shifted
            View v = (View) mRecords.get(i).tileView;

            boolean inRange = draggingRight ? (i > from && i <= to)
                                           : (i >= to && i < from);
            if (!inRange) {
                v.animate().translationX(0).translationY(0).setDuration(120).start();
                continue;
            }

            // This tile shifts one position in the opposite direction of the drag
            int targetSlot = draggingRight ? i - 1 : i + 1;
            float[] srcCenter = slotCenter(i);
            float[] dstCenter = slotCenter(targetSlot);
            v.animate()
                    .translationX(dstCenter[0] - srcCenter[0])
                    .translationY(dstCenter[1] - srcCenter[1])
                    .setDuration(120)
                    .start();
        }
    }

    /**
     * Returns the (centerX, centerY) of slot index in the grid coordinate space.
     */
    private float[] slotCenter(int index) {
        if (index < 0 || index >= mRecords.size()) return new float[]{0, 0};
        // Use the actual laid-out position of the tile
        View tv = (View) mRecords.get(index).tileView;
        return new float[]{
                tv.getLeft() + tv.getWidth()  / 2f,
                tv.getTop()  + tv.getHeight() / 2f
        };
    }

    /**
     * Returns which slot (row-major index) is at touch position (x, y), or -1.
     * Uses actual tile positions for accuracy.
     */
    private int getSlotAt(float x, float y) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < mRecords.size(); i++) {
            if (i == mDragFromIndex) continue;
            View tv = (View) mRecords.get(i).tileView;
            float cx = tv.getLeft() + tv.getWidth()  / 2f;
            float cy = tv.getTop()  + tv.getHeight() / 2f;
            float dist = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Tile circle/pill helper
    // -------------------------------------------------------------------------

    boolean isTileCircle(String tileSpec) {
        if (tileSpec == null) return true;
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_PREFIX_SHAPE + tileSpec, true);
    }

    // -------------------------------------------------------------------------
    // QSTileLayout interface
    // -------------------------------------------------------------------------

    @Override
    public void setListening(boolean listening, UiEventLogger uiEventLogger) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, mListening);
        }
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        return ((View) tile.tileView).getTop();
    }

    @Override
    public boolean setMinRows(int minRows) {
        if (minRows < 2) minRows = 2;
        if (mMinRows != minRows) {
            mMinRows = minRows;
            updateResources();
            return true;
        }
        return false;
    }

    @Override
    public boolean setMaxColumns(int maxColumns) {
        mMaxColumns = maxColumns;
        return updateColumns();
    }

    @Override
    public void addTile(TileRecord tile) {
        mRecords.add(tile);
        tile.tile.setListening(this, mListening);

        if (tile.tileView instanceof QSTileViewImpl) {
            QSTileViewImpl view = (QSTileViewImpl) tile.tileView;
            view.setTileMode(isTileCircle(tile.tile.getTileSpec()));
            view.setEditMode(sEditMode);
            view.setOnResizeClickListener(null);
            // Wire long-press for drag initiation
            view.setOnLongClickListener(v -> {
                if (sEditMode) {
                    startDragFromRecord(tile);
                    return true;
                }
                return false;
            });
        }

        addTileView(tile);
    }

    protected void addTileView(TileRecord tile) {
        addView((View) tile.tileView);
    }

    @Override
    public void removeTile(TileRecord tile) {
        mRecords.remove(tile);
        removeView((View) tile.tileView);
    }

    public void removeAllViews() {
        mRecords.clear();
        super.removeAllViews();
    }

    // -------------------------------------------------------------------------
    // Resources
    // -------------------------------------------------------------------------

    public boolean updateResources() {
        Resources res = getResources();
        mResourceColumns      = 4;
        mResourceCellHeight   = res.getDimensionPixelSize(mResourceCellHeightResId);
        mCellMarginHorizontal = res.getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
        mSidePadding          = useSidePadding() ? mCellMarginHorizontal / 2 : 0;
        mCellMarginVertical   = res.getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);

        int defaultMax = Math.max(1, res.getInteger(R.integer.quick_settings_max_rows));
        mMaxAllowedRows = Math.max(mMinRows, defaultMax);
        if (mLessRows) mMaxAllowedRows = Math.max(mMinRows, mMaxAllowedRows - 1);

        mTempTextView.dispatchConfigurationChanged(mContext.getResources().getConfiguration());
        estimateCellHeight();
        if (updateColumns()) {
            requestLayout();
            return true;
        }
        return false;
    }

    protected boolean useSidePadding() {
        return false;
    }

    private boolean updateColumns() {
        int oldColumns = mColumns;
        mColumns = Math.min(mMaxColumns, mResourceColumns);
        return mColumns != oldColumns;
    }

    // -------------------------------------------------------------------------
    // Measure
    // -------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int numTiles = mRecords.size();
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int availableWidth = width - getPaddingStart() - getPaddingEnd();
        final int gaps = mColumns - 1;
        final int singleCellWidth =
                (availableWidth - (mCellMarginHorizontal * gaps) - mSidePadding * 2) / mColumns;

        int currentRow = 0;
        int currentColumn = 0;
        View previousView = this;
        int verticalMeasure = exactly(getCellHeight());

        for (int i = 0; i < numTiles; i++) {
            TileRecord record = mRecords.get(i);
            if (((View) record.tileView).getVisibility() == GONE) continue;

            boolean isCircle = isTileCircle(record.tile.getTileSpec());
            int span = isCircle ? 1 : 2;

            if (record.tileView instanceof QSTileViewImpl) {
                ((QSTileViewImpl) record.tileView).setTileMode(isCircle);
                ((QSTileViewImpl) record.tileView).setEditMode(sEditMode);
            }

            if (currentColumn + span > mColumns) {
                currentRow++;
                currentColumn = 0;
            }

            if (currentRow >= mMaxAllowedRows) {
                ((View) record.tileView).measure(exactly(0), exactly(0));
                continue;
            }

            int tileWidth = (span * singleCellWidth) + ((span - 1) * mCellMarginHorizontal);
            ((View) record.tileView).measure(exactly(tileWidth), verticalMeasure);
            previousView = record.tileView.updateAccessibilityOrder(previousView);

            if (i == 0 || mCellHeight <= 0) {
                mCellHeight = ((View) record.tileView).getMeasuredHeight();
            }

            currentColumn += span;
            if (currentColumn >= mColumns) {
                currentRow++;
                currentColumn = 0;
            }
        }

        // Compute actual rows used
        if (currentColumn == 0 && currentRow > 0) mRows = currentRow;
        else mRows = currentRow + 1;
        if (mRows < mMinRows) mRows = mMinRows;
        if (mRows > mMaxAllowedRows) mRows = mMaxAllowedRows;

        int height = (mCellHeight + mCellMarginVertical) * mRows - mCellMarginVertical;
        if (height < 0) height = 0;

        setMeasuredDimension(width, height);

        // Update phantom grid params so lines align with actual tile positions
        updatePhantomGridParams(singleCellWidth);
    }

    private void updatePhantomGridParams(int singleCellWidth) {
        // Cell size = tile_height (perfect square)
        int cellSize = getCellHeight();
        mPhantomGrid.setGridParams(
                mColumns, mRows,
                cellSize, mCellMarginHorizontal, mCellMarginVertical,
                mSidePadding, 0);

        // Mark pill gaps so vertical lines are skipped where a pill spans
        mPhantomGrid.clearPills();
        int row = 0, col = 0;
        for (TileRecord record : mRecords) {
            boolean isCircle = isTileCircle(record.tile.getTileSpec());
            int span = isCircle ? 1 : 2;

            if (col + span > mColumns) {
                row++;
                col = 0;
            }
            if (row >= mMaxAllowedRows) break;

            // A pill at (row, col) blocks the vertical line at colGap = col
            if (!isCircle && col < mColumns - 1) {
                mPhantomGrid.setPillAt(row, col);
            }

            col += span;
            if (col >= mColumns) {
                row++;
                col = 0;
            }
        }
    }

    public boolean updateMaxRows(int allowedHeight, int tilesCount) {
        final int available = allowedHeight + mCellMarginVertical;
        final int previousRows = mRows;
        int calc = available / (getCellHeight() + mCellMarginVertical);
        if (calc < mMinRows) calc = mMinRows;
        mRows = calc;
        if (mRows >= mMaxAllowedRows) mRows = mMaxAllowedRows;
        return previousRows != mRows;
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutTileRecords(mRecords.size(), true);
    }

    private void layoutTileRecords(int numRecords, boolean forLayout) {
        int row = 0, column = 0;
        mLastTileBottom = 0;

        final int width = getMeasuredWidth();
        final int availableWidth = width - getPaddingStart() - getPaddingEnd();
        final int gaps = mColumns - 1;
        final int singleCellWidth =
                (availableWidth - (mCellMarginHorizontal * gaps) - mSidePadding * 2) / mColumns;

        for (int i = 0; i < numRecords; i++) {
            final TileRecord record = mRecords.get(i);
            View tv = (View) record.tileView;
            if (tv.getVisibility() == GONE) continue;

            if (row >= mMaxAllowedRows) {
                tv.layout(0, 0, 0, 0);
                continue;
            }

            boolean isCircle = isTileCircle(record.tile.getTileSpec());
            int span = isCircle ? 1 : 2;

            if (column + span > mColumns) {
                row++;
                column = 0;
                if (row >= mMaxAllowedRows) {
                    tv.layout(0, 0, 0, 0);
                    continue;
                }
            }

            int slotWidth   = (span * singleCellWidth) + ((span - 1) * mCellMarginHorizontal);
            int tileWidth   = tv.getMeasuredWidth();
            int centerOff   = (slotWidth - tileWidth) / 2;
            int gridLeft    = getPaddingStart() + mSidePadding
                    + (column * (singleCellWidth + mCellMarginHorizontal));
            int leftPos     = gridLeft + centerOff;
            final int top   = getRowTop(row);
            final int right = leftPos + tileWidth;
            final int bottom = top + tv.getMeasuredHeight();

            if (forLayout) {
                tv.layout(leftPos, top, right, bottom);
            } else {
                tv.setLeftTopRightBottom(leftPos, top, right, bottom);
            }
            record.tileView.setPosition(i);
            mLastTileBottom = top + tv.getMeasuredHeight();

            column += span;
            if (column >= mColumns) {
                row++;
                column = 0;
            }
        }
    }

    protected int getRowTop(int row) {
        return row * (getCellHeight() + mCellMarginVertical);
    }

    protected int getColumnStart(int column) {
        return getPaddingStart() + mSidePadding
                + column * (mCellWidth + mCellMarginHorizontal);
    }

    @Override
    public int getTilesHeight() {
        return mLastTileBottom;
    }

    protected static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected void estimateCellHeight() {
        mEstimatedCellHeight = mResourceCellHeight;
    }

    protected int getCellHeight() {
        return Math.max(mResourceCellHeight, mEstimatedCellHeight);
    }

    public int maxTiles() {
        return Math.max(mColumns * mRows, 1);
    }

    @Override
    public int getNumVisibleTiles() {
        return mRecords.size();
    }

    @Override
    public void setSquishinessFraction(float squishinessFraction) {
        // No squish in this ROM
    }

    @Override
    public void setExpansion(float expansion, float proposedTranslation) {
        if (expansion == 0f && sEditMode) {
            setEditMode(false);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCollectionInfo(
                new AccessibilityNodeInfo.CollectionInfo(mRecords.size(), 1, false));
    }
}
