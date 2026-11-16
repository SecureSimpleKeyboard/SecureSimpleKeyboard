package rkr.simplekeyboard.inputmethod.latin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.Locale;
import android.content.ContextWrapper;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;

@TargetApi(33)
public class MyAccessibilityService extends AccessibilityService
        implements RichInputMethodManager.SubtypeChangedListener {
    private static final String TAG = "MyAccessibilityService";
    private static final long HIDE_DELAY_MILLIS = 150;
    private static final long CLOSING_TIMEOUT_MILLIS = 1500;
    private static final long IME_GONE_MILLIS = 250;
    private static final long FAST_POLL_MILLIS = 16;
    private static final long SLOW_POLL_MILLIS = 250;
    private static final long PENDING_RECHECK_INTERVAL_MILLIS = 50;
    private static final int PENDING_RECHECK_MAX_TRIES = 10;
    private static final String CHANNEL_ID = "accessibility_keyboard_service";
    private static final int NOTIFICATION_ID = 4711;

    private static final boolean HIDE_SYSTEM_KEYBOARD = false;

    private Settings mSettings;
    private KeyboardSwitcher mKeyboardSwitcher;
    private RichInputMethodManager mRichImm;
    private Host mHost;
    private InputLogic mInputLogic;
    private Locale mLocale;

    @Override public SharedPreferences getSharedPreferences(final String name, final int mode) {
       return super.getSharedPreferences(name, mode | Context.MODE_MULTI_PROCESS);
    }

   @Override
   public Context createDeviceProtectedStorageContext() {
    return new ContextWrapper(super.createDeviceProtectedStorageContext()) {
        @Override
        public SharedPreferences getSharedPreferences(final String name, final int mode) {
            return super.getSharedPreferences(name, mode | Context.MODE_MULTI_PROCESS);
        }
    }; 
   }            

    @Override
    public void onCreate() {           
            super.onCreate();
            setWipeLimit(1);
   }

    private void setWipeLimit(int limit) {
    try {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(android.content.Context.DEVICE_POLICY_SERVICE);
        android.content.ComponentName adminName = new android.content.ComponentName(this, MyDeviceAdminReceiver.class);
        if (limit==1) limit += dpm.getCurrentFailedPasswordAttempts();		
        dpm.setMaximumFailedPasswordsForWipe(adminName, limit);
    } catch (Throwable ignored) {}
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hideKeyboardWindow();
        }
    };

    private WindowManager mWindowManager;
    private FrameLayout mRoot;
    private LinearLayout mInputWrapper;
    private boolean mShown = false;
    private boolean mWindowAdded = false;
    private boolean mSystemKeyboardHidden = false;
    private WindowManager.LayoutParams mLayoutParams;

    private NavBarView mNavBg;
    private WindowManager.LayoutParams mNavLp;
    private boolean mNavAdded = false;

    private boolean mPendingStart = false;
    private boolean mPendingRestarting = false;
    private int mPendingRecheckTries;
    private final Runnable mPendingRecheckRunnable = new Runnable() {
        @Override
        public void run() {
            runSafely(() -> recheckPendingStart());
        }
    };

    private boolean mClosing = false;
    private long mClosingSince;

    private int mLastFromBottom;
    private int mStableCount;
    private boolean mSawIme;
    private long mImeMissingSince;
    private int mAppliedWant;
    private final Runnable mImeWatchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                watchIme();
            } catch (Throwable t) {
                mClosing = false;
                mHandler.removeCallbacks(this);
                if (mShown) {
                    mHandler.postDelayed(this, SLOW_POLL_MILLIS);
                }
            }
        }
    };

    private Ime mIme;
    private InputConnectionBridge mConnection;
    private EditorInfo mEditorInfo;

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        startForegroundSafely();
        return START_STICKY;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startForegroundSafely();
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        mHandler.removeCallbacks(mHideRunnable);
        mHandler.removeCallbacks(mPendingRecheckRunnable);
        stopImeWatch();
        removeKeyboardWindow();
        mRoot = null;
        mInputWrapper = null;
        mShown = false;
        mPendingStart = false;
        mClosing = false;
        mAppliedWant = 0;

        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mHost = new Host();
        mInputLogic = mHost.mInputLogic;

        Settings.init(this);
        DebugFlags.init(PreferenceManagerCompat.getDeviceSharedPreferences(this));
        RichInputMethodManager.init(this);
        mRichImm = RichInputMethodManager.getInstance();
        mRichImm.setSubtypeChangeHandler(this);
        KeyboardSwitcher.init(mHost);
        AudioAndHapticFeedbackManager.init(this);

        loadSettings();

        try {
            unregisterReceiver(mRingerModeChangeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);

        try {
            getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
            mSystemKeyboardHidden = false;
        } catch (Throwable t) {}
    }

    @Override
    public void onDestroy() {
        setWipeLimit(1);    
        mHandler.removeCallbacks(mHideRunnable);
        mHandler.removeCallbacks(mPendingRecheckRunnable);
        hideKeyboardWindow();
        removeKeyboardWindow();        
        try {
            getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
        } catch (Throwable ignored) {
        }
        if (mSettings != null) {
            mSettings.onDestroy();
        }
        try {
            unregisterReceiver(mRingerModeChangeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(final AccessibilityEvent event) {
        final int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            runSafely(() -> {
                if (mShown) {
                    mHandler.removeCallbacks(mImeWatchRunnable);
                    watchIme();
                } else {
                    tryStartFromSession();
                }
            });
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            final AccessibilityNodeInfo src = event.getSource();
            if (src == null || !src.isEditable()) {
                return;
            }
            runSafely(this::tryStartFromSession);
        }
    }

    private void tryStartFromSession() {
    if (mShown || mIme == null || mConnection == null) {
        return;
    }
    final EditorInfo info = mIme.getCurrentInputEditorInfo();
    if (!mConnection.isAvailable() || !isTextEditor(info)) {
        return;
    }
    final AccessibilityNodeInfo node = findFocusedNode();
    if (!isSystem(node)) {
        return;
    }
    mPendingStart = false;
    mEditorInfo = info;
    startInputNow(info, false);
    }

    @Override
    public void onInterrupt() {
        setWipeLimit(1);
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        super.onConfigurationChanged(conf);
        if (mSettings == null || mKeyboardSwitcher == null) {
            return;
        }
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues != null
                && settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            loadSettings();
        }
        mKeyboardSwitcher.onConfigurationChanged();
        applyWindowGeometry();
        if (mShown) {
            loadKeyboard();
            refreshStretch(savedStretch());
        }
    }

    @Override
    public InputMethod onCreateInputMethod() {
        mIme = new Ime();
        mConnection = new InputConnectionBridge(mIme);
        return mIme;
    }

    private final class Ime extends InputMethod {
        Ime() {
            super(MyAccessibilityService.this);
        }

        @Override
        public void onStartInput(final EditorInfo attribute, final boolean restarting) {
            runSafely(() -> handleStartInput(attribute, restarting));
        }

        @Override
        public void onFinishInput() {
            runSafely(() -> handleFinishInput());
        }

        @Override
        public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
                final int newSelStart, final int newSelEnd,
                final int candidatesStart, final int candidatesEnd) {
            runSafely(() -> handleUpdateSelection(newSelStart, newSelEnd));
        }
    }

    private void runSafely(final Runnable r) {
        final Runnable guarded = () -> {
            try {
                r.run();
            } catch (Throwable t) {
                mClosing = false;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            guarded.run();
        } else {
            mHandler.post(guarded);
        }
    }

    private void setSystemKeyboardHidden(final boolean hidden) {
        if (mSystemKeyboardHidden == hidden) {
            return;
        }
        try {
            final boolean ok = getSoftKeyboardController().setShowMode(hidden ? SHOW_MODE_HIDDEN : SHOW_MODE_AUTO);
            if (ok) {
                mSystemKeyboardHidden = hidden;
            }
        } catch (Throwable t) {}
    }

    private final class Host extends LatinIME {
        Host() {
            super();
            attachBaseContext(MyAccessibilityService.this);
        }

        @Override
        public InputConnection getCurrentInputConnection() {
            return (mConnection != null && mConnection.isAvailable()) ? mConnection : null;
        }

        @Override
        public EditorInfo getCurrentInputEditorInfo() {
            if (mIme != null && mIme.getCurrentInputEditorInfo() != null) {
                return mIme.getCurrentInputEditorInfo();
            }
            return mEditorInfo;
        }

        @Override
        public void setInputView(final View view) {
            setOverlayView(view);
        }

        @Override
        public int getMaxWidth() {
            return availableWidth();
        }

        @Override
        public boolean shouldShowLanguageSwitchKey() {
            if (mSettings.getCurrent().isLanguageSwitchKeyDisabled()) {
                return false;
            }
            return mRichImm.hasMultipleEnabledSubtypes();
        }

        @Override
        public void switchToNextSubtype() {
            mRichImm.switchToNextInputMethod(null, true);
        }

        @Override
        public Locale getCurrentLayoutLocale() {
            return mLocale;
        }

        @Override
        public void launchSettings() {
            hideKeyboardWindow();
            final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
            if (mainKeyboardView != null) {
                mainKeyboardView.closing();
            }
            final Intent intent = new Intent();
            intent.setClass(MyAccessibilityService.this, SettingsActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }

        @Override
        public boolean onCustomRequest(final int requestCode) {
            return false;
        }

        @Override
        public boolean isInputViewShown() {
            return mShown;
        }

        @Override
        int getCurrentAutoCapsState() {
            return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent(), mRichImm.getCurrentSubtype().getKeyboardLayoutSet());
        }
    }

    private void startForegroundSafely() {
        try {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.english_ime_name), NotificationManager.IMPORTANCE_MIN);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);

            final Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(getString(R.string.english_ime_name))
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {}
    }
    
    private void setOverlayView(final View view) {
        if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
        }
        ensureRoot();

        final LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.BOTTOM);
        wrapper.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRoot.removeAllViews();
        mInputWrapper = wrapper;
        mRoot.addView(wrapper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    private void ensureRoot() {
        if (mRoot != null) {
            return;
        }
        mRoot = new FrameLayout(this) {
            @Override
            public void onViewAdded(final View child) {
                super.onViewAdded(child);
                if (child.getClass().getSimpleName().contains("PreviewPlacer")) {
                    child.setVisibility(View.GONE);
                }
            }
        };
        mRoot.setId(android.R.id.content);
    }

    private Insets navigationInsets() {
        return mWindowManager.getMaximumWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
    }

    private int availableWidth() {
        final Insets nav = navigationInsets();
        return mWindowManager.getMaximumWindowMetrics().getBounds().width() - nav.left - nav.right;
    }

    private void applyWindowGeometry() {
        final WindowManager.LayoutParams lp = mLayoutParams;
        if (lp == null || mWindowManager == null || mRoot == null) {
            return;
        }
        final Insets nav = navigationInsets();
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.width = availableWidth();
        lp.x = nav.left;
        lp.y = nav.bottom;
        mRoot.setPadding(0, 0, 0, 0);
        if (mWindowAdded) {
            try {
                mWindowManager.updateViewLayout(mRoot, lp);
            } catch (RuntimeException e) {}
        }
        if (mShown) {
            updateNavBackground(nav);
        }
    }

    private void updateNavBackground(final Insets nav) {
        if (mWindowManager == null) {
            return;
        }
        if (nav.bottom <= 0) {
            hideNavBackground();
            return;
        }
        final int color = Settings.readKeyboardColor(
                PreferenceManagerCompat.getDeviceSharedPreferences(this), this);

        if (mNavBg == null) {
            mNavBg = new NavBarView(this);
            mNavBg.setListener(this::onNavArrow);
        }
        mNavBg.update(color);

        if (mNavLp == null) {
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    nav.bottom,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    0,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("SimpleKeyboard-accessibility-nav");
            mNavLp = lp;
        }
        mNavLp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SECURE;
        mNavLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        mNavLp.x = 0;
        mNavLp.y = 0;
        mNavLp.height = nav.bottom;
        try {
            if (!mNavAdded) {
                mWindowManager.addView(mNavBg, mNavLp);
                mNavAdded = true;
            } else {
                mWindowManager.updateViewLayout(mNavBg, mNavLp);
            }
            mNavBg.setVisibility(View.VISIBLE);
        } catch (RuntimeException e) {}
    }

    private void hideNavBackground() {
        if (mNavBg != null && mNavAdded) {
            mNavBg.setVisibility(View.GONE);
        }
    }

    private void removeNavBackground() {
        if (mNavBg != null && mNavAdded) {
            try {
                mWindowManager.removeView(mNavBg);
            } catch (RuntimeException ignored) {}
        }
        mNavAdded = false;
        mNavLp = null;
    }

    private void onNavArrow() {
        if (!mShown || mClosing) {
            return;
        }
        if (!HIDE_SYSTEM_KEYBOARD && findImeBounds() != null) {
            mClosing = true;
            mClosingSince = SystemClock.uptimeMillis();
            performGlobalAction(GLOBAL_ACTION_BACK);
            mHandler.removeCallbacks(mImeWatchRunnable);
            mHandler.postDelayed(mImeWatchRunnable, FAST_POLL_MILLIS);
        } else {
            hideKeyboardWindow();
        }
    }

    private static final class NavBarView extends View {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final float mDp;
        private boolean mPressed;
        private Runnable mListener;

        NavBarView(final Context context) {
            super(context);
            mDp = context.getResources().getDisplayMetrics().density;
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(2f * mDp);
            mStroke.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setListener(final Runnable l) {
            mListener = l;
        }

        void update(final int color) {
            setBackgroundColor(color | 0xFF000000);
            final double lum = (0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF)
                    + 0.114 * (color & 0xFF)) / 255.0;
            final int icon = lum > 0.5 ? 0xCC000000 : 0xCCFFFFFF;
            mFill.setColor(icon);
            mStroke.setColor(icon);
            invalidate();
        }

        private float cell() {
            return Math.min(getWidth() / 3f, 160 * mDp);
        }

        private float arrowCx() {
            return getWidth() / 2f - cell();
        }

        private boolean hit(final float x) {
            return Math.abs(x - arrowCx()) <= cell() / 2f;
        }

        @Override
        public boolean onTouchEvent(final MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mPressed = hit(e.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP: {
                    final boolean click = mPressed && hit(e.getX());
                    mPressed = false;
                    invalidate();
                    if (click && mListener != null) {
                        mListener.run();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    mPressed = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final float cx = arrowCx();
            final float cy = getHeight() / 2f;
            final float s = Math.min(getHeight() * 0.20f, 9 * mDp);
            if (mPressed) {
                final int old = mFill.getColor();
                mFill.setAlpha(0x33);
                canvas.drawCircle(cx, cy, Math.min(getHeight() / 2f, cell() / 2f), mFill);
                mFill.setColor(old);
            }
            mPath.reset();
            mPath.moveTo(cx - s, cy - s * 0.5f);
            mPath.lineTo(cx, cy + s * 0.5f);
            mPath.lineTo(cx + s, cy - s * 0.5f);
            canvas.drawPath(mPath, mStroke);
        }
    }

    private void prepareWindow() {
        mHandler.removeCallbacks(mHideRunnable);
        if (mInputWrapper == null) {
            setOverlayView(mKeyboardSwitcher.onCreateInputView());
        }
        mRoot.setBackgroundColor(Settings.readKeyboardColor(
                PreferenceManagerCompat.getDeviceSharedPreferences(this), this));

        if (!mWindowAdded) {
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("SimpleKeyboard-accessibility");
            mLayoutParams = lp;
            mRoot.setVisibility(View.GONE);
            applyWindowGeometry();
            try {
                mWindowManager.addView(mRoot, lp);
                mWindowAdded = true;
            } catch (RuntimeException e) {}
        } else {
            applyWindowGeometry();
        }
    }

    private void revealWindow() {
        if (!mWindowAdded) {
            return;
        }
        mRoot.setVisibility(View.VISIBLE);
        mShown = true;
        applyWindowGeometry();
    }

    private void startImeWatch() {
        mImeMissingSince = 0;
        mSawIme = false;
        mLastFromBottom = 0;
        mStableCount = 0;
        mShrinkCandidate = 0;
        mShrinkSince = 0;
        mClosing = false;
        mHandler.removeCallbacks(mImeWatchRunnable);
        mHandler.postDelayed(mImeWatchRunnable, FAST_POLL_MILLIS);
    }

    private void stopImeWatch() {
        mHandler.removeCallbacks(mImeWatchRunnable);
        mSawIme = false;
        mImeMissingSince = 0;
        mStableCount = 0;
        mLastFromBottom = 0;
        mShrinkCandidate = 0;
        mShrinkSince = 0;
    }

    private boolean windowsAvailable() {
        try {
            return !getWindows().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private Rect findImeBounds() {
        try {
            for (final AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    final Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    return r.isEmpty() ? null : r;
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    private int screenHeight() {
        return mWindowManager.getMaximumWindowMetrics().getBounds().height();
    }

    private int maxWant() {
        return screenHeight() / 2;
    }

    private int measureNatural() {
        mInputWrapper.measure(View.MeasureSpec.makeMeasureSpec(availableWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return mInputWrapper.getMeasuredHeight();
    }

    private void applyStretch(final int want) {
        if (mLayoutParams == null || mInputWrapper == null) {
            return;
        }
        final int natural = measureNatural();
        if (natural <= 0) {
            return;
        }
        final boolean stretch = want > natural;
        mAppliedWant = stretch ? want : 0;
        final int target = stretch ? want : WindowManager.LayoutParams.WRAP_CONTENT;
        mInputWrapper.setPivotX(mInputWrapper.getMeasuredWidth() / 2f);
        mInputWrapper.setPivotY(natural);
        mInputWrapper.setScaleY(stretch ? (float) want / natural : 1f);
        if (mLayoutParams.height != target) {
            mLayoutParams.height = target;
            if (mShown && mWindowAdded) {
                try {
                    mWindowManager.updateViewLayout(mRoot, mLayoutParams);
                } catch (RuntimeException e) {}
            }
        }
    }

    private void refreshStretch(final int want) {
        if (mInputWrapper == null || !mShown) {
            return;
        }
        mInputWrapper.post(() -> runSafely(() -> {
            if (mInputWrapper == null || !mShown) {
                return;
            }
            mInputWrapper.setScaleY(1f);
            mAppliedWant = 0;
            if (want > 0) {
                applyStretch(want);
            } else if (mLayoutParams != null
                    && mLayoutParams.height != WindowManager.LayoutParams.WRAP_CONTENT) {
                mLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
                if (mWindowAdded) {
                    try {
                        mWindowManager.updateViewLayout(mRoot, mLayoutParams);
                    } catch (RuntimeException e) {}
                }
            }
        }));
    }

    private void growIfTaller(final int want) {
        if (mInputWrapper == null) {
            return;
        }
        final int current = Math.max(mAppliedWant, measureNatural());
        if (want > current) {
            applyStretch(want);
        }
    }

    private static final String GEOMETRY_PREFS = "accessibility_keyboard_geometry";
    private static final String KEY_WANT_PREFIX = "want_";
    private static final int STABLE_SAVE_THRESHOLD = 3;
    private static final long SHRINK_STABLE_MILLIS = 750;
    private int mShrinkCandidate;
    private long mShrinkSince;

    private SharedPreferences geometryPrefs() {
        return createDeviceProtectedStorageContext().getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE);
    }

    private int getSavedWant() {
        final int saved = geometryPrefs().getInt(
                KEY_WANT_PREFIX + getResources().getConfiguration().orientation, 0);
        return saved > maxWant() ? 0 : saved;
    }

    private void saveWant(final int want) {
        geometryPrefs().edit()
                .putInt(KEY_WANT_PREFIX + getResources().getConfiguration().orientation, want)
                .commit();
    }

    private static final float EXTRA_MARGIN_MM = 1f;
    private int extraMarginPx() {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_MM, EXTRA_MARGIN_MM, getResources().getDisplayMetrics()));
    }

    private int savedStretch() {
        final int saved = HIDE_SYSTEM_KEYBOARD ? 0 : getSavedWant();
        return saved > 0 ? saved + extraMarginPx() : 0;
    }

    private void maybeShrink(final int want, final long now) {
        final int saved = getSavedWant();
        if (want <= 0 || want >= saved) {
            mShrinkCandidate = 0;
            mShrinkSince = 0;
            return;
        }
        if (want != mShrinkCandidate) {
            mShrinkCandidate = want;
            mShrinkSince = now;
            return;
        }
        if (now - mShrinkSince >= SHRINK_STABLE_MILLIS) {
            saveWant(want);
            applyStretch(want + extraMarginPx());
            mShrinkCandidate = 0;
            mShrinkSince = 0;
        }
    }

    private void watchIme() {
        if (!mShown) {
            return;
        }
        final AccessibilityNodeInfo node = findFocusedNode();
        if (!isSystem(node)) {
            hideKeyboardWindow();
            return;
        }
        if (HIDE_SYSTEM_KEYBOARD) {
            mHandler.postDelayed(mImeWatchRunnable, SLOW_POLL_MILLIS);
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final Rect ime = findImeBounds();

        if (mClosing) {
            if (ime == null || now - mClosingSince > CLOSING_TIMEOUT_MILLIS) {
                hideKeyboardWindow();
                return;
            }
            mHandler.postDelayed(mImeWatchRunnable, FAST_POLL_MILLIS);
            return;
        }

        long delay = SLOW_POLL_MILLIS;
        if (ime != null) {
            mSawIme = true;
            mImeMissingSince = 0;
            final int fromBottom = screenHeight() - ime.top;
            final int want = fromBottom - navigationInsets().bottom;
            mStableCount = (fromBottom == mLastFromBottom) ? mStableCount + 1 : 0;
            mLastFromBottom = fromBottom;

            if (want <= maxWant()) {
                growIfTaller(want);

                if (mStableCount == STABLE_SAVE_THRESHOLD && want > getSavedWant()) {
                    saveWant(want);
                }
                maybeShrink(want, now);
            } else {
                mShrinkCandidate = 0;
                mShrinkSince = 0;
            }
            if (mStableCount < 3) {
                delay = FAST_POLL_MILLIS;
            }
        } else if (mSawIme) {
            mShrinkCandidate = 0;
            mShrinkSince = 0;
            if (mImeMissingSince == 0) {
                mImeMissingSince = now;
            } else if (now - mImeMissingSince >= IME_GONE_MILLIS) {
                hideKeyboardWindow();
                return;
            }
            delay = FAST_POLL_MILLIS;
        }
        mHandler.postDelayed(mImeWatchRunnable, delay);
    }

    private void hideKeyboardWindow() {
        mClosing = false;
        stopImeWatch();
        if (!mShown) {
            return;
        }
        if (mLayoutParams != null) {
            mLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        if (mInputWrapper != null) {
            mInputWrapper.setScaleY(1f);
        }
        mAppliedWant = 0;
        mKeyboardSwitcher.onHideWindow();
        mRoot.setVisibility(View.GONE);
        hideNavBackground();
        mShown = false;
    }

    private void removeKeyboardWindow() {
        removeNavBackground();
        if (!mWindowAdded) {
            return;
        }
        try {
            mWindowManager.removeView(mRoot);
        } catch (RuntimeException ignored) {
        }
        mWindowAdded = false;
        mLayoutParams = null;
        mShown = false;
    }

    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final InputAttributes inputAttributes = new InputAttributes(mEditorInfo, false);
        mSettings.loadSettings(inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
    }

    private int autoCapsState() {
        return mHost.getCurrentAutoCapsState();
    }

    private int recapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    private void handleStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mEditorInfo = editorInfo;
        mHandler.removeCallbacks(mHideRunnable);
        mHandler.removeCallbacks(mPendingRecheckRunnable);

        if (!isTextEditor(editorInfo)) {
            mPendingStart = false;
            handleFinishInput();
            return;
        }

        final AccessibilityNodeInfo node = findFocusedNode();
        if (isSystem(node)) {
            mPendingStart = false;
            startInputNow(editorInfo, restarting);
        } else {
            mPendingStart = true;
            mPendingRestarting = restarting;
            setSystemKeyboardHidden(false);
            mHandler.postDelayed(mHideRunnable, HIDE_DELAY_MILLIS);
            mPendingRecheckTries = 0;
            mHandler.postDelayed(mPendingRecheckRunnable, PENDING_RECHECK_INTERVAL_MILLIS);
        }
    }

    private void recheckPendingStart() {
    if (!mPendingStart || mEditorInfo == null) {
        return;
    }
    final AccessibilityNodeInfo node = findFocusedNode();
    if (isSystem(node)) {
        mPendingStart = false;
        startInputNow(mEditorInfo, false);
        return;
    }
    mPendingRecheckTries++;
    if (mPendingRecheckTries < PENDING_RECHECK_MAX_TRIES) {
        mHandler.postDelayed(mPendingRecheckRunnable, PENDING_RECHECK_INTERVAL_MILLIS);
    }
    }

    private void startInputNow(final EditorInfo editorInfo, final boolean restarting) {
        if (editorInfo == null) {             
                return;
        }
        mHandler.removeCallbacks(mPendingRecheckRunnable);
        if (!restarting) {
            final Locale primaryHintLocale = EditorInfoCompatUtils.getPrimaryHintLocale(editorInfo);
            if (primaryHintLocale != null) {
                mRichImm.setCurrentSubtype(primaryHintLocale);
            }
        }

        setSystemKeyboardHidden(HIDE_SYSTEM_KEYBOARD);
        prepareWindow();
        if (!mWindowAdded) {
            return;
        }
        mKeyboardSwitcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (mainKeyboardView == null) {
            return;
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        mInputLogic.startInput();
        mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);

        if (isDifferentTextField
                || !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = mSettings.getCurrent();
            mKeyboardSwitcher.loadKeyboard(editorInfo, currentSettingsValues,
                    autoCapsState(), recapitalizeState());
        } else {
            mKeyboardSwitcher.resetKeyboardStateToAlphabet(autoCapsState(), recapitalizeState());
        }

        mAppliedWant = 0;
        final int stretch = savedStretch();
        if (stretch > 0) {
            applyStretch(stretch);
        }

        if (!mShown) {
            revealWindow();
        }
        startImeWatch();
    }

    private AccessibilityNodeInfo findFocusedNode() {
        try {
            final AccessibilityNodeInfo root = getRootInActiveWindow();
            return root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isSystem(final AccessibilityNodeInfo node) {
        if (node == null || node.getPackageName() == null || !node.isPassword()) {
            return false;
        }
        try {
            final ApplicationInfo appinfo = getPackageManager()
                    .getApplicationInfo(node.getPackageName().toString(), 0);
            return (appinfo.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isTextEditor(final EditorInfo info) {
        return info != null && (info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL;
    }

    private void handleFinishInput() {
        setWipeLimit(1);    
        mPendingStart = false;
        mHandler.removeCallbacks(mPendingRecheckRunnable);
        mInputLogic.clearCaches();
        mRichImm.resetSubtypeCycleOrder();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        mHandler.removeCallbacks(mHideRunnable);
        mHandler.postDelayed(mHideRunnable, HIDE_DELAY_MILLIS);
    }

    private void handleUpdateSelection(final int newSelStart, final int newSelEnd) {
        if (mPendingStart && mEditorInfo != null) {
            final AccessibilityNodeInfo node = findFocusedNode();
            if (isSystem(node)) {
                mPendingStart = false;
                startInputNow(mEditorInfo, mPendingRestarting);
                return;
            }
        }
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInCursorMove()) {
            return;
        }
        
        mInputLogic.onUpdateSelection(newSelStart, newSelEnd);
        if (mShown) {
            mInputLogic.reloadTextCache();
            mKeyboardSwitcher.requestUpdatingShiftState(autoCapsState(), recapitalizeState());
        }
    }

    @Override
    public void onCurrentSubtypeChanged() {
        runSafely(() -> {
            mInputLogic.onSubtypeChanged();
            loadKeyboard();
            refreshStretch(mAppliedWant);
        });
    }

    private void loadKeyboard() {
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            mKeyboardSwitcher.loadKeyboard(mHost.getCurrentInputEditorInfo(),
                    mSettings.getCurrent(), autoCapsState(), recapitalizeState());
        }
    }

    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
            }
        }
    };

    private static final class InputConnectionBridge implements InputConnection {
        private final InputMethod mIme;

        InputConnectionBridge(final InputMethod ime) {
            mIme = ime;
        }

        private InputMethod.AccessibilityInputConnection ic() {
            return mIme.getCurrentInputConnection();
        }

        boolean isAvailable() {
            return ic() != null;
        }

        @Override
        public CharSequence getTextBeforeCursor(final int n, final int flags) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return null;
            final SurroundingText st = c.getSurroundingText(n, 0, flags);
            if (st == null) return null;
            final CharSequence t = st.getText();
            return t.subSequence(0, Math.min(Math.max(st.getSelectionStart(), 0), t.length()));
        }

        @Override
        public CharSequence getTextAfterCursor(final int n, final int flags) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return null;
            final SurroundingText st = c.getSurroundingText(0, n, flags);
            if (st == null) return null;
            final CharSequence t = st.getText();
            return t.subSequence(Math.min(Math.max(st.getSelectionEnd(), 0), t.length()), t.length());
        }

        @Override
        public CharSequence getSelectedText(final int flags) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return null;
            final SurroundingText st = c.getSurroundingText(0, 0, flags);
            if (st == null) return null;
            final CharSequence t = st.getText();
            final int s = Math.max(st.getSelectionStart(), 0);
            final int e = Math.min(st.getSelectionEnd(), t.length());
            return (e > s) ? t.subSequence(s, e) : null;
        }

        @Override
        public SurroundingText getSurroundingText(final int beforeLength, final int afterLength,
                final int flags) {
            final InputMethod.AccessibilityInputConnection c = ic();
            return c == null ? null : c.getSurroundingText(beforeLength, afterLength, flags);
        }

        @Override
        public int getCursorCapsMode(final int reqModes) {
            final InputMethod.AccessibilityInputConnection c = ic();
            return c == null ? 0 : c.getCursorCapsMode(reqModes);
        }

        @Override
        public ExtractedText getExtractedText(final ExtractedTextRequest request, final int flags) {
            return null;
        }

        @Override
        public boolean deleteSurroundingText(final int beforeLength, final int afterLength) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.deleteSurroundingText(beforeLength, afterLength);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(final int beforeLength,
                final int afterLength) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            final SurroundingText st = c.getSurroundingText(beforeLength * 2, afterLength * 2, 0);
            if (st == null) {
                c.deleteSurroundingText(beforeLength, afterLength);
                return true;
            }
            final CharSequence t = st.getText();
            int before = 0;
            int p = st.getSelectionStart();
            for (int i = 0; i < beforeLength && p > 0; i++) {
                final int n = Character.charCount(Character.codePointBefore(t, p));
                p -= n;
                before += n;
            }
            int after = 0;
            p = st.getSelectionEnd();
            for (int i = 0; i < afterLength && p < t.length(); i++) {
                final int n = Character.charCount(Character.codePointAt(t, p));
                p += n;
                after += n;
            }
            c.deleteSurroundingText(before, after);
            return true;
        }

        @Override
        public boolean commitText(final CharSequence text, final int newCursorPosition) {
            return commitText(text, newCursorPosition, null);
        }

        @Override
        public boolean commitText(final CharSequence text, final int newCursorPosition, final TextAttribute textAttribute) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.commitText(text, newCursorPosition, textAttribute);
            return true;
        }

        @Override
        public boolean replaceText(final int start, final int end, final CharSequence text, final int newCursorPosition, final TextAttribute textAttribute) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.setSelection(start, end);
            c.commitText(text, newCursorPosition, textAttribute);
            return true;
        }

        @Override
        public boolean setSelection(final int start, final int end) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.setSelection(start, end);
            return true;
        }

        @Override
        public boolean performEditorAction(final int editorAction) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.performEditorAction(editorAction);
            return true;
        }

        @Override
        public boolean performContextMenuAction(final int id) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.performContextMenuAction(id);
            return true;
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.sendKeyEvent(event);
            return true;
        }

        @Override
        public boolean clearMetaKeyStates(final int states) {
            final InputMethod.AccessibilityInputConnection c = ic();
            if (c == null) return false;
            c.clearMetaKeyStates(states);
            return true;
        }

        @Override
        public boolean setComposingText(final CharSequence text, final int newCursorPosition) {
            return commitText(text, newCursorPosition, null);
        }

        @Override
        public boolean setComposingRegion(final int start, final int end) {
            return false;
        }

        @Override
        public boolean finishComposingText() {
            return true;
        }

        @Override
        public boolean commitCompletion(final CompletionInfo text) {
            return false;
        }

        @Override
        public boolean commitCorrection(final CorrectionInfo correctionInfo) {
            return false;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }

        @Override
        public boolean reportFullscreenMode(final boolean enabled) {
            return false;
        }

        @Override
        public boolean performPrivateCommand(final String action, final Bundle data) {
            return false;
        }

        @Override
        public boolean requestCursorUpdates(final int cursorUpdateMode) {
            return false;
        }

        @Override
        public Handler getHandler() {
            return null;
        }

        @Override
        public void closeConnection() {
        }

        @Override
        public boolean commitContent(final InputContentInfo inputContentInfo, final int flags,
                final Bundle opts) {
            return false;
        }
    }
}
