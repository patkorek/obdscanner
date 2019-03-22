package com.pat.obd_scanner.io;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import dagger.Module;
import dagger.Provides;

@Module
public class SharedPreferencesModule {

    private Context context;

    public SharedPreferencesModule(Context context) {
        this.context = context;
    }

    @Provides
    SharedPreferences provideSharedPreferences() {
        return context.getSharedPreferences("PrefName",Context.MODE_PRIVATE);
    }

    @Provides
    NotificationManager provideNotificationManager() {
        return (NotificationManager) context
                .getSystemService(Application.NOTIFICATION_SERVICE);
    }
}