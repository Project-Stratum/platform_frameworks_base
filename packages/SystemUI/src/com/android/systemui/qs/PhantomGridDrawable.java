/*
 * Copyright (C) 2024 tequilaOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.qs;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * Drawable that renders the "phantom drag grid" drawn BEHIND QS tiles in TileLayout.
 *
 * Rules:
 *  - Drawn in dispatchDraw() BEFORE super (so tiles appear on top).
 *  - Visible ONLY while a tile is being actively dragged.
 *  - Fades in on drag start, fades out on drop.
 *  - Grid lines pass exactly through the MIDDLE of the margin between tiles.
 *  - Each cell is a PERFECT SQUARE (no corner radius).
 *  - For pill tiles (span=2): the vertical line that would cut through the pill
 *    is skipped for that row segment only. Horizontal lines always drawn.
 *  - Hovered drop slot gets a subtle square fill highlight.
 */
public class PhantomGridDrawable extends Drawable {

    private static final long FADE_DURATION_MS = 160;

    // White at 20% opacity for grid lines
    private static final int LINE_ALPHA  = (int) (0xFF * 0.20f);
    // White at 10% opacity for hover fill
    private static final int HOVER_ALPHA = (int) (0xFF * 0.10f);

    private final Paint mLinePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHoverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Grid params — set by TileLayout after measure/layout
    private int mColumns;
    private int mRows;
    private int mCellSize;      // perfect square: width == height
    private int mMarginH;       // horizontal gap between tiles
    private int mMarginV;       // vertical gap between tiles
    private int mSidePadding;   // left padding of TileLayout
    private int mTopPadding;    // top padding of TileLayout

    // [row][colGap] true = a pill spans this gap in this row → skip vertical line segment
    private boolean[][] mPillGaps;

    // Hovered slot index (row-major, 0-based). -1 = none.
    private int mHoverSlot = -1;

    // Animated alpha [0..1]
    private float mDrawAlpha = 0f;

    private ValueAnimator mAnimator;

    public PhantomGridDrawable() {
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(1.5f);
        mLinePaint.setColor(Color.WHITE);
        mLinePaint.setAlpha(LINE_ALPHA);

        mHoverPaint.setStyle(Paint.Style.FILL);
        mHoverPaint.setColor(Color.WHITE);
        mHoverPaint.setAlpha(HOVER_ALPHA);
    }

    // ---- Layout params set by TileLayout ----

    /**
     * Called from TileLayout after onMeasure so the grid aligns perfectly with tiles.
     *
     * @param columns      number of tile columns
     * @param rows         number of tile rows on this page
     * @param cellSize     tile size in px (width == height, perfect square)
     * @param marginH      horizontal margin between tiles
     * @param marginV      vertical margin between tiles
     * @param sidePadding  left/right padding inside TileLayout
     * @param topPadding   top padding inside TileLayout
     */
    public void setGridParams(int columns, int rows, int cellSize,
            int marginH, int marginV, int sidePadding, int topPadding) {
        mColumns     = columns;
        mRows        = rows;
        mCellSize    = cellSize;
        mMarginH     = marginH;
        mMarginV     = marginV;
        mSidePadding = sidePadding;
        mTopPadding  = topPadding;
        // Allocate pill-gap array
        mPillGaps = new boolean[Math.max(rows, 1)][Math.max(columns - 1, 1)];
        invalidateSelf();
    }

    /**
     * Mark that a pill tile starts at (row, col), so the vertical gap line
     * between col and col+1 must be skipped for that row.
     */
    public void setPillAt(int row, int col) {
        if (mPillGaps == null) return;
        if (row < 0 || row >= mPillGaps.length) return;
        if (col < 0 || col >= mPillGaps[row].length) return;
        mPillGaps[row][col] = true;
    }

    public void clearPills() {
        if (mPillGaps != null) {
            for (boolean[] row : mPillGaps) Arrays.fill(row, false);
        }
    }

    /** Highlight the slot the dragged tile is currently hovering over. -1 clears. */
    public void setHoverSlot(int slot) {
        if (mHoverSlot != slot) {
            mHoverSlot = slot;
            invalidateSelf();
        }
    }

    // ---- Show / hide with fade ----

    public void show() {
        animateTo(1f);
    }

    public void hide() {
        mHoverSlot = -1;
        animateTo(0f);
    }

    private void animateTo(float target) {
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = ValueAnimator.ofFloat(mDrawAlpha, target);
        mAnimator.setDuration(FADE_DURATION_MS);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(anim -> {
            mDrawAlpha = (float) anim.getAnimatedValue();
            invalidateSelf();
        });
        mAnimator.start();
    }

    // ---- Drawing ----

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mDrawAlpha <= 0f || mCellSize <= 0 || mColumns <= 0 || mRows <= 0) return;

        int lineAlpha  = (int) (LINE_ALPHA  * mDrawAlpha);
        int hoverAlpha = (int) (HOVER_ALPHA * mDrawAlpha);
        mLinePaint.setAlpha(lineAlpha);
        mHoverPaint.setAlpha(hoverAlpha);

        // ---- Hover slot highlight (filled square, no radius) ----
        if (mHoverSlot >= 0) {
            int hRow = mHoverSlot / mColumns;
            int hCol = mHoverSlot % mColumns;
            if (hRow < mRows && hCol < mColumns) {
                float left   = mSidePadding + hCol * (mCellSize + mMarginH);
                float top    = mTopPadding  + hRow * (mCellSize + mMarginV);
                float right  = left + mCellSize;
                float bottom = top  + mCellSize;
                canvas.drawRect(left, top, right, bottom, mHoverPaint);
            }
        }

        // ---- Horizontal lines — through middle of vertical gaps between rows ----
        // Grid total width from leftmost tile left to rightmost tile right
        float gridLeft  = mSidePadding;
        float gridRight = mSidePadding + mColumns * mCellSize + (mColumns - 1) * mMarginH;

        for (int row = 0; row < mRows - 1; row++) {
            // Y = bottom of row tile + half of vertical margin
            float y = mTopPadding + (row + 1) * (mCellSize + mMarginV) - mMarginV / 2f;
            canvas.drawLine(gridLeft, y, gridRight, y, mLinePaint);
        }

        // ---- Vertical lines — through middle of horizontal gaps between columns ----
        // Draw per row segment so pill tiles can skip their gap
        for (int col = 0; col < mColumns - 1; col++) {
            // X = right of col tile + half of horizontal margin
            float x = mSidePadding + (col + 1) * (mCellSize + mMarginH) - mMarginH / 2f;

            for (int row = 0; row < mRows; row++) {
                // Skip this segment if a pill spans this gap in this row
                boolean skip = mPillGaps != null
                        && row < mPillGaps.length
                        && col < mPillGaps[row].length
                        && mPillGaps[row][col];
                if (skip) continue;

                float segTop    = mTopPadding + row * (mCellSize + mMarginV);
                float segBottom = segTop + mCellSize;
                canvas.drawLine(x, segTop, x, segBottom, mLinePaint);
            }
        }
    }

    @Override public void setAlpha(int alpha) { /* controlled internally */ }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mLinePaint.setColorFilter(colorFilter);
        mHoverPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
