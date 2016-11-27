/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
	private static final Typeface NORMAL_TYPEFACE =
			Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

	/**
	 * Update rate in milliseconds for interactive mode. We update once a second since seconds are
	 * displayed in interactive mode.
	 */
	private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

	/**
	 * Handler message id for updating the time periodically in interactive mode.
	 */
	private static final int MSG_UPDATE_TIME = 0;
	private static final String PATH_WEATHER = "/weather";
	private static final String KEY_HIGH = "high_temp";
	private static final String KEY_LOW = "low_temp";
	private static final String KEY_ID = "weather_id";
	private String mHighTemperature;
	private String mLowTemperature;
	private int mWeatherId;
	private GoogleApiClient mGoogleApiClient;
	@Override
	public Engine onCreateEngine() {
		return new Engine();
	}

	private static class EngineHandler extends Handler {
		private final WeakReference<MyWatchFace.Engine> mWeakReference;

		public EngineHandler(MyWatchFace.Engine reference) {
			mWeakReference = new WeakReference<>(reference);
		}

		@Override
		public void handleMessage(Message msg) {
			MyWatchFace.Engine engine = mWeakReference.get();
			if (engine != null) {
				switch (msg.what) {
					case MSG_UPDATE_TIME:
						engine.handleUpdateTimeMessage();
						break;
				}
			}
		}
	}

	private class Engine extends CanvasWatchFaceService.Engine implements
			GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener{
		final Handler mUpdateTimeHandler = new EngineHandler(this);
		boolean mRegisteredTimeZoneReceiver = false;

		boolean mAmbient;
		Calendar mCalendar;
		final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				mCalendar.setTimeZone(TimeZone.getDefault());
				mCalendar.setTimeInMillis(System.currentTimeMillis());
			}
		};
		// Paint objects
		Paint mPaintBackground;
		Paint mPaintTime;
		Paint mPaintDate;
		Paint mPaintLowTemperature;
		Paint mPaintHighTemperature;

		// Float offsets
		float mXOffsetTime;
		float mXOffsetDate;
		float mYOffsetTime;
		float mYOffsetDate;
		float mYOffsetDivider;
		float mYOffsetWeather;
		/**
		 * Whether the display supports fewer bits for each color in ambient mode. When true, we
		 * disable anti-aliasing in ambient mode.
		 */
		boolean mLowBitAmbient;
		Context mContext;
		@Override
		public void onCreate(SurfaceHolder holder) {
			super.onCreate(holder);
			mContext = getApplicationContext();
			setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
					.setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
					.setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
					.setShowSystemUiTime(false)
					.build());
			Resources resources = MyWatchFace.this.getResources();

			mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
					.addConnectionCallbacks(this)
					.addOnConnectionFailedListener(this)
					.addApi(Wearable.API)
					.build();
			mPaintBackground = new Paint();
			mPaintBackground.setColor(ContextCompat.getColor(mContext,R.color.primary));

			mPaintTime = getPaintForCurrentTime(R.color.digital_text);
			mPaintDate = getPaintForDate();
			mPaintLowTemperature = getPaintForTemperature(R.color.primary_light);
			mPaintHighTemperature = getPaintForTemperature(R.color.digital_text);

			mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
			mYOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);

			mYOffsetDivider = resources.getDimension(R.dimen.y_offset_divider);
			mYOffsetWeather = resources.getDimension(R.dimen.y_offset_weather);

			mXOffsetTime = mPaintTime.measureText("12:00") / 2;
			mXOffsetDate = mPaintDate.measureText("WED, JUN 13, 2016") / 2;

			mCalendar = Calendar.getInstance();
		}

		@Override
		public void onDestroy() {
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			super.onDestroy();
		}

		private Paint getPaintForCurrentTime(int textColor) {
			Paint paint = new Paint();
			paint.setColor(textColor);
			paint.setTypeface(NORMAL_TYPEFACE);
			paint.setAntiAlias(true);
			paint.setTextSize(getResources().getDimension(R.dimen.digital_time_text_size));
			return paint;
		}

		private Paint getPaintForDate() {
			Paint paint = new Paint();
			paint.setTypeface(NORMAL_TYPEFACE);
			paint.setAntiAlias(true);
			paint.setTextSize(getResources().getDimension(R.dimen.digital_date_text_size));
			return paint;
		}

		private Paint getPaintForTemperature(int textColor) {
			Paint paint = new Paint();
			paint.setColor(textColor);
			paint.setTypeface(NORMAL_TYPEFACE);
			paint.setAntiAlias(true);
			paint.setTextSize(getResources().getDimension(R.dimen.digital_temp_text_size));
			return paint;
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			super.onVisibilityChanged(visible);

			if (visible) {
				registerReceiver();
				mGoogleApiClient.connect();
				// Update time zone in case it changed while we weren't visible.
				mCalendar.setTimeZone(TimeZone.getDefault());
				mCalendar.setTimeInMillis(System.currentTimeMillis());
			} else {
				unregisterReceiver();
				if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
					Wearable.DataApi.removeListener(mGoogleApiClient, this);
					mGoogleApiClient.disconnect();
				}
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		private void registerReceiver() {
			if (mRegisteredTimeZoneReceiver) {
				return;
			}
			mRegisteredTimeZoneReceiver = true;
			IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
			MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
		}

		private void unregisterReceiver() {
			if (!mRegisteredTimeZoneReceiver) {
				return;
			}
			mRegisteredTimeZoneReceiver = false;
			MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
		}

		@Override
		public void onApplyWindowInsets(WindowInsets insets) {
			super.onApplyWindowInsets(insets);
		}

		@Override
		public void onPropertiesChanged(Bundle properties) {
			super.onPropertiesChanged(properties);
			mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
		}

		@Override
		public void onTimeTick() {
			super.onTimeTick();
			invalidate();
		}

		@Override
		public void onAmbientModeChanged(boolean inAmbientMode) {
			super.onAmbientModeChanged(inAmbientMode);
			if (mAmbient != inAmbientMode) {
				mAmbient = inAmbientMode;
				invalidate();
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		@Override
		public void onDraw(Canvas canvas, Rect bounds) {
			// Draw the background.
			if (isInAmbientMode()) {
				canvas.drawColor(Color.BLACK);
			} else {
				canvas.drawRect(0, 0, bounds.width(), bounds.height(), mPaintBackground);
			}

			// Draw H:MM in ambient mode or H:MM:SS in interactive mode.
			mCalendar.setTimeInMillis(System.currentTimeMillis());

			// Draw time
			int hourOfDay = mCalendar.get(Calendar.HOUR_OF_DAY);
			int minute = mCalendar.get(Calendar.MINUTE);
			String currentTime = String.format(Locale.getDefault(),"%02d:%02d", hourOfDay, minute);
			canvas.drawText(currentTime, bounds.centerX() - mXOffsetTime, mYOffsetTime, mPaintTime);

			// Draw date
			final String DAY_OF_WEEK = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT,
					Locale.getDefault());
			final String MONTH = mCalendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault());
			final int DAY_OF_MONTH = mCalendar.get(Calendar.DAY_OF_MONTH);
			final int YEAR = mCalendar.get(Calendar.YEAR);
			final String DATE_TODAY = String.format(Locale.getDefault(),"%s, %s %d, %d", DAY_OF_WEEK.toUpperCase(), MONTH.toUpperCase(), DAY_OF_MONTH, YEAR);

			if (isInAmbientMode()) {
				mPaintDate.setColor(ContextCompat.getColor(mContext,R.color.digital_text));
			} else {
				mPaintDate.setColor(ContextCompat.getColor(mContext,R.color.primary_light));
			}
			canvas.drawText(DATE_TODAY, bounds.centerX() - mXOffsetDate, mYOffsetDate, mPaintDate);
			if (mHighTemperature != null && mLowTemperature != null) {
				int textMargin = 20, iconMargin = 30;
				// draw date-temperature divider line
				canvas.drawLine(bounds.centerX() - mXOffsetDate, mYOffsetDivider, bounds.centerX() + mXOffsetDate, mYOffsetDivider, mPaintDate);

				float highTextSize = mPaintHighTemperature.measureText(mHighTemperature);
				if (mAmbient) {
					mPaintLowTemperature.setColor(ContextCompat.getColor(mContext,R.color.digital_text));
					float lowTextSize = mPaintLowTemperature.measureText(mLowTemperature);
					float xOffset = bounds.centerX() - ((highTextSize + lowTextSize + textMargin) / 2);
					canvas.drawText(mHighTemperature, xOffset, mYOffsetWeather, mPaintHighTemperature);
					canvas.drawText(mLowTemperature, xOffset + highTextSize + textMargin, mYOffsetWeather, mPaintLowTemperature);
				} else {
					mPaintLowTemperature.setColor(ContextCompat.getColor(mContext,R.color.primary_light));
					float xOffset = bounds.centerX() - (highTextSize / 2);
					canvas.drawText(mHighTemperature, xOffset, mYOffsetWeather, mPaintHighTemperature);
					canvas.drawText(mLowTemperature, bounds.centerX() + (highTextSize / 2) + textMargin, mYOffsetWeather, mPaintLowTemperature);

					Drawable drawable = ContextCompat.getDrawable(mContext,Utility.getIconResourceForWeatherCondition(mWeatherId));
					Bitmap icon = ((BitmapDrawable) drawable).getBitmap();
					float scaledWidth = (mPaintHighTemperature.getTextSize() / icon.getHeight()) * icon.getWidth();
					Bitmap weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mPaintHighTemperature.getTextSize(), true);
					float iconXOffset = bounds.centerX() - ((highTextSize / 2) + weatherIcon.getWidth() + iconMargin);
					canvas.drawBitmap(weatherIcon, iconXOffset, mYOffsetWeather - weatherIcon.getHeight()+5, null);
				}
			}

		}

		/**
		 * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
		 * or stops it if it shouldn't be running but currently is.
		 */
		private void updateTimer() {
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			if (shouldTimerBeRunning()) {
				mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
			}
		}

		/**
		 * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
		 * only run when we're visible and in interactive mode.
		 */
		private boolean shouldTimerBeRunning() {
			return isVisible() && !isInAmbientMode();
		}

		/**
		 * Handle updating the time periodically in interactive mode.
		 */
		private void handleUpdateTimeMessage() {
			invalidate();
			if (shouldTimerBeRunning()) {
				long timeMs = System.currentTimeMillis();
				long delayMs = INTERACTIVE_UPDATE_RATE_MS
						- (timeMs % INTERACTIVE_UPDATE_RATE_MS);
				mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
			}
		}

		@Override
		public void onConnected(@Nullable Bundle bundle) {
			Wearable.DataApi.addListener(mGoogleApiClient, this);
			Log.e("GOOGLE_API_CLIENT", "Connected");
		}

		@Override
		public void onConnectionSuspended(int i) {
			Log.e("GOOGLE_API_CLIENT", "Connection Suspended");
		}

		@Override
		public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
			Log.e("GOOGLE_API_CLIENT", "Connection Failed: " + connectionResult.getErrorMessage());
		}

		// Wearable Data Change Listener
		@Override
		public void onDataChanged(DataEventBuffer dataEventBuffer) {
			for (DataEvent dataEvent : dataEventBuffer) {
				if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
					DataItem dataItem = dataEvent.getDataItem();
					if (dataItem.getUri().getPath().compareTo(PATH_WEATHER) == 0) {
						DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
						mHighTemperature = dataMap.getString(KEY_HIGH);
						mLowTemperature = dataMap.getString(KEY_LOW);
						mWeatherId = dataMap.getInt(KEY_ID);
						Log.e("WATCH_DATA", "\nHigh: " + mHighTemperature + "\nLow: " + mLowTemperature + "\nID: " + mWeatherId);
						invalidate();
					}
				}
			}
		}
	}
}
