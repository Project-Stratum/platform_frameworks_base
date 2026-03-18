/*
 * Copyright (C) 2024 tequilaOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.qs.customize;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable pool of inactive QS tiles shown below the active grid in edit mode.
 * Full width, no side margins - takes entire screen width.
 */
public class InactiveTilePoolView extends ScrollView implements TileQueryHelper.TileStateListener {

    private static final String SHAPE_PREFS = "qs_tile_config";
    private static final String PREF_CIRCLE  = "tile_is_circle_";
    private static final int    COLUMNS      = 4;

    private LinearLayout mContainer;
    private LinearLayout mTileGrid;

    private List<String> mCurrentSpecs = new ArrayList<>();
    private List<TileQueryHelper.TileInfo> mAllTiles = new ArrayList<>();

    private OnTileAddedListener mAddListener;
    private int mMaxHeight = -1;

    public interface OnTileAddedListener {
        void onTileAdded(String spec);
    }

    public InactiveTilePoolView(Context context) {
        super(context);
        init();
    }

    public InactiveTilePoolView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InactiveTilePoolView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackground(null);
        
        // NO side padding on ScrollView itself - full width
        setPadding(0, 0, 0, 0);

        mContainer = new LinearLayout(getContext());
        mContainer.setOrientation(LinearLayout.VERTICAL);
        mContainer.setClipChildren(false);
        mContainer.setClipToPadding(false);

        // Top corner radius background
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#12FFFFFF"));
        float cornerRadius = dp(24);
        bg.setCornerRadii(new float[]{
                cornerRadius, cornerRadius,
                cornerRadius, cornerRadius,
                0f, 0f,
                0f, 0f});
        mContainer.setBackground(bg);

        // Internal padding for content (not affecting width)
        mContainer.setPadding(dp(16), dp(12), dp(16), dp(24));

        addSeparator();
        addTileGrid();

        // Add container with FULL width
        addView(mContainer, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setMaxHeight(int maxHeight) {
        mMaxHeight = maxHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxHeight > 0) {
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSize = MeasureSpec.getSize(heightMeasureSpec);
            if (heightMode != MeasureSpec.EXACTLY && heightSize > mMaxHeight) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void addSeparator() {
        TextView label = new TextView(getContext());
        label.setText("More tiles");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTextColor(0x55FFFFFF);
        label.setLetterSpacing(0.12f);
        label.setAllCaps(true);
        label.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(10);
        mContainer.addView(label, lp);

        View divider = new View(getContext());
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divLp.bottomMargin = dp(16);
        mContainer.addView(divider, divLp);
    }

    private void addTileGrid() {
        mTileGrid = new LinearLayout(getContext());
        mTileGrid.setOrientation(LinearLayout.VERTICAL);
        mTileGrid.setClipChildren(false);
        mContainer.addView(mTileGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setCurrentSpecs(List<String> specs) {
        mCurrentSpecs = new ArrayList<>(specs);
        rebuildGrid();
    }

    public void setOnTileAddedListener(OnTileAddedListener listener) {
        mAddListener = listener;
    }

    @Override
    public void onTilesChanged(List<TileQueryHelper.TileInfo> tiles) {
        mAllTiles = new ArrayList<>(tiles);
        rebuildGrid();
    }

    private void rebuildGrid() {
        if (mTileGrid == null) return;
        mTileGrid.removeAllViews();

        List<TileQueryHelper.TileInfo> all = mAllTiles;
        if (all.isEmpty()) return;

        int count = all.size();
        for (int rowStart = 0; rowStart < count; rowStart += COLUMNS) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(4);
            mTileGrid.addView(row, rowLp);

            for (int col = 0; col < COLUMNS; col++) {
                int idx = rowStart + col;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (idx < count) {
                    View cell = buildTileCell(all.get(idx));
                    row.addView(cell, cellLp);
                } else {
                    View spacer = new View(getContext());
                    row.addView(spacer, cellLp);
                }
            }
        }
    }

    private View buildTileCell(TileQueryHelper.TileInfo info) {
        boolean isActive = mCurrentSpecs.contains(info.spec);

        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(8), dp(4), dp(10));

        int tileSize = getContext().getResources()
                .getDimensionPixelSize(R.dimen.qs_tile_height);

        // Circular wrapper
        FrameLayout iconWrapper = new FrameLayout(getContext());
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(isActive ? 0x55FFFFFF : 0xFF1A1A1A);
        iconWrapper.setBackground(circleBg);

        QSIconViewImpl iconView = new QSIconViewImpl(getContext());
        
        // Set icon with null check
        if (info.state != null) {
            iconView.setIcon(info.state, false);
        }

        int iconSize = getContext().getResources()
                .getDimensionPixelSize(R.dimen.qs_icon_size);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER;
        iconWrapper.addView(iconView, iconLp);

        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(tileSize, tileSize);
        wrapperLp.gravity = Gravity.CENTER_HORIZONTAL;
        cell.addView(iconWrapper, wrapperLp);

        // Tile name
        TextView nameLabel = new TextView(getContext());
        String name = (info.state != null && info.state.label != null)
                ? info.state.label.toString()
                : info.spec;
        nameLabel.setText(name);
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        nameLabel.setTextColor(isActive ? 0x55FFFFFF : 0xCCFFFFFF);
        nameLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        nameLabel.setMaxLines(2);
        nameLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);

        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(5);
        cell.addView(nameLabel, nameLp);

        if (isActive) {
            cell.setAlpha(0.4f);
        } else {
            cell.setClickable(true);
            cell.setFocusable(true);
            TypedValue rippleVal = new TypedValue();
            getContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, rippleVal, true);
            cell.setForeground(getContext().getDrawable(rippleVal.resourceId));
            cell.setOnClickListener(v -> {
                if (mAddListener != null) {
                    getContext().getSharedPreferences(SHAPE_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_CIRCLE + info.spec, true)
                            .apply();
                    mAddListener.onTileAdded(info.spec);
                }
            });
        }

        return cell;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics()));
    }
}
