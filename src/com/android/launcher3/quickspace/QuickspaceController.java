/*
 * Copyright (C) 2018 CypherOS
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
package com.android.launcher3.quickspace;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.crdroid.OmniJawsClient;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.util.MediaSessionManagerHelper;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.List;

public class QuickspaceController implements OmniJawsClient.OmniJawsObserver, MediaSessionManagerHelper.MediaMetadataListener {

    public final ArrayList<OnDataListener> mListeners = new ArrayList();
    private static final String SETTING_WEATHER_LOCKSCREEN_UNIT = "weather_lockscreen_unit";
    private static final boolean DEBUG = false;
    private static final String TAG = "Launcher3:QuickspaceController";

    private final Context mContext;
    private final Handler mHandler;
    private QuickEventsController mEventsController;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private Drawable mConditionImage;

    private boolean mUseImperialUnit;

    private MediaMetadata mMediaMetadata;
    private String mLastTrackTitle = null;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    private Runnable mOnDataUpdatedRunnable = new Runnable() {
            @Override
            public void run() {
                for (OnDataListener list : mListeners) {
                    list.onDataUpdated();
                }
            }
        };

    private Runnable mWeatherRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    mWeatherClient.queryWeather();
                    mWeatherInfo = mWeatherClient.getWeatherInfo();
                    if (mWeatherInfo != null) {
                        mConditionImage = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);
                    }
                    notifyListeners();
                } catch(Exception e) {
                    // Do nothing
                }
            }
        };

    public interface OnDataListener {
        void onDataUpdated();
    }

    public QuickspaceController(Context context) {
        mContext = context;
        mHandler = new Handler();
        mEventsController = new QuickEventsController(context);
        mWeatherClient = new OmniJawsClient(context);
    }

    private void addWeatherProvider() {
        if (!Utilities.isQuickspaceWeather(mContext)) return;
        mWeatherClient.addObserver(this);
        queryAndUpdateWeather();
    }

    public void addListener(OnDataListener listener) {
        mListeners.add(listener);
        addWeatherProvider();
        getMSMHInstance().addMediaMetadataListener(this);
        listener.onDataUpdated();
    }

    private void removeListener(OnDataListener listener) {
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(this);
        }
        mListeners.remove(listener);
    }

    public boolean isQuickEvent() {
        return mEventsController.isQuickEvent();
    }

    public QuickEventsController getEventController() {
        return mEventsController;
    }

    public boolean isWeatherAvailable() {
        return mWeatherClient != null && mWeatherClient.isOmniJawsEnabled();
    }

    public Drawable getWeatherIcon() {
        return mConditionImage;
    }

    public String getWeatherTemp() {
        boolean shouldShowCity = Utilities.QuickSpaceShowCity(mContext);
        boolean showWeatherText = Utilities.QuickSpaceShowWeatherText(mContext);
        if (mWeatherInfo != null) {
            String formattedCondition = mWeatherInfo.condition;
            if (formattedCondition.toLowerCase().contains("clouds")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_clouds);
            } else if (formattedCondition.toLowerCase().contains("rain")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_rain);
            } else if (formattedCondition.toLowerCase().contains("clear")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_clear);
            } else if (formattedCondition.toLowerCase().contains("storm")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_storm);
            } else if (formattedCondition.toLowerCase().contains("snow")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_snow);
            } else if (formattedCondition.toLowerCase().contains("wind")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_wind);
            } else if (formattedCondition.toLowerCase().contains("mist")) {
                formattedCondition = mContext.getResources().getString(R.string.quick_event_weather_mist);
            }
            String weatherTemp = (shouldShowCity ? mWeatherInfo.city : "") + " " + mWeatherInfo.temp +
                    mWeatherInfo.tempUnits  + (showWeatherText ? " · "  + formattedCondition : "");
            return weatherTemp;
        }
        return null;
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    public void onPause() {
        cancelListeners();
    }

    public void onResume() {
        maybeInitExecutor();
        mEventsController.onResume();
        updateMediaController();
        notifyListeners();
    }

    private void maybeInitExecutor() {
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }
    }

    private void cancelListeners() {
        if (mEventsController != null) {
            mEventsController.onPause();
        }
        for (OnDataListener listener : new ArrayList<>(mListeners)) {
            removeListener(listener);
        }
        unregisterMediaController();
        mHandler.removeCallbacksAndMessages(null);
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    public void onDestroy() {
        cancelListeners();
        mWeatherClient = null;
        mWeatherInfo = null;
        mConditionImage = null;
        mMediaMetadata = null;
        executorService = null;
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        Log.d(TAG, "weatherError " + errorReason);
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            notifyListeners();
        }
    }

    @Override
    public void updateSettings() {
        Log.i(TAG, "updateSettings");
        queryAndUpdateWeather();
    }

    private void queryAndUpdateWeather() {
        maybeInitExecutor();
        executorService.execute(mWeatherRunnable);
    }

    public void notifyListeners() {
        mHandler.post(mOnDataUpdatedRunnable);
    }

    private void unregisterMediaController() {
        getMSMHInstance().removeMediaMetadataListener(this);
    }
    
    private MediaSessionManagerHelper getMSMHInstance() {
        return MediaSessionManagerHelper.Companion.getInstance(mContext);
    }

    private void updateMediaController() {
        if (!Utilities.isQuickspaceNowPlaying(mContext)) {
            unregisterMediaController();
            return;
        }
        mMediaMetadata = getMSMHInstance().getCurrentMediaMetadata();
        boolean isPlaying = getMSMHInstance().isMediaPlaying();
        String trackArtist = isPlaying && mMediaMetadata != null ? mMediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : "";
        String trackTitle = isPlaying && mMediaMetadata != null ? mMediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
        mEventsController.setMediaInfo(trackTitle, trackArtist, isPlaying);
        mEventsController.updateQuickEvents();
        notifyListeners();
    }
    
    @Override
    public void onMediaMetadataChanged() {
        updateMediaController();
    }

    @Override
    public void onPlaybackStateChanged() {
        updateMediaController();
    }
}
