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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create("sans-serif-thin", Typeface.NORMAL);

    private final static String LOG_TAG = "SunshineWatchService";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MINUTE = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_REQUEST_PATH = "/weather-request";
        private static final String LOW_TEMP_KEY = "low_temp_key";
        private static final String HIGH_TEMP_KEY = "high_temp_key";
        private static final String WEATHER_CONDITION_KEY = "weather_condition_key";
        private static final String REQUEST_ID_STRING = "request_id";
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;
        private Paint mBackgroundPaint, mHourBgPaint, mMinuteBgPaint, mWeatherBitmapPaint;
        private RectF mHourRect, mMinuteRect, mWeatherContainer;
        private Paint mTimeTextPaint, mDateTextPaint, mHourTextPaint, mMinuteTextPaint, mAmPmTextPaint, mTempTextPaint;
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            }
        };
        private boolean mLowBitAmbient;
        private Bitmap mBackgroundBitmap, mWeatherBitmap;
        private boolean is24Hrs;
        private GoogleApiClient mGoogleApiClient;
        private Toast mToast;
        private String mTempText;
        private long mLastSyncTime;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.bg);
            mWeatherBitmap = BitmapFactory.decodeResource(resources, R.drawable.art_clear);
            mTempTextPaint = createTextPaint(Color.BLACK);
            mWeatherBitmapPaint = new Paint();

            //noinspection deprecation
            mTimeTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTimeTextPaint.setShadowLayer(1.0f, 0, 1.0f, Color.BLACK);
            mHourTextPaint = createTextPaint(Color.BLACK);
            mMinuteTextPaint = createTextPaint(Color.BLACK);
            mDateTextPaint = createTextPaint(Color.BLACK);
            mAmPmTextPaint = createTextPaint(Color.BLACK);
            mHourBgPaint = new Paint();
            mMinuteBgPaint = new Paint();
            mHourBgPaint.setShadowLayer(1.0f, 0, 1.0f, Color.BLACK);
            mMinuteBgPaint.setShadowLayer(1.0f, 0, 1.0f, Color.BLACK);
            mHourBgPaint.setColor(Color.WHITE);
            mMinuteBgPaint.setColor(Color.WHITE);
            mHourBgPaint.setAntiAlias(true);
            mMinuteBgPaint.setAntiAlias(true);

            mCalendar = Calendar.getInstance();
            is24Hrs = DateFormat.is24HourFormat(getApplicationContext());
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)

                    .addApi(Wearable.API)
                    .build();
            mTempText = String.format(Locale.getDefault(), getString(R.string.temp_format), "-", "-");

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            float scale = ((float) width) / (float) mBackgroundBitmap.getWidth();
            mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    (int) (mBackgroundBitmap.getWidth() * scale),
                    (int) (mBackgroundBitmap.getHeight() * scale), true);


            mTimeTextPaint.setTextSize(is24Hrs ? height * 0.3F : height * 0.225F);
            mDateTextPaint.setTextSize(height * 0.075F);
            mHourRect = new RectF(width * 0.1F, height * 0.3125F, width * 0.475F, height * 0.6875F);
            mMinuteRect = new RectF(width * 0.525F, height * 0.3125F, width * 0.9F, height * 0.6875F);
            mWeatherContainer = new RectF(0, height * 0.85F, width, height);
            float timeTextSize = mHourRect.height() * 0.6F;
            float amPmTextSize = height * 0.075F;
            mHourTextPaint.setTextSize(timeTextSize);
            mMinuteTextPaint.setTextSize(timeTextSize);
            mAmPmTextPaint.setTextSize(amPmTextSize);
            mTempTextPaint.setTextSize(width * 0.08F);

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
                is24Hrs = DateFormat.is24HourFormat(getApplicationContext());
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.disconnect();
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
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
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
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
            if (mLowBitAmbient) {
                mTimeTextPaint.setAntiAlias(!inAmbientMode);
                mDateTextPaint.setAntiAlias(!inAmbientMode);
            }
            invalidate();


            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TAP:
                    if (mWeatherContainer.contains(x, y)) {
                        requestWeatherUpdate();
                    }
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            Date date = new Date();
            date.setTime(now);
            String dateFormatString = getString(R.string.date_format);
            SimpleDateFormat sdf = new SimpleDateFormat(dateFormatString, Locale.getDefault());
            String dateString = sdf.format(date);
            String hourText = is24Hrs ? String.format(Locale.getDefault(), getString(R.string.time_format_single), mCalendar.get(Calendar.HOUR_OF_DAY)) : String.format(Locale.getDefault(), "%02d", mCalendar.get(Calendar.HOUR));
            String amPmText = (mCalendar.get(Calendar.AM_PM) == 0) ? getString(R.string.am) : getString(R.string.pm);
            String minuteText = String.format(Locale.getDefault(), getString(R.string.time_format_single), mCalendar.get(Calendar.MINUTE));
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                String fullTime = is24Hrs ? String.format(Locale.getDefault(), getString(R.string.time_format_full), hourText, minuteText)
                        : String.format(Locale.getDefault(), getString(R.string.time_format_full_am_pm), hourText, minuteText, amPmText);
                canvas.drawText(fullTime, bounds.centerX() - mTimeTextPaint.measureText(fullTime) / 2, bounds.centerY(), mTimeTextPaint);
                mDateTextPaint.setColor(Color.WHITE);
                canvas.drawText(dateString, bounds.centerX() - mDateTextPaint.measureText(dateString) / 2, bounds.height() * 0.6F, mDateTextPaint);
            } else {


                canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
                canvas.drawOval(mHourRect, mHourBgPaint);
                canvas.drawOval(mMinuteRect, mMinuteBgPaint);
                float weatherSize = bounds.width() * 0.1F;
                mWeatherBitmap = Bitmap.createScaledBitmap(mWeatherBitmap, (int) (weatherSize), (int) (weatherSize), true);
                canvas.drawText(hourText, mHourRect.centerX() - mHourTextPaint.measureText(hourText) / 2, mHourRect.centerY() + (mHourTextPaint.descent() - mHourTextPaint.ascent()) / 3, mHourTextPaint);
                canvas.drawText(minuteText, mMinuteRect.centerX() - mMinuteTextPaint.measureText(minuteText) / 2, mHourRect.centerY() + (mHourTextPaint.descent() - mHourTextPaint.ascent()) / 3, mMinuteTextPaint);
                if (!is24Hrs) {
                    canvas.drawText(amPmText, bounds.centerX() - mAmPmTextPaint.measureText(amPmText) / 2, bounds.height() * 0.6875F, mAmPmTextPaint);
                }
                mDateTextPaint.setColor(Color.BLACK);
                canvas.drawText(dateString, bounds.centerX() - mDateTextPaint.measureText(dateString) / 2, mHourRect.bottom + bounds.height() * 0.1F, mDateTextPaint);
                float weatherXOffset = bounds.centerX() - (mWeatherBitmap.getWidth() + mTempTextPaint.measureText(mTempText) + 0.025F * bounds.width()) / 2;
                canvas.drawBitmap(mWeatherBitmap, weatherXOffset, bounds.height() * 0.85F, mWeatherBitmapPaint);
                canvas.drawText(mTempText, weatherXOffset + mWeatherBitmap.getWidth() + 0.025F * bounds.width(), bounds.height() * 0.92F, mTempTextPaint);
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
                long delayMs = INTERACTIVE_UPDATE_RATE_MINUTE
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MINUTE);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void requestWeatherUpdate() {
            makeToast(getString(R.string.updating_weather_toast));

            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_REQUEST_PATH);
            putDataMapRequest.getDataMap().putLong(REQUEST_ID_STRING, System.currentTimeMillis());
            PutDataRequest putDataRequest = putDataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataRequest)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            String logText = dataItemResult.getStatus().isSuccess() ?
                                    "Successful request"
                                    : "Request failed " + dataItemResult.getStatus().getStatusMessage();
                            Log.d(LOG_TAG, "onResult " + logText);
                        }
                    });

        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            long currentTime = System.currentTimeMillis();
            //Since we don't want to request weather sync manually every visibility change
            //we set 4 hours interval between allowed updates, unless tapped on weather
            //If there were changes in weather pushed from the server to mobile or manual sync on mobile
            //weather will sync anyway
            if ((currentTime - mLastSyncTime) > 240 * 60 * 1000) {
                requestWeatherUpdate();
            }

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged is called on wearable");
            String high, low;
            mLastSyncTime = System.currentTimeMillis();
            int condition;
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().equals(WEATHER_PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        high = dataMap.containsKey(HIGH_TEMP_KEY) ? formatTemperature(dataMap.getDouble(HIGH_TEMP_KEY)) : "N/A";
                        low = dataMap.containsKey(LOW_TEMP_KEY) ? formatTemperature(dataMap.getDouble(LOW_TEMP_KEY)) : "N/A";
                        condition = dataMap.containsKey(WEATHER_CONDITION_KEY) ? dataMap.getInt(WEATHER_CONDITION_KEY) : 800;
                        configureWeatherView(high, low, condition);
                    }
                }
            }

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        private void configureWeatherView(String high, String low, int condition) {
            mWeatherBitmap = BitmapFactory.decodeResource(SunshineWatchFaceService.this.getResources(), getArtResourceForWeatherCondition(condition));
            mTempText = String.format(Locale.getDefault(), getString(R.string.temp_format), high, low);
            makeToast(getString(R.string.weather_updated_toast));
            invalidate();

        }

        private void makeToast(String message) {

            if (mToast != null) {
                mToast.cancel();
            }
            mToast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT);
            mToast.show();
        }
    }

    private String formatTemperature(double temperature) {
        return String.format(SunshineWatchFaceService.this.getResources().getString(R.string.format_temperature), temperature);
    }

    private int getArtResourceForWeatherCondition(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.art_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.art_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.art_rain;
        } else if (weatherId == 511) {
            return R.drawable.art_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.art_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.art_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.art_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.art_storm;
        } else if (weatherId == 800) {
            return R.drawable.art_clear;
        } else if (weatherId == 801) {
            return R.drawable.art_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.art_clouds;
        }
        return -1;
    }


}