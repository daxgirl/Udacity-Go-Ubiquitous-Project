package com.example.android.sunshine.app.sync;

import android.util.Log;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

public class WearRequestListenerService extends WearableListenerService {
    private static final String WEATHER_REQUEST_PATH = "/weather-request";
    private final static String LOG_TAG = "SunshineWatchService";


    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(LOG_TAG, "onDataChanged request made from wearable");
        for(DataEvent dataEvent : dataEvents){
            if(dataEvent.getType() == DataEvent.TYPE_CHANGED){
                if(dataEvent.getDataItem().getUri().getPath().equals(WEATHER_REQUEST_PATH)){
                    Log.d(LOG_TAG, "onDataChanged data changed");
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }


}
