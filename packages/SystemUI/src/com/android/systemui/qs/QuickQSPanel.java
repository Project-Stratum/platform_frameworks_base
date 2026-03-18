/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import android.annotation.NonNull;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSPanelControllerBase.TileRecord;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;
import com.android.systemui.tuner.TunerService;

public class QuickQSPanel extends QSPanel implements TunerService.Tunable {

    private static final String TAG = "QuickQSPanel";
    private static final String PREFS_FILE = "qs_tile_config";
    private static final String PREF_PREFIX_SHAPE = "tile_is_circle_";
    public static final int TUNER_MAX_TILES_FALLBACK = 6;

    private QSLogger mQsLogger;
    private boolean mDisabledByPolicy;
    private int mMaxTiles;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxTiles = 50; 
    }

    @Override
    protected void setHorizontalContentContainerClipping() {
        mHorizontalContentContainer.setClipToPadding(false);
        mHorizontalContentContainer.setClipChildren(false);
    }

    @Override
    public void setBrightnessView(@NonNull View view) {
        if (mBrightnessView != null) {
            removeView(mBrightnessView);
        }
        mBrightnessView = view;
        mAutoBrightnessView = view.findViewById(R.id.brightness_icon);
        setBrightnessViewMargin();
        if (mBrightnessView != null) {
            addView(mBrightnessView);
            mBrightnessView.setVisibility(GONE);
        }
    }

    View getBrightnessView() {
        return mBrightnessView;
    }

    private void setBrightnessViewMargin() {
        if (mBrightnessView != null) {
            MarginLayoutParams lp = (MarginLayoutParams) mBrightnessView.getLayoutParams();
            lp.topMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.qqs_bottom_brightness_margin_top);
            lp.bottomMargin = 0;
            mBrightnessView.setLayoutParams(lp);
        }
    }

    @Override
    void initialize(QSLogger qsLogger) {
        mQsLogger = qsLogger;
        super.initialize(mQsLogger);
        if (mHorizontalContentContainer != null) {
            mHorizontalContentContainer.setClipChildren(false);
        }
    }

    @Override
    public TileLayout getOrCreateTileLayout() {
        QQSSideLabelTileLayout layout = new QQSSideLabelTileLayout(mContext);
        layout.setId(R.id.qqs_tile_layout);
        return layout;
    }

    @Override
    protected boolean displayMediaMarginsOnMedia() {
        return false;
    }

    @Override
    protected boolean mediaNeedsTopMargin() {
        return true;
    }

    @Override
    protected void updatePadding() {
        int bottomPadding = getResources().getDimensionPixelSize(R.dimen.qqs_layout_padding_bottom);
        setPaddingRelative(getPaddingStart(),
                getPaddingTop(),
                getPaddingEnd(),
                bottomPadding);
    }

    @Override
    protected String getDumpableTag() {
        return TAG;
    }

    @Override
    protected boolean shouldShowDetail() {
        return !mExpanded;
    }

    @Override
    protected void drawTile(QSPanelControllerBase.TileRecord r, State state) {
        if (state instanceof SignalState) {
            SignalState copy = new SignalState();
            state.copyTo(copy);
            copy.activityIn = false;
            copy.activityOut = false;
            state = copy;
        }
        super.drawTile(r, state);
    }

    public void setMaxTiles(int maxTiles) {
        mMaxTiles = 50;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QS_SHOW_BRIGHTNESS_SLIDER:
                super.onTuningChanged(key, "0");
                break;
            default:
                super.onTuningChanged(key, newValue);
         }
    }

    public int getNumQuickTiles() {
        return mMaxTiles;
    }

    public static int parseNumTiles(String numTilesValue) {
        try {
            return Integer.parseInt(numTilesValue);
        } catch (NumberFormatException e) {
            return TUNER_MAX_TILES_FALLBACK;
        }
    }

    void setDisabledByPolicy(boolean disabled) {
        if (disabled != mDisabledByPolicy) {
            mDisabledByPolicy = disabled;
            setVisibility(disabled ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void setVisibility(int visibility) {
        if (mDisabledByPolicy) {
            if (getVisibility() == View.GONE) {
                return;
            }
            visibility = View.GONE;
        }
        super.setVisibility(visibility);
    }

    @Override
    protected QSEvent openPanelEvent() {
        return QSEvent.QQS_PANEL_EXPANDED;
    }

    @Override
    protected QSEvent closePanelEvent() {
        return QSEvent.QQS_PANEL_COLLAPSED;
    }

    @Override
    protected QSEvent tileVisibleEvent() {
        return QSEvent.QQS_TILE_VISIBLE;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    static class QQSSideLabelTileLayout extends SideLabelTileLayout {

        private boolean mLastSelected;

        QQSSideLabelTileLayout(Context context) {
            super(context, null);
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            setLayoutParams(lp);
            setMaxColumns(4);
            setMinRows(2);
            TileLayout.addLayout(this);
        }

        @Override
        public boolean updateResources() {
            mResourceCellHeightResId = R.dimen.qs_quick_tile_size;
            mResourceColumns = 4;
            mResourceCellHeight = mContext.getResources().getDimensionPixelSize(mResourceCellHeightResId);
            mCellMarginHorizontal = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
            mSidePadding = 0; 
            mCellMarginVertical = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
            
            mMaxAllowedRows = 2;
            mMinRows = 2;
            
            boolean columnsChanged = updateColumns();
            if (columnsChanged) {
                requestLayout();
                return true;
            }
            return false;
        }
        
        @Override
        public boolean updateMaxRows(int allowedHeight, int tilesCount) {
            final int previousRows = mRows;
            mRows = 2;
            return previousRows != mRows;
        }

        @Override
        public void addTile(TileRecord tile) {
            super.addTile(tile);
            if (tile.tileView instanceof QSTileViewImpl) {
                QSTileViewImpl qsView = (QSTileViewImpl) tile.tileView;
                qsView.setOnResizeClickListener(null);
                qsView.setEditMode(false);
                qsView.setOnLongClickListener(null);
                qsView.setLongClickable(false);
            }
        }

        @Override
        protected void estimateCellHeight() {
            FontSizeUtils.updateFontSize(mTempTextView, R.dimen.qs_tile_text_size);
            int unspecifiedSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            mTempTextView.measure(unspecifiedSpec, unspecifiedSpec);
            int padding = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_padding);
            mEstimatedCellHeight = mTempTextView.getMeasuredHeight() + padding * 2;
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            updateResources();
        }

        boolean isTileCircle(String tileSpec) {
            if (tileSpec == null) return true;
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            return prefs.getBoolean(PREF_PREFIX_SHAPE + tileSpec, true);
        }
        
        private boolean updateColumns() {
            int oldColumns = mColumns;
            mColumns = 4; 
            return oldColumns != mColumns;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int numTiles = mRecords.size();
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int availableWidth = width - getPaddingStart() - getPaddingEnd();
            
            if (mColumns <= 0) {
                mColumns = 4;
            }
            
            final int gaps = mColumns - 1;
            final int singleCellWidth = (availableWidth - (mCellMarginHorizontal * gaps)) / mColumns;

            View previousView = this;
            int verticalMeasure = exactly(getCellHeight());

            for (int i = 0; i < numTiles; i++) {
                TileRecord record = mRecords.get(i);
                if (record.tileView.getVisibility() == GONE) continue;

                boolean isCircle = true;
                if (record.tile != null) {
                    isCircle = isTileCircle(record.tile.getTileSpec());
                }
                int span = isCircle ? 1 : 2;

                if (record.tileView instanceof QSTileViewImpl) {
                    ((QSTileViewImpl) record.tileView).setTileMode(isCircle);
                    ((QSTileViewImpl) record.tileView).setEditMode(false);
                }

                int tileWidth = (span * singleCellWidth) + ((span - 1) * mCellMarginHorizontal);
                record.tileView.measure(exactly(tileWidth), verticalMeasure);
                previousView = record.tileView.updateAccessibilityOrder(previousView);
                
                if (i == 0 || mCellHeight <= 0) {
                    mCellHeight = record.tileView.getMeasuredHeight();
                }
            }

            mRows = 2;
            
            int height = (mCellHeight + mCellMarginVertical) * 2;
            height -= mCellMarginVertical;
            if (height < 0) height = 0;

            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int numRecords = mRecords.size();
            
            int row = 0;
            int column = 0;
            int tilesLaidOut = 0;

            int width = getMeasuredWidth();
            int availableWidth = width - getPaddingStart() - getPaddingEnd();
            int gaps = mColumns - 1;
            int singleCellWidth = (availableWidth - (mCellMarginHorizontal * gaps)) / mColumns;

            for (int i = 0; i < numRecords; i++) {
                final TileRecord record = mRecords.get(i);
                if (record.tileView.getVisibility() == GONE) continue;
                
                boolean isCircle = true;
                if (record.tile != null) {
                    isCircle = isTileCircle(record.tile.getTileSpec());
                }
                int span = isCircle ? 1 : 2;

                if (column + span > mColumns) {
                    row++;
                    column = 0;
                }
                
                if (row >= 2) {
                    record.tileView.layout(0, 0, 0, 0);
                } else {
                    int slotWidth = (span * singleCellWidth) + ((span - 1) * mCellMarginHorizontal);
                    int tileWidth = record.tileView.getMeasuredWidth();
                    int centerOffset = (slotWidth - tileWidth) / 2;

                    int gridLeft = getPaddingStart() + (column * (singleCellWidth + mCellMarginHorizontal));
                    int leftPos = gridLeft + centerOffset;
                    
                    final int top = getRowTop(row);
                    final int right = leftPos + tileWidth;
                    final int bottom = top + record.tileView.getMeasuredHeight();

                    record.tileView.layout(leftPos, top, right, bottom);
                }
                
                record.tileView.setPosition(i);
                tilesLaidOut++;
                column += span;
                
                if (column >= mColumns) {
                    row++;
                    column = 0;
                }
            }
        }

        @Override
        public void setListening(boolean listening, UiEventLogger uiEventLogger) {
            boolean startedListening = !mListening && listening;
            super.setListening(listening, uiEventLogger);
            if (startedListening) {
                for (int i = 0; i < getNumVisibleTiles(); i++) {
                    QSTile tile = mRecords.get(i).tile;
                    uiEventLogger.logWithInstanceId(QSEvent.QQS_TILE_VISIBLE, 0,
                            tile.getMetricsSpec(), tile.getInstanceId());
                }
            }
        }

        @Override
        public void setExpansion(float expansion, float proposedTranslation) {
            if (expansion <= 0f) {
                TileLayout.disableEditMode();
            }
            
            if (expansion > 0f && expansion < 1f) {
                return;
            }
            boolean selected = (expansion == 1f || proposedTranslation < 0f);
            if (mLastSelected == selected) {
                return;
            }
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).setSelected(selected);
            }
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            mLastSelected = selected;
        }
    }
}
