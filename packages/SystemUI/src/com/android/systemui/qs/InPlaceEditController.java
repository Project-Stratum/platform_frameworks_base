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

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.qs.customize.InactiveTilePoolView;
import com.android.systemui.qs.customize.TileQueryHelper;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Central controller for in-place QS edit mode.
 */
@QSScope
public class InPlaceEditController {

    private static final long FADE_DURATION_MS   = 200;
    private static final long POOL_FADE_DELAY_MS = 80;
    private static final long POOL_FADE_DURATION = 250;
    private static final long HANDLE_STAGGER_MS  = 20;

    private final Context mContext;
    private final QSPanelController mQsPanelController;
    private final TileQueryHelper mTileQueryHelper;
    private final QSHost mQsHost;

    private View mEditButtonView;
    private View mBrightnessView;
    private View mFooterActionsView;
    private View mMediaHostView;
    private ImageView mBackArrow;
    private ViewGroup mQsPanelView;
    private InactiveTilePoolView mPoolView;
    private ViewGroup mPoolContainer;

    private boolean mIsInEditMode = false;

    public interface EditModeListener {
        void onEditModeEntered();
        void onEditModeExited();
    }

    private final List<EditModeListener> mListeners = new ArrayList<>();

    @Inject
    public InPlaceEditController(
            Context context,
            QSPanelController qsPanelController,
            TileQueryHelper tileQueryHelper,
            QSHost qsHost) {
        mContext = context;
        mQsPanelController = qsPanelController;
        mTileQueryHelper = tileQueryHelper;
        mQsHost = qsHost;
    }

    // ---- Setters called from QSFragment ----

    public void setQsPanelView(ViewGroup qsPanel) {
        mQsPanelView = qsPanel;
        createBackArrow();
    }

    public void setEditButtonView(View editButton) {
        mEditButtonView = editButton;
    }

    public void setBrightnessView(View brightness) {
        mBrightnessView = brightness;
    }

    public void setFooterActionsView(View footerActions) {
        mFooterActionsView = footerActions;
    }

    public void setMediaHostView(View media) {
        mMediaHostView = media;
    }

    public void setPoolView(InactiveTilePoolView pool) {
        mPoolView = pool;
    }

    public void setPoolContainer(ViewGroup container) {
        mPoolContainer = container;
        if (mPoolContainer != null) {
            mPoolContainer.setAlpha(0f);
            mPoolContainer.setVisibility(View.GONE);
        }
    }

    public void addListener(EditModeListener listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
    }

    public void removeListener(EditModeListener listener) {
        mListeners.remove(listener);
    }

    public boolean isInEditMode() {
        return mIsInEditMode;
    }

    // ---- Back arrow creation ----

    private void createBackArrow() {
        if (mQsPanelView == null) return;

        mBackArrow = new ImageView(mContext);
        mBackArrow.setImageResource(R.drawable.ic_arrow_back);
        mBackArrow.setColorFilter(android.graphics.Color.WHITE);
        mBackArrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mBackArrow.setContentDescription("Exit edit mode");

        int arrowSize = mContext.getResources()
                .getDimensionPixelSize(R.dimen.qs_footer_action_button_size);
        int margin = mContext.getResources()
                .getDimensionPixelSize(R.dimen.qs_panel_padding);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(arrowSize, arrowSize);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = margin;
        lp.topMargin = margin;
        mBackArrow.setLayoutParams(lp);

        try {
            mBackArrow.setBackground(mContext.getDrawable(R.drawable.ripple_drawable_20dp));
        } catch (Exception ignored) {}

        mBackArrow.setClickable(true);
        mBackArrow.setFocusable(true);
        mBackArrow.setAlpha(0f);
        mBackArrow.setVisibility(View.INVISIBLE);
        mBackArrow.setOnClickListener(v -> exitEditMode());

        // Add to QSPanel as overlay
        if (mQsPanelView instanceof FrameLayout) {
            ((FrameLayout) mQsPanelView).addView(mBackArrow);
        }
    }

    // ---- Enter edit mode ----

    public void enterEditMode() {
        if (mIsInEditMode) return;
        mIsInEditMode = true;

        // 1. Back arrow fades in
        if (mBackArrow != null) {
            mBackArrow.setAlpha(0f);
            mBackArrow.setVisibility(View.VISIBLE);
            mBackArrow.animate().alpha(1f).setDuration(FADE_DURATION_MS).start();
        }

        // 2. Edit button GONE (no space taken)
        setGone(mEditButtonView);

        // 3. Brightness, media, footer actions GONE (no space taken)
        setGone(mBrightnessView);
        setGone(mMediaHostView);
        setGone(mFooterActionsView);

        // 4. Inactive tile pool fades in
        if (mPoolContainer != null) {
            mPoolContainer.setAlpha(0f);
            mPoolContainer.setVisibility(View.VISIBLE);
            fadeIn(mPoolContainer, POOL_FADE_DURATION, POOL_FADE_DELAY_MS);
        }

        // 5. Enable drag + phantom grid
        TileLayout.setEditMode(true);

        // 6. Staggered handle animation
        animateHandlesIn();

        // 7. Query inactive tiles
        if (mPoolView != null) {
            List<String> currentSpecs = new ArrayList<>();
            for (com.android.systemui.plugins.qs.QSTile tile : mQsHost.getTiles()) {
                currentSpecs.add(tile.getTileSpec());
            }
            mPoolView.setCurrentSpecs(currentSpecs);
            mTileQueryHelper.setListener(mPoolView);
            mTileQueryHelper.queryTiles(mQsHost);
        }

        for (EditModeListener l : mListeners) l.onEditModeEntered();
    }

    // ---- Exit edit mode ----

    public void exitEditMode() {
        if (!mIsInEditMode) return;
        mIsInEditMode = false;

        // 1. Back arrow fades out
        if (mBackArrow != null) {
            mBackArrow.animate()
                    .alpha(0f)
                    .setDuration(FADE_DURATION_MS)
                    .withEndAction(() -> mBackArrow.setVisibility(View.INVISIBLE))
                    .start();
        }

        // 2. Edit button back (VISIBLE)
        setVisible(mEditButtonView);

        // 3. Brightness, media, footer actions back (VISIBLE)
        setVisible(mBrightnessView);
        setVisible(mMediaHostView);
        setVisible(mFooterActionsView);

        // 4. Pool fades out then GONE
        if (mPoolContainer != null) {
            final ViewGroup pool = mPoolContainer;
            pool.animate()
                    .alpha(0f)
                    .setDuration(FADE_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        pool.setVisibility(View.GONE);
                        pool.setAlpha(0f);
                    })
                    .start();
        }

        // 5. Disable drag + grid
        TileLayout.setEditMode(false);

        // 6. Handles out
        animateHandlesOut();

        // 7. Save order
        mQsPanelController.saveEditedTileSpecs();

        for (EditModeListener l : mListeners) l.onEditModeExited();
    }

    // ---- Add tile from pool ----

    public void addTileFromPool(String tileSpec) {
        if (tileSpec == null) return;
        mQsHost.addTile(tileSpec);
        
        // Refresh the pool to remove the added tile
        if (mPoolView != null) {
            List<String> currentSpecs = new ArrayList<>();
            for (com.android.systemui.plugins.qs.QSTile tile : mQsHost.getTiles()) {
                currentSpecs.add(tile.getTileSpec());
            }
            mPoolView.setCurrentSpecs(currentSpecs);
        }
    }

    // ---- Handle animations ----

    private void animateHandlesIn() {
        QSPanel.QSTileLayout layout = mQsPanelController.getTileLayout();
        if (!(layout instanceof PagedTileLayout)) return;
        PagedTileLayout paged = (PagedTileLayout) layout;
        TileLayout page = paged.getPageAt(paged.getCurrentItem());
        if (page == null) return;
        for (int i = 0; i < page.mRecords.size(); i++) {
            View tileView = (View) page.mRecords.get(i).tileView;
            long delay = i * HANDLE_STAGGER_MS;
            if (tileView instanceof QSTileViewImpl) {
                ((QSTileViewImpl) tileView).animateHandleIn(delay);
            }
        }
    }

    private void animateHandlesOut() {
        QSPanel.QSTileLayout layout = mQsPanelController.getTileLayout();
        if (!(layout instanceof PagedTileLayout)) return;
        PagedTileLayout paged = (PagedTileLayout) layout;
        for (int p = 0; p < paged.getPageCount(); p++) {
            TileLayout page = paged.getPageAt(p);
            if (page == null) continue;
            for (QSPanelControllerBase.TileRecord record : page.mRecords) {
                View tileView = (View) record.tileView;
                if (tileView instanceof QSTileViewImpl) {
                    ((QSTileViewImpl) tileView).animateHandleOut();
                }
            }
        }
    }

    // ---- Animation helpers (GONE instead of INVISIBLE) ----

    private void setGone(View view) {
        if (view == null) return;
        view.setVisibility(View.GONE);
    }

    private void setVisible(View view) {
        if (view == null) return;
        view.setVisibility(View.VISIBLE);
    }

    private void fadeIn(View view, long duration, long startDelay) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .setDuration(duration)
                .setStartDelay(startDelay)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
