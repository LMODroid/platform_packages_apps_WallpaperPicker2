/*
 * Copyright (C) 2023 droid-ng
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

package com.android.wallpaper.widget;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Highlight dialog that highlights a View or position on screen, similar to Chromium UI element
 *
 * <br/>
 * Note that the content View you pass in can NOT have any padding set. If that is desired, please
 * use an FrameLayout to contain your View and pass the FrameLayout in. This is to allow
 * customization of background color of your root view which applies to the arrow too (the content
 * is shifted out using padding).
 *
 * <br/>
 *  Example usage:<code>
 * 		FrameLayout v = new FrameLayout(this);
 * 		TextView c = new TextView(this);
 * 		c.setText("Hello World");
 * 		v.addView(c);
 * 		v.setBackgroundColor(Color.BLUE);
 * 		HighlightDialog d = new HighlightDialog(this,
 * 				Set.of(new Pair<>(findViewById(R.id.textView), v)),
 * 				100, 100, 100, 100,
 * 				37.5f, 37.5f, 5f, 50, 25);
 * 		d.show();
 *  </code><br/>
 * 	More complex example:<code>
 * 	protected void onCreate(Bundle savedInstanceState) {
 * 		super.onCreate(savedInstanceState);
 * 		setContentView(R.layout.activity_main);
 * 	    // wait till views rendered once, then display dialog
 * 		HighlightDialog.showOnceReady(findViewById(R.id.tv), () -> {
 * 			HighlightDialog d = new HighlightDialog(this, Set.of(
 * 					createViewContent(R.id.tv, Color.BLUE),
 * 					createViewContent(R.id.textView, Color.GRAY)
 * 			), 100, 100, 100, 100, // invisible border in activity of 100px to avoid dialog in edge
 * 					37.5f, 37.5f, 5f, 50, 25); // 37.5f corner radius, 5f elevation, 50x25px arrow
 * 			d.show();
 *        });
 * 	 }
 * 	private Pair<View, View> createViewContent(int id, int cc) {
 * 		FrameLayout v = new FrameLayout(this);
 * 		LinearLayout l = new LinearLayout(this);
 * 		TextView c = new TextView(this);
 * 		c.setText("Hello World");
 * 		c.setTextColor(Color.WHITE);
 * 		l.addView(c);
 * 		l.setPadding(20, 20, 20, 0);
 * 		v.addView(l);
 * 		v.setBackgroundColor(cc);
 * 		return new Pair<>(findViewById(id), v);
 * 	}
 *  </code><br/>
 */
public class HighlightDialog extends Dialog {
	private final List<Pair<Rect, View>> mScreenRect;
	private final int mMinBorderLeft, mMinBorderTop, mMinBorderRight, mMinBorderBottom;
	private final float mCornerRadiusX, mCornerRadiusY, mElevation;
	private final int mArrowWidth, mArrowHeight;

	public HighlightDialog(Context context, Set<Pair<View, View>> screenRect,
	                       int minBorderLeft, int minBorderTop, int minBorderRight,
	                       int minBorderBottom, float cornerRadiusX, float cornerRadiusY,
	                       float elevation, int arrowWidth, int arrowHeight) {
		this(context, getLocationsOnScreen(screenRect), minBorderLeft, minBorderTop,
				minBorderRight, minBorderBottom, cornerRadiusX, cornerRadiusY, elevation,
				arrowWidth, arrowHeight);
	}

	public HighlightDialog(Context context, int themeResId,
	                       Set<Pair<View, View>> screenRect, int minBorderLeft, int minBorderTop,
	                       int minBorderRight, int minBorderBottom, float cornerRadiusX,
	                       float cornerRadiusY, float elevation, int arrowWidth,
	                       int arrowHeight) {
		this(context, themeResId, getLocationsOnScreen(screenRect), minBorderLeft, minBorderTop,
				minBorderRight, minBorderBottom, cornerRadiusX, cornerRadiusY, elevation,
				arrowWidth, arrowHeight);
	}

	public HighlightDialog(Context context, List<Pair<Rect, View>> screenRect,
	                       int minBorderLeft, int minBorderTop, int minBorderRight,
	                       int minBorderBottom, float cornerRadiusX, float cornerRadiusY,
	                       float elevation, int arrowWidth, int arrowHeight) {
		this(context, true, null, screenRect, minBorderLeft, minBorderTop,
				minBorderRight, minBorderBottom, cornerRadiusX, cornerRadiusY, elevation,
				arrowWidth, arrowHeight);
	}

	protected HighlightDialog(Context context, boolean cancelable, OnCancelListener cancelListener,
	                          List<Pair<Rect, View>> screenRect, int minBorderLeft,
	                          int minBorderTop, int minBorderRight, int minBorderBottom,
	                          float cornerRadiusX, float cornerRadiusY, float elevation,
	                          int arrowWidth, int arrowHeight) {
		this(context, android.R.style.Theme_Translucent_NoTitleBar, screenRect, minBorderLeft,
				minBorderTop, minBorderRight, minBorderBottom, cornerRadiusX, cornerRadiusY,
				elevation, arrowWidth, arrowHeight);
		setCancelable(cancelable);
		setOnCancelListener(cancelListener);
	}

	public HighlightDialog(Context context, int themeResId,
	                       List<Pair<Rect, View>> screenRect, int minBorderLeft, int minBorderTop,
	                       int minBorderRight, int minBorderBottom, float cornerRadiusX,
	                       float cornerRadiusY, float elevation, int arrowWidth,
	                       int arrowHeight) {
		super(context, themeResId);
		mScreenRect = screenRect;
		mMinBorderLeft = minBorderLeft;
		mMinBorderTop = minBorderTop;
		mMinBorderRight = minBorderRight;
		mMinBorderBottom = minBorderBottom;
		mCornerRadiusX = cornerRadiusX;
		mCornerRadiusY = cornerRadiusY;
		mElevation = elevation;
		mArrowWidth = arrowWidth;
		mArrowHeight = arrowHeight;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Window w = Objects.requireNonNull(getWindow());
		w.setDecorFitsSystemWindows(false);
		w.getAttributes().windowAnimations
				= android.R.style.Animation_Toast;
		w.setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
				WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		w.setStatusBarColor(Color.TRANSPARENT);
		w.setNavigationBarColor(Color.TRANSPARENT);
		FrameLayout view = new FrameLayout(getContext());
		for (Pair<Rect, View> r : mScreenRect) {
			HighlightLayout h = new HighlightLayout(getContext(), r.first,
					mMinBorderLeft, mMinBorderTop, mMinBorderRight, mMinBorderBottom,
					mArrowWidth, mArrowHeight, mCornerRadiusX, mCornerRadiusY,
					r.second);
			h.setOnClickListener(unused -> dismiss());
			h.setElevation(mElevation);
			view.addView(h);
		}
		setContentView(view);
	}

	@SuppressLint("ViewConstructor")
	private static class HighlightLayout extends FrameLayout {
		private final Rect mArrowRect;
		private final Rect mMetricsRect;
		private final boolean mIsFacingUp;
		private final RectF mBoxRect;
		private final Path mPath;
		private final View mDisplayLayout;
		private final int mMinBorderLeft, mMinBorderRight, mMinBorderTop, mMinBorderBottom;
		private final float mCornerRadiusX, mCornerRadiusY;

		public HighlightLayout(Context context, Rect screenRect, int minBorderLeft,
		                       int minBorderTop, int minBorderRight, int minBorderBottom,
		                       int arrowWidth, int arrowHeight, float cornerRadiusX,
		                       float cornerRadiusY, View displayLayout) {
			this(context, null, screenRect, minBorderLeft, minBorderTop, minBorderRight,
					minBorderBottom, arrowWidth, arrowHeight, cornerRadiusX, cornerRadiusY,
					displayLayout);
		}

		public HighlightLayout(Context context, AttributeSet attrs, Rect screenRect,
		                       int minBorderLeft, int minBorderTop, int minBorderRight,
		                       int minBorderBottom, int arrowWidth, int arrowHeight,
		                       float cornerRadiusX, float cornerRadiusY, View displayLayout) {
			this(context, attrs, 0, screenRect, minBorderLeft, minBorderTop,
					minBorderRight, minBorderBottom, arrowWidth, arrowHeight, cornerRadiusX,
					cornerRadiusY, displayLayout);
		}

		public HighlightLayout(Context context, AttributeSet attrs, int defStyleAttr,
		                       Rect screenRect, int minBorderLeft, int minBorderTop,
		                       int minBorderRight, int minBorderBottom, int arrowWidth,
		                       int arrowHeight, float cornerRadiusX, float cornerRadiusY,
		                       View displayLayout) {
			this(context, attrs, defStyleAttr, 0, screenRect, minBorderLeft, minBorderTop,
					minBorderRight, minBorderBottom, arrowWidth, arrowHeight, cornerRadiusX,
					cornerRadiusY, displayLayout);
		}

		public HighlightLayout(Context context, AttributeSet attrs, int defStyleAttr,
		                       int defStyleRes, Rect screenRect, int minBorderLeft,
		                       int minBorderTop, int minBorderRight, int minBorderBottom,
		                       int arrowWidth, int arrowHeight, float cornerRadiusX,
		                       float cornerRadiusY, View displayLayout) {
			super(context, attrs, defStyleAttr, defStyleRes);
			mPath = new Path(); mBoxRect = new RectF(); mArrowRect = new Rect();
			mDisplayLayout = displayLayout;
			if (mDisplayLayout.getPaddingLeft() != 0 || mDisplayLayout.getPaddingTop() != 0 ||
					mDisplayLayout.getPaddingRight() != 0 || mDisplayLayout.getPaddingLeft() != 0) {
				throw new IllegalArgumentException("Display layout must not have padding set!");
			}
			addView(mDisplayLayout, new FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			mMetricsRect = getContext().getSystemService(WindowManager.class)
					.getCurrentWindowMetrics().getBounds();
			mMinBorderLeft = minBorderLeft;
			mMinBorderRight = minBorderRight;
			mMinBorderTop = minBorderTop;
			mMinBorderBottom = minBorderBottom;
			mCornerRadiusX = cornerRadiusX;
			mCornerRadiusY = cornerRadiusY;

			mIsFacingUp = (mMetricsRect.height() / 2) < screenRect.bottom; // true if box over view
			arrowWidth = Math.min(mMetricsRect.width() - (mMinBorderLeft + mMinBorderRight
					+ (2 * (int) mCornerRadiusX)), arrowWidth);
			arrowHeight = Math.min(mMetricsRect.height() - (mMinBorderTop + mMinBorderBottom),
					arrowHeight);
			int left = Math.max(mMetricsRect.left + mMinBorderLeft + (int) mCornerRadiusX,
					Math.min(mMetricsRect.right - mMinBorderRight - arrowWidth,
					screenRect.left + (screenRect.width() / 2) - (arrowWidth / 2)));
			int top = Math.max(mMetricsRect.top + mMinBorderTop,
					Math.min(mMetricsRect.bottom - mMinBorderBottom - arrowHeight,
					mIsFacingUp ? screenRect.top - arrowHeight : screenRect.bottom));
			mArrowRect.set(left, top, left + arrowWidth, top + arrowHeight);
			mDisplayLayout.setPadding(0, mIsFacingUp ? 0 : mArrowRect.height(),
					0, mIsFacingUp ? mArrowRect.height() : 0);
			setClipToOutline(true);
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			setMeasuredDimension(mMetricsRect.width(), mMetricsRect.height());
			mDisplayLayout.measure(MeasureSpec.makeMeasureSpec(mMetricsRect.width()
							- (mMinBorderLeft + mMinBorderRight), MeasureSpec.AT_MOST),
					MeasureSpec.makeMeasureSpec(mMetricsRect.height() - (mIsFacingUp ?
							mArrowRect.bottom : mArrowRect.top), MeasureSpec.AT_MOST));
			int boxHeight = Math.max(0, Math.min(Math.min(mMetricsRect.height() -
					(mMinBorderTop + mMinBorderBottom), mMetricsRect.height() -
					(mIsFacingUp ? mArrowRect.top : mArrowRect.bottom)),
					mDisplayLayout.getMeasuredHeight()));
			int boxWidth = Math.max(mArrowRect.width(), Math.min(mMetricsRect.width() -
							(mMinBorderLeft + mMinBorderRight), mDisplayLayout.getMeasuredWidth()));
			int left = Math.max(mMetricsRect.left + mMinBorderLeft,
					Math.min(mMetricsRect.right - mMinBorderRight - boxWidth,
							(mArrowRect.left + (mArrowRect.width() / 2)) - (boxWidth / 2)));
			int top = Math.max(mMetricsRect.top + mMinBorderTop,
					Math.min(mMetricsRect.bottom - mMinBorderBottom - boxHeight,
							mIsFacingUp ? (mArrowRect.top - boxHeight) : mArrowRect.bottom));
			mBoxRect.set(
					left,
					top,
					Math.min(mMetricsRect.right - mMinBorderRight, left + boxWidth),
					Math.min(mMetricsRect.bottom - mMinBorderBottom, top + boxHeight)
			);
			mPath.reset();
			// Draw arrow
			if (mIsFacingUp) {
				mPath.moveTo(mArrowRect.right, mBoxRect.bottom);
				mPath.lineTo(mArrowRect.right - (mArrowRect.width() / 2f), mArrowRect.bottom);
				mPath.lineTo(mArrowRect.left, mBoxRect.bottom);
			} else {
				mPath.moveTo(mArrowRect.left, mBoxRect.top);
				mPath.lineTo(mArrowRect.left + (mArrowRect.width() / 2f), mArrowRect.top);
				mPath.lineTo(mArrowRect.right, mBoxRect.top);
			}
			mPath.addRoundRect(mBoxRect, mCornerRadiusX, mCornerRadiusY, Path.Direction.CW);
			// ALWAYS reset this when path changes to invalidate outline!
			setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View view, Outline outline) {
					outline.setPath(mPath);
				}
			});
		}

		@Override
		protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
			int count = this.getChildCount();
			for (int i = 0; i < count; i++) {
				View child = this.getChildAt(i);
				child.layout((int) mBoxRect.left,
						mIsFacingUp ? (int) mBoxRect.top : mArrowRect.top,
						(int) mBoxRect.right,
						mIsFacingUp ? mArrowRect.bottom : (int) mBoxRect.bottom);
			}
		}
	}


	private static List<Pair<Rect, View>> getLocationsOnScreen(Set<Pair<View, View>> pairs) {
		return pairs.stream().map(HighlightDialog::getLocationOnScreen)
				.collect(Collectors.toUnmodifiableList());
	}

	private static Pair<Rect, View> getLocationOnScreen(Pair<View, View> pair) {
		return new Pair<>(getLocationOnScreen(pair.first), pair.second);
	}

	/**
	 * Helper method to get View location in window, stored in Rect.
	 * @param v View to operate on.
	 * @return View location in window, stored in Rect.
	 */
	public static Rect getLocationOnScreen(View v) {
		int[] out = new int[2];
		v.getLocationInWindow(out);
		return new Rect(
				out[0], out[1], out[0] + v.getWidth(), out[1] + v.getHeight()
		);
	}

	/**
	 * Helper method to show dialog when views are laid out.
	 * @param v View to wait for.
	 * @param r Lambada that shows HighlightDialog.
	 */
	public static void showOnceReady(View v, Runnable r) {
		v.getViewTreeObserver().addOnGlobalLayoutListener(
				new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
				r.run();
			}
		});
	}
}
