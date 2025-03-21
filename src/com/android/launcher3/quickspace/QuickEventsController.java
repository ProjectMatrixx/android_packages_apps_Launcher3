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

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;
import android.view.View;
import android.view.View.OnClickListener;

import androidx.core.content.ContextCompat;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import java.util.Calendar;
import java.util.Random;

import java.util.List;

import com.android.launcher3.util.MediaSessionManagerHelper;

public class QuickEventsController {

    private static final String SETTING_DEVICE_INTRO_COMPLETED = "device_introduction_completed";
    private final Context mContext;
    private final Resources mResources;

    private String mEventTitle;
    private String mEventTitleSub;
    private String mGreetings;
    private String mClockExt;
    private OnClickListener mEventTitleSubAction = null;
    private Drawable mEventSubIcon = null;

    private boolean mIsQuickEvent = false;
    private boolean mRegistered = false;

    // Device Intro
    private boolean mIsFirstTimeDone = false;
    private SharedPreferences mPreferences;

    // PSA + Personality
    private String[] mPSAStr;
    private BroadcastReceiver mPSAListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            psonalityEvent();
        }
    };

    // NowPlaying
    private boolean mEventNowPlaying = false;
    private String mNowPlayingTitle;
    private String mNowPlayingArtist;
    private boolean mPlayingActive = false;

    public QuickEventsController(Context context) {
        mContext = context;
        mResources = context.getResources();
        initQuickEvents();
    }

    public void initQuickEvents() {
        mPreferences = mContext.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        mIsFirstTimeDone = mPreferences.getBoolean(SETTING_DEVICE_INTRO_COMPLETED, false);
        registerPSAListener();
        updateQuickEvents();
    }

    private void registerPSAListener() {
        if (mRegistered) return;
        mRegistered = true;
        IntentFilter psonalityIntent = new IntentFilter();
        psonalityIntent.addAction(Intent.ACTION_TIME_TICK);
        psonalityIntent.addAction(Intent.ACTION_TIME_CHANGED);
        psonalityIntent.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(mPSAListener, psonalityIntent, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterPSAListener() {
        if (!mRegistered) return;
        mRegistered = false;
        mContext.unregisterReceiver(mPSAListener);
    }

    public void updateQuickEvents() {
        if (!mRegistered) return;
        if (mIsFirstTimeDone) {
            nowPlayingEvent();
            initNowPlayingEvent();
            psonalityEvent();
        } else {
            deviceIntroEvent();
        }
    }

    private void deviceIntroEvent() {
        mIsQuickEvent = true;

        if (Utilities.useAlternativeQuickspaceUI(mContext)) {
            mEventTitle = mResources.getString(R.string.quick_event_rom_intro_welcome_ext);
        } else {
            mEventTitle = mResources.getString(R.string.quick_event_rom_intro_welcome);
        }
        mPSAStr = mResources.getStringArray(R.array.welcome_message_variants);
        mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
        mEventSubIcon = ContextCompat.getDrawable(mContext, R.drawable.ic_quickspace_matrixx);
        mGreetings = mResources.getString(R.string.quickspace_grt_general);
        mClockExt = mResources.getString(R.string.quickspace_ext_two);

        mEventTitleSubAction = new OnClickListener() {
            @Override
            public void onClick(View view) {
                mContext.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(SETTING_DEVICE_INTRO_COMPLETED, true)
                        .commit();
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    Launcher.getLauncher(mContext).startActivitySafely(view, intent, null);
                } catch (ActivityNotFoundException ex) {
                }
                mIsQuickEvent = false;
            }
        };
    }

    private void nowPlayingEvent() {
        if (mEventNowPlaying) {
            boolean infoExpired = !mPlayingActive;
            if (infoExpired) {
                mIsQuickEvent = false;
                mEventNowPlaying = false;
            }
        }
    }

    private void initNowPlayingEvent() {
        if (!Utilities.isQuickspaceNowPlaying(mContext)) return;

        if (!mPlayingActive) return;

        if (mNowPlayingTitle == null) return;

        mEventTitle = mNowPlayingTitle;
        mGreetings = mResources.getString(R.string.qe_now_playing_ext_one);
        mClockExt = "";
        if (mNowPlayingArtist == null ) {
            mEventTitleSub = mResources.getString(R.string.qe_now_playing_unknown_artist);
        } else {
            mEventTitleSub = mNowPlayingArtist;
        }
        mEventSubIcon = getMSMHInstance().getMediaAppIcon();
        mIsQuickEvent = true;
        mEventNowPlaying = true;

        mEventTitleSubAction = new View.OnClickListener() {
	        @Override
	        public void onClick(View view) {
		        getMSMHInstance().launchMediaApp();
	        }
	    };
    }

    private void psonalityEvent() {
        if (mEventNowPlaying) return;

	    mEventTitle = Utilities.formatDateTime(mContext);
        mEventTitleSubAction = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent calendarIntent = new Intent(Intent.ACTION_MAIN);
                calendarIntent.addCategory(Intent.CATEGORY_APP_CALENDAR);

                Intent clockIntent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);

                PackageManager packageManager = mContext.getPackageManager();
                List<ResolveInfo> calendarApps = packageManager.queryIntentActivities(calendarIntent, PackageManager.MATCH_DEFAULT_ONLY);
                List<ResolveInfo> clockApps = packageManager.queryIntentActivities(clockIntent, PackageManager.MATCH_DEFAULT_ONLY);

                if (!calendarApps.isEmpty()) {
                    calendarIntent.setPackage(calendarApps.get(0).activityInfo.packageName);
                    try {
                        mContext.startActivity(calendarIntent);
                    } catch (ActivityNotFoundException e) {
                    }
                } else if (!clockApps.isEmpty()) {
                    clockIntent.setPackage(clockApps.get(0).activityInfo.packageName);
                    try {
                        mContext.startActivity(clockIntent);
                    } catch (ActivityNotFoundException e) {
                    }
                } else {
                    Toast.makeText(mContext, R.string.intent_no_app_clock_found, Toast.LENGTH_SHORT).show();
                }
            }
        };

        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        if (hourOfDay >= 5 && hourOfDay <= 9) {
            mGreetings = mResources.getString(R.string.quickspace_grt_morning);
            mClockExt = mResources.getString(R.string.quickspace_ext_one);
        } else if (hourOfDay >= 12 && hourOfDay <= 15) {
            mGreetings = mResources.getString(R.string.quickspace_grt_afternoon);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 16 && hourOfDay <= 20) {
            mGreetings = mResources.getString(R.string.quickspace_grt_evening);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 21 && hourOfDay <= 23) {
            mGreetings = mResources.getString(R.string.quickspace_grt_night);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 0 && hourOfDay <= 3) {
            mGreetings = mResources.getString(R.string.quickspace_grt_night);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else {
            mGreetings = mResources.getString(R.string.quickspace_grt_general);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        }

        if (!Utilities.isQuickspacePersonalityEnabled(mContext)) {
            mIsQuickEvent = false;
            return;
        }

        int luckNumber = getLuckyNumber(13);
        if (luckNumber < 7) {
            mIsQuickEvent = false;
            return;
        } else if (luckNumber == 7) {
            mPSAStr = mResources.getStringArray(R.array.quickspace_psa_random);
            mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
            mEventSubIcon = ContextCompat.getDrawable(mContext, R.drawable.ic_quickspace_matrixx);
            mIsQuickEvent = true;
            return;
        }

        mEventSubIcon = null;

        switch (hourOfDay) {
            case 5: case 6: case 7: case 8: case 9:
                mPSAStr = mResources.getStringArray(R.array.quickspace_psa_morning);
                mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
                mIsQuickEvent = true;
                break;

            case 19: case 20: case 21:
                mPSAStr = mResources.getStringArray(R.array.quickspace_psa_evening);
                mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
                mIsQuickEvent = true;
                break;

            case 16: case 17: case 18:
                mPSAStr = mResources.getStringArray(R.array.quickspace_psa_early_evening);
                mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
                mIsQuickEvent = true;
                break;

            case 12: case 13: case 14: case 15:
                mPSAStr = mResources.getStringArray(R.array.quickspace_psa_noon);
                mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
                mIsQuickEvent = true;
                break;

            case 0: case 1: case 2: case 3:
                mPSAStr = mResources.getStringArray(R.array.quickspace_psa_midnight);
                mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
                mIsQuickEvent = true;
                break;

            default:
                mIsQuickEvent = false;
                break;
        }
    }

    public boolean isQuickEvent() {
        return mIsQuickEvent;
    }

    public boolean isDeviceIntroCompleted() {
        return mIsFirstTimeDone;
    }

    public String getTitle() {
        return mEventTitle;
    }

    public String getActionTitle() {
        return mEventTitleSub;
    }

    public String getClockExt() {
        return mClockExt;
    }

    public String getGreetings() {
        return mGreetings;
    }

    public OnClickListener getAction() {
        return mEventTitleSubAction;
    }

    public Drawable getActionIcon() {
        return mEventSubIcon;
    }

    public int getLuckyNumber(int max) {
        return getLuckyNumber(0, max);
    }

    public int getLuckyNumber(int min, int max) {
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    public void setMediaInfo(String title, String artist, boolean activePlayback) {
        mNowPlayingTitle = title;
        mNowPlayingArtist = artist;
        mPlayingActive = activePlayback;
    }

    public boolean isNowPlaying() {
        return mPlayingActive;
    }

    public void onPause() {
        unregisterPSAListener();
    }

    public void onResume() {
        registerPSAListener();
    }
    
    private MediaSessionManagerHelper getMSMHInstance() {
        return MediaSessionManagerHelper.Companion.getInstance(mContext);
    }
}
