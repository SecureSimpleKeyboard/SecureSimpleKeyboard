package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

public class EmojiStripView extends View {
    public interface Listener { void onEmoji(String emoji); }

    private static final String[] EMOJIS = {    
        "🔥","💯","✨","🎉","❄️","⭐","🚀","🏷️",
        "😀","😁","😂","🙂","😉","😍","😎","😘",
        "🤔","🙄","😭","😢","😞","😡","🤡","🤦",
        "👍","👎","👏","🙌","🙏","🤝","💪","👋",
        "❤️","💔","✅","❌","🎁","🌹","☕","🍕","🐶","🐱","🐔","🐷"   
    };

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller mScroller;
    private final GestureDetector mDetector;
    private final float mCell;
    private float mOffset;
    private Listener mListener;

    public EmojiStripView(final Context context) {
        super(context);
        final float d = context.getResources().getDisplayMetrics().density;
        mCell = 44 * d;
        mPaint.setTextSize(24 * context.getResources().getDisplayMetrics().scaledDensity);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mScroller = new OverScroller(context);
        mDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                mScroller.forceFinished(true);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                mOffset += dx;
                invalidate();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                mScroller.fling((int) mOffset, 0, (int) -vx, 0,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0);
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                final int index = (int) Math.floor((mOffset + e.getX()) / mCell);
                if (mListener != null) {
                    mListener.onEmoji(EMOJIS[Math.floorMod(index, EMOJIS.length)]);
                }
                return true;
            }
        });
    }

    public void setListener(final Listener l) { mListener = l; }

    public int getStripHeightPx() { return (int) mCell; }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        return mDetector.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mOffset = mScroller.getCurrX();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int first = (int) Math.floor(mOffset / mCell);
        final int count = (int) Math.ceil(getWidth() / mCell) + 2;
        final float y = getHeight() / 2f - (mPaint.descent() + mPaint.ascent()) / 2f;
        for (int i = first; i < first + count; i++) {
            final float x = i * mCell - mOffset + mCell / 2f;
            canvas.drawText(EMOJIS[Math.floorMod(i, EMOJIS.length)], x, y, mPaint);
        }
    }
}
