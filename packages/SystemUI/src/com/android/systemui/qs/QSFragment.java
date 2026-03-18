/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.media.dagger.MediaModule.QS_PANEL;
import static com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;
import static com.android.systemui.statusbar.disableflags.DisableFlagsLogger.DisableState;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Trace;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.app.animation.Interpolators;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.compose.ComposeFacade;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.media.controls.ui.MediaHost;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.customize.InactiveTilePoolView;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSFragmentComponent;
import com.android.systemui.qs.footer.ui.binder.FooterActionsViewBinder;
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.util.LifecycleFragment;
import com.android.systemui.util.Utils;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

public class QSFragment extends LifecycleFragment implements QS, CommandQueue.Callbacks,
        StatusBarStateController.StateListener, Dumpable {
    private static final String TAG = "QS";
    private static final boolean DEBUG = false;
    private static final String EXTRA_EXPANDED = "expanded";
    private static final String EXTRA_LISTENING = "listening";
    private static final String EXTRA_VISIBLE = "visible";

    private final Rect mQsBounds = new Rect();
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mBypassController;
    private boolean mQsExpanded;
    private boolean mHeaderAnimating;
    private boolean mStackScrollerOverscrolling;

    private QSAnimator mQSAnimator;
    private HeightListener mPanelView;
    private QSSquishinessController mQSSquishinessController;
    protected QuickStatusBarHeader mHeader;
    protected NonInterceptingScrollView mQSPanelScrollView;
    private boolean mListening;
    private QSContainerImpl mContainer;
    private int mLayoutDirection;
    private QSFooter mFooter;
    private float mLastQSExpansion = -1;
    private float mLastPanelFraction;
    private float mSquishinessFraction = 1;
    private boolean mQsDisabled;
    private int[] mLocationTemp = new int[2];

    private final RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final MediaHost mQsMediaHost;
    private final MediaHost mQqsMediaHost;
    private final QSFragmentComponent.Factory mQsComponentFactory;
    private final QSFragmentDisableFlagsLogger mQsFragmentDisableFlagsLogger;
    private final LargeScreenShadeInterpolator mLargeScreenShadeInterpolator;
    private final FeatureFlags mFeatureFlags;
    private final QSLogger mLogger;
    private final FooterActionsController mFooterActionsController;
    private final FooterActionsViewModel.Factory mFooterActionsViewModelFactory;
    private final FooterActionsViewBinder mFooterActionsViewBinder;
    private final ListeningAndVisibilityLifecycleOwner mListeningAndVisibilityLifecycleOwner;
    private boolean mShowCollapsedOnKeyguard;
    private boolean mLastKeyguardAndExpanded;
    private int mStatusBarState = -1;
    private QSContainerImplController mQSContainerImplController;
    private int[] mTmpLocation = new int[2];
    private int mLastViewHeight;
    private float mLastHeaderTranslation;
    private QSPanelController mQSPanelController;
    private QuickQSPanelController mQuickQSPanelController;
    private QSCustomizerController mQSCustomizerController;
    private FooterActionsViewModel mQSFooterActionsViewModel;
    @Nullable
    private ScrollListener mScrollListener;
    private boolean mInSplitShade;
    private boolean mTransitioningToFullShade;
    private final DumpManager mDumpManager;
    private float mLockscreenToShadeProgress;
    private boolean mOverScrolling;
    private boolean mQsVisible;
    private boolean mIsSmallScreen;

    // ---- In-place edit mode ----
    private InPlaceEditController mInPlaceEditController;
    private InactiveTilePoolView mPoolView;
    private ViewGroup mPoolContainer;

    @Inject
    public QSFragment(RemoteInputQuickSettingsDisabler remoteInputQsDisabler,
            SysuiStatusBarStateController statusBarStateController, CommandQueue commandQueue,
            @Named(QS_PANEL) MediaHost qsMediaHost,
            @Named(QUICK_QS_PANEL) MediaHost qqsMediaHost,
            KeyguardBypassController keyguardBypassController,
            QSFragmentComponent.Factory qsComponentFactory,
            QSFragmentDisableFlagsLogger qsFragmentDisableFlagsLogger,
            DumpManager dumpManager, QSLogger qsLogger,
            FooterActionsController footerActionsController,
            FooterActionsViewModel.Factory footerActionsViewModelFactory,
            FooterActionsViewBinder footerActionsViewBinder,
            LargeScreenShadeInterpolator largeScreenShadeInterpolator,
            FeatureFlags featureFlags) {
        mRemoteInputQuickSettingsDisabler = remoteInputQsDisabler;
        mQsMediaHost = qsMediaHost;
        mQqsMediaHost = qqsMediaHost;
        mQsComponentFactory = qsComponentFactory;
        mQsFragmentDisableFlagsLogger = qsFragmentDisableFlagsLogger;
        mLogger = qsLogger;
        mLargeScreenShadeInterpolator = largeScreenShadeInterpolator;
        mFeatureFlags = featureFlags;
        commandQueue.observe(getLifecycle(), this);
        mBypassController = keyguardBypassController;
        mStatusBarStateController = statusBarStateController;
        mDumpManager = dumpManager;
        mFooterActionsController = footerActionsController;
        mFooterActionsViewModelFactory = footerActionsViewModelFactory;
        mFooterActionsViewBinder = footerActionsViewBinder;
        mListeningAndVisibilityLifecycleOwner = new ListeningAndVisibilityLifecycleOwner();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        try {
            Trace.beginSection("QSFragment#onCreateView");
            inflater = inflater.cloneInContext(new ContextThemeWrapper(getContext(),
                    R.style.Theme_SystemUI_QuickSettings));
            return inflater.inflate(R.layout.qs_panel, container, false);
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        QSFragmentComponent qsFragmentComponent = mQsComponentFactory.create(this);
        mQSPanelController = qsFragmentComponent.getQSPanelController();
        mQuickQSPanelController = qsFragmentComponent.getQuickQSPanelController();

        mQSPanelController.init();
        mQuickQSPanelController.init();

        mQSFooterActionsViewModel = mFooterActionsViewModelFactory.create(this);
        bindFooterActionsView(view);
        mFooterActionsController.init();

        mQSPanelScrollView = view.findViewById(R.id.expanded_qs_scroll_view);
        mQSPanelScrollView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    updateQsBounds();
                });
        mQSPanelScrollView.setOnScrollChangeListener(
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    mQSAnimator.requestAnimatorUpdate();
                    if (mScrollListener != null) {
                        mScrollListener.onQsPanelScrollChanged(scrollY);
                    }
                });
        mHeader = view.findViewById(R.id.header);
        mFooter = qsFragmentComponent.getQSFooter();

        mQSContainerImplController = qsFragmentComponent.getQSContainerImplController();
        mQSContainerImplController.init();
        mContainer = mQSContainerImplController.getView();
        mDumpManager.registerDumpable(mContainer.getClass().getName(), mContainer);

        mQSAnimator = qsFragmentComponent.getQSAnimator();
        mQSSquishinessController = qsFragmentComponent.getQSSquishinessController();

        mQSCustomizerController = qsFragmentComponent.getQSCustomizerController();
        mQSCustomizerController.init();
        mQSCustomizerController.setQs(this);
        if (savedInstanceState != null) {
            setQsVisible(savedInstanceState.getBoolean(EXTRA_VISIBLE));
            setExpanded(savedInstanceState.getBoolean(EXTRA_EXPANDED));
            setListening(savedInstanceState.getBoolean(EXTRA_LISTENING));
            setEditLocation(view);
            mQSCustomizerController.restoreInstanceState(savedInstanceState);
            if (mQsExpanded) {
                mQSPanelController.getTileLayout().restoreInstanceState(savedInstanceState);
            }
        }
        mStatusBarStateController.addCallback(this);
        onStateChanged(mStatusBarStateController.getState());
        view.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    boolean sizeChanged = (oldTop - oldBottom) != (top - bottom);
                    if (sizeChanged) {
                        setQsExpansion(mLastQSExpansion, mLastPanelFraction,
                                mLastHeaderTranslation, mSquishinessFraction);
                    }
                });
        mQSPanelController.setUsingHorizontalLayoutChangeListener(
                () -> {
                    mQSPanelController.getMediaHost().getHostView().setAlpha(1.0f);
                    mQSAnimator.requestAnimatorUpdate();
                });

        // Wire in-place edit mode after everything else is ready
        wireInPlaceEditMode(view, qsFragmentComponent);
    }

    /**
     * Wires up InPlaceEditController with all the views it needs to fade in/out.
     * Called at the end of onViewCreated so all views are guaranteed to exist.
     */
    private void wireInPlaceEditMode(View view, QSFragmentComponent qsFragmentComponent) {
        mInPlaceEditController = qsFragmentComponent.getInPlaceEditController();

        // 1. QSPanel itself for back arrow overlay
        QSPanel qsPanel = view.findViewById(R.id.quick_settings_panel);
        mInPlaceEditController.setQsPanelView(qsPanel);

        // 2. Brightness slider
        View brightness = mQSPanelController.getBrightnessView();
        mInPlaceEditController.setBrightnessView(brightness);

        // 3. Footer actions row
        View footerActions = view.findViewById(R.id.qs_footer_actions);
        mInPlaceEditController.setFooterActionsView(footerActions);

        // 4. Media host view
        View mediaHost = mQsMediaHost.getHostView();
        mInPlaceEditController.setMediaHostView(mediaHost);

        // 5. Build inactive tile pool - FULL WIDTH, no margins
        if (qsPanel != null) {
            // Pool container - full width with NO side margins
            mPoolContainer = new LinearLayout(getContext());
            ((LinearLayout) mPoolContainer).setOrientation(LinearLayout.VERTICAL);
            mPoolContainer.setAlpha(0f);
            mPoolContainer.setVisibility(View.GONE);

            // Pool view with max height
            mPoolView = new InactiveTilePoolView(getContext());
            int maxPoolHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.4f);
            mPoolView.setMaxHeight(maxPoolHeight);
            mPoolView.setOnTileAddedListener(spec -> mInPlaceEditController.addTileFromPool(spec));

            // Add pool to container with FULL width
            mPoolContainer.addView(mPoolView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Add pool container to QSPanel with FULL width, NO margins
            ViewGroup.MarginLayoutParams poolParams = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            poolParams.setMargins(0, 0, 0, 0); // NO side margins
            qsPanel.addView(mPoolContainer, poolParams);

            mInPlaceEditController.setPoolView(mPoolView);
            mInPlaceEditController.setPoolContainer(mPoolContainer);
        }
    }

    private void bindFooterActionsView(View root) {
        LinearLayout footerActionsView = root.findViewById(R.id.qs_footer_actions);

        if (!mFeatureFlags.isEnabled(Flags.COMPOSE_QS_FOOTER_ACTIONS)
                || !ComposeFacade.INSTANCE.isComposeAvailable()) {
            Log.d(TAG, "Binding the View implementation of the QS footer actions");
            mFooterActionsViewBinder.bind(footerActionsView, mQSFooterActionsViewModel,
                    mListeningAndVisibilityLifecycleOwner);
            return;
        }

        Log.d(TAG, "Binding the Compose implementation of the QS footer actions");
        View composeView = ComposeFacade.INSTANCE.createFooterActionsView(root.getContext(),
                mQSFooterActionsViewModel, mListeningAndVisibilityLifecycleOwner);
        composeView.setId(R.id.qs_footer_actions);

        ViewGroup parent = (ViewGroup) footerActionsView.getParent();
        ViewGroup.LayoutParams layoutParams = footerActionsView.getLayoutParams();
        int index = parent.indexOfChild(footerActionsView);
        parent.removeViewAt(index);
        parent.addView(composeView, index, layoutParams);
    }

    @Override
    public void setScrollListener(ScrollListener listener) {
        mScrollListener = listener;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDumpManager.registerDumpable(getClass().getName(), this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusBarStateController.removeCallback(this);
        if (mListening) {
            setListening(false);
        }
        if (mQSCustomizerController != null) {
            mQSCustomizerController.setQs(null);
        }
        mScrollListener = null;
        if (mContainer != null) {
            mDumpManager.unregisterDumpable(mContainer.getClass().getName());
        }
        mDumpManager.unregisterDumpable(getClass().getName());
        mListeningAndVisibilityLifecycleOwner.destroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_EXPANDED, mQsExpanded);
        outState.putBoolean(EXTRA_LISTENING, mListening);
        outState.putBoolean(EXTRA_VISIBLE, mQsVisible);
        if (mQSCustomizerController != null) {
            mQSCustomizerController.saveInstanceState(outState);
        }
        if (mQsExpanded) {
            mQSPanelController.getTileLayout().saveInstanceState(outState);
        }
    }

    @VisibleForTesting
    boolean isListening() { return mListening; }

    @VisibleForTesting
    boolean isExpanded() { return mQsExpanded; }

    @VisibleForTesting
    boolean isQsVisible() { return mQsVisible; }

    @Override
    public View getHeader() { return mHeader; }

    @Override
    public void setHasNotifications(boolean hasNotifications) {}

    @Override
    public void setPanelView(HeightListener panelView) {
        mPanelView = panelView;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setEditLocation(getView());
        if (newConfig.getLayoutDirection() != mLayoutDirection) {
            mLayoutDirection = newConfig.getLayoutDirection();
            if (mQSAnimator != null) {
                mQSAnimator.onRtlChanged();
            }
        }
        updateQsState();
    }

    @Override
    public void setFancyClipping(int leftInset, int top, int rightInset, int bottom,
            int cornerRadius, boolean visible, boolean fullWidth) {
        if (getView() instanceof QSContainerImpl) {
            ((QSContainerImpl) getView()).setFancyClipping(leftInset, top, rightInset, bottom,
                    cornerRadius, visible, fullWidth);
        }
    }

    @Override
    public boolean isFullyCollapsed() {
        return mLastQSExpansion == 0.0f || mLastQSExpansion == -1;
    }

    @Override
    public void setCollapsedMediaVisibilityChangedListener(Consumer<Boolean> listener) {
        mQuickQSPanelController.setMediaVisibilityChangedListener(listener);
    }

    private void setEditLocation(View view) {
        View edit = view.findViewById(android.R.id.edit);
        int[] loc = edit.getLocationOnScreen();
        int x = loc[0] + edit.getWidth() / 2;
        int y = loc[1] + edit.getHeight() / 2;
        mQSCustomizerController.setEditLocation(x, y);
    }

    @Override
    public void setContainerController(QSContainerController controller) {
        mQSCustomizerController.setContainerController(controller);
    }

    @Override
    public boolean isCustomizing() {
        return mQSCustomizerController.isCustomizing()
                || (mInPlaceEditController != null && mInPlaceEditController.isInEditMode());
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        int state2BeforeAdjustment = state2;
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);

        mQsFragmentDisableFlagsLogger.logDisableFlagChange(
                /* new= */ new DisableState(state1, state2BeforeAdjustment),
                /* newAfterLocalModification= */ new DisableState(state1, state2)
        );

        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mContainer.disable(state1, state2, animate);
        mHeader.disable(state1, state2, animate);
        mFooter.disable(state1, state2, animate);
        updateQsState();
    }

    private void updateQsState() {
        final boolean expandVisually = mQsExpanded || mStackScrollerOverscrolling
                || mHeaderAnimating;
        mQSPanelController.setExpanded(mQsExpanded);
        boolean keyguardShowing = isKeyguardState();
        mHeader.setVisibility((mQsExpanded || !keyguardShowing || mHeaderAnimating
                || mShowCollapsedOnKeyguard)
                ? View.VISIBLE
                : View.INVISIBLE);
        mHeader.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (mQsExpanded && !mStackScrollerOverscrolling), mQuickQSPanelController);
        boolean qsPanelVisible = !mQsDisabled && expandVisually;
        boolean footerVisible = qsPanelVisible && (mQsExpanded || !keyguardShowing
                || mHeaderAnimating || mShowCollapsedOnKeyguard);
        mFooter.setVisibility(footerVisible ? View.VISIBLE : View.INVISIBLE);
        mQSFooterActionsViewModel.onVisibilityChangeRequested(footerVisible);
        mFooter.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (mQsExpanded && !mStackScrollerOverscrolling));
        mQSPanelController.setVisibility(qsPanelVisible ? View.VISIBLE : View.INVISIBLE);
        if (DEBUG) {
            Log.d(TAG, "Footer: " + footerVisible + ", QS Panel: " + qsPanelVisible);
        }
    }

    private boolean isKeyguardState() {
        return mStatusBarStateController.getCurrentOrUpcomingState() == KEYGUARD;
    }

    private void updateShowCollapsedOnKeyguard() {
        boolean showCollapsed = mBypassController.getBypassEnabled()
                || (mTransitioningToFullShade && !mInSplitShade);
        if (showCollapsed != mShowCollapsedOnKeyguard) {
            mShowCollapsedOnKeyguard = showCollapsed;
            updateQsState();
            if (mQSAnimator != null) {
                mQSAnimator.setShowCollapsedOnKeyguard(showCollapsed);
            }
            if (!showCollapsed && isKeyguardState()) {
                setQsExpansion(mLastQSExpansion, mLastPanelFraction, 0, mSquishinessFraction);
            }
        }
    }

    public QSPanelController getQSPanelController() {
        return mQSPanelController;
    }

    public void setBrightnessMirrorController(
            BrightnessMirrorController brightnessMirrorController) {
        mQSPanelController.setBrightnessMirror(brightnessMirrorController);
        mQuickQSPanelController.setBrightnessMirror(brightnessMirrorController);
    }

    @Override
    public boolean isShowingDetail() {
        return mQSCustomizerController.isCustomizing();
    }

    @Override
    public void setHeaderClickable(boolean clickable) {
        if (DEBUG) Log.d(TAG, "setHeaderClickable " + clickable);
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (DEBUG) Log.d(TAG, "setExpanded " + expanded);
        mQsExpanded = expanded;

        // Collapsing QS → exit in-place edit mode automatically
        if (!expanded && mInPlaceEditController != null
                && mInPlaceEditController.isInEditMode()) {
            mInPlaceEditController.exitEditMode();
        }

        if (mInSplitShade && mQsExpanded) {
            setListening(true);
        } else {
            updateQsPanelControllerListening();
        }
        updateQsState();
    }

    private void setKeyguardShowing(boolean keyguardShowing) {
        if (DEBUG) Log.d(TAG, "setKeyguardShowing " + keyguardShowing);
        mLastQSExpansion = -1;

        if (mQSAnimator != null) {
            mQSAnimator.setOnKeyguard(keyguardShowing);
        }

        mFooter.setKeyguardShowing(keyguardShowing);
        updateQsState();
    }

    @Override
    public void setOverscrolling(boolean stackScrollerOverscrolling) {
        if (DEBUG) Log.d(TAG, "setOverscrolling " + stackScrollerOverscrolling);
        mStackScrollerOverscrolling = stackScrollerOverscrolling;
        updateQsState();
    }

    @Override
    public void setListening(boolean listening) {
        if (DEBUG) Log.d(TAG, "setListening " + listening);
        mListening = listening;
        mQSContainerImplController.setListening(listening && mQsVisible);
        mListeningAndVisibilityLifecycleOwner.updateState();
        updateQsPanelControllerListening();
    }

    private void updateQsPanelControllerListening() {
        mQSPanelController.setListening(mListening && mQsVisible, mQsExpanded);
    }

    @Override
    public void setQsVisible(boolean visible) {
        if (DEBUG) Log.d(TAG, "setQsVisible " + visible);
        mQsVisible = visible;
        setListening(mListening);
        mListeningAndVisibilityLifecycleOwner.updateState();
    }

    @Override
    public void setHeaderListening(boolean listening) {
        mQSContainerImplController.setListening(listening);
    }

    @Override
    public void setInSplitShade(boolean inSplitShade) {
        mInSplitShade = inSplitShade;
        updateShowCollapsedOnKeyguard();
        updateQsState();
    }

    @Override
    public void setTransitionToFullShadeProgress(
            boolean isTransitioningToFullShade,
            @FloatRange(from = 0.0, to = 1.0) float qsTransitionFraction,
            @FloatRange(from = 0.0, to = 1.0) float qsSquishinessFraction) {
        if (isTransitioningToFullShade != mTransitioningToFullShade) {
            mTransitioningToFullShade = isTransitioningToFullShade;
            updateShowCollapsedOnKeyguard();
        }
        mLockscreenToShadeProgress = qsTransitionFraction;
        setQsExpansion(mLastQSExpansion, mLastPanelFraction, mLastHeaderTranslation,
                isTransitioningToFullShade ? qsSquishinessFraction : mSquishinessFraction);
    }

    @Override
    public void setOverScrollAmount(int overScrollAmount) {
        mOverScrolling = overScrollAmount != 0;
        View view = getView();
        if (view != null) {
            view.setTranslationY(overScrollAmount);
        }
    }

    @Override
    public int getHeightDiff() {
        return mQSPanelScrollView.getBottom() - mHeader.getBottom()
                + mHeader.getPaddingBottom();
    }

    @Override
    public void setIsNotificationPanelFullWidth(boolean isFullWidth) {
        mIsSmallScreen = isFullWidth;
    }

    @Override
    public void setQsExpansion(float expansion, float panelExpansionFraction,
            float proposedTranslation, float squishinessFraction) {
        float headerTranslation = mTransitioningToFullShade ? 0 : proposedTranslation;
        float alphaProgress = calculateAlphaProgress(panelExpansionFraction);
        setAlphaAnimationProgress(alphaProgress);
        mContainer.setExpansion(expansion);
        final float translationScaleY = (mInSplitShade
                ? 1 : QSAnimator.SHORT_PARALLAX_AMOUNT) * (expansion - 1);
        boolean onKeyguard = isKeyguardState();
        boolean onKeyguardAndExpanded = onKeyguard && !mShowCollapsedOnKeyguard;
        if (!mHeaderAnimating && !headerWillBeAnimating() && !mOverScrolling) {
            getView().setTranslationY(
                    onKeyguardAndExpanded
                            ? translationScaleY * mHeader.getHeight()
                            : headerTranslation);
        }
        int currentHeight = getView().getHeight();
        if (expansion == mLastQSExpansion
                && mLastKeyguardAndExpanded == onKeyguardAndExpanded
                && mLastViewHeight == currentHeight
                && mLastHeaderTranslation == headerTranslation
                && mSquishinessFraction == squishinessFraction
                && mLastPanelFraction == panelExpansionFraction) {
            return;
        }
        mLastHeaderTranslation = headerTranslation;
        mLastPanelFraction = panelExpansionFraction;
        mSquishinessFraction = squishinessFraction;
        mLastQSExpansion = expansion;
        mLastKeyguardAndExpanded = onKeyguardAndExpanded;
        mLastViewHeight = currentHeight;

        boolean fullyExpanded = expansion == 1;
        boolean fullyCollapsed = expansion == 0.0f;
        int heightDiff = getHeightDiff();
        float panelTranslationY = translationScaleY * heightDiff;

        if (expansion < 1 && expansion > 0.99) {
            if (mQuickQSPanelController.switchTileLayout(false)) {
                mHeader.updateResources();
            }
        }
        mQSPanelController.setIsOnKeyguard(onKeyguard);
        mFooter.setExpansion(onKeyguardAndExpanded ? 1 : expansion);
        float footerActionsExpansion =
                onKeyguardAndExpanded ? 1 : mInSplitShade ? alphaProgress : expansion;
        mQSFooterActionsViewModel.onQuickSettingsExpansionChanged(footerActionsExpansion,
                mInSplitShade);
        mQSPanelController.setRevealExpansion(expansion);
        mQSPanelController.getTileLayout().setExpansion(expansion, proposedTranslation);
        mQuickQSPanelController.getTileLayout().setExpansion(expansion, proposedTranslation);

        float qsScrollViewTranslation =
                onKeyguard && !mShowCollapsedOnKeyguard ? panelTranslationY : 0;
        mQSPanelScrollView.setTranslationY(qsScrollViewTranslation);

        if (fullyCollapsed) {
            mQSPanelScrollView.setScrollY(0);
        }

        if (!fullyExpanded) {
            mQsBounds.top = (int) -mQSPanelScrollView.getTranslationY();
            mQsBounds.right = mQSPanelScrollView.getWidth();
            mQsBounds.bottom = mQSPanelScrollView.getHeight();
        }
        updateQsBounds();

        if (mQSSquishinessController != null) {
            mQSSquishinessController.setSquishiness(mSquishinessFraction);
        }
        if (mQSAnimator != null) {
            mQSAnimator.setPosition(expansion);
        }
        if (!mInSplitShade
                || mStatusBarStateController.getState() == KEYGUARD
                || mStatusBarStateController.getState() == SHADE_LOCKED) {
            mQsMediaHost.setSquishFraction(1.0F);
        } else {
            mQsMediaHost.setSquishFraction(mSquishinessFraction);
        }
        updateMediaPositions();
    }

    private void setAlphaAnimationProgress(float progress) {
        final View view = getView();
        if (progress == 0 && view.getVisibility() != View.INVISIBLE) {
            mLogger.logVisibility("QS fragment", View.INVISIBLE);
            view.setVisibility(View.INVISIBLE);
        } else if (progress > 0 && view.getVisibility() != View.VISIBLE) {
            mLogger.logVisibility("QS fragment", View.VISIBLE);
            view.setVisibility(View.VISIBLE);
        }
        view.setAlpha(interpolateAlphaAnimationProgress(progress));
    }

    private float calculateAlphaProgress(float panelExpansionFraction) {
        if (mIsSmallScreen) return 1;
        if (mInSplitShade) {
            if (mTransitioningToFullShade
                    || mStatusBarStateController.getCurrentOrUpcomingState() == KEYGUARD) {
                return mLockscreenToShadeProgress;
            } else {
                return panelExpansionFraction;
            }
        }
        if (mTransitioningToFullShade) {
            return mLockscreenToShadeProgress;
        } else {
            return panelExpansionFraction;
        }
    }

    private float interpolateAlphaAnimationProgress(float progress) {
        if (mQSPanelController.isBouncerInTransit()) {
            return BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(progress);
        }
        if (isKeyguardState()) return progress;
        if (mIsSmallScreen) {
            return ShadeInterpolation.getContentAlpha(progress);
        } else {
            return mLargeScreenShadeInterpolator.getQsAlpha(progress);
        }
    }

    @VisibleForTesting
    void updateQsBounds() {
        if (mLastQSExpansion == 1.0f) {
            int sideMargin = getResources().getDimensionPixelSize(
                    R.dimen.qs_tiles_page_horizontal_margin) * 2;
            mQsBounds.set(-sideMargin, 0, mQSPanelScrollView.getWidth() + sideMargin,
                    mQSPanelScrollView.getHeight());
        }
        mQSPanelScrollView.setClipBounds(mQsBounds);

        mQSPanelScrollView.getLocationOnScreen(mLocationTemp);
        int left = mLocationTemp[0];
        int top = mLocationTemp[1];
        mQsMediaHost.getCurrentClipping().set(left, top,
                left + getView().getMeasuredWidth(),
                top + mQSPanelScrollView.getMeasuredHeight()
                        - mQSPanelController.getPaddingBottom());
    }

    private void updateMediaPositions() {
        if (Utils.useQsMediaPlayer(getContext())) {
            View hostView = mQsMediaHost.getHostView();
            if (mLastQSExpansion > 0 && !isKeyguardState() && !mQqsMediaHost.getVisible()
                    && !mQSPanelController.shouldUseHorizontalLayout() && !mInSplitShade) {
                float interpolation = 1.0f - mLastQSExpansion;
                interpolation = Interpolators.ACCELERATE.getInterpolation(interpolation);
                float translationY = -hostView.getHeight() * 1.3f * interpolation;
                hostView.setTranslationY(translationY);
            } else {
                hostView.setTranslationY(0);
            }
        }
    }

    private boolean headerWillBeAnimating() {
        return mStatusBarState == KEYGUARD && mShowCollapsedOnKeyguard && !isKeyguardState();
    }

    @Override
    public void animateHeaderSlidingOut() {
        if (DEBUG) Log.d(TAG, "animateHeaderSlidingOut");
        if (getView().getY() == -mHeader.getHeight()) {
            return;
        }
        mHeaderAnimating = true;
        getView().animate().y(-mHeader.getHeight())
                .setStartDelay(0)
                .setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (getView() != null) {
                            getView().animate().setListener(null);
                        }
                        mHeaderAnimating = false;
                        updateQsState();
                    }
                })
                .start();
    }

    @Override
    public void setCollapseExpandAction(Runnable action) {
        mQSPanelController.setCollapseExpandAction(action);
        mQuickQSPanelController.setCollapseExpandAction(action);
    }

    @Override
    public void closeDetail() {
        mQSPanelController.closeDetail();
    }

    @Override
    public void closeCustomizer() {
        mQSCustomizerController.hide();

        // Also exit in-place edit mode if it is active
        if (mInPlaceEditController != null && mInPlaceEditController.isInEditMode()) {
            mInPlaceEditController.exitEditMode();
        }

        if (mQuickQSPanelController != null) {
            mQuickQSPanelController.refreshAllTiles();
            mQuickQSPanelController.setTiles();
            getView().post(() -> {
                if (mQuickQSPanelController != null) {
                    mQuickQSPanelController.setTiles();
                }
            });
        }
    }

    public void notifyCustomizeChanged() {
        mContainer.updateExpansion();
        boolean customizing = isCustomizing();
        mQSPanelScrollView.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mFooter.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mQSFooterActionsViewModel.onVisibilityChangeRequested(!customizing);
        mHeader.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mPanelView.onQsHeightChanged();
    }

    @Override
    public int getDesiredHeight() {
        if (mQSCustomizerController.isCustomizing()) {
            return getView().getHeight();
        }
        return getView().getMeasuredHeight();
    }

    @Override
    public void setHeightOverride(int desiredHeight) {
        mContainer.setHeightOverride(desiredHeight);
    }

    @Override
    public int getQsMinExpansionHeight() {
        if (mInSplitShade) {
            return getQsMinExpansionHeightForSplitShade();
        }
        return mHeader.getHeight();
    }

    private int getQsMinExpansionHeightForSplitShade() {
        getView().getLocationOnScreen(mLocationTemp);
        int top = mLocationTemp[1];
        int originalTop = (int) (top - getView().getTranslationY());
        return originalTop + getView().getHeight();
    }

    @Override
    public void hideImmediately() {
        getView().animate().cancel();
        getView().setY(-getQsMinExpansionHeight());
    }

    private final ViewTreeObserver.OnPreDrawListener mStartHeaderSlidingIn
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getView().getViewTreeObserver().removeOnPreDrawListener(this);
            getView().animate()
                    .translationY(0f)
                    .setDuration(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setListener(mAnimateHeaderSlidingInListener)
                    .start();
            return true;
        }
    };

    private final Animator.AnimatorListener mAnimateHeaderSlidingInListener
            = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mHeaderAnimating = false;
            updateQsState();
            getView().animate().setListener(null);
        }
    };

    @Override
    public void onUpcomingStateChanged(int upcomingState) {
        if (upcomingState == KEYGUARD) {
            onStateChanged(upcomingState);
        }
    }

    @Override
    public void onStateChanged(int newState) {
        if (newState == mStatusBarState) return;
        mStatusBarState = newState;
        setKeyguardShowing(newState == KEYGUARD);
        updateShowCollapsedOnKeyguard();
    }

    @VisibleForTesting
    public ListeningAndVisibilityLifecycleOwner getListeningAndVisibilityLifecycleOwner() {
        return mListeningAndVisibilityLifecycleOwner;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("QSFragment:");
        ipw.increaseIndent();
        ipw.println("mQsBounds: " + mQsBounds);
        ipw.println("mQsExpanded: " + mQsExpanded);
        ipw.println("mHeaderAnimating: " + mHeaderAnimating);
        ipw.println("mStackScrollerOverscrolling: " + mStackScrollerOverscrolling);
        ipw.println("mListening: " + mListening);
        ipw.println("mQsVisible: " + mQsVisible);
        ipw.println("mLayoutDirection: " + mLayoutDirection);
        ipw.println("mLastQSExpansion: " + mLastQSExpansion);
        ipw.println("mLastPanelFraction: " + mLastPanelFraction);
        ipw.println("mSquishinessFraction: " + mSquishinessFraction);
        ipw.println("mQsDisabled: " + mQsDisabled);
        ipw.println("mTemp: " + Arrays.toString(mLocationTemp));
        ipw.println("mShowCollapsedOnKeyguard: " + mShowCollapsedOnKeyguard);
        ipw.println("mLastKeyguardAndExpanded: " + mLastKeyguardAndExpanded);
        ipw.println("mStatusBarState: " + StatusBarState.toString(mStatusBarState));
        ipw.println("mTmpLocation: " + Arrays.toString(mTmpLocation));
        ipw.println("mLastViewHeight: " + mLastViewHeight);
        ipw.println("mLastHeaderTranslation: " + mLastHeaderTranslation);
        ipw.println("mInSplitShade: " + mInSplitShade);
        ipw.println("mTransitioningToFullShade: " + mTransitioningToFullShade);
        ipw.println("mLockscreenToShadeProgress: " + mLockscreenToShadeProgress);
        ipw.println("mOverScrolling: " + mOverScrolling);
        ipw.println("isCustomizing: " + mQSCustomizerController.isCustomizing());
        ipw.println("isInEditMode: " + (mInPlaceEditController != null
                && mInPlaceEditController.isInEditMode()));
        View view = getView();
        if (view != null) {
            ipw.println("top: " + view.getTop());
            ipw.println("y: " + view.getY());
            ipw.println("translationY: " + view.getTranslationY());
            ipw.println("alpha: " + view.getAlpha());
            ipw.println("height: " + view.getHeight());
            ipw.println("measuredHeight: " + view.getMeasuredHeight());
            ipw.println("clipBounds: " + view.getClipBounds());
        } else {
            ipw.println("getView(): null");
        }
        QuickStatusBarHeader header = mHeader;
        if (header != null) {
            ipw.println("headerHeight: " + header.getHeight());
            ipw.println("Header visibility: " + visibilityToString(header.getVisibility()));
        } else {
            ipw.println("mHeader: null");
        }
    }

    private static String visibilityToString(int visibility) {
        if (visibility == View.VISIBLE) return "VISIBLE";
        if (visibility == View.INVISIBLE) return "INVISIBLE";
        return "GONE";
    }

    @VisibleForTesting
    class ListeningAndVisibilityLifecycleOwner implements LifecycleOwner {
        private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);
        private boolean mDestroyed = false;

        {
            updateState();
        }

        @Override
        public Lifecycle getLifecycle() {
            return mLifecycleRegistry;
        }

        public void updateState() {
            if (mDestroyed) {
                mLifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
                return;
            }
            if (!mListening) {
                mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
                return;
            }
            if (!mQsVisible) {
                mLifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                return;
            }
            mLifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        }

        public void destroy() {
            mDestroyed = true;
            updateState();
        }
    }
}
