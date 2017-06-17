/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.TypedValue;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.BatteryLevelTextView;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.ClockController;
import com.android.systemui.statusbar.policy.NetworkTraffic;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controls everything regarding the icons in the status bar and on Keyguard, including, but not
 * limited to: notification icons, signal cluster, additional status icons, and clock in the status
 * bar.
 */
public class StatusBarIconController extends StatusBarIconList implements Tunable {

    public static final long DEFAULT_TINT_ANIMATION_DURATION = 120;
    public static final String ICON_BLACKLIST = "icon_blacklist";
    public static final int DEFAULT_ICON_TINT = Color.WHITE;

    private Context mContext;
    private View mStatusBar;
    private PhoneStatusBar mPhoneStatusBar;
    private DemoStatusIcons mDemoStatusIcons;

    private LinearLayout mStatusBarContents;
    private LinearLayout mSystemIconArea;
    private LinearLayout mStatusIcons;
    private SignalClusterView mSignalCluster;
    private LinearLayout mStatusIconsKeyguard;

    private NotificationIconAreaController mNotificationIconAreaController;
    private View mNotificationIconAreaInner;

    private BatteryMeterView mBatteryMeterView;
    private BatteryMeterView mBatteryMeterViewKeyguard;
    private ClockController mClockController;
    private View mCenterClockLayout;
    private NetworkTraffic mNetworkTraffic;
    private Ticker mTicker;
    private View mTickerView;
    private TextView mWifiSsidLabel;
    private TextView mCarrierLabel;

    private int mIconSize;
    private int mIconHPadding;

    private int mIconTint = DEFAULT_ICON_TINT;
    private float mDarkIntensity;
    private final Rect mTintArea = new Rect();
    private static final Rect sTmpRect = new Rect();
    private static final int[] sTmpInt2 = new int[2];

    private boolean mTransitionPending;
    private boolean mTintChangePending;
    private float mPendingDarkIntensity;
    private ValueAnimator mTintAnimator;

    private int mDarkModeIconColorSingleTone;
    private int mLightModeIconColorSingleTone;

    private boolean mShowTicker = false;
    private boolean mTicking;

    private final Handler mHandler;
    private boolean mTransitionDeferring;
    private long mTransitionDeferringStartTime;
    private long mTransitionDeferringDuration;

    private final ArraySet<String> mIconBlacklist = new ArraySet<>();

    private BatteryLevelTextView mBatteryLevelView;

    private final Runnable mTransitionDeferringDoneRunnable = new Runnable() {
        @Override
        public void run() {
            mTransitionDeferring = false;
        }
    };

    public StatusBarIconController(Context context, View statusBar, View keyguardStatusBar,
            PhoneStatusBar phoneStatusBar) {
        super(context.getResources().getStringArray(
                com.android.internal.R.array.config_statusBarIcons));
        mContext = context;
        mStatusBar = statusBar;
        mPhoneStatusBar = phoneStatusBar;
        mSystemIconArea = (LinearLayout) statusBar.findViewById(R.id.system_icon_area);
        mStatusIcons = (LinearLayout) statusBar.findViewById(R.id.statusIcons);
        mSignalCluster = (SignalClusterView) statusBar.findViewById(R.id.signal_cluster);
        mStatusBarContents = (LinearLayout) statusBar.findViewById(R.id.status_bar_contents);

        mNotificationIconAreaController = SystemUIFactory.getInstance()
                .createNotificationIconAreaController(context, phoneStatusBar);
        mNotificationIconAreaInner =
                mNotificationIconAreaController.getNotificationInnerAreaView();

        ViewGroup notificationIconArea =
                (ViewGroup) statusBar.findViewById(R.id.notification_icon_area);
        notificationIconArea.addView(mNotificationIconAreaInner);

        mStatusIconsKeyguard = (LinearLayout) keyguardStatusBar.findViewById(R.id.statusIcons);

        mBatteryMeterView = (BatteryMeterView) statusBar.findViewById(R.id.battery);
        mBatteryMeterViewKeyguard = (BatteryMeterView) keyguardStatusBar.findViewById(R.id.battery);
        scaleBatteryMeterViews(context);

        mNetworkTraffic = (NetworkTraffic) statusBar.findViewById(R.id.networkTraffic);
        mWifiSsidLabel = (TextView) statusBar.findViewById(R.id.status_bar_wifi_label);
        mCarrierLabel = (TextView) statusBar.findViewById(R.id.statusbar_carrier_text);
        mDarkModeIconColorSingleTone = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeIconColorSingleTone = context.getColor(R.color.light_mode_icon_color_single_tone);
        mHandler = new Handler();
        mClockController = new ClockController(statusBar, mNotificationIconAreaController, mHandler);
        mCenterClockLayout = statusBar.findViewById(R.id.center_clock_layout);
        loadDimens();

        mBatteryLevelView = (BatteryLevelTextView) statusBar.findViewById(R.id.battery_level);

        TunerService.get(mContext).addTunable(this, ICON_BLACKLIST);
    }

    public void setSignalCluster(SignalClusterView signalCluster) {
        mSignalCluster = signalCluster;
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    private void scaleBatteryMeterViews(Context context) {
        Resources res = context.getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);
        // Set the start margin of the battery view instead of
        // the end padding of the signal cluster to prevent
        // excess padding when the battery view is hidden
        int marginStart = res.getDimensionPixelSize(R.dimen.signal_cluster_battery_padding);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));

        scaledLayoutParams.setMarginsRelative(marginStart, 0, 0, marginBottom);

        mBatteryMeterView.setLayoutParams(scaledLayoutParams);
        mBatteryMeterViewKeyguard.setLayoutParams(scaledLayoutParams);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!ICON_BLACKLIST.equals(key)) {
            return;
        }
        mIconBlacklist.clear();
        mIconBlacklist.addAll(getIconBlacklist(newValue));

        boolean showClock = !mIconBlacklist.remove("clock");
        mClockController.setVisibility(showClock);

        ArrayList<StatusBarIconView> views = new ArrayList<StatusBarIconView>();
        // Get all the current views.
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            views.add((StatusBarIconView) mStatusIcons.getChildAt(i));
        }
        // Remove all the icons.
        for (int i = views.size() - 1; i >= 0; i--) {
            removeIcon(views.get(i).getSlot());
        }
        // Add them all back
        for (int i = 0; i < views.size(); i++) {
            setIcon(views.get(i).getSlot(), views.get(i).getStatusBarIcon());
        }
    }
    private void loadDimens() {
        mIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size);
        mIconHPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_padding);
        mClockController.updateFontSize();
    }

    private void addSystemIcon(int index, StatusBarIcon icon) {
        String slot = getSlot(index);
        int viewIndex = getViewIndex(index);
        boolean blocked = mIconBlacklist.contains(slot);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null, blocked);
        view.set(icon);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
        lp.setMargins(mIconHPadding, 0, mIconHPadding, 0);
        mStatusIcons.addView(view, viewIndex, lp);

        view = new StatusBarIconView(mContext, slot, null, blocked);
        view.set(icon);
        mStatusIconsKeyguard.addView(view, viewIndex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize));
        applyIconTint();
    }

    public void setIcon(String slot, int resourceId, CharSequence contentDescription) {
        int index = getSlotIndex(slot);
        StatusBarIcon icon = getIcon(index);
        if (icon == null) {
            icon = new StatusBarIcon(UserHandle.SYSTEM, mContext.getPackageName(),
                    Icon.createWithResource(mContext, resourceId), 0, 0, contentDescription);
            setIcon(slot, icon);
        } else {
            icon.icon = Icon.createWithResource(mContext, resourceId);
            icon.contentDescription = contentDescription;
            handleSet(index, icon);
        }
    }

    public void setExternalIcon(String slot) {
        int viewIndex = getViewIndex(getSlotIndex(slot));
        int height = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_drawing_size);
        ImageView imageView = (ImageView) mStatusIcons.getChildAt(viewIndex);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        setHeightAndCenter(imageView, height);
        imageView = (ImageView) mStatusIconsKeyguard.getChildAt(viewIndex);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        setHeightAndCenter(imageView, height);
    }

    private void setHeightAndCenter(ImageView imageView, int height) {
        ViewGroup.LayoutParams params = imageView.getLayoutParams();
        params.height = height;
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).gravity = Gravity.CENTER_VERTICAL;
        }
        imageView.setLayoutParams(params);
    }

    public void setIcon(String slot, StatusBarIcon icon) {
        setIcon(getSlotIndex(slot), icon);
    }

    public void removeIcon(String slot) {
        int index = getSlotIndex(slot);
        removeIcon(index);
    }

    public void setIconVisibility(String slot, boolean visibility) {
        int index = getSlotIndex(slot);
        StatusBarIcon icon = getIcon(index);
        if (icon == null || icon.visible == visibility) {
            return;
        }
        icon.visible = visibility;
        handleSet(index, icon);
    }

    @Override
    public void removeIcon(int index) {
        if (getIcon(index) == null) {
            return;
        }
        super.removeIcon(index);
        int viewIndex = getViewIndex(index);
        mStatusIcons.removeViewAt(viewIndex);
        mStatusIconsKeyguard.removeViewAt(viewIndex);
    }

    @Override
    public void setIcon(int index, StatusBarIcon icon) {
        if (icon == null) {
            removeIcon(index);
            return;
        }
        boolean isNew = getIcon(index) == null;
        super.setIcon(index, icon);
        if (isNew) {
            addSystemIcon(index, icon);
        } else {
            handleSet(index, icon);
        }
    }

    private void handleSet(int index, StatusBarIcon icon) {
        int viewIndex = getViewIndex(index);
        StatusBarIconView view = (StatusBarIconView) mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
        view = (StatusBarIconView) mStatusIconsKeyguard.getChildAt(viewIndex);
        view.set(icon);
        applyIconTint();
    }

    public void updateNotificationIcons(NotificationData notificationData) {
        mNotificationIconAreaController.updateNotificationIcons(notificationData);
    }

    public void hideSystemIconArea(boolean animate) {
        animateHide(mSystemIconArea, animate);
        animateHide(mCenterClockLayout, animate);
    }

    public void showSystemIconArea(boolean animate) {
        animateShow(mSystemIconArea, animate);
        animateShow(mCenterClockLayout, animate);
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate);
        animateHide(mCenterClockLayout, animate);
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        animateShow(mCenterClockLayout, animate);
    }

    public void setClockVisibility(boolean visible) {
        mClockController.setVisibility(visible);
    }

    public void dump(PrintWriter pw) {
        int N = mStatusIcons.getChildCount();
        pw.println("  icon views: " + N);
        for (int i=0; i<N; i++) {
            StatusBarIconView ic = (StatusBarIconView) mStatusIcons.getChildAt(i);
            pw.println("    [" + i + "] icon=" + ic);
        }
        super.dump(pw);
    }

    public void dispatchDemoCommand(String command, Bundle args) {
        if (mDemoStatusIcons == null) {
            mDemoStatusIcons = new DemoStatusIcons(mStatusIcons, mIconSize);
        }
        mDemoStatusIcons.dispatchDemoCommand(command, args);
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(View.INVISIBLE);
            return;
        }
        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.INVISIBLE);
                    }
                });
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(50)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mPhoneStatusBar.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mPhoneStatusBar.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mPhoneStatusBar.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    /**
     * Sets the dark area so {@link #setIconsDark} only affects the icons in the specified area.
     *
     * @param darkArea the area in which icons should change it's tint, in logical screen
     *                 coordinates
     */
    public void setIconsDarkArea(Rect darkArea) {
        if (darkArea == null && mTintArea.isEmpty()) {
            return;
        }
        if (darkArea == null) {
            mTintArea.setEmpty();
        } else {
            mTintArea.set(darkArea);
        }
        applyIconTint();
        mNotificationIconAreaController.setTintArea(darkArea);
    }

    public void setIconsDark(boolean dark, boolean animate) {
        if (!animate) {
            setIconTintInternal(dark ? 1.0f : 0.0f);
        } else if (mTransitionPending) {
            deferIconTintChange(dark ? 1.0f : 0.0f);
        } else if (mTransitionDeferring) {
            animateIconTint(dark ? 1.0f : 0.0f,
                    Math.max(0, mTransitionDeferringStartTime - SystemClock.uptimeMillis()),
                    mTransitionDeferringDuration);
        } else {
            animateIconTint(dark ? 1.0f : 0.0f, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
    }

    private void animateIconTint(float targetDarkIntensity, long delay,
            long duration) {
        if (mTintAnimator != null) {
            mTintAnimator.cancel();
        }
        if (mDarkIntensity == targetDarkIntensity) {
            return;
        }
        mTintAnimator = ValueAnimator.ofFloat(mDarkIntensity, targetDarkIntensity);
        mTintAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setIconTintInternal((Float) animation.getAnimatedValue());
            }
        });
        mTintAnimator.setDuration(duration);
        mTintAnimator.setStartDelay(delay);
        mTintAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mTintAnimator.start();
    }

    private void setIconTintInternal(float darkIntensity) {
        mDarkIntensity = darkIntensity;
        mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightModeIconColorSingleTone, mDarkModeIconColorSingleTone);
        mNotificationIconAreaController.setIconTint(mIconTint);
        applyIconTint();
    }

    private void deferIconTintChange(float darkIntensity) {
        if (mTintChangePending && darkIntensity == mPendingDarkIntensity) {
            return;
        }
        mTintChangePending = true;
        mPendingDarkIntensity = darkIntensity;
    }

    /**
     * @return the tint to apply to {@param view} depending on the desired tint {@param color} and
     *         the screen {@param tintArea} in which to apply that tint
     */
    public static int getTint(Rect tintArea, View view, int color) {
        if (isInArea(tintArea, view)) {
            return color;
        } else {
            return DEFAULT_ICON_TINT;
        }
    }

    /**
     * @return the dark intensity to apply to {@param view} depending on the desired dark
     *         {@param intensity} and the screen {@param tintArea} in which to apply that intensity
     */
    public static float getDarkIntensity(Rect tintArea, View view, float intensity) {
        if (isInArea(tintArea, view)) {
            return intensity;
        } else {
            return 0f;
        }
    }

    /**
     * @return true if more than half of the {@param view} area are in {@param area}, false
     *         otherwise
     */
    private static boolean isInArea(Rect area, View view) {
        if (area.isEmpty()) {
            return true;
        }
        sTmpRect.set(area);
        view.getLocationOnScreen(sTmpInt2);
        int left = sTmpInt2[0];

        int intersectStart = Math.max(left, area.left);
        int intersectEnd = Math.min(left + view.getWidth(), area.right);
        int intersectAmount = Math.max(0, intersectEnd - intersectStart);

        boolean coversFullStatusBar = area.top <= 0;
        boolean majorityOfWidth = 2 * intersectAmount > view.getWidth();
        return majorityOfWidth && coversFullStatusBar;
    }

    private void applyIconTint() {
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mStatusIcons.getChildAt(i);
            v.setImageTintList(ColorStateList.valueOf(getTint(mTintArea, v, mIconTint)));
        }
        mSignalCluster.setIconTint(mIconTint, mDarkIntensity, mTintArea);
        mBatteryMeterView.setDarkIntensity(
                isInArea(mTintArea, mBatteryMeterView) ? mDarkIntensity : 0);
        mClockController.setTextColor(mTintArea, mIconTint);
        mBatteryLevelView.setTextColor(getTint(mTintArea, mBatteryLevelView, mIconTint));
	    mNetworkTraffic.setDarkIntensity(mDarkIntensity);
	    mWifiSsidLabel.setTextColor(getTint(mTintArea, mWifiSsidLabel, mIconTint));
        mCarrierLabel.setTextColor(getTint(mTintArea, mCarrierLabel, mIconTint));
        if (mTicker != null && mTickerView != null) {
            mTicker.setTextColor(mIconTint);
            mTicker.setIconColorTint(mIconTint);
        }
    }

    public void appTransitionPending() {
        mTransitionPending = true;
    }

    public void appTransitionCancelled() {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
        mTransitionPending = false;
    }

    public void appTransitionStarting(long startTime, long duration) {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity,
                    Math.max(0, startTime - SystemClock.uptimeMillis()),
                    duration);

        } else if (mTransitionPending) {

            // If we don't have a pending tint change yet, the change might come in the future until
            // startTime is reached.
            mTransitionDeferring = true;
            mTransitionDeferringStartTime = startTime;
            mTransitionDeferringDuration = duration;
            mHandler.removeCallbacks(mTransitionDeferringDoneRunnable);
            mHandler.postAtTime(mTransitionDeferringDoneRunnable, startTime);
        }
        mTransitionPending = false;
    }

    public static ArraySet<String> getIconBlacklist(String blackListStr) {
        ArraySet<String> ret = new ArraySet<String>();
        if (blackListStr == null) {
            blackListStr = "rotate,headset";
        }
        String[] blacklist = blackListStr.split(",");
        for (String slot : blacklist) {
            if (!TextUtils.isEmpty(slot)) {
                ret.add(slot);
            }
        }
        return ret;
    }

    public void onDensityOrFontScaleChanged() {
        loadDimens();
        mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
        updateClock();
        updateLabels();
        updateBatteryLevelText();
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            View child = mStatusIcons.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
            lp.setMargins(mIconHPadding, 0, mIconHPadding, 0);
            child.setLayoutParams(lp);
        }
        for (int i = 0; i < mStatusIconsKeyguard.getChildCount(); i++) {
            View child = mStatusIconsKeyguard.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
            child.setLayoutParams(lp);
        }
        scaleBatteryMeterViews(mContext);
    }

    private void updateClock() {
        mClockController.updateFontSize();
        mClockController.setPaddingRelative(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_starting_padding),
                0,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_end_padding),
                0);
    }

    private void updateLabels() {
        FontSizeUtils.updateFontSize(mWifiSsidLabel, R.dimen.status_bar_wifi_ssid_height);
        FontSizeUtils.updateFontSize(mCarrierLabel, R.dimen.status_bar_carrier_height);
    }

    private void updateBatteryLevelText() {
        FontSizeUtils.updateFontSize(mBatteryLevelView, R.dimen.battery_level_text_size);
    }

    public void updateShowTicker(boolean show) {
        mShowTicker = show;
        if (mShowTicker && (mTicker == null || mTickerView == null)) {
            inflateTickerView();
        }
    }

    private void inflateTickerView() {
        final ViewStub tickerStub = (ViewStub) mStatusBar.findViewById(R.id.ticker_stub);
        if (tickerStub != null) {
            mTickerView = tickerStub.inflate();
            mTicker = new MyTicker(mContext, mStatusBar);

            TickerView tickerView = (TickerView) mStatusBar.findViewById(R.id.tickerText);
            tickerView.mTicker = mTicker;
        } else {
            mShowTicker = false;
        }
    }

    public void addTickerEntry(StatusBarNotification n) {
        mTicker.addEntry(n);
    }

    public void removeTickerEntry(StatusBarNotification n) {
        mTicker.removeEntry(n);
    }

    public void haltTicker() {
        if (mTicking) {
            mTicker.halt();
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, View sb) {
            super(context, sb);
        }

        @Override
        public void tickerStarting() {
            if (!mShowTicker) return;
            mTicking = true;
            mStatusBarContents.setVisibility(View.GONE);
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out,
                    null));
            if (ClockController.mActiveClock == ClockController.mCenterClock) {
                mCenterClockLayout.setVisibility(View.GONE);
                mCenterClockLayout.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
            }
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
        }

        @Override
        public void tickerDone() {
            if (!mShowTicker) return;
            mStatusBarContents.setVisibility(View.VISIBLE);
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in,
                    null));
            mTickerView.setVisibility(View.GONE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
            if (ClockController.mActiveClock == ClockController.mCenterClock) {
                mCenterClockLayout.setVisibility(View.VISIBLE);
                mCenterClockLayout.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            }
        }

        public void tickerHalting() {
            if (!mShowTicker) return;
            if (mStatusBarContents.getVisibility() != View.VISIBLE) {
                mStatusBarContents.setVisibility(View.VISIBLE);
                mStatusBarContents
                        .startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
                if (ClockController.mActiveClock == ClockController.mCenterClock) {
                    mCenterClockLayout.setVisibility(View.VISIBLE);
                    mCenterClockLayout.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
                }
            }
            mTickerView.setVisibility(View.GONE);
            // we do not animate the ticker away at this point, just get rid of it (b/6992707)
        }
    }

    Animation.AnimationListener mTickingDoneListener = new Animation.AnimationListener() {;
        public void onAnimationEnd(Animation animation) {
            mTicking = false;
        }
        public void onAnimationRepeat(Animation animation) {
        }
        public void onAnimationStart(Animation animation) {
        }
    };

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(mContext, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }
}
